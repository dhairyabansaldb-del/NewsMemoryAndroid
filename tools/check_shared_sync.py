"""Verify the app and the eval harnesses really do agree.

shared/pipeline-config.json + shared/clustering-prompt-v2.txt (clustering) and
shared/entity-config.json + shared/entity-prompt-v1.txt (entity backfill) are the
single source of truth: a Gradle task generates SharedConfig.kt from all four, and
the harnesses read them directly. This script checks the *generated Kotlin* against
the values the harnesses actually load, so a broken codegen (bad escaping, a renamed
key, a stale build) is caught rather than silently producing an eval that measures a
configuration the app doesn't ship.

    ./gradlew.bat generateSharedConfig     # refresh the generated Kotlin first
    python tools/check_shared_sync.py      # exit 0 = in sync, 1 = divergence

Worth running after touching shared/, the codegen task, or either prompt.

Adding a const to the codegen without adding a check row here used to be a silent
hole — the new key simply went unguarded. `unchecked consts` closes it: every
`const val` (plus STOPWORDS) in the generated file must be read by some row below,
so forgetting one now fails the check instead of nothing.
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GENERATED = ROOT / "app/build/generated/sharedconfig/com/dhairya/newsmemory/SharedConfig.kt"

sys.path.insert(0, str(ROOT / "tools"))
import eval_clustering as ev  # noqa: E402  (path set above)
import eval_entities as ee  # noqa: E402  (imports eval_clustering; see tools/README.md)

# Every generated name a check row actually reads — see "unchecked consts" in main().
READ = set()


def kotlin_const(src, name):
    READ.add(name)
    m = re.search(rf"const val {name}(?::\s*\w+)? = (.+)", src)
    if not m:
        raise SystemExit(f"could not find const {name} in generated Kotlin")
    return m.group(1).strip()


def unescape_kotlin_string(literal):
    """Inverse of the escaping in app/build.gradle.kts GenerateSharedConfig.esc()."""
    s = literal.strip()
    if not (s.startswith('"') and s.endswith('"')):
        raise SystemExit(f"expected a quoted string, got: {s[:40]}")
    body = s[1:-1]
    out, i = [], 0
    while i < len(body):
        ch = body[i]
        if ch == "\\" and i + 1 < len(body):
            nxt = body[i + 1]
            out.append({"n": "\n", '"': '"', "\\": "\\", "$": "$"}.get(nxt, nxt))
            i += 2
        else:
            out.append(ch)
            i += 1
    return "".join(out)


def main():
    if not GENERATED.exists():
        raise SystemExit(f"generated Kotlin not found at {GENERATED}\n"
                         f"run: ./gradlew.bat generateSharedConfig")
    kt = GENERATED.read_text(encoding="utf-8")

    stopwords_block = re.search(r"STOPWORDS[^=]*= setOf\((.*?)\)", kt, re.S).group(1)
    kt_stopwords = set(re.findall(r'"([^"]+)"', stopwords_block))
    READ.add("STOPWORDS")   # read by the regex above, not through kotlin_const

    checks = [
        ("clusteringModel", kotlin_const(kt, "CLUSTERING_MODEL").strip('"'), ev.CLUSTERING_MODEL),
        ("reasoningEffort", kotlin_const(kt, "REASONING_EFFORT").strip('"'), ev.REASONING_EFFORT),
        ("tpmBudget", int(kotlin_const(kt, "TPM_BUDGET")), ev.TPM_BUDGET),
        ("minCompletionTokens", int(kotlin_const(kt, "MIN_COMPLETION_TOKENS")), ev.MIN_COMPLETION_TOKENS),
        ("defaultBodyChars", int(kotlin_const(kt, "DEFAULT_BODY_CHARS")), ev.DEFAULT_BODY_CHARS),
        ("jaccardThreshold", float(kotlin_const(kt, "JACCARD_THRESHOLD")), ev.JACCARD_THRESHOLD),
        ("promptVersion", kotlin_const(kt, "PROMPT_VERSION").strip('"'), ev.SHARED["promptVersion"]),
        ("stopwords", kt_stopwords, ev.STOPWORDS),
        ("systemPrompt", unescape_kotlin_string(kotlin_const(kt, "CLUSTERING_SYSTEM_PROMPT")), ev.PROMPT_V2),

        # --- entity extraction (shared/entity-config.json + the entity prompt) ---
        ("entity promptVersion", kotlin_const(kt, "ENTITY_PROMPT_VERSION").strip('"'), ee.CFG["promptVersion"]),
        ("entity entityModel", kotlin_const(kt, "ENTITY_MODEL").strip('"'), ee.ENTITY_MODEL),
        ("entity reasoningEffort", kotlin_const(kt, "ENTITY_EFFORT").strip('"'), ee.REASONING_EFFORT),
        ("entity batchSize", int(kotlin_const(kt, "ENTITY_BATCH_SIZE")), ee.BATCH_SIZE),
        ("entity maxEntitiesPerItem", int(kotlin_const(kt, "ENTITY_MAX_PER_ITEM")), ee.MAX_ENTITIES),
        ("entity tpmBudget", int(kotlin_const(kt, "ENTITY_TPM_BUDGET")), ee.TPM_BUDGET),
        ("entity completionBase", int(kotlin_const(kt, "ENTITY_COMPLETION_BASE")), ee.COMPLETION_BASE),
        ("entity completionPerItem", int(kotlin_const(kt, "ENTITY_COMPLETION_PER_ITEM")), ee.COMPLETION_PER_ITEM),
        ("entity minCompletionTokens", int(kotlin_const(kt, "ENTITY_MIN_COMPLETION_TOKENS")),
         ee.MIN_COMPLETION_TOKENS),
        ("entity bodyChars", int(kotlin_const(kt, "ENTITY_BODY_CHARS")), ee.BODY_CHARS),
        ("entity systemPrompt", unescape_kotlin_string(kotlin_const(kt, "ENTITY_SYSTEM_PROMPT")), ee.PROMPT),
    ]

    failures = 0
    for name, from_kotlin, from_python in checks:
        if from_kotlin == from_python:
            print(f"  ok        {name}")
            continue
        failures += 1
        print(f"  MISMATCH  {name}")
        if isinstance(from_kotlin, set) and isinstance(from_python, set):
            print(f"      only in Kotlin: {sorted(from_kotlin - from_python)}")
            print(f"      only in Python: {sorted(from_python - from_kotlin)}")
        elif isinstance(from_kotlin, str) and "\n" in (from_kotlin + str(from_python)):
            k_lines, p_lines = from_kotlin.splitlines(), str(from_python).splitlines()
            print(f"      kotlin {len(k_lines)} lines, python {len(p_lines)} lines")
            for i, (a, b) in enumerate(zip(k_lines, p_lines)):
                if a != b:
                    print(f"      first diff at line {i + 1}:\n        kotlin: {a!r}\n        python: {b!r}")
                    break
        else:
            print(f"      kotlin={from_kotlin!r}  python={from_python!r}")

    # A generated const with no check row is unguarded config — the drift this whole file
    # exists to catch, arriving through the one door it used to leave open.
    unchecked = sorted(set(re.findall(r"const val (\w+)", kt)) - READ)
    if unchecked:
        failures += 1
        print(f"  UNCHECKED {', '.join(unchecked)}")
        print("      generated but never compared — add a row to `checks` above")

    print()
    if failures:
        print(f"DIVERGENCE: {failures} value(s) differ between the app and the harness.")
        return 1
    print("ALL IN SYNC — the harness evaluates the configuration the app ships.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
