# tools/ — offline clustering evaluation

**Read this before changing the clustering prompt or model.**

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
