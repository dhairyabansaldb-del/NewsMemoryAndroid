"""Compare two eval_clustering runs (A = baseline, B = proposed) window by window.

Usage:
  python tools/eval_report.py --a full_A.jsonl --b full_B.jsonl [--details N]
"""

import argparse
import json


def load(path):
    rows = {}
    for line in open(path, encoding="utf-8"):
        r = json.loads(line)
        rows[r["window"]] = r
    return rows


def agg(rows):
    llm = [r for r in rows.values() if r["mode"] == "LLM"]
    fall = [r for r in rows.values() if r["mode"] != "LLM"]
    out = {
        "windows": len(rows),
        "llm_ok": len(llm),
        "fallbacks": len(fall),
        "fallback_reasons": [r.get("error", "?")[:60] for r in fall],
    }
    if llm:
        out.update(
            stories=sum(r["stories"] for r in llm),
            clusters=sum(r["clusters"] for r in llm),
            multi=sum(r["multi"] for r in llm),
            mega=sum(r["mega"] for r in llm),
            worst_cluster=max(r["max_cluster"] for r in llm),
            windows_with_mega=sum(1 for r in llm if r["mega"] > 0),
            dupes=sum(r.get("dupes_dropped", 0) for r in llm),
            orphans=sum(r.get("orphans", 0) for r in llm),
            splits=sum(r.get("id_splits", 0) for r in llm),
            truncated=sum(1 for r in llm if r.get("finish_reason") == "length"),
            avg_tokens=sum(r.get("tokens", 0) for r in llm) // max(len(llm), 1),
        )
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--a", required=True)
    ap.add_argument("--b", required=True)
    ap.add_argument("--details", type=int, default=5, help="worst-N windows to detail")
    args = ap.parse_args()

    A, B = load(args.a), load(args.b)

    print(f"{'metric':<24}{'A (baseline)':>16}{'B (proposed)':>16}")
    print("-" * 56)
    aa, bb = agg(A), agg(B)
    keys = ["windows", "llm_ok", "fallbacks", "stories", "clusters", "multi",
            "mega", "windows_with_mega", "worst_cluster", "dupes", "orphans",
            "splits", "truncated", "avg_tokens"]
    for k in keys:
        print(f"{k:<24}{str(aa.get(k, '-')):>16}{str(bb.get(k, '-')):>16}")

    for name, a in (("A", aa), ("B", bb)):
        if a["fallback_reasons"]:
            print(f"\n{name} fallback reasons:")
            for r in a["fallback_reasons"]:
                print("  -", r)

    # Worst over-merge windows under A, with B's take on the same window.
    ranked = sorted(
        (r for r in A.values() if r["mode"] == "LLM"),
        key=lambda r: -(r["stories"] - r["clusters"]),
    )
    print(f"\n=== top {args.details} A over-merge windows (stories -> clusters), B beside ===")
    for r in ranked[: args.details]:
        b = B.get(r["window"], {})
        print(f"{r['window']}: A {r['stories']}->{r['clusters']} (mega {r['mega']}, max {r['max_cluster']})"
              f" | B {b.get('stories', '-')}->{b.get('clusters', '-')} "
              f"(mega {b.get('mega', '-')}, max {b.get('max_cluster', '-')}, mode {b.get('mode', '-')})")


if __name__ == "__main__":
    main()
