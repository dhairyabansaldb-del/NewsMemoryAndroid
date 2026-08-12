"""Offline evaluation harness for News Memory ENTITY EXTRACTION.

Why this exists
---------------
v1's whole thesis is recurrence: counting how often a canonical subject comes back.
That only works if `entities` / `item_entities` are populated, and they largely are
not — entities were only ever written on the LLM clustering path, so in the July
export 87 of 96 digests ran HEURISTIC and produced 88 entities across 3,008 digest
items. The archive is nearly uncountable and needs a backfill.

The hard-won lesson of PROJECT_MEMORY §5.1 is that LLM output quality on real, messy
notification text cannot be settled by unit tests: a clustering prompt that passed
every test produced two days of digests worse than the heuristic it replaced. So the
backfill prompt gets the same treatment clustering-v2 got — measured offline against
the live Groq API over a month of real captured items, BEFORE any Kotlin touches the
real database. This script is that gate; `eval_entities_report.py` reads its output.

Usage
-----
    python tools/eval_entities.py --export archive-export.json --out ents.jsonl
    python tools/eval_entities.py --export archive-export.json --dry-run --limit 36
    python tools/eval_entities_report.py --run ents.jsonl --sample 40

The export is a Settings -> "Export archive (JSON)" file. **It is personal
notification history — keep it and every result file out of git.**

What it feeds the model
-----------------------
One line per `digest_items` row: the item's headline plus a body snippet joined
through `item_sources` -> `raw_notifications`. Title-only input is nearly blind (§5.1:
36% of captured titles are <=3 words), which is exactly why clustering-v2 sends
title+body — the same rule applies here, with the digest headline standing in for the
title.

`topic_label` is deliberately NOT sent to the model. It is carried through to the
output for the report's benefit only. Heuristic-mode digests label items with a single
top-TF token ("Dropping", "Kishans"), and feeding that in would inject exactly the kind
of junk this prompt is built to reject.

Configuration lives in shared/entity-config.json + shared/entity-prompt-v1.txt, both
of which the Gradle codegen now emits as `SharedConfig.ENTITY_*` for the app's own
extractor (`llm/EntityExtractor.kt`). `tools/check_shared_sync.py` compares the two.
**Keep the batching, prompt assembly and response parsing here in step with that
Kotlin** — a harness that has drifted is measuring an app you don't ship.
"""

import argparse
import json
import random
import sys
import time
from collections import defaultdict
from pathlib import Path

# --------------------------------------------------------------- reuse, not re-derive
# groq_call (incl. its 400/429/5xx retry behaviour), the token estimator, the rolling
# 60s TPM limiter and the Kotlin-Normalizer-equivalent normalize() are all generic and
# already correct in the clustering harness. Import them rather than forking them: a
# second copy of the rate-limit logic is a second copy to get wrong.
# NOTE: importing eval_clustering also loads shared/pipeline-config.json at import
# time — this harness therefore requires that file to be readable even though it uses
# none of its values. See tools/README.md ("coupling worth fixing").
sys.path.insert(0, str(Path(__file__).resolve().parent))
from eval_clustering import (  # noqa: E402  (path set above)
    RollingTokenLimiter,
    TPM_HARD_CAP,
    est_tokens,
    groq_call,
    normalize,
)

SHARED_DIR = Path(__file__).resolve().parent.parent / "shared"
CFG = json.loads((SHARED_DIR / "entity-config.json").read_text(encoding="utf-8"))
PROMPT = (SHARED_DIR / CFG["promptFile"]).read_text(encoding="utf-8").rstrip()

ENTITY_MODEL = CFG["entityModel"]
REASONING_EFFORT = CFG["reasoningEffort"]
BATCH_SIZE = CFG["batchSize"]
MAX_ENTITIES = CFG["maxEntitiesPerItem"]
TPM_BUDGET = CFG["tpmBudget"]
COMPLETION_BASE = CFG["completionBase"]
COMPLETION_PER_ITEM = CFG["completionPerItem"]
MIN_COMPLETION_TOKENS = CFG["minCompletionTokens"]
BODY_CHARS = CFG["bodyChars"]


# ---------------------------------------------------------------- export parsing


def _get(row, *names, default=None):
    """The export is kotlinx-serialized (camelCase property names), but the same rows
    are described by their snake_case column names elsewhere. Accept either."""
    for n in names:
        if n in row and row[n] is not None:
            return row[n]
    return default


def _clean(text):
    return (text or "").replace("\n", " ").replace("\r", " ").strip()


def load_items(export_path):
    """digest_items joined through item_sources to raw_notifications.

    Returns one dict per digest item in a stable (digest_id, position, id) order so
    that --skip resumes deterministically.
    """
    data = json.loads(Path(export_path).read_text(encoding="utf-8"))

    raws = {}
    for r in data.get("rawNotifications") or data.get("raw_notifications") or []:
        raws[_get(r, "id")] = r

    by_item = defaultdict(list)
    for s in data.get("itemSources") or data.get("item_sources") or []:
        raw = raws.get(_get(s, "rawId", "raw_id"))
        if raw is not None:
            by_item[_get(s, "itemId", "item_id")].append(raw)

    items = []
    for di in data.get("digestItems") or data.get("digest_items") or []:
        item_id = _get(di, "id")
        headline = _clean(_get(di, "headline", default=""))
        if not headline:
            continue
        srcs = by_item.get(item_id, [])
        items.append({
            "item_id": item_id,
            "digest_id": _get(di, "digestId", "digest_id", default=""),
            "topic_label": _get(di, "topicLabel", "topic_label", default=""),
            "position": _get(di, "position", default=0),
            "source_count": _get(di, "sourceCount", "source_count", default=0),
            "headline": headline,
            "snippet": pick_snippet(headline, srcs),
            "n_raw": len(srcs),
        })
    items.sort(key=lambda i: (i["digest_id"], i["position"], i["item_id"]))
    return items


def pick_snippet(headline, raw_rows):
    """The body text that best adds information to the headline.

    §5.1: 36% of captured titles are <=3 words, so title-only input clusters (and
    extracts) nearly blind. Prefer the longest source *body* that isn't just the
    headline again; fall back to a source *title* when every body is empty — which is
    common for Dailyhunt-shaped rows where the headline lives in EXTRA_TEXT.
    """
    hn = normalize(headline)
    best = ""
    for r in raw_rows:
        for field in ("body", "title"):
            t = _clean(r.get(field))
            if not t:
                continue
            n = normalize(t)
            if not n or n == hn or n in hn:
                continue
            # Longest wins regardless of which row or field it came from: the point
            # is maximum added information, not provenance.
            if len(t) > len(best):
                best = t
    return best


# ---------------------------------------------------------------- prompt building


def build_user(batch, body_chars):
    lines = ["Items:"]
    for i, it in enumerate(batch, start=1):
        line = f"{i}. {it['headline']}"
        snip = it["snippet"]
        if snip:
            line += f" — {snip[:body_chars]}"
        lines.append(line)
    return "\n".join(lines)


def plan_batch(batch):
    """Size the request so that input + max_completion_tokens fits the TPM budget.

    Groq's free tier pre-counts input + max_completion_tokens against the 8000 TPM
    cap, so the completion budget has to be derived from the real prompt size, and
    snippets shrink when a batch would otherwise leave no room to answer in.
    """
    # What the answer needs: a floor (reasoning tokens at effort=low are charged to
    # the completion too) plus a per-item allowance for the entity list itself.
    want = max(COMPLETION_BASE + COMPLETION_PER_ITEM * len(batch), MIN_COMPLETION_TOKENS)
    body_chars = BODY_CHARS
    while True:
        user = build_user(batch, body_chars)
        est_input = est_tokens(PROMPT) + est_tokens(user)
        room = TPM_BUDGET - est_input
        if room >= want or body_chars <= 40:
            break
        body_chars -= 40      # shrink snippets only when there is genuinely no room
    # If we land here still short, the batch is pathological (huge snippets); the
    # reservation is nonetheless well under TPM_HARD_CAP, so send it and let the
    # finish_reason record the truncation rather than silently dropping items.
    max_completion = max(min(want, room), MIN_COMPLETION_TOKENS)
    return user, est_input, max_completion, body_chars


# ---------------------------------------------------------------- response parsing


class BadResponse(Exception):
    pass


def parse_entities(content, n):
    """Tolerant read of the id-keyed batch response.

    Accepts the documented {"items":[{"id":1,"entities":[...]}]}, a bare list of those
    objects, and an id->entities mapping, because a model that is otherwise extracting
    well should not be scored as a total failure over an envelope key. Anything else
    is a real malformed response and gets one re-ask before the batch is recorded as
    an error.
    """
    parsed = json.loads(content)

    rows = None
    if isinstance(parsed, dict):
        for key in ("items", "results", "extractions", "entities"):
            if isinstance(parsed.get(key), list):
                rows = parsed[key]
                break
        if rows is None and parsed and all(str(k).strip().isdigit() for k in parsed):
            rows = [{"id": int(k), "entities": v} for k, v in parsed.items()]
    elif isinstance(parsed, list):
        rows = parsed
    if rows is None:
        raise BadResponse("no id-keyed item list in response")

    out, extra, seen = {}, [], set()
    for row in rows:
        if not isinstance(row, dict):
            continue
        try:
            rid = int(row.get("id"))
        except (TypeError, ValueError):
            continue
        if not (1 <= rid <= n) or rid in seen:
            extra.append(row.get("id"))
            continue
        seen.add(rid)
        ents = row.get("entities")
        if isinstance(ents, str):          # observed shape drift: a comma string
            ents = [e for e in ents.split(",")]
        if not isinstance(ents, list):
            ents = []
        cleaned, keys = [], set()
        for e in ents:
            if not isinstance(e, str):
                continue
            e = " ".join(e.split()).strip(" ,;:-–—\"'")
            k = normalize(e)
            if not k or k in keys:
                continue
            keys.add(k)
            cleaned.append(e)
        out[rid] = cleaned[:MAX_ENTITIES]

    if not out:
        raise BadResponse("no usable ids in response")
    missing = [i for i in range(1, n + 1) if i not in out]
    return out, missing, extra


# ---------------------------------------------------------------- one batch


def run_batch(key, model, effort, batch, index, limiter=None, attempts=2):
    user, est_input, max_completion, body_chars = plan_batch(batch)
    result = {
        "batch": index,
        "n": len(batch),
        "model": model,
        "effort": effort,
        "prompt_version": CFG["promptVersion"],
        "body_chars": body_chars,
        "est_input": est_input,
        "max_completion": max_completion,
        "reserved": est_input + max_completion,
        "finish_reason": None,
        "reask": False,
        "error": None,
        "missing_ids": [],
        "extra_ids": [],
        "prompt_items": user[:6000],
        "items": [dict(it, entities=None) for it in batch],
    }

    last_err = None
    for attempt in range(attempts):
        try:
            if limiter:
                # Reserve the worst case BEFORE sending. Reacting to 429s after the
                # fact is not enough — several medium calls 20-30s apart still bust
                # the per-minute cap in sum (observed live 2026-07-17).
                limiter.wait_for(result["reserved"])
            content, finish, total = groq_call(
                key, model, PROMPT, user, max_completion, reasoning_effort=effort
            )
            if limiter:
                limiter.record(total or result["reserved"])
            result["finish_reason"] = finish
            result["tokens"] = total
            result["raw_response"] = (content or "")[:6000]
            byid, missing, extra = parse_entities(content, len(batch))
            for i, it in enumerate(result["items"], start=1):
                it["entities"] = byid.get(i)   # None = model never answered for this id
            result.update(reask=attempt > 0, missing_ids=missing, extra_ids=extra,
                          error=None)
            return result
        except (json.JSONDecodeError, BadResponse, KeyError) as e:
            last_err = f"{type(e).__name__}: {e}"
            result["reask"] = attempt + 1 < attempts
            continue
        except Exception as e:          # HTTP, network, timeout — groq_call already retried
            last_err = f"{type(e).__name__}: {e}"
            break

    # A failed call still SPENT tokens at the provider, and the rolling window has to
    # know that. Leaving the count unset collapses the pause and cascades into a run
    # of 429s that measures the harness rather than the prompt (§5.1 / the clustering
    # harness learned this the same way). Charge the reservation we asked for.
    result["tokens"] = result["reserved"]
    result["error"] = str(last_err)[:300]
    if limiter:
        limiter.record(result["reserved"])
    return result


# ---------------------------------------------------------------- main


def read_key(key_file):
    for line in open(key_file, encoding="utf-8"):
        if line.startswith("GROQ_API_KEY="):
            k = line.split("=", 1)[1].strip()
            if k:
                return k
    return None


def main():
    ap = argparse.ArgumentParser(
        description="Evaluate entity extraction over a real archive export.")
    ap.add_argument("--export", required=True, help="Settings -> Export archive (JSON) file")
    ap.add_argument("--out", help="JSONL output, one line per batch (required unless --dry-run)")
    ap.add_argument("--key-file", default="local.properties")
    ap.add_argument("--limit", type=int, default=0, help="only first N digest items (0 = all)")
    ap.add_argument("--skip", type=int, default=0,
                    help="skip first N digest items and APPEND to --out (resume)")
    ap.add_argument("--model", default=ENTITY_MODEL)
    ap.add_argument("--effort", default=REASONING_EFFORT, help="reasoning_effort for this run")
    ap.add_argument("--batch", type=int, default=BATCH_SIZE, help="digest items per API call")
    ap.add_argument("--delay", type=float, default=0.5, help="seconds between calls")
    ap.add_argument("--sample-items", type=int, default=0,
                    help="randomly sample N items from the export instead of taking a prefix")
    ap.add_argument("--seed", type=int, default=7, help="seed for --sample-items")
    ap.add_argument("--dry-run", action="store_true",
                    help="print the batching plan and the exact prompts; make no API calls")
    args = ap.parse_args()

    if not args.dry_run and not args.out:
        ap.error("--out is required unless --dry-run")
    if args.batch < 1:
        ap.error("--batch must be >= 1")

    items = load_items(args.export)
    total_items = len(items)
    if args.sample_items:
        random.Random(args.seed).shuffle(items)
        items = items[: args.sample_items]
        items.sort(key=lambda i: (i["digest_id"], i["position"], i["item_id"]))
    if args.skip:
        items = items[args.skip:]
    if args.limit:
        items = items[: args.limit]

    batches = [items[i:i + args.batch] for i in range(0, len(items), args.batch)]
    print(f"export: {total_items} digest items; running {len(items)} "
          f"in {len(batches)} batches of {args.batch}", flush=True)

    if args.dry_run:
        dry_run(batches, args)
        return

    key = read_key(args.key_file)
    assert key, f"GROQ_API_KEY not found in {args.key_file}"

    limiter = RollingTokenLimiter()
    out = open(args.out, "a" if args.skip else "w", encoding="utf-8")
    done = args.skip
    base = args.skip // args.batch      # keep batch numbers monotonic across a resume
    for i, batch in enumerate(batches):
        res = run_batch(key, args.model, args.effort, batch, base + i, limiter=limiter)
        out.write(json.dumps(res, ensure_ascii=False) + "\n")
        out.flush()
        done += len(batch)
        n_ents = sum(len(it["entities"] or []) for it in res["items"])
        n_zero = sum(1 for it in res["items"] if it["entities"] == [])
        print(f"[{i + 1}/{len(batches)}] items={res['n']} entities={n_ents} zero={n_zero} "
              f"tok={res.get('tokens', '-')} {res['error'] or ''}".rstrip(), flush=True)
        time.sleep(args.delay)
    out.close()
    print(f"\ndone. resume an interrupted run with --skip {done}")


def dry_run(batches, args):
    """Everything the live path would do, minus the network — this is how the harness
    is verified on a machine with no API key."""
    reserved_total = 0
    for i, batch in enumerate(batches):
        user, est_input, max_completion, body_chars = plan_batch(batch)
        reserved = est_input + max_completion
        reserved_total += reserved
        print("\n" + "=" * 78)
        print(f"BATCH {i + 1}/{len(batches)}  items={len(batch)}  "
              f"model={args.model}  effort={args.effort}  body_chars={body_chars}")
        print(f"  est_input={est_input}  max_completion={max_completion}  "
              f"reserved={reserved} (TPM hard cap {TPM_HARD_CAP})")
        print(f"  digests: {sorted({it['digest_id'] for it in batch})}")
        print("-" * 78)
        if i == 0:
            print("[system prompt]")
            print(PROMPT)
            print("-" * 78)
        print("[user message]")
        print(user)

    if not batches:
        print("no items selected")
        return
    avg = reserved_total / len(batches)
    per_min = TPM_HARD_CAP / avg if avg else 0.0
    print("\n" + "=" * 78)
    print(f"PLAN: {len(batches)} calls, avg reservation {avg:.0f} tok")
    if per_min:
        print(f"      {per_min:.2f} calls/min under the {TPM_HARD_CAP} TPM cap "
              f"(~{60 / per_min:.0f}s effective spacing)")
        print(f"      estimated wall clock: {len(batches) / per_min:.0f} min")
    print("dry run — no API calls were made")


if __name__ == "__main__":
    main()
