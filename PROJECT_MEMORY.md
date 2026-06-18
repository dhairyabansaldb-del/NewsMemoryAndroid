# PROJECT_MEMORY.md — News Memory (Android)

> Context-handoff doc for an engineer with zero prior context. Prioritizes the
> non-obvious: decisions, reasoning, history, gotchas. Last updated 2026-06-18.

**Naming note:** the app is **News Memory** (package `com.dhairya.newsmemory`). The
working folder is `C:\Signals Noted project` and an early idea name was "Signals Noted";
ignore both — the shipped name is "News Memory". **"Marginalia" is a *different*, earlier
web project by the same owner** (Dhairya); the design docs cite it only as precedent for
the Groq/Kotlin stack. There is no Marginalia code here.

This project uses **v0 / v1 / v2** phasing, not "V1/V1.5". Map accordingly: v0 = the
internal data-pipe milestone (mostly built), v1 = the shippable memory layer (not built
yet), v2 = future graph UI.

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
- The real value (v1, not yet built) is **recurrence surfacing**: the archive notices what
  is *building* across days ("4th story on FII selling this week") by counting, not by
  interpreting headlines.

Core problem: news arrives as fragmented one-line pushes that don't *retain*; multi-day
through-lines are lost. Existing tools deliver/bundle news but don't *remember* it.

The product thesis is **deliberate cadence + memory**. Breaking news intentionally does
NOT reach the user in real time — only via the three digests. This is a feature.

---

## 2. ARCHITECTURE

**Single Android app, no backend, no auth, no accounts.** The only external call is to the
Groq API (planned, Phase 5), at most ~4×/day. Everything else is on-device.

```
Android OS notifications
   → NewsListenerService (allowlist gate FIRST, then capture + intercept)
   → Room DB ("the archive" — single source of truth)
   → DigestPipeline (WorkManager + AlarmManager, 3×/day)
        → near-dup merge → cluster (heuristic now / Groq in v1) → assemble → 1 push
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
- **Ktor 3.0.3 (CIO)** + kotlinx-serialization for the Groq call (not yet wired).
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
- `AppContainer.kt`, `App.kt`, `MainActivity.kt`

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
- **`entities`** + **`item_entities`** — canonical recurring subjects. **Written in v0,
  read in v1.** Merge key is `normalized` (UNIQUE). This is the substrate that makes v1
  recurrence a pure counting query.

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
   independent of Groq and shrinks the LLM payload. Groq (Phase 5) adds topic labels +
   entities; on any failure we fall back to heuristic and tag the digest `HEURISTIC`.
   Trade-off: heuristic topic labels are bad (single top-TF token → "Dropping", "Kishans").
   Accepted because Groq replaces them and degradation is visible.

3. **Fully on-device, no backend.** A year of headlines is ~36 MB. Rejected Supabase. This
   is also the strongest version of the privacy goal. Manual JSON export (SAF) is the only
   data-egress hatch (planned Phase 5).

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

What's NOT shipped: v1 (recurrence, query screen) and Phase 5 (Groq).

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

---

## 7. CONVENTIONS

- **Single `:app` module.** Package-by-feature under `com.dhairya.newsmemory`.
- **No DI framework** — constructor injection from `AppContainer`. Don't add Hilt.
- **Pure logic extracted for testing:** `CapturePolicy`, `NotificationExtractor`, `Deduper`,
  `WindowCalculator`, `RebinderLogic` are pure objects with unit tests. Prefer this pattern.
- **Tests:** JUnit + Robolectric + in-memory Room. 66 unit tests currently green. Run:
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
- **Commits:** one per phase, descriptive multi-line body, co-authored trailer. Work happens
  on `master`; `main` is the nominal default. Commit messages written via a temp file
  (`.git/COMMIT_MSG.tmp` + `git commit -F`) because PowerShell here-strings mangled them.
- **gitignored:** `local.properties`, `*.apk`, `notif-dump*.txt` (contain personal data),
  build dirs.

---

## 8. v1.5-equivalent — CURRENT WORK (= Phase 5, the Groq integration)

We are between the Almanac UI (done) and **Phase 5 (Groq)**, which is the next thing to build.

**Done:** Phases 1–4, A, B, C. Capture + interception + encoding verified on-device. Almanac
UI installed and under the owner's visual review.

**In progress / next (Phase 5 scope):**
- Ktor `GroqClient` (`llm/`), key from `local.properties`, timeout 30s, retry ×2 on 429/5xx.
- Versioned clustering prompt that ALSO assigns a **canonical topic from the Almanac
  taxonomy** (Markets / AI & Agents / IPO / Policy / Tech) + a clean representative headline
  + entities. This is what lights up the topic-chip colours (currently neutral because
  heuristic mode emits single-word labels).
- Strict JSON validation (ids partition the input set) → heuristic fallback on any failure.
- Entity upserts wired into the existing `DigestPipeline` transaction (the cluster engine is
  already pluggable — `DigestPipeline(clusterEngine = ...)`).
- Settings **JSON export** (SAF) — currently a stubbed `onExport = {}` row.
- Model: `llama-3.1-8b-instant` for clustering; `llama-3.3-70b-versatile` for the v1 one-shot query.

**Blockers / pending:**
- Needs the owner's **Groq API key** (he agreed to provide it at Phase 5).
- Owner is mid-review of Phase C; UI tweaks may land before Phase 5.

**Exactly where we left off:** Phase C committed (`0e4f26c`) and installed on the device for
visual review. The immediate next step was to collect the owner's Almanac feedback, then
start Phase 5 (Groq) — which also retroactively fixes the topic labels/colours.

After Phase 5 comes the **v0 gate** (3-day live test) and then **v1** (recurrence engine +
flags + one-shot query screen), then the **30-day kill-gate** (the real go/no-go: do the
recurrence pushes surface things the user didn't already know?).

---

## 9. OPEN THREADS / TODOs

- **Replace `fallbackToDestructiveMigration()` with a real migration** before any long-lived
  data accumulation. (Landmine, §6.)
- **Quiet the `NewsListener` debug logging** before v0 final.
- **Topic taxonomy** depends on Groq; heuristic labels stay ugly until Phase 5.
- **JSON export** is a stub.
- **Overnight listener-survival test** on One UI was started but never formally confirmed
  across a full night — still the #1 platform risk.
- **"What's building"** on Home is a V1 stub (the design itself tags it V1).
- **Phase C visual feedback** from the owner is outstanding (fonts rendering? light theme?
  expand/collapse feel?).
- **UNCERTAIN:** whether web/browser push sources are in scope (PRD left this open); whether
  the v1 feedback/taste loop ships at all (explicitly fenced to a future addendum).

---

## Footer: deeper references (read these for full detail)

These hold more depth than is reproduced above. **The authoritative specs now live in
`docs/` in the repo** (added 2026-06-18):
- **`docs/Engineering_Design_Document.md`** (EDD) — the authoritative implementation spec.
  Code comments cite it by section ("EDD §4.1" etc.); they point here.
- **`docs/System_Architecture_Document.md`** (SAD) — components, boundaries, data flow.
- **`docs/README.md`** + **`docs/Visualizer.html`** — the locked Almanac UI spec (design
  tokens, exact colours/sizes/copy per screen). The README is the design source of truth;
  "Cloud Core" in it means *this* app.
- **`docs/PRD_news_memory_layer.md`** — the upstream PRD: the *what/why* (problem, goals,
  non-goals, success metrics, the v0 and 30-day v1 gates). The EDD/SAD are downstream of this.
- **`docs/ADDENDUM-interception.md`** — the interception decision in full.
- **`app/schemas/com.dhairya.newsmemory.data.db.ArchiveDatabase/{1,2}.json`** — exact schema.
- **`gradle/libs.versions.toml`** — exact dependency versions.

The full spec set (PRD + EDD + SAD + Almanac handoff) is self-contained in `docs/` — a fresh
clone has everything.
```
