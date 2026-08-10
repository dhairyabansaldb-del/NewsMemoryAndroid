"""Turn an eval_entities.py run into a judgement about extraction quality.

    python tools/eval_entities_report.py --run ents.jsonl
    python tools/eval_entities_report.py --run ents.jsonl --export archive-export.json
    python tools/eval_entities_report.py --run ents.jsonl --sample 40 --sample-filter junk

The clustering report compares two runs on cluster shape. Entity extraction fails in
completely different ways, so this measures different things.

Entities exist to be COUNTED. An entity that appears once, ever, is dead weight: it
cost a row and can never produce a recurrence. An entity so generic it appears
everywhere is worse — it makes "what's building" say "markets". So the questions are:

  1. Is it inventing?      -> entities-per-item distribution, zero-entity rate
  2. Does it collapse?     -> distinct surface forms vs distinct normalised keys, and
                              near-duplicate keys that SHOULD have collapsed and didn't
  3. Is any of it usable?  -> COUNTABLE SHARE: the fraction of extracted mentions whose
                              normalised key appears in 2+ distinct digest items.
                              **This is the number to read first.** It is the product
                              metric restated: everything outside it is a row that will
                              never contribute a single recurrence.
  4. Is it junk?           -> publisher names, bare generic words, long phrases, and
                              seen-once multi-word phrases (the "one-off" shape)

None of this replaces reading the output. `--sample N` prints headline -> entities side
by side; that hand-inspection is the real gate, exactly as it was for clustering-v2.

The normalised key is Normalizer.normalize() (imported from the clustering harness) —
the same merge key the `entities` table uses (UNIQUE on `normalized`), so two surface
forms that share a key really would collapse to one row on device.
"""

import argparse
import json
import random
import sys
from collections import Counter, defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from eval_clustering import normalize  # noqa: E402  (path set above)

# Bare words that match almost any news day. Flagged, never silently dropped — the
# point of the report is to show you what the prompt did, not to launder it.
GENERIC = {
    "markets", "market", "stocks", "stock", "shares", "share", "economy", "business",
    "news", "sensex today", "investors", "investment", "trading", "technology", "tech",
    "ai", "startups", "startup", "government", "india news", "sports", "money",
    "finance", "company", "companies", "industry", "sector", "world", "today",
    "update", "updates", "report", "growth", "prices", "price", "deal", "deals",
}


def load_run(path):
    """Returns (batches, items). An item's entities are None when the model never
    answered for that id, or the whole batch errored — those are excluded from
    quality rates, and counted separately as coverage loss."""
    batches, items = [], []
    for line in open(path, encoding="utf-8"):
        line = line.strip()
        if not line:
            continue
        b = json.loads(line)
        batches.append(b)
        for it in b.get("items") or []:
            it = dict(it)
            it["_error"] = b.get("error")
            items.append(it)
    return batches, items


def load_publishers(export_path):
    """Real publisher/app names from the export — a far better junk detector than a
    hardcoded list, because it is this device's actual news sources."""
    data = json.loads(Path(export_path).read_text(encoding="utf-8"))
    names = set()
    for r in data.get("rawNotifications") or data.get("raw_notifications") or []:
        p = r.get("publisher")
        if p:
            names.add(normalize(p))
        pkg = r.get("packageName") or r.get("package_name") or ""
        seg = pkg.split(".")[-1] if pkg else ""
        if len(seg) > 3:
            names.add(normalize(seg))
    return {n for n in names if n}


def pct(n, d):
    return f"{100.0 * n / d:5.1f}%" if d else "    -"


def bar(n, d, width=28):
    return "#" * int(round(width * n / d)) if d else ""


# ---------------------------------------------------------------- analysis


def analyse(items, publishers):
    ok = [it for it in items if it.get("entities") is not None]
    lost = [it for it in items if it.get("entities") is None]

    counts = Counter(len(it["entities"]) for it in ok)
    mentions = []                                  # (surface, key, item, digest)
    for it in ok:
        for e in it["entities"]:
            k = normalize(e)
            if k:
                mentions.append((e, k, it["item_id"], it["digest_id"]))

    key_items = defaultdict(set)      # key -> distinct digest_item ids
    key_digests = defaultdict(set)    # key -> distinct digests (days/slots)
    key_forms = defaultdict(Counter)  # key -> surface forms seen
    for surface, k, item_id, digest_id in mentions:
        key_items[k].add(item_id)
        key_digests[k].add(digest_id)
        key_forms[k][surface] += 1

    singletons = {k for k, v in key_items.items() if len(v) == 1}
    countable = sum(1 for _, k, _, _ in mentions if len(key_items[k]) >= 2)

    return {
        "ok": ok, "lost": lost, "counts": counts, "mentions": mentions,
        "key_items": key_items, "key_digests": key_digests, "key_forms": key_forms,
        "singletons": singletons, "countable": countable,
        "publishers": publishers,
    }


def junk_flags(key, surface, publishers, n_items=None):
    flags = []
    words = key.split()
    if n_items == 1 and len(words) >= 3:
        # Seen once, and long enough to be a sentence fragment: the signature of a
        # descriptive phrase lifted from a single headline ("record single-day fall").
        # Frequency alone can't say this — a genuinely new subject is also seen once —
        # but frequency AND shape together is a good bet.
        flags.append("ONE-OFF")
    if key in publishers:
        flags.append("PUBLISHER")
    if key in GENERIC or (len(words) == 1 and key in GENERIC):
        flags.append("GENERIC")
    if len(words) > 4 or len(surface) > 36:
        # Real canonical subjects are short ("RBI", "US-India trade deal"). Anything
        # longer is usually a descriptive phrase lifted from one headline, which can
        # never recur verbatim.
        flags.append("LONG")
    if any(ch.isdigit() for ch in key) and not any(ch.isalpha() for ch in key):
        flags.append("NUMERIC")
    if (surface and surface == surface.lower() and len(words) == 1
            and key not in GENERIC and any(ch.isalpha() for ch in key)):
        # A lone lowercase common noun in English headline text is usually a category,
        # not a subject. Real exceptions exist ("rupee", "gold") — hence a flag.
        flags.append("lowercase")
    return flags


def near_dupe_keys(key_items, limit=2000):
    """Keys that SHOULD have collapsed and did not — the canonicalisation failure mode.

    "Sensex" and "BSE Sensex" normalise to different keys, so on device they become two
    entity rows and each recurrence count is halved. Detect it by token containment,
    which catches the article/prefix/suffix variants the prompt is meant to strip.
    """
    keys = sorted(key_items, key=lambda k: -len(key_items[k]))[:limit]
    toks = {k: frozenset(k.split()) for k in keys}
    pairs = []
    for i, a in enumerate(keys):
        ta = toks[a]
        for b in keys[i + 1:]:
            tb = toks[b]
            if ta == tb or not (ta < tb or tb < ta):
                continue
            pairs.append((len(key_items[a]) + len(key_items[b]), a, b))
    pairs.sort(reverse=True)
    return pairs


# ---------------------------------------------------------------- printing


def report(args, batches, items, a):
    ok, lost = a["ok"], a["lost"]
    mentions = a["mentions"]
    key_items, key_digests, key_forms = a["key_items"], a["key_digests"], a["key_forms"]

    failed_batches = [b for b in batches if b.get("error")]
    tokens = sum(b.get("tokens") or 0 for b in batches)
    truncated = [b for b in batches if b.get("finish_reason") == "length"]

    print("=" * 72)
    print("RUN")
    print("=" * 72)
    print(f"  batches                 {len(batches)}  ({len(failed_batches)} errored, "
          f"{len(truncated)} hit the completion cap)")
    print(f"  digest items sent       {len(items)}")
    print(f"  items with a result     {len(ok)}   ({pct(len(ok), len(items))})")
    print(f"  items lost              {len(lost)}   "
          f"(batch error, or the model skipped the id)")
    print(f"  tokens (incl. failures) {tokens}")
    if failed_batches:
        errs = Counter((b.get("error") or "")[:60] for b in failed_batches)
        print("  top errors:")
        for e, n in errs.most_common(5):
            print(f"      {n:4d}  {e}")
    if not ok:
        print("\nno usable items — nothing further to measure.")
        return

    print()
    print("=" * 72)
    print("1. IS IT INVENTING?  entities per item")
    print("=" * 72)
    counts = a["counts"]
    hi = max(counts) if counts else 0
    for n in range(0, max(hi, 4) + 1):
        c = counts.get(n, 0)
        print(f"  {n} entities  {c:6d}  {pct(c, len(ok))}  {bar(c, len(ok))}")
    zero = counts.get(0, 0)
    mean = len(mentions) / len(ok)
    over = sum(c for n, c in counts.items() if n > 4)
    print(f"\n  mean entities/item      {mean:.2f}")
    print(f"  ZERO-ENTITY RATE        {pct(zero, len(ok))}   ({zero}/{len(ok)})")
    print("      too low  -> the prompt is reaching; junk rows are being invented for")
    print("                  teasers and promos that genuinely contain no subject.")
    print("      too high -> extraction is blind and the backfill buys nothing.")
    print("      Sanity band for this archive: roughly 10-35%.")
    if over:
        print(f"  !! {over} items exceeded the 4-entity cap — the parser should have"
              " clamped these; check maxEntitiesPerItem.")

    print()
    print("=" * 72)
    print("2. DOES IT COLLAPSE?  surface forms vs normalised keys")
    print("=" * 72)
    forms = {s for s, _, _, _ in mentions}
    print(f"  entity mentions         {len(mentions)}")
    print(f"  distinct surface forms  {len(forms)}")
    print(f"  distinct normalised keys{len(key_items):6d}   "
          f"(the rows this would create in `entities`)")
    print(f"  mentions per key        {len(mentions) / len(key_items):.2f}")
    print(f"  singleton keys          {len(a['singletons'])}   "
          f"({pct(len(a['singletons']), len(key_items))} of keys — the tail)")

    multi = [(k, v) for k, v in key_forms.items() if len(v) > 1]
    print(f"  keys with >1 surface    {len(multi)}   "
          f"(normalisation already merged these)")
    if multi:
        multi.sort(key=lambda kv: -len(kv[1]))
        for k, v in multi[:5]:
            print(f"      {k!r}: {', '.join(sorted(v))[:80]}")

    print()
    print("=" * 72)
    print("3. IS ANY OF IT USABLE?  >>> COUNTABLE SHARE <<<")
    print("=" * 72)
    countable = a["countable"]
    print(f"  mentions whose key appears in 2+ distinct digest items:")
    print(f"      COUNTABLE SHARE     {pct(countable, len(mentions))}   "
          f"({countable}/{len(mentions)})")
    print("      This is the number to read first. It is the v1 thesis measured")
    print("      directly: everything outside it is a row that can never produce a")
    print("      recurrence. Below ~40% the extraction is not earning the backfill.")
    recurring_keys = sum(1 for k in key_digests if len(key_digests[k]) >= 2)
    print(f"  keys seen in 2+ digests {recurring_keys}   "
          f"({pct(recurring_keys, len(key_items))} of keys)")

    print(f"\n  top {args.top} entities by distinct digest items:")
    top = sorted(key_items, key=lambda k: (-len(key_items[k]), k))[: args.top]
    for k in top:
        name = key_forms[k].most_common(1)[0][0]
        flags = junk_flags(k, name, a["publishers"], len(key_items[k]))
        mark = ("  <-- " + ",".join(flags)) if flags else ""
        print(f"      {len(key_items[k]):5d} items  {len(key_digests[k]):4d} digests  "
              f"{name}{mark}")

    if not args.no_near_dupes:
        pairs = near_dupe_keys(key_items)
        print(f"\n  near-duplicate keys that did NOT collapse ({len(pairs)} pairs):")
        if not pairs:
            print("      none — canonicalisation is holding.")
        for _, x, y in pairs[: args.top]:
            print(f"      {len(key_items[x]):4d}x {x!r}   vs   {len(key_items[y]):4d}x {y!r}")

    print()
    print("=" * 72)
    print("4. JUNK")
    print("=" * 72)
    flagged = Counter()
    examples = defaultdict(list)
    for k in key_items:
        name = key_forms[k].most_common(1)[0][0]
        for f in junk_flags(k, name, a["publishers"], len(key_items[k])):
            flagged[f] += len(key_items[k])
            if len(examples[f]) < 12:
                examples[f].append(name)
    if not flagged:
        print("  nothing flagged.")
    for f, n in flagged.most_common():
        print(f"  {f:<10} {n:5d} mentions   e.g. {', '.join(examples[f])[:110]}")
    print("\n  PUBLISHER and GENERIC hits are prompt failures — each one is an entity")
    print("  that will match every day and drown the real threads. LONG and lowercase")
    print("  are hints, not verdicts; read them in --sample before acting.")

    if args.sample:
        print_sample(args, a)


def print_sample(args, a):
    ok = a["ok"]
    pool = ok
    if args.sample_filter == "zero":
        pool = [it for it in ok if not it["entities"]]
    elif args.sample_filter == "nonzero":
        pool = [it for it in ok if it["entities"]]
    elif args.sample_filter == "junk":
        ki = a["key_items"]
        pool = [it for it in ok if any(
            junk_flags(normalize(e), e, a["publishers"], len(ki[normalize(e)]))
            for e in it["entities"] if normalize(e))]
    rng = random.Random(args.seed)
    pick = pool if len(pool) <= args.sample else rng.sample(pool, args.sample)
    pick.sort(key=lambda i: (i["digest_id"], i.get("position", 0)))

    print()
    print("=" * 72)
    print(f"SAMPLE ({len(pick)} of {len(pool)} matching '{args.sample_filter}') — "
          "THIS is the real gate")
    print("=" * 72)
    for it in pick:
        ents = it["entities"]
        print(f"\n  [{it['digest_id']}] {it['headline'][:110]}")
        if it.get("snippet"):
            print(f"      snippet: {it['snippet'][:110]}")
        print(f"      -> {ents if ents else '(none)'}")
    print("\n  Read these as a human: is every listed entity something you would want")
    print("  to see a count for, and is every (none) genuinely subject-free?")


def main():
    ap = argparse.ArgumentParser(
        description="Judge an entity-extraction run from eval_entities.py.")
    ap.add_argument("--run", required=True, help="the .jsonl written by eval_entities.py")
    ap.add_argument("--export", default=None,
                    help="the archive export — enables the real publisher-name junk check")
    ap.add_argument("--top", type=int, default=25, help="rows in the top-N listings")
    ap.add_argument("--sample", type=int, default=0,
                    help="print N items as headline -> entities for hand inspection")
    ap.add_argument("--sample-filter", default="all",
                    choices=["all", "zero", "nonzero", "junk"])
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--no-near-dupes", action="store_true",
                    help="skip the O(n^2) near-duplicate key scan")
    args = ap.parse_args()

    batches, items = load_run(args.run)
    if not batches:
        raise SystemExit(f"{args.run} is empty")
    publishers = load_publishers(args.export) if args.export else set()
    if not publishers:
        print("(no --export given: publisher-name junk detection is off)\n")
    report(args, batches, items, analyse(items, publishers))


if __name__ == "__main__":
    main()
