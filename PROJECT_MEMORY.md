# PROJECT_MEMORY.md — News Memory (Android)

> Context-handoff doc for an engineer with zero prior context. Prioritizes the
> non-obvious: decisions, reasoning, history, gotchas. Last updated 2026-08-11.
> **Start here: §8 (current state), §5.1 (why Phase 5 looks the way it does), and
> §5.2 (v1 and why the archive needed backfilling).**

**Naming note:** the app is **News Memory** (package `com.dhairya.newsmemory`). The
working folder is `C:\Signals Noted project` and an early idea name was "Signals Noted";
ignore both — the shipped name is "News Memory". **"Marginalia" is a *different*, earlier
web project by the same owner** (Dhairya); the design docs cite it only as precedent for
the Groq/Kotlin stack. There is no Marginalia code here.

This project uses **v0 / v1 / v2** phasing, not "V1/V1.5". Map accordingly: v0 = the
internal data-pipe milestone (**shipped, in daily use since 2026-07**), v1 = the shippable
memory layer, i.e. recurrence (**code-complete 2026-08-11, not yet verified on device** — §8),
v2 = future graph UI.

---

## 1. PROJECT OVERVIEW

News Memory is a **personal, single-user Android app** (the owner is the primary user;
it's also a portfolio piece). It is **not** a notification noise-reducer — that framing
was explicitly rejected. It is a **personal news memory layer**:

- A background `NotificationListenerService` captures notifications from a user-chosen
  allowlist of news apps.
- It **intercepts** them (removes them from the system shade) and stores them in an
  on-device archive.
- Three times a day it produces a **Digest**: near-duplicate stories merged, grouped by
  topic, delivered as one push.
- The real value (v1, built) is **recurrence surfacing**: the archive notices what is
  *building* across days ("4th story on FII selling this week") by counting, not by
  interpreting headlines.

Core problem: news arrives as fragmented one-line pushes that don't *retain*; multi-day
through-lines are lost. Existing tools deliver/bundle news but don't *remember* it.

The product thesis is **deliberate cadence + memory**. Breaking news intentionally does
NOT reach the user in real time — only via the three digests. This is a feature.

---

## 2. ARCHITECTURE

**Single Android app, no backend, no auth, no accounts.** The only external call is to the
Groq API (live since Phase 5), at most ~4×/day. Everything else is on-device.

```
Android OS notifications
   → NewsListenerService (allowlist gate FIRST, then capture + intercept)
   → Room DB ("the archive" — single source of truth)
   → DigestPipeline (WorkManager + AlarmManager, 3×/day)
        → near-dup merge → cluster (Groq, heuristic fallback) → assemble → 1 push
   → Compose UI (reads the archive; "Almanac" design)
   → [v1] Memory engine: recurrence counting over the archive
```

**Tech stack** (exact versions in `gradle/libs.versions.toml`):
- **Kotlin 2.1.0 + Jetpack Compose** (BOM 2024.12.01) + Material 3. Why: `NotificationListenerService`
  is a native Android API; cross-platform would still need a native module. Compose chosen
  over XML for less boilerplate.
- **Room 2.6.1** over SQLite (the archive is the most important artifact; compile-checked
  queries + migrations + Flow). **DataStore 1.1.1** for prefs (allowlist, digest times, theme).
- **WorkManager 2.10.0 + AlarmManager** for scheduling (exact-while-idle alarm fires the
  slot; WorkManager does the pipeline work with retries).
- **Ktor 3.0.3 (CIO)** + kotlinx-serialization for the Groq call (`llm/GroqClient`).
- **No DI framework.** Hand-rolled `AppContainer` (created in `App.onCreate()`) exposes
  singletons. Hilt/Koin rejected as overkill for a single-module first Android app.
- **AGP 8.7.3, minSdk 29, targetSdk 35, compileSdk 35.** Target device: Samsung Galaxy
  S24 FE (One UI). Distribution: sideloaded debug APK.

**Cost of the whole stack: ₹0.** Groq free tier (~14,400 req/day limit vs ~4 used).

**Key directories** (`app/src/main/java/com/dhairya/newsmemory/`):
- `capture/` — `NewsListenerService`, `NotificationExtractor`, `CapturePolicy`, `ListenerRebinder`
- `data/` — `SettingsStore`, `NotificationRepository`, `db/` (entities, DAOs, `ArchiveDatabase`)
- `pipeline/` — `DigestPipeline`, `Deduper`, `HeuristicClusterer`, `Windows`, `DigestScheduling`,
  `Workers`, `DigestNotifier`
- `ui/` — `theme/`, `components/`, `home/`, `digest/`, `archive/`, `allowlist/`, `settings/`, `onboarding/`
- `llm/` — `GroqClient` (transport only), `GroqClusterEngine` + `ClusterResponseParser`,
  `EntityExtractor` + `EntityResponseParser`, `prompts/`
- `memory/` — `RecurrenceEngine` (counting, no Groq) and `EntityBackfill` (v1, §5.2)
- `AppContainer.kt`, `App.kt`, `MainActivity.kt`
- `shared/` (repo root) — the cross-language config both Kotlin and the Python harnesses read
  (§7). Generated into `SharedConfig.kt` at build time.

Build/run: see §7.

---

## 3. DATA MODEL

Room DB `archive.db`, **currently version 2**. Six tables; all six (including v1 tables)
shipped in v0 so there is **no migration between v0 and v1** — v1 is new *readers* of
existing data. Schema JSON is committed at `app/schemas/.../1.json` and `2.json`.

- **`raw_notifications`** — every captured notification, immutable. Key fields:
  `package_name`, `publisher` (source *within* the app, e.g. "The Verge"; nullable),
  `title` (RESOLVED headline, **not** raw `EXTRA_TITLE`), `body` (resolved snippet),
  raw payload preserved: `raw_title`, `raw_text`, `big_text`, `sub_text`, `text_lines`,
  `template`, `parse_quality`. Plus `content_hash`, `posted_at`, `captured_at`,
  `window_bucket`, `window_id`.
  - **Non-obvious constraint:** `UNIQUE(content_hash, window_bucket)` drops exact dupes at
    insert time. `content_hash = sha256(normalize(title)+"|"+normalize(body))`.
- **`digests`** — one row per run, **including empty ones**. PK `id` = `window_id`
  (`"YYYY-MM-DD-{M|E|N}"`), which is the **idempotency key for the whole pipeline**.
  `pipeline_mode` = `LLM` | `HEURISTIC` (degradation is visible, never silent).
  `opened_at` null = never opened (archived silently).
- **`digest_items`** — one merged story cluster. `topic_label`, `headline`, `source_count`, `position`.
- **`item_sources`** — which raw notifications fed which cluster (item_id, raw_id).
- **`entities`** + **`item_entities`** — canonical recurring subjects. Merge key is
  `normalized` (UNIQUE). This is the substrate that makes recurrence a pure counting query.
  **Written only on the LLM clustering path** — heuristic digests write none, which is why
  most of the historical archive needed backfilling (§5.2). Also written by `EntityBackfill`.

Recurrence (v1) = `COUNT(DISTINCT digest_items)` joined through `item_entities` for an
entity over a trailing window. A `recurrence_threads` table was deliberately NOT created —
threads are derived (entity + date range), not stored, to avoid a sync problem.

---

## 4. KEY DECISIONS & RATIONALE (most important)

1. **Memory layer, not noise-reducer.** Rejected a pure noise-reducer: free behaviours
   (mute apps, 3 alarms) replicate it, and OS makers ship summarization with deeper access.
   The defensible core is the *archive + recurrence*.

2. **Hybrid clustering: heuristic always, Groq on top.** Stage-2 Jaccard near-dup merge
   (`Deduper`, threshold 0.55, union-find) runs **always** — it's the v0 dedup guarantee
   independent of Groq and shrinks the LLM payload. Groq adds same-event clustering, topic
   labels, a synthesized headline + entities; on any failure we fall back to heuristic and
   tag the digest `HEURISTIC`. Trade-off: heuristic topic labels are bad (single top-TF
   token → "Dropping", "Kishans"). Accepted because Groq replaces them and degradation is
   visible. **The fallback being silent-by-design is exactly how the pipeline once sat in
   permanent heuristic mode unnoticed — see §5.1.**

3. **Fully on-device, no backend.** A year of headlines is ~36 MB. Rejected Supabase. This
   is also the strongest version of the privacy goal. Manual JSON export (SAF) is the only
   data-egress hatch (shipped, `371ce94`).

4. **Schema is a first-class deliverable, shipped whole in v0.** All v1 tables exist now so
   there's no migration mid-project, and by v1 day one the archive already holds weeks of
   entity data. Entities are written in v0 even though nothing reads them yet.

5. **Recurrence = counting only, never LLM judgement on individual headlines.** This is the
   explicit defence against "clickbait inheritance" — headlines are engagement bait, so the
   memory layer must stay in counting/threading territory.

6. **INTERCEPTION (reversal of original design).** The PRD/SAD originally said the app is
   "a listener, not a manager" and "never dismisses other apps' notifications." The owner
   **reversed this on 2026-06-17**: News Memory now calls `cancelNotification(key)` after
   capture so allowlisted apps' notifications leave the shade entirely. Global, all
   allowlisted apps. Documented in `docs/ADDENDUM-interception.md`. Trade-offs accepted:
   can't pre-empt a post (brief blip possible), and if the listener is dead notifications
   appear normally (fail-open).

7. **No live feed.** Even with interception, intercepted notifications surface ONLY via the
   3 digests + Archive — consistent with the deliberate-cadence thesis. A Block-style
   real-time list was explicitly declined.

8. **Per-app interception NOT offered; unparseable apps are flagged, not excluded.** Apps
   whose notifications can't be parsed get a "limited support" flag surfaced in the allowlist.

9. **One UI survival strategy.** Samsung kills bound services. Mitigations: battery →
   Unrestricted (enforced in onboarding), a 30-min `ListenerRebinderWorker` that toggles the
   component + `requestRebind` when the heartbeat is stale, and a visible heartbeat so dead
   time shows in the health panel. Accepted limitation: notifications posted while the
   listener is dead are unrecoverable.

10. **Chained one-at-a-time exact alarms**, not a repeating alarm — Android limits idle-exact
    alarms. Plus an hourly catch-up WorkManager job: "late digest beats lost digest", and
    idempotency keys make double-runs harmless.

11. **Almanac UI** (the locked redesign). Custom warm-cream light + after-dark themes (NOT
    Material dynamic color), bundled Newsreader/Instrument Sans/Space Grotesk variable fonts.
    Default theme **Auto** (two of three daily reads land after dark).

---

## 5. HISTORY: v0 build → now

Built phase-by-phase, each phase committed and verified on-device on the S24 FE. Git log
is the source of truth; summary:

- **Phase 1** (`03532be`) — scaffold, full 6-table Room schema, DAOs, `SettingsStore`.
- **Phase 2** (`842c61a`) — 3-step onboarding (notification access, POST_NOTIFICATIONS,
  exact alarms + battery), allowlist picker.
- **Phase 3** (`e266e25`) — real listener, insert path, heartbeat, rebinder.
- **Phase 4** (`4bac7ea`) — scheduler, worker, heuristic pipeline, push, first digest/archive UI.
- **Then the owner reviewed real output and flagged two problems → a mid-course pivot:**
  - **Phase A** (`557b61d`, calibrated in `19afa41`) — capture/encoding was wrong: for
    aggregator apps the digest showed publisher/app names ("The Verge", "inshorts") instead
    of headlines. Root cause: old code rendered `title ?: body`, but Google News puts the
    publisher in `EXTRA_TITLE` and the headline in `EXTRA_TEXT`; Inshorts puts the app name
    in `EXTRA_TITLE`; Dailyhunt leaves title null. Fixed with `NotificationExtractor`
    (shape classifier) + schema v2 (publisher + raw payload). **Calibrated against a live
    `adb shell dumpsys notification` dump** of the owner's actual apps.
  - **Phase B** (folded into `557b61d` / `093edb7`) — interception (decision #6).
- **Phase C** (`0e4f26c`) — the Almanac UI redesign (theming, fonts, all screens, bottom nav).
- **Phase C feedback** (`bfbedf8`) — hero card now shows the most *recent* digest (was: first
  non-empty slot in M→E→N order, so a 6-story Evening hid a 34-story Night); Archive became a
  month calendar (tap a date → that day's three slots).
- **Phase 5 (Groq), built in four commits** — see §5.1 below for the full arc:
  - `b27ac42` — `llm/` package: Ktor `GroqClient`, versioned prompt, strict response
    validation, `GroqClusterEngine` with heuristic fallback, wired via `AppContainer` only
    when a key is present.
  - `371ce94` — JSON archive export via SAF (the EDD §3 egress hatch; was a dead stub).
  - `65361b9` — re-arm the alarm when digest times change (a time change previously took
    effect only after the *next* digest ran); `GroqCluster` debug logging.
  - `5567b78` — parser repairs LLM responses instead of rejecting them.
  - `47fe0eb` — **clustering-v2**: the quality fix set (§5.1).

- **v1 (recurrence), built 2026-08-11 across five parallel tracks** — see §5.2.

What's NOT shipped: the one-shot query screen (EDD §7.2), deliberately deferred.

---

## 5.2 v1 (RECURRENCE): WHAT WAS BUILT, AND THE THING THAT NEARLY WASN'T NOTICED

**The finding that reshaped the plan.** Entities are written at exactly one site —
`DigestPipeline` — guarded by `if (cluster.entities.isNotEmpty())`, and `HeuristicClusterer`
never sets them. So **heuristic-mode digests wrote ZERO entity rows.** In the July export that
meant 87 of 96 digests contributed nothing: 88 entities across 3,008 digest items. Recurrence
counts over `item_entities`, so the archive looked full and was almost entirely uncountable.
Without backfill the 30-day kill-gate clock would have restarted from the day v1 shipped.

**What shipped:**
- **`memory/RecurrenceEngine`** — three counting rules (≥3 items in 7 days, ≥3 publishers in one
  digest, ≥5 distinct days in 30), one flag per item, capped at 5 per digest, deterministic
  tie-break so repeated runs over unchanged data agree. **No Groq client, by design** (§4.5).
- **Derived at read time.** `digest_items` has no flag column, so v1 needed **no migration** —
  which is what made removing `fallbackToDestructiveMigration()` safe to do in the same breath.
- **`memory/EntityBackfill` + `llm/EntityExtractor`** — fills entities for historical items.
- **UI**: recurrence chips on story cards; the "What's building" card is real (7×4 dot matrix,
  today bottom-right, drawn from `activeDayOffsets`).

**Backfill progress is a WATERMARK, not a "has entities" check.** This is the non-obvious part.
Progress cannot be inferred from whether an item has entities, because **zero entities is a
correct and common answer** — teasers, app promos and junk headlines legitimately have no
recurring subject. Those rows would look unstarted forever and be re-extracted every run, so the
job would never terminate. `digest_item` ids are autoincrement, so "already attempted" is exactly
`id <= watermark`, held in DataStore. It advances **only after a batch succeeds**, so a failed
call is retried rather than skipped. Consequence worth knowing: `reset()` re-offers only items
that extracted to *zero* — items that already got entities are excluded by the query's
`ie.item_id IS NULL` and a prompt change can never revise them.

**Extraction failure THROWS rather than degrading**, unlike clustering. There is no heuristic
entity extractor to fall back to, and a throw is what leaves the watermark unadvanced.
`BatchOutcome.failed` exists because otherwise a failed batch and an empty queue are both
`(0, 0, n)` and Settings would report "3000 to go" forever — the same silent degradation that
hid the permanent heuristic fallback (§5.1).

**Two capture-side fixes were sequenced BEFORE backfill deliberately.** Publisher/account-name-only
titles now classify UNPARSEABLE, *and* `DigestPipeline` now filters them out — labelling alone
wasn't enough, they were still being presented as stories. This had to land first: an account
name recurs every single day, so a junk row admitted to the archive would have outranked every
real story in the memory layer. `tools/eval_clustering.py` applies the same filter, or a replay
would measure a pipeline the app doesn't ship.

**Landmine found by building in fresh worktrees:** `GenerateSharedConfig.esc()` escaped `\n` but
not `\r`. With `core.autocrlf=true` and no `.gitattributes`, **any fresh clone on Windows failed
to compile** (~200 errors in generated code). A long-lived working tree hides it because its copy
is already LF. Fixed in `esc()` *and* pinned via `.gitattributes`.

---

## 5.1 PHASE 5 (GROQ): WHY IT LOOKS THE WAY IT DOES

Phase 5 took three rounds of real-world correction. The *end state* is simple; the reasons
are not, and every one of them is a trap you'd fall back into by "simplifying".

**Round 1 — validation was too strict (fixed in `5567b78`).** The EDD says the LLM response
is valid only if the returned ids *partition* the input set. Enforced literally, that
rejected essentially every real response: at ~30 headlines the model reliably fumbles id
bookkeeping (a duplicate id, a dropped one) even when the grouping is good. The pipeline sat
in permanent heuristic fallback and nobody noticed, because fallback is silent by design.
**`ClusterResponseParser` now REPAIRS rather than rejects** — dedupe ids, drop out-of-range,
unplaced ids become singleton clusters, blank topic gets a heuristic label. A throw (→
heuristic) is reserved for real failure: malformed JSON, empty cluster list, or the model
placing under half the headlines.

**Round 2 — the owner used it for two days and it was WORSE than the heuristic.** Four
distinct failures, four distinct root causes (all fixed in `47fe0eb`):
1. **Topical mega-clusters.** The v1 prompt permitted grouping by "the same closely-related
   topic", so the model built topic buckets ("all business news") instead of same-event
   clusters — worst case, ten notifications under one "Inc42" card. Because a digest card
   shows only the representative headline, every swallowed story became *invisible*, and
   because items sort by summed source count, the garbage floated to the top.
2. **Headline hid half the merge.** The prompt could only *pick* an input headline
   ("never invent headlines" — the anti-hallucination rule), so a correct 2-source merge
   still showed one side only.
3. **Wrong topic labels**, and teaser-title starvation: 36% of captured titles are ≤3 words
   ("Five-year low", "Apply or skip?", or an X account name as the title) — the model was
   clustering nearly blind because only titles were sent.
4. **Residual heuristic fallbacks.**

**The fix was validated BEFORE shipping, on 30 days of real captured data.** Rather than
iterate on-device a day at a time, `tools/eval_clustering.py` replays every captured window
from a Settings JSON export through the real pipeline against the live Groq API. Config A
(as-shipped) vs config B (proposed) over 37 comparable windows:

| metric | A | B |
|---|---|---|
| mega-clusters (>4 stories) | 70 | **0** |
| windows containing one | 28/37 | **0/37** |
| worst single cluster | 38 stories | 2 stories |

Every merge B made was hand-checked and correct. **Keep this harness working** — it is the
only way to evaluate a prompt/model change without burning days of live use.

**What clustering-v2 actually changed** (don't undo these):
- **Prompt is same-EVENT-only**, with explicit negative examples ("both are markets news" is
  not a reason to merge) and a stated expectation that singleton clusters are normal.
- **A synthesized `headline` per cluster**, capped at 16 words and constrained to facts
  present in the inputs — the deliberate, contained relaxation of "never invent headlines"
  (the raw-notification drawer still shows the true sources underneath).
- **`title + body` snippet sent per story**, not title alone — this is what rescues teaser
  titles and account-name titles.
- **Model is `openai/gpt-oss-120b` at `reasoning_effort=low`** (llama-3.1-8b showed a hard
  capacity ceiling — flawless at 15 headlines, sloppy by 30; and Groq is deprecating
  llama-3.3). `low` beat `medium` in the replay: same merge quality, half the tokens, and
  large windows stopped truncating.
- **`Deduper` mixes body tokens in when a title is degenerate (≤2 tokens).** Rows sharing a
  useless title ("Inc42") have Jaccard 1.0 with each other, so stage 2 was force-merging
  unrelated stories *before Groq ever saw them*. This one is upstream of the LLM entirely.
- **Concatenated-id repair**: gpt-oss-120b's signature mistake is a dropped comma, emitting
  ids 6 and 8 as `68`. Split it when the split is unique.
- **One re-ask on a malformed response** before falling back (the model is non-deterministic
  enough that a retry often succeeds). A *network/HTTP* failure does not re-ask — `GroqClient`
  already retried internally.
- **Per-call token budgeting.** The free tier pre-counts input + `max_completion_tokens`
  against an 8000 TPM cap, so the completion budget is derived from actual prompt size (and
  body snippets shrink if a huge digest won't fit). `GroqClient` also retries HTTP 400 —
  the free tier intermittently 400s valid requests under load.

**Verified live on-device 2026-08-10:** `LLM ok: 15 stories → 15 clusters via
openai/gpt-oss-120b` — zero mega-clusters, informative synthesized headlines, correct topic
chips, no "basic grouping" tag.

---

## 6. GOTCHAS & LANDMINES

- **`EXTRA_TITLE` is often NOT the headline.** Aggregators (Google News, pkg
  `com.google.android.apps.magazines`) put the publisher there; Inshorts (`com.nis.app`)
  puts the app name; Dailyhunt (`com.eterno`) leaves it null with the headline in
  `EXTRA_TEXT`. `NotificationExtractor` encodes these rules. Moneycontrol (`com.divum.*`)
  is genuinely inconsistent — we store both title and text and let Groq pick later. **If
  you "simplify" the extractor back to title-first, you reintroduce the original bug.**
- **Aggregator with junk/empty body must be UNPARSEABLE, never fall back to the publisher
  name as a headline.** There's a `JUNK` set (e.g. "you have a notification") for this.
- **Listener only gets `onNotificationPosted` for posts AFTER it connects.** After
  `adb install -r` the listener rebinds and anything already in the shade is never
  delivered → not captured, not intercepted. Fixed: `onListenerConnected` sweeps
  `getActiveNotifications()` through the same path (look for `fromBacklog`). This was a
  real bug found on-device.
- **Schema v2 uses `fallbackToDestructiveMigration()`** — installing over an older schema
  WIPES the archive. Fine now (pre-accumulation) but **must be replaced with a real
  migration before the 30-day v1 data-accumulation window starts.**
- **WorkManager is on-demand initialized** via `App : Configuration.Provider` + a manifest
  `<provider tools:node="remove">` for `WorkManagerInitializer`. Scheduling work in
  `App.onCreate()` crashes Robolectric tests otherwise (this bit us — 15 tests went red).
- **Source counts key on `publisher`, not `package_name`** (so multiple Google News outlets
  count as separate sources). See `Deduper` and `DigestPipeline`.
- **One UI / Samsung Auto Blocker** blocks both sideloading and USB debugging by default;
  it must be turned off to install. The misleading "problem parsing the package" error is
  usually Auto Blocker or a full disk, not a bad APK.
- **`NewsListenerService` has debug `Log.d(TAG="NewsListener")` left in** intentionally to
  watch capture/interception during testing. Quiet it before declaring v0 final.
- **`com.wverlaek.block` (the "Block" app)** is also an active notification listener on the
  test device — independent of us but can act on the same notifications; don't be confused
  if shade behaviour looks odd during testing.
- **Variable fonts need `@OptIn(ExperimentalTextApi::class)`** (FontVariation) — already set
  at the top of `ui/theme/Type.kt`.
- **Shell can no longer broadcast to `DigestAlarmReceiver`** (it's not exported; worked
  2026-06-19, silently blocked by 2026-07-14 after a One UI tightening). `am broadcast` still
  prints `result=0` — the denial only appears in `dumpsys activity broadcasts`. Working
  trigger for a manual digest:
  ```
  adb shell run-as com.dhairya.newsmemory am broadcast --user 0 \
    -n com.dhairya.newsmemory/.pipeline.DigestAlarmReceiver -e window_id 2026-08-10-E
  ```
  `--user 0` is required (the default user -2 throws INTERACT_ACROSS_USERS).
- **Don't try to read `archive.db` off the device on this host.** The pull is byte-identical
  (md5 matches) but the device WAL can't be replayed by the host's Python 3.9 sqlite, so
  every table reads as empty; there's no `sqlite3` binary on device or host. Verify state
  through the app UI instead: `adb shell am start -n com.dhairya.newsmemory/.MainActivity
  --es digest_id <window_id>` **on a cold start** (a warm activity ignores the extra), plus
  logcat.
- **`GroqCluster` logcat tag is the definitive pipeline-mode signal.** `adb logcat
  GroqCluster:V '*:S'` prints either `LLM ok: N stories → M clusters via <model>` or the
  failure reason plus `→ heuristic fallback`. Note the digest UI's "basic grouping" tag means
  the same thing — its *absence* is the success signal.
- **USB debugging gets turned off across long gaps.** Re-enable: Settings → About phone →
  Software information → tap Build number ×7 → Developer options → USB debugging. If the
  device still won't authorise, check Auto Blocker (Security and privacy).

---

## 7. CONVENTIONS

- **Single `:app` module.** Package-by-feature under `com.dhairya.newsmemory`.
- **No DI framework** — constructor injection from `AppContainer`. Don't add Hilt.
- **Pure logic extracted for testing:** `CapturePolicy`, `NotificationExtractor`, `Deduper`,
  `WindowCalculator`, `RebinderLogic`, `ClusterResponseParser` are pure objects with unit
  tests. Prefer this pattern — it's why the LLM repair logic is testable without a network.
- **Tests:** JUnit + Robolectric + in-memory Room; Ktor `MockEngine` for the Groq path (no
  network in tests). 174 unit tests currently green. Run:
  ```
  $env:JAVA_HOME="C:\Users\bandh\android-tools\jdk\jdk-17.0.19+10"
  .\gradlew.bat testDebugUnitTest
  ```
- **Build / install (no Android Studio required):**
  ```
  .\gradlew.bat assembleDebug
  C:\Users\bandh\android-tools\sdk\platform-tools\adb.exe install -r app\build\outputs\apk\debug\app-debug.apk
  ```
  JDK 17, Android SDK, and Gradle live under `C:\Users\bandh\android-tools\` (this machine
  had no toolchain; it was installed from scratch). `JAVA_HOME`/`ANDROID_HOME` set at user scope.
- **Secrets:** Groq key goes in `local.properties` as `GROQ_API_KEY` (gitignored) → exposed
  via `BuildConfig.GROQ_API_KEY`. Never commit it.
- **`shared/` is the single source of truth for anything the app and the eval harness must
  agree on** — clustering model, reasoning effort, token budget, Jaccard threshold,
  stopwords, and the prompt text itself. Kotlin reads it at *build* time (a Gradle task
  generates `SharedConfig.kt`, so they stay compile-time constants — no runtime parsing on
  the digest path); `tools/eval_clustering.py` reads it at *run* time. **Never copy a value
  out of `shared/` into either language** — that reintroduces the drift this prevents, and
  a harness that has drifted is measuring an app you don't ship. Verify with
  `./gradlew.bat generateSharedConfig && python tools/check_shared_sync.py`.
  App-internal constants with no harness counterpart (HTTP retries, timeouts, heartbeat
  intervals, channel ids) deliberately stay in their own companion objects.
- **Commits:** one per phase, descriptive multi-line body, co-authored trailer. Work happens
  on `master`; `main` is the nominal default. Commit messages written via a temp file
  (`.git/COMMIT_MSG.tmp` + `git commit -F`) because PowerShell here-strings mangled them.
- **gitignored:** `local.properties`, `*.apk`, `notif-dump*.txt` (contain personal data),
  build dirs.

---

## 8. CURRENT STATE — v1 is code-complete and UNVERIFIED ON DEVICE

**Done:** Phases 1–4, A, B, C, Phase C feedback, Phase 5 (Groq, §5.1), and **v1 (recurrence,
§5.2)**. HEAD is `f210f51`, working tree clean, pushed to
`github.com/dhairyabansaldb-del/NewsMemoryAndroid` (branch `master`). **174 unit tests green,
`assembleDebug` clean, zero compiler warnings, `check_shared_sync.py` in sync.**

v0's gate is **passed by observation, not by a formal test**: three weeks of daily use, digests
arriving on schedule and the listener surviving overnight — you'd have noticed weeks of silence.
Treat overnight survival as confirmed empirically but never instrumented.

**Exactly where we left off — READ THIS FIRST:** every line of v1 is written and unit-tested,
and **none of it has run on the phone.** The device has not been connected since v1 began. In
particular the entity backfill has **never made a real Groq call** — every test uses MockEngine.
Nothing is in flight in git; the gap is verification, not code.

**The immediate next step is on-device verification, in this order:**
1. Install, open Settings → **"Build memory from archive"**. Tap once and watch one batch of 12.
   `adb logcat EntityBackfill:V '*:S'` prints attempted/linked/watermark per batch.
2. **Hand-check the entities it wrote before letting it drain.** This is the §5.1 discipline: LLM
   output quality on real notification text is not settled by unit tests. If they look wrong,
   change `shared/entity-prompt-v1.txt`, `reset()`, and re-run — that is what reset is for.
   `tools/eval_entities.py` can rehearse the same prompt offline against an export first.
3. Let it drain (~250 batches, hourly worker, bounded 12/tick), then confirm recurrence chips
   appear on genuinely recurring stories and "What's building" shows a real entity.
4. Confirm no clustering regression: `adb logcat GroqCluster:V '*:S'` still prints
   `LLM ok: N stories → M clusters`.

**Then the 30-day kill-gate** — the real go/no-go, and a *product* question no amount of code
quality settles: do the recurrence pushes surface things the owner didn't already know?
Backfill is what lets this be judged on June–August history instead of restarting the clock.

---

## 9. OPEN THREADS / TODOs

Blocking the kill-gate:
- **Nothing in v1 has run on the device.** See §8. The backfill has never made a live Groq call.
- **Entity extraction quality is unvalidated on real data.** The offline harness
  (`tools/eval_entities.py`) exists precisely so this can be checked before trusting it; it has
  not been run against a real export. Its headline metric is **countable share** — the fraction
  of extracted mentions whose normalised key appears in 2+ digest items. Everything outside it
  is a row that can never produce a recurrence. Gate around ~40%, then read samples by hand.

Known gaps (real, not blocking):
- **"What's building" is not tappable.** The design says tapping opens that signal's cluster;
  there is no navigation callback and no destination screen yet. A comment marks the spot.
- **`llama-3.3-70b-versatile` (EDD §7.2, the one-shot query) is being deprecated by Groq.** The
  query screen is deferred; pick a replacement when building it. `openai/gpt-oss-120b` is the
  obvious candidate — it is already proven here for both clustering and extraction.
- **The two eval harnesses are coupled.** `eval_entities.py` imports `eval_clustering.py`, whose
  module level loads `pipeline-config.json` it never uses. Lift the shared Groq plumbing into
  `tools/groq_util.py`. Also: `eval_clustering.py` sets `result["tokens"]` on a failed call but
  never records it into the rate limiter, so failed spend is invisible to pacing —
  `eval_entities.py` gets this right; copy in that direction.
- **Harness and app compose batches differently.** The harness orders items by
  `(digest_id, position, id)` and skips blank headlines; the DAO orders by `di.id`. Per-batch
  behaviour is identical, but a live run and an eval run won't group the same items.
- **`docs/Visualizer.html` is not usable as a reference** — a JS bundle, so screen markup isn't
  greppable. The UI was built from `docs/README.md` prose alone.

Resolved since the last revision: `fallbackToDestructiveMigration()` **removed** (v1 needed no
migration, so nothing had to be written); junk capture rows now UNPARSEABLE *and* excluded from
digests; `NewsListener` chatter quieted behind a `VERBOSE` flag (and `onListenerDisconnected`
added — nothing was reporting the unbind); "What's building" is real.

Open questions:
- **UNCERTAIN:** whether web/browser push sources are in scope (PRD left this open); whether
  the v1 feedback/taste loop ships at all (explicitly fenced to a future addendum).

Resolved since the last revision: Phase C visual feedback (collected → `bfbedf8`); JSON export
(shipped, `371ce94`); topic taxonomy/ugly labels (fixed by Phase 5 + clustering-v2).

---

## Footer: deeper references (read these for full detail)

**THIS FILE IS THE LIVING RECORD.** The `docs/` set was frozen on 2026-08-10: it captures
design intent at the start of the build and is no longer maintained. Where `docs/` and this
file disagree, **this file wins**; where this file and the code disagree, **the code wins**.
Code comments still cite "EDD §4.1" — those are pointers into the frozen spec for context,
not statements that the spec is current.

- **`docs/Engineering_Design_Document.md`** (EDD) — **FROZEN 2026-08-10.** The original
  implementation spec. Its header carries a table of every known divergence from the shipped
  app (phases A/B/C, interception, repair-not-reject validation, the model change, the
  prompt contract). Read it for original reasoning, never as current truth.
- **`docs/System_Architecture_Document.md`** (SAD) — **FROZEN.** Components, boundaries,
  data flow. Note its "listener, not a manager" boundary was reversed by the interception
  decision — see the addendum below.
- **`docs/README.md`** + **`docs/Visualizer.html`** — the locked Almanac UI spec (design
  tokens, exact colours/sizes/copy per screen). The README is the design source of truth;
  "Cloud Core" in it means *this* app.
- **`docs/PRD_news_memory_layer.md`** — **FROZEN.** The upstream PRD: the *what/why*
  (problem, goals, non-goals, success metrics, the v0 and 30-day v1 gates). The EDD/SAD are
  downstream of this. Its problem statement and gate definitions still stand — it's the one
  frozen doc whose *intent* hasn't drifted, only its implementation detail.
- **`docs/ADDENDUM-interception.md`** — the interception decision in full.
- **`app/schemas/com.dhairya.newsmemory.data.db.ArchiveDatabase/{1,2}.json`** — exact schema.
- **`gradle/libs.versions.toml`** — exact dependency versions.
- **`tools/eval_clustering.py`** + **`tools/eval_report.py`** — the offline replay harness
  (§5.1). Replays real captured windows from a Settings JSON export through the pipeline
  against the live Groq API, so a prompt/model change can be A/B'd on a month of real data
  before it ships. **Use this before changing the prompt or model.** Note it holds Python
  ports of `Normalizer`/`Deduper`/`ClusterResponseParser` — keep them in sync with the Kotlin.
  Exports contain personal notification content: keep them out of git.

The EDD's freeze notice carries the full divergence table; §5.1 above explains the reasoning
behind each one. The full original spec set (PRD + EDD + SAD + Almanac handoff) remains
self-contained in `docs/` — a fresh clone has everything, as history.
