"""Offline replay harness for the News Memory clustering pipeline.

Replays real captured windows (from a Settings JSON export) through the digest
pipeline stages — near-dup merge, LLM clustering, response repair — against the
live Groq API, so prompt/model changes can be evaluated on a month of real data
before anything ships to the device.

Usage:
  python tools/eval_clustering.py --export <archive-export.json> --config A --out a.jsonl
  python tools/eval_clustering.py --export <archive-export.json> --config B --out b.jsonl

Configs:
  A = pipeline as shipped 2026-07-14: llama-3.1-8b-instant, title-only input,
      clustering-v1 prompt (same story OR closely-related topic).
  B = proposed fix set: openai/gpt-oss-120b, title+body input, clustering-v2
      prompt (same-event only + synthesized headline), degenerate-title dedup
      tokens, max_tokens guard, one re-ask on malformed response.

The export contains personal notification content — keep it (and the results)
out of git. The Kotlin sources of truth are util/Normalizer.kt,
pipeline/Deduper.kt, llm/ClusterResponseParser.kt, llm/prompts/ — keep the
ports below in sync when those change.
"""

import argparse
import json
import re
import time
import urllib.request
import urllib.error
from collections import defaultdict
from pathlib import Path

GROQ_URL = "https://api.groq.com/openai/v1/chat/completions"

# ---------------------------------------------------------------- Normalizer port

STOPWORDS = {
    "a", "an", "the", "of", "in", "on", "at", "to", "for", "by", "with", "from",
    "and", "or", "but", "as", "is", "are", "was", "were", "be", "been", "has",
    "have", "had", "will", "would", "could", "should", "may", "might", "its",
    "it", "this", "that", "these", "those", "after", "before", "over", "under",
    "up", "down", "out", "off", "amid", "vs", "via", "into", "about", "than",
    "not", "no", "new", "say", "says", "said",
}


def normalize(text):
    if not text:
        return ""
    out = []
    for ch in text.lower():
        if ch.isalnum():
            out.append(ch)
        elif ch.isspace():
            out.append(" ")
    return re.sub(r" +", " ", "".join(out)).strip()


def title_tokens(text):
    return {t for t in normalize(text).split(" ") if len(t) > 1 and t not in STOPWORDS}


def jaccard(a, b):
    if not a or not b:
        return 0.0
    inter = len(a & b)
    return inter / (len(a) + len(b) - inter)


# ---------------------------------------------------------------- Deduper port


def dedup_tokens(row, enrich_degenerate):
    """Config A: tokens from title (falls back to body) — as shipped.
    Config B: when the title yields <=2 tokens (account names, teasers),
    mix in the body so identical degenerate titles stop force-merging."""
    toks = title_tokens(row.get("title") or row.get("body"))
    if enrich_degenerate and len(toks) <= 2 and row.get("body"):
        toks = toks | title_tokens(row["body"])
    return toks


def dedup_merge(rows, enrich_degenerate=False):
    """Jaccard >= 0.55 union-find; longest headline represents (Deduper.kt)."""
    if not rows:
        return []
    tokens = [dedup_tokens(r, enrich_degenerate) for r in rows]
    parent = list(range(len(rows)))

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    for i in range(len(rows)):
        for j in range(i + 1, len(rows)):
            if jaccard(tokens[i], tokens[j]) >= 0.55:
                parent[find(i)] = find(j)

    groups = defaultdict(list)
    for i in range(len(rows)):
        groups[find(i)].append(rows[i])

    merged = []
    for members in groups.values():
        rep = max(members, key=lambda r: len(r.get("title") or r.get("body") or ""))
        sources = {r.get("publisher") or r["packageName"] for r in members}
        merged.append({"representative": rep, "members": members, "sourceCount": len(sources)})
    merged.sort(key=lambda s: -s["sourceCount"])
    return merged


# ---------------------------------------------------------------- Prompts

TAXONOMY = "Markets, AI & Agents, IPO, Policy, Tech"

PROMPT_V1 = f"""You are a news-desk editor. You are given a numbered list of news headlines and you
group them into story clusters, then label each cluster. Reply with ONLY a JSON
object — no prose, no markdown.

Rules:
- Put headlines that cover the same story, or the same closely-related topic, in one
  cluster. A headline with no relatives forms its own single-headline cluster.
- Every input id must appear in exactly one cluster. Never invent ids, never drop an
  id, never repeat an id, and never merge clearly unrelated stories.
- "representative" is the id of the single clearest, most complete headline in the
  cluster — the one whose wording best stands for the group.
- "topic" is a concise label. Prefer one of these canonical topics when it genuinely
  fits the cluster: {TAXONOMY}. Otherwise write your own 2–4 word
  topic label. Do not force an unrelated story into a canonical topic.
- "entities" are the recurring, trackable subjects of the cluster: organisations,
  people, financial instruments, or ongoing events — as short canonical names
  (e.g. "FII selling", "Sensex", "Jaishankar", "RBI"). At most 4 per cluster. Skip
  generic words. Use an empty array if there are none worth tracking.

Output schema (exactly this shape):
{{"clusters":[{{"topic":"Markets","headline_ids":[1,3],"representative":3,"entities":["Sensex","FII selling"]}}]}}"""

PROMPT_V2 = """You are a news-desk editor. You receive a numbered list of news items (headline, and
sometimes a snippet after an em dash). Group them into clusters of the SAME NEWS EVENT,
then label and summarise each cluster. Reply with ONLY a JSON object — no prose.

CLUSTERING RULE — put two items in one cluster ONLY if they report the same underlying
news event: the same announcement, match, incident, deal, or viral moment. A reader who
saw one item would learn nothing substantial from the other.
- Different outlets covering the same event MUST be merged even when worded very
  differently: "RBI holds repo rate steady at 6.5%" and "RBI keeps repo rate unchanged
  in policy review" are the same decision -> one cluster.
- NEVER group items merely because they share a topic, industry, company type, or
  category: "Zomato tests voice ordering" and "Swiggy raises funding" are both
  food-delivery business news but different events -> separate clusters. "Both are
  markets news" is NOT a reason to merge.
- Singleton clusters are normal; some windows contain no same-event pairs at all.

For each cluster:
- "topic": use one of these canonical topics when the cluster genuinely fits:
    Markets (indices, stocks, FII/DII flows, market moves)
    AI & Agents (artificial intelligence products, models, research, agents)
    IPO (IPOs, listings, fundraising rounds, venture funding)
    Policy (government policy, regulation, courts, RBI decisions)
    Tech (technology companies and products that are not AI)
  Otherwise write a short 1–3 word topic of your own (e.g. Sports, Entertainment,
  Weather, Crime, World, Politics). Never force an unrelated story into a canonical
  topic, and never use vague labels like "General" or "News".
- "headline": ONE concise, informative headline (max 16 words) stating the news of
  this cluster. Use ONLY facts present in the provided items — never add numbers,
  names, or claims that are not in the text. When an item's headline is a vague
  teaser ("Five-year low", "Apply or skip?"), rewrite it into an informative
  headline using its snippet. For multi-item clusters, cover the event, not one item.
- "headline_ids": the input ids in this cluster. Every input id must appear in
  exactly ONE cluster across the whole response — never drop, invent, or repeat ids.
- "representative": the id whose text best stands for the cluster.
- "entities": up to 4 recurring trackable subjects (organisations, people,
  instruments, ongoing events) as short canonical names, e.g. "Sensex", "RBI",
  "FII selling", "Jaishankar". Empty array if none.

Output schema (exactly this shape):
{"clusters":[{"topic":"Markets","headline":"Sensex falls 800 points as FII selling continues","headline_ids":[1,3],"representative":3,"entities":["Sensex","FII selling"]}]}"""


def build_user(stories, with_body, body_chars=160):
    lines = ["Headlines:"]
    for i, s in enumerate(stories):
        rep = s["representative"]
        title = (rep.get("title") or rep.get("body") or "").replace("\n", " ").strip()
        line = f"{i + 1}. {title}"
        body = (rep.get("body") or "").replace("\n", " ").strip()
        if with_body and body and body != title:
            line += f" — {body[:body_chars]}"
        lines.append(line)
    return "\n".join(lines)


# ---------------------------------------------------------------- Groq call


def est_tokens(text):
    return len(text) // 4 + 1


def groq_call(key, model, system, user, max_tokens=None, max_retries=4, reasoning_effort=None):
    body = {
        "model": model,
        "temperature": 0.2,
        "response_format": {"type": "json_object"},
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ],
    }
    if max_tokens:
        body["max_completion_tokens"] = max_tokens
    if reasoning_effort:
        body["reasoning_effort"] = reasoning_effort
    req = urllib.request.Request(
        GROQ_URL,
        data=json.dumps(body).encode(),
        headers={
            "Authorization": f"Bearer {key}",
            "Content-Type": "application/json",
            "User-Agent": "news-memory-eval/1.0",
        },
    )
    attempt = 0
    while True:
        try:
            with urllib.request.urlopen(req, timeout=120) as resp:
                data = json.load(resp)
            choice = data["choices"][0]
            total = (data.get("usage") or {}).get("total_tokens") or 0
            return choice["message"]["content"], choice.get("finish_reason"), total
        except urllib.error.HTTPError as e:
            # 400 included: Groq free tier intermittently 400s valid requests.
            if e.code in (400, 429, 500, 502, 503) and attempt < max_retries:
                wait = int(e.headers.get("retry-after") or 0) or min(60, 5 * 2 ** attempt)
                time.sleep(wait)
                attempt += 1
                continue
            raise
        except (urllib.error.URLError, TimeoutError):
            if attempt < max_retries:
                time.sleep(5 * 2 ** attempt)
                attempt += 1
                continue
            raise


# ------------------------------------------------- ClusterResponseParser port


class BeyondRepair(Exception):
    pass


def parse_and_repair(content, stories, expect_headline):
    """Mirror of ClusterResponseParser.parse plus the proposed headline field."""
    parsed = json.loads(content)
    clusters_in = parsed.get("clusters") or []
    if not clusters_in:
        raise BeyondRepair("no clusters")

    n = len(stories)
    seen = set()
    stats = {"dupes_dropped": 0, "out_of_range": 0, "orphans": 0, "empty_clusters": 0,
             "id_splits": 0}

    def split_concatenated(tok):
        """An out-of-range id whose digits split uniquely into two valid unseen
        ids is a dropped comma (observed live: ids 6,8 emitted as 68)."""
        s = str(tok)
        candidates = []
        for cut in range(1, len(s)):
            a, b = s[:cut], s[cut:]
            if b.startswith("0"):
                continue
            ia, ib = int(a), int(b)
            if 1 <= ia <= n and 1 <= ib <= n and ia != ib and ia not in seen and ib not in seen:
                candidates.append((ia, ib))
        return candidates[0] if len(candidates) == 1 else None

    clusters = []
    for c in clusters_in:
        ids = []
        raw_ids = []
        for i in c.get("headline_ids") or []:
            if isinstance(i, int) and i > n:
                pair = split_concatenated(i)
                if pair:
                    stats["id_splits"] += 1
                    raw_ids.extend(pair)
                    continue
            raw_ids.append(i)
        for i in raw_ids:
            if not isinstance(i, int) or not (1 <= i <= n):
                stats["out_of_range"] += 1
                continue
            if i in seen:
                stats["dupes_dropped"] += 1
                continue
            seen.add(i)
            ids.append(i)
        if not ids:
            stats["empty_clusters"] += 1
            continue
        rep = c.get("representative")
        rep = rep if rep in ids else ids[0]
        rep_row = stories[rep - 1]["representative"]
        headline = (c.get("headline") or "").strip() if expect_headline else ""
        if not headline:
            headline = (rep_row.get("title") or rep_row.get("body") or "")
        clusters.append({
            "topic": (c.get("topic") or "").strip() or "General",
            "headline": headline,
            "ids": ids,
            "representative": rep,
            "entities": [e.strip() for e in (c.get("entities") or []) if e and e.strip()][:4],
        })

    if len(seen) * 2 < n:
        raise BeyondRepair(f"coverage {len(seen)}/{n}")

    for i in range(1, n + 1):
        if i not in seen:
            stats["orphans"] += 1
            row = stories[i - 1]["representative"]
            clusters.append({
                "topic": "General",
                "headline": row.get("title") or row.get("body") or "",
                "ids": [i],
                "representative": i,
                "entities": [],
            })
    return clusters, stats


# ---------------------------------------------------------------- Configs

# Free-tier TPM for openai/gpt-oss-120b is 8000 and Groq pre-counts
# input + max_completion_tokens against it, so the completion budget must be
# derived per request, and calls must be paced to stay under the per-minute cap.
TPM_BUDGET = 7600

CONFIGS = {
    "A": dict(model="llama-3.1-8b-instant", prompt=PROMPT_V1, with_body=False,
              enrich_degenerate=False, tpm_paced=False, reask_on_malformed=False,
              expect_headline=False, reasoning_effort=None),
    "B": dict(model="openai/gpt-oss-120b", prompt=PROMPT_V2, with_body=True,
              enrich_degenerate=True, tpm_paced=True, reask_on_malformed=True,
              expect_headline=True, reasoning_effort="low"),
}


def run_window(key, cfg, bucket, rows):
    stories = dedup_merge(rows, cfg["enrich_degenerate"])
    body_chars = 160
    user = build_user(stories, cfg["with_body"], body_chars)
    # Shrink snippets until the request fits the TPM pre-check with a workable
    # completion budget left over.
    max_tokens = None
    if cfg["tpm_paced"]:
        while True:
            inp = est_tokens(cfg["prompt"]) + est_tokens(user)
            max_tokens = TPM_BUDGET - inp
            if max_tokens >= 2500 or body_chars <= 40:
                break
            body_chars -= 40
            user = build_user(stories, cfg["with_body"], body_chars)
    result = {
        "window": bucket, "raw": len(rows), "stories": len(stories),
        "mode": None, "finish_reason": None, "reask": False,
        "est_input": est_tokens(cfg["prompt"]) + est_tokens(user),
    }
    attempts = 2 if cfg["reask_on_malformed"] else 1
    last_err = None
    for attempt in range(attempts):
        try:
            content, finish, total = groq_call(key, cfg["model"], cfg["prompt"], user,
                                               max_tokens, reasoning_effort=cfg["reasoning_effort"])
            result["finish_reason"] = finish
            result["tokens"] = total
            result["raw_response"] = content[:6000]
            result["prompt_items"] = user[:4000]
            clusters, stats = parse_and_repair(content, stories, cfg["expect_headline"])
            sizes = [len(c["ids"]) for c in clusters]
            result.update(
                mode="LLM", reask=attempt > 0, clusters=len(clusters),
                multi=sum(1 for s in sizes if s > 1), max_cluster=max(sizes),
                mega=sum(1 for s in sizes if s > 4), **stats,
                output=[{
                    "topic": c["topic"], "headline": c["headline"][:140],
                    "n": len(c["ids"]), "entities": c["entities"],
                    "members": [
                        ((stories[i - 1]["representative"].get("title")
                          or stories[i - 1]["representative"].get("body") or "")[:90])
                        for i in c["ids"]
                    ],
                } for c in clusters],
            )
            return result
        except (json.JSONDecodeError, BeyondRepair, KeyError) as e:
            last_err = f"{type(e).__name__}: {e}"
            result["reask"] = attempt + 1 < attempts
            continue
        except Exception as e:
            last_err = f"{type(e).__name__}: {e}"
            break
    result.update(mode="FALLBACK", error=str(last_err)[:200])
    return result


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--export", required=True)
    ap.add_argument("--config", required=True, choices=list(CONFIGS))
    ap.add_argument("--out", required=True)
    ap.add_argument("--key-file", default="local.properties")
    ap.add_argument("--limit", type=int, default=0, help="only first N windows (0 = all)")
    ap.add_argument("--only", default=None, help="run a single window bucket, e.g. 2026-06-18-N")
    ap.add_argument("--effort", default=None, help="override reasoning_effort for this run")
    ap.add_argument("--delay", type=float, default=1.0, help="seconds between calls")
    args = ap.parse_args()

    key = None
    for line in open(args.key_file, encoding="utf-8"):
        if line.startswith("GROQ_API_KEY="):
            key = line.split("=", 1)[1].strip()
    assert key, "GROQ_API_KEY not found"

    data = json.load(open(args.export, encoding="utf-8"))
    windows = defaultdict(list)
    for r in data["rawNotifications"]:
        windows[r["windowBucket"]].append(r)
    buckets = sorted(windows)
    if args.only:
        buckets = [b for b in buckets if b == args.only]
    if args.limit:
        buckets = buckets[: args.limit]

    cfg = dict(CONFIGS[args.config])
    if args.effort:
        cfg["reasoning_effort"] = args.effort
    out = open(args.out, "w", encoding="utf-8")
    for i, bucket in enumerate(buckets):
        res = run_window(key, cfg, bucket, windows[bucket])
        out.write(json.dumps(res, ensure_ascii=False) + "\n")
        out.flush()
        print(f"[{i + 1}/{len(buckets)}] {bucket}: {res['mode']} "
              f"stories={res['stories']} clusters={res.get('clusters', '-')} "
              f"mega={res.get('mega', '-')} tok={res.get('tokens', '-')}", flush=True)
        # TPM pacing: keep the rolling per-minute token spend under the cap.
        pause = args.delay
        if cfg["tpm_paced"] and res.get("tokens"):
            pause = max(pause, 60.0 * res["tokens"] / TPM_BUDGET)
        time.sleep(pause)
    out.close()


if __name__ == "__main__":
    main()
