# tools/ — offline LLM evaluation

**Read this before changing the clustering prompt, the entity prompt, or the model.**

Two harnesses live here, both built on the same principle: a prompt change gets measured
on a month of real captured data before it ships. Clustering first (below); entity
extraction second ([jump](#entity-extraction-eval)).

## Why this exists

The digest pipeline's clustering stage is the one part of News Memory whose quality
can't be settled by unit tests — it depends on how a language model behaves on real,
messy notification text at real digest sizes. That was learned the expensive way: a
prompt that looked correct, and passed every test, produced two days of digests
noticeably *worse* than the heuristic it replaced — mega-clusters swallowing unrelated
stories, headlines that hid half of what they merged, wrong topic labels.

Iterating on-device costs a day per attempt and gives you one noisy sample. This harness
replays **every window you've already captured** — a month of real data — through the
same pipeline against the live Groq API, so a change can be measured before it ships.
That's how clustering-v2 was validated: 70 mega-clusters across 37 windows became 0.

## The files

| File | What it does |
|---|---|
| `eval_clustering.py` | Replays captured windows through dedup → LLM → repair, writes one JSON line per window |
| `eval_report.py` | Compares two runs: fallback rate, mega-clusters, repairs, worst over-merges |
| `check_shared_sync.py` | Verifies the harness and the app really do use identical config |
| `eval_entities.py` | Extracts entities from real `digest_items`, one JSON line per batch |
| `eval_entities_report.py` | Judges an entity run: zero rate, collapse quality, countable share, junk |

## Getting the data

The harness reads a **Settings → Export archive (JSON)** export from the device:

```bash
adb shell ls /sdcard/Download/news-memory-archive-*.json
adb pull /sdcard/Download/news-memory-archive-2026-07-17.json ./archive-export.json
```

**That file is personal notification history — keep it and every result file out of
git.** Work in a scratch directory outside the repo.

## Running a comparison

```bash
# baseline vs proposed, one API call per window
python tools/eval_clustering.py --export archive-export.json --config A --out a.jsonl
python tools/eval_clustering.py --export archive-export.json --config B --out b.jsonl
python tools/eval_report.py --a a.jsonl --b b.jsonl
```

`--only 2026-06-18-N` runs a single window (fast, cheap — good for iterating on prompt
wording). `--limit N` caps the run. `--effort low|medium|high` overrides reasoning effort.

**Config A** is the pipeline as it shipped on 2026-07-14 — kept as the historical
baseline. **Config B** mirrors what the app ships today: model, reasoning effort, and
prompt all load from `shared/`, so B is not a guess about production, it *is*
production.

To test a change: edit `shared/`, run B, compare against a saved baseline.

## Rate limits — the thing that will bite you

Groq's free tier has a per-minute token cap (8000 for `openai/gpt-oss-120b`) and a
daily cap. Two consequences, both learned live:

- The harness paces itself between calls and derives a per-request completion budget.
  A full 91-window run takes roughly an hour. **This is expected, not a hang.**
- A long run can exhaust the *daily* quota partway through. Then every remaining window
  429s. `--skip N` resumes an interrupted run.

If windows start failing in a row, check the actual error in the output before assuming
the pipeline is broken:

```bash
python -c "import json;[print(json.loads(l)['window'], json.loads(l).get('error')) for l in open('b.jsonl')]"
```

## Keeping the harness honest

The harness is only useful if it evaluates the configuration the app actually ships.
Everything both sides must agree on lives in `shared/`:

- `shared/pipeline-config.json` — model, reasoning effort, token budget, Jaccard
  threshold, stopwords
- `shared/clustering-prompt-v2.txt` — the prompt itself

Kotlin reads them at **build** time (a Gradle task generates `SharedConfig.kt`, so these
stay compile-time constants with no runtime parsing on the digest path); Python reads
them directly at **run** time. Change a value once and both pick it up.

Verify after touching `shared/`, the codegen, or the prompt:

```bash
./gradlew.bat generateSharedConfig    # refresh the generated Kotlin
python tools/check_shared_sync.py     # exit 0 = in sync
```

**What is still hand-ported and can drift:** the harness contains Python ports of
`Normalizer`, `Deduper`, and `ClusterResponseParser`. The *values* they use are shared,
but the *logic* is duplicated. If you change how those algorithms work in Kotlin, update
the ports too — `check_shared_sync.py` will not catch a logic divergence.

## Reading the output

Each line of the `.jsonl` is one window:

```
window, raw, stories, mode (LLM|FALLBACK), clusters, multi, mega, max_cluster,
dupes_dropped, orphans, id_splits, tokens, finish_reason, output[...]
```

The numbers that matter:

- **`mega`** — clusters holding more than 4 stories. Should be **0**. Anything else means
  the model is topic-bucketing again, the exact failure clustering-v2 was built to fix.
- **`mode`** — `FALLBACK` means the response was unusable and the heuristic ran instead.
- **`multi`** — genuine merges. Expect a handful per window, not dozens; most real
  windows contain few true same-event duplicates.
- **`orphans` / `id_splits` / `dupes_dropped`** — repairs applied to sloppy model
  bookkeeping. Non-zero is normal and fine; that's the parser doing its job.

Numbers alone aren't enough — **read the actual merges** before trusting a config. A run
with zero mega-clusters can still be merging the wrong things.

---

# Entity-extraction eval

**Read this before changing `shared/entity-prompt-v1.txt`, and before any Kotlin writes
backfilled entities to the real database.**

## Why this exists

v1's whole product thesis is recurrence — counting how often a canonical subject comes
back. That is a pure counting query over `entities` / `item_entities`, and those tables
are nearly empty. Entities were only ever written on the **LLM** clustering path, and in
the July export **87 of 96 digests ran heuristic**: 88 entities across 3,008 digest
items. The historical archive is effectively uncountable, so old digest items need an
entity backfill.

But §5.1 is unambiguous: LLM quality on real messy notification text cannot be settled by
unit tests. A clustering prompt that passed every test produced two days of digests
*worse* than the heuristic it replaced. A backfill is worse than a bad digest — a digest
is gone tomorrow, a junk entity row sits in the archive and poisons every count that ever
touches it. So extraction quality is measured offline first, exactly as clustering-v2
was. **This is that gate. Do not run the backfill on the real DB until a run passes it.**

## Running it

```bash
# no key needed — prints the exact prompts and the batching plan, calls nothing
python tools/eval_entities.py --export archive-export.json --dry-run --limit 36

# a real run, then read it
python tools/eval_entities.py --export archive-export.json --out ents.jsonl
python tools/eval_entities_report.py --run ents.jsonl --export archive-export.json --sample 40
```

`--limit N` / `--skip N` count **digest items**, not batches; the script prints the exact
`--skip` to resume with when it finishes. `--sample-items N --seed S` takes a random
spread across the whole archive instead of a prefix — better for a first look, because
the first N items are all one week. `--model` / `--effort` / `--batch` override
`shared/entity-config.json` for one run.

Passing `--export` to the *report* is optional but worth it: it loads the device's real
publisher and package names, so "is the model extracting the outlet instead of the
subject" is checked against actual sources rather than a guessed list.

## What gets sent

One line per `digest_items` row: the item's headline plus a body snippet joined through
`item_sources` → `raw_notifications`. **Not headline alone** — §5.1 established that 36%
of captured titles are ≤3 words, which is why clustering-v2 sends title+body, and the
same blindness applies here.

`topic_label` is deliberately **not** sent. Heuristic-mode digests label items with a
single top-TF token ("Dropping", "Kishans"); feeding that in would inject precisely the
junk the prompt exists to reject. It's carried into the output for the report only.

The harness also does **not** clean up the model's entities beyond trimming whitespace
and capping at 4. It would be easy to strip a leading "the" in Python and watch the
numbers improve — and completely self-defeating, because the thing being measured is
whether *the prompt* canonicalises. A harness that launders its input measures nothing.

## Rate limits and batch size

Same 8000 TPM free-tier cap, same pre-counting of input + `max_completion_tokens`, same
`RollingTokenLimiter` (imported from `eval_clustering.py`, not reimplemented). At the
default batch of 12:

```
input      ~= prompt (~750 tok) + 12 items x ~65 tok  ~= 1500
completion  = 400 + 70/item                            = 1240
reservation                                            ~= 2750
8000 / 2750 -> ~2.9 calls per rolling 60s window, ~21s effective spacing
```

3,008 items ÷ 12 ≈ 250 calls ≈ **90 minutes**. Expected, not a hang. Bigger batches are
cheaper (the prompt amortises) but §5.1 saw gpt-oss-120b start fumbling id bookkeeping
around 30 items, and a lost batch costs more, so 12 is the deliberate middle.

**The trap:** a failed call still spends tokens at the provider. This harness charges its
full reservation to the limiter on the error path as well as the success path. The
clustering harness records the estimate into the *result* but not into the *limiter* — if
you copy from it, don't copy that. An unrecorded failure collapses the next pause and
cascades into a run of 429s that measures the harness, not the prompt.

## Reading the report

Four sections, in the order you should read them:

1. **Entities per item + zero-entity rate.** Zero is a correct and common answer, and the
   prompt says so explicitly. A rate near 0% means the model is reaching — inventing
   something for teasers and app promos that genuinely contain no subject. A rate near
   100% means extraction is blind and the backfill buys nothing. Roughly 10–35% is the
   sane band for this archive.
2. **Collapse quality.** Distinct surface forms vs distinct *normalised* keys — the key
   is `Normalizer.normalize()`, the same `UNIQUE` merge key the `entities` table uses, so
   two forms sharing a key really would become one row on device. Then
   **near-duplicate keys that did NOT collapse**: `sensex` vs `bse sensex` are two rows
   and each recurrence count is halved. That listing is the canonicalisation bug report.
3. **`COUNTABLE SHARE` — read this first.** The fraction of extracted mentions whose
   normalised key appears in **2+ distinct digest items**. It is the v1 thesis stated as
   a number: everything outside it is a row that can never produce a single recurrence.
   A long tail of singletons means the model is emitting one-off descriptive phrases, and
   no amount of good-looking top-20 entities makes up for it. Below ~40%, the extraction
   is not earning the backfill.
4. **Junk.** `PUBLISHER` and `GENERIC` hits are outright prompt failures — each is an
   entity that matches every day and drowns the real threads. `ONE-OFF`, `LONG`,
   `NUMERIC`, `lowercase` are hints, not verdicts.

Then **`--sample 40`**, and read it as a human. That is the real gate, the same way every
clustering-v2 merge was hand-checked before it shipped. `--sample-filter junk` and
`--sample-filter zero` narrow it to the two places quality actually breaks: things it
should not have extracted, and things it should have.

## Config, and what reads it

`shared/entity-config.json` (model, effort, batch size, token budget) and
`shared/entity-prompt-v1.txt` (the prompt).

**Both are now in the Gradle codegen and both are checked by `check_shared_sync.py`** —
the app grew an entity path (`llm/EntityExtractor.kt`, driven by `memory/EntityBackfill.kt`),
so there are two languages reading these files and `shared/` is the only thing keeping them
honest. `GenerateSharedConfig` emits them as `SharedConfig.ENTITY_*` consts; the sync check
compares the generated Kotlin against what this harness loads, and now also fails on any
generated const that has no check row at all.

What that buys, and what it costs you: change a value here and both the app and this
harness pick it up — but the Kotlin mirrors this file's `build_user`, `pick_snippet`,
`plan_batch` and `parse_entities` **by hand**, and `check_shared_sync.py` cannot see a
logic divergence. If you change how a batch is assembled or a response is read, change
`llm/EntityExtractor.kt`, `llm/prompts/EntityPrompt.kt` and `llm/EntityResponseParser.kt`
with it. (Same standing caveat as the clustering ports: shared values, hand-ported logic.)

`shared/pipeline-config.json` is untouched by any of this.

## Coupling worth fixing

`eval_entities.py` imports `groq_call`, `est_tokens`, `normalize`, `RollingTokenLimiter`
and `TPM_HARD_CAP` from `eval_clustering.py` rather than forking them — one copy of the
rate-limit logic is the whole point. The cost: importing `eval_clustering` executes its
module level, which **loads `shared/pipeline-config.json`**, so the entity harness needs
that file readable even though it uses none of its values. The clean fix, when someone
touches these files next, is to lift the provider plumbing into a `tools/groq_util.py`
that both harnesses import. Key loading is duplicated for the same reason — it is inline
in `eval_clustering.main()`, not a function.
