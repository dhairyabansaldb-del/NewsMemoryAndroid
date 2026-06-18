# Engineering Design Document — Personal News Memory Layer (Android)

**Status:** Approved for build
**Companion documents:** PRD (requirements), System Architecture Document (component structure)
**Owner:** Dhairya
**Last updated:** 10 June 2026

This document specifies *how* each component is built: schemas, algorithms, permission flows, scheduling mechanics, prompts, failure handling, the Claude Code build plan, and honest effort estimates. Decisions locked with the product owner: native Kotlin + Compose; hybrid LLM (Groq primary, heuristics fallback); fully on-device storage; unread digests archive silently; digest unit is headline-list-grouped-by-topic; target device Samsung Galaxy S24 FE; sideloaded APK; free tools only; v1 query is one-shot.

---

## 1. Project setup

- **Module:** single `:app` module. No multi-module structure — unnecessary for this size and it slows Claude Code iteration.
- **minSdk 29** (Android 10), **targetSdk 35**. The S24 FE runs Android 14/15 (One UI 6.1/7); minSdk 29 keeps APIs modern without legacy branches.
- **Dependencies:** Compose BOM + Material 3, Room (+ ksp), DataStore Preferences, WorkManager, Ktor Client (CIO engine) + kotlinx-serialization, Coil not needed (no images in v0/v1).
- **No DI framework.** A hand-rolled `AppContainer` created in `Application.onCreate()` exposes singletons: `ArchiveDatabase`, `NotificationRepository`, `DigestRepository`, `GroqClient`, `DigestPipeline`, `RecurrenceEngine` (v1), `SettingsStore`.
- **Package structure:**

```
com.dhairya.newsmemory/
  capture/        NewsListenerService, ListenerRebinder
  data/           db/ (entities, DAOs, ArchiveDatabase), SettingsStore
  pipeline/       DigestPipeline, Deduper, HeuristicClusterer, DigestAlarmScheduler,
                  DigestWorker, BootReceiver
  llm/            GroqClient, prompts, GroqModels (request/response DTOs)
  memory/         RecurrenceEngine, QueryEngine        (v1)
  ui/             theme/, onboarding/, allowlist/, digest/, archive/, query/, settings/
  AppContainer.kt, App.kt, MainActivity.kt
```

---

## 2. Permissions and onboarding flow

The app needs two special grants plus one battery exemption. Onboarding is a three-step Compose flow; each step deep-links to the exact settings screen and detects completion on resume.

**Step 1 — Notification Access** (`BIND_NOTIFICATION_LISTENER_SERVICE`). Intent: `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`. Verify via `NotificationManagerCompat.getEnabledListenerPackages(context)`. The onboarding copy must state the privacy contract plainly: *"This app reads notifications only from apps you select on the next screen. Everything else is invisible to it — the check happens before a notification is ever read."*

**Step 2 — Post Notifications** (`POST_NOTIFICATIONS`, runtime permission, API 33+). Needed to deliver the three digest pushes.

**Step 3 — Exact alarms + battery.** `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM`: on targetSdk 35 declare `USE_EXACT_ALARM` is *not* permitted for this app category — declare `SCHEDULE_EXACT_ALARM` and route the user to `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM` if `AlarmManager.canScheduleExactAlarms()` is false. Then send the user to App Info → Battery → **Unrestricted**. On One UI this single setting is the difference between a listener that survives overnight and one that silently dies; onboarding must not be skippable past this step without an explicit "I understand digests may be unreliable" acknowledgement.

**Allowlist picker:** query `PackageManager` for launchable installed apps, render with checkboxes, persist chosen package names to DataStore as a `Set<String>`. Provide search. Ship with zero defaults — the user must opt apps in.

---

## 3. Data schema (the v1 substrate — most important v0 artifact)

Six Room tables. v1 tables ship in v0 so no migration sits between phases.

```sql
-- every captured notification, immutable
raw_notifications(
  id            INTEGER PK AUTOINCREMENT,
  package_name  TEXT NOT NULL,           -- source app
  title         TEXT,
  body           TEXT,
  content_hash  TEXT NOT NULL,           -- sha256(normalized title+body)
  posted_at     INTEGER NOT NULL,        -- epoch millis from sbn.postTime
  captured_at   INTEGER NOT NULL,
  window_id     TEXT,                    -- assigned at digest time, e.g. "2026-06-10-M"
  UNIQUE(content_hash, window_bucket)    -- window_bucket = date+slot derived col;
)                                        -- kills exact dupes at insert

-- one row per digest run (including empty ones)
digests(
  id            TEXT PK,                 -- = window_id "YYYY-MM-DD-{M|E|N}"
  slot          TEXT NOT NULL,           -- MORNING | EVENING | NIGHT
  window_start  INTEGER NOT NULL,
  window_end    INTEGER NOT NULL,
  created_at    INTEGER NOT NULL,
  item_count    INTEGER NOT NULL,
  source_count  INTEGER NOT NULL,
  pipeline_mode TEXT NOT NULL,           -- LLM | HEURISTIC (degradation visible)
  opened_at     INTEGER                  -- null = never opened (archived silently)
)

-- one row per merged story cluster inside a digest
digest_items(
  id            INTEGER PK AUTOINCREMENT,
  digest_id     TEXT NOT NULL REFERENCES digests(id),
  topic_label   TEXT NOT NULL,           -- e.g. "Indian Markets"
  headline      TEXT NOT NULL,           -- representative headline
  source_count  INTEGER NOT NULL,
  position      INTEGER NOT NULL
)

-- membership: which raw notifications fed which cluster
item_sources(
  item_id       INTEGER REFERENCES digest_items(id),
  raw_id        INTEGER REFERENCES raw_notifications(id),
  PRIMARY KEY(item_id, raw_id)
)

-- canonical entities/topics (v1 read path, v0 write path)
entities(
  id            INTEGER PK AUTOINCREMENT,
  name          TEXT NOT NULL,           -- canonical: "FII selling", "Sarvam"
  normalized    TEXT NOT NULL UNIQUE,    -- lowercase, trimmed; merge key
  first_seen    INTEGER NOT NULL,
  last_seen     INTEGER NOT NULL
)

item_entities(
  item_id       INTEGER REFERENCES digest_items(id),
  entity_id     INTEGER REFERENCES entities(id),
  PRIMARY KEY(item_id, entity_id)
)
```

Recurrence in v1 is then a single query: count `digest_items` joined through `item_entities` for a given entity over a trailing window. A separate `recurrence_threads` table is deliberately *not* created — threads are derived (entity + date range), not stored, which avoids a sync problem. If v2's graph UI needs materialized threads, that's a v2 migration.

**Indexes:** `raw_notifications(posted_at)`, `raw_notifications(window_id)`, `item_entities(entity_id)`, `digests(created_at)`.

**Export:** a settings action serializes all tables to a single JSON file via the Storage Access Framework share-sheet. ~20 lines of code; insurance against phone loss; keeps the no-backend decision honest.

---

## 4. Capture layer

### 4.1 The listener

```kotlin
class NewsListenerService : NotificationListenerService() {
  override fun onNotificationPosted(sbn: StatusBarNotification) {
    val allowlist = settingsStore.allowlistSnapshot()   // cached, hot-reloaded
    if (sbn.packageName !in allowlist) return           // FIRST LINE. Non-allowlisted
                                                        // content is never touched.
    if (sbn.isOngoing) return                           // skip persistent/media notifs
    val extras = sbn.notification.extras
    val title = extras.getCharSequence(EXTRA_TITLE)?.toString()
    val body  = extras.getCharSequence(EXTRA_TEXT)?.toString()
        ?: extras.getCharSequence(EXTRA_BIG_TEXT)?.toString()
    if (title.isNullOrBlank() && body.isNullOrBlank()) return
    repository.insertRaw(sbn.packageName, title, body, sbn.postTime)  // hash + dedupe inside
  }
}
```

Rules: no network, no LLM, no clustering at capture time; insert and return. Group-summary notifications (`FLAG_GROUP_SUMMARY`) are skipped to avoid double-capture. The allowlist snapshot is refreshed via a DataStore flow collector so edits apply without restarting the service.

### 4.2 Exact-duplicate suppression at insert

`content_hash = sha256(normalize(title) + "|" + normalize(body))` where normalize = lowercase, collapse whitespace, strip emoji/punctuation. The unique constraint silently drops re-posts of the same story by the same or different apps within the same window. Near-duplicates (reworded headlines) are the pipeline's job, not capture's.

### 4.3 Surviving One UI (the project's #1 technical risk)

Samsung kills bound services aggressively. Mitigations, in order of impact: (1) battery → Unrestricted, enforced in onboarding; (2) a `ListenerRebinder` WorkManager periodic job (every 30 min) that checks `isNotificationListenerAccessGranted` + a service-alive heartbeat timestamp, and if the service looks dead calls `requestRebind(componentName)` after toggling the component enabled-state (the documented workaround for zombie listener bindings); (3) heartbeat = service writes `last_alive` to DataStore every `onNotificationPosted` and on a 15-min internal timer; the digest pipeline records gaps so dead time is *visible in the archive* rather than silent. Accepted limitation per PRD: notifications posted while the listener is dead are unrecoverable.

---

## 5. Digest pipeline

### 5.1 Windows

Defaults (user-editable in settings): **Morning 08:30** covers 22:30→08:30, **Evening 18:00** covers 08:30→18:00, **Night 22:30** covers 18:00→22:30. Window edges derive from the configured times; changing a time reshapes neighbouring windows. `window_id = "YYYY-MM-DD-{M|E|N}"` is the idempotency key for the whole pipeline.

### 5.2 Pipeline stages

```
load raw rows in window
  → stage 1: exact dupes already gone (insert-time)
  → stage 2: HEURISTIC near-dup merge
  → stage 3: LLM cluster + label + entities   ──fail──► stage 3b: heuristic clusters
  → stage 4: assemble digest, write to archive (transaction)
  → stage 5: post ONE push notification
```

**Stage 2 — heuristic near-dup merge.** Token-based Jaccard similarity on normalized title words (stopwords removed). Threshold ≥ 0.55 → same story. Union-find to form groups; the longest headline in a group becomes representative; `source_count` = distinct packages in group. This runs *always* (it shrinks the LLM payload and is the v0 dedup guarantee independent of Groq).

**Stage 3 — Groq call.** One request per digest. Model `llama-3.1-8b-instant`, `response_format: json_object`, temperature 0.2. Input: numbered list of merged headlines. Output contract:

```json
{ "clusters": [ { "topic": "Indian Markets",
                  "headline_ids": [1,4,7],
                  "representative": 4,
                  "entities": ["FII selling", "Sensex"] } ] }
```

The prompt (versioned in `llm/prompts/`) instructs: group by story/topic, 2–6 word topic labels, entities are *recurring trackable subjects* (orgs, people, instruments, ongoing events), canonical short names, ≤4 per cluster, never invent headlines, every headline id appears exactly once. Validation on parse: ids partition the input set; otherwise treat as failure → stage 3b. Entities are written in v0 even though nothing reads them until v1 — this is the PRD's "schema as first-class deliverable" made concrete: by v1 day one, the archive already contains weeks of entity data.

**Stage 3b — heuristic fallback.** Clusters = stage-2 groups; topic label = source app's category or top-TF token, capitalized; entities skipped (backfilled later, §7.3). Digest row records `pipeline_mode = HEURISTIC` and the digest UI shows a small "basic grouping" tag — degradation is visible, never silent.

**Stage 4 — assembly.** Single Room transaction: digest row, items, item_sources, entities (upsert by `normalized`), item_entities. Items ordered by `source_count` desc (multi-source stories are the strongest signal headlines can carry).

**Stage 5 — push.** One notification: "Morning Digest — 12 stories from 4 apps", tap → digest detail. Channel: `digests`, default importance.

**Empty/low handling (PRD P0):** 0 raw items → write digest row with `item_count=0`, post *no* push. 1–3 items → normal flow, push text "Quiet window — 2 stories". Unopened previous digest → nothing special happens: it is already archived; `opened_at` stays null ("archive silently", as decided).

### 5.3 Scheduling mechanics

`DigestAlarmScheduler` sets one `setExactAndAllowWhileIdle` alarm for the *next* upcoming slot only (Android limits idle-exact alarms; chaining one-at-a-time is the reliable pattern). Alarm → broadcast receiver → enqueue `DigestWorker` (expedited, `OneTimeWorkRequest`, unique name = window_id, `ExistingWorkPolicy.KEEP`) → on completion schedule the next slot's alarm. `BootReceiver` (BOOT_COMPLETED) re-arms after reboot. A safety-net periodic WorkManager job (hourly) checks whether any window closed without a digest row and runs catch-up — late digest beats lost digest, and idempotency keys make double-runs harmless.

---

## 6. Groq client

Ktor client; base `https://api.groq.com/openai/v1/chat/completions`; key read from `local.properties` at build time into `BuildConfig` (acceptable for a personal sideloaded APK; never commit the key — `.gitignore` entry is part of project scaffold). Timeout 30 s; retry ×2 with exponential backoff on 429/5xx; any failure after retries → heuristic fallback, never a user-facing error. Free-tier budget: ~4 calls/day vs ~14,400/day limit — three orders of magnitude of headroom; rate limits are a non-issue by design.

---

## 7. Memory engine (v1)

### 7.1 Recurrence surfacing (the product's core value)

At stage 4, for each digest item's entities, run:

```sql
SELECT COUNT(DISTINCT di.id) FROM digest_items di
JOIN item_entities ie ON ie.item_id = di.id
JOIN digests d ON d.id = di.digest_id
WHERE ie.entity_id = :eid AND d.window_start >= :sevenDaysAgo
```

Flag rules (tunable constants): count ≥ 3 in 7 days → "Nth story on X this week"; same entity in ≥ 3 sources within one digest → "X across N sources"; entity present ≥ 5 distinct days in 30 → "X has run for N days this month". Max one flag per item (priority: weekly count > multi-source > monthly run) and max 5 flagged items per digest to keep flags meaningful. Flags render as a highlighted chip on the digest item. *Counting only — no LLM judgement on individual headlines — which is exactly the PRD's defence against clickbait inheritance.*

### 7.2 One-shot query

Query screen: text field + answer card, no history. Flow: pull last 30 days of `digest_items` (topic, headline, entities, date, source_count; cap ~400 items, newest first) → single Groq call (`llama-3.3-70b-versatile` here — synthesis over hundreds of lines justifies the bigger free model) with the question and the instruction to answer only from the provided log and to cite counts/dates → render text. No follow-ups in v1.

### 7.3 Entity backfill

Hourly safety-net job also finds digest items with zero entities (heuristic-mode digests) and, when network allows, runs entity extraction for them. Keeps recurrence counting honest across degraded windows.

### 7.4 Feedback loop (P1 — explicitly fenced)

Per the PRD's "high risk of swallowing the schedule": **not designed in this document.** When v1 P0 is live and stable, the chosen mechanism will be *stored rules* (user corrections persisted as plain-text rules prepended to the clustering prompt) — the simplest of the three options the PRD lists — and it gets its own one-page addendum then. No code paths for it are scaffolded now.

---

## 8. UI specification (Compose, Material 3)

| Screen | Contents | Notes |
|---|---|---|
| Onboarding | 3 permission steps (§2) | Re-shown if any grant is revoked |
| Allowlist | Searchable installed-app list, toggles | Reachable from settings anytime |
| Home / Today | Today's 3 digest cards (or "upcoming at 18:00") | Default screen |
| Digest detail | Items grouped under topic headers; source count chip; recurrence flag chip (v1); long-press → source headlines | The product's main surface |
| Archive | Reverse-chron list grouped by date; pipeline_mode tag; tap → detail | v2's graph view replaces/extends this |
| Query (v1) | Text field, answer card, 3 example prompts | One-shot |
| Settings | Digest times (3 pickers), allowlist link, export JSON, health panel | Health panel: listener alive?, last capture, battery-exemption status — debugging One UI without logcat |

Theme: Material 3 dynamic color, dark-mode aware. No custom design system — this is a utility; polish budget goes to the digest detail screen and (v2) the graph.

---

## 9. Testing & validation plan

**Unit (JUnit, no device):** normalizer + hash; Jaccard near-dup merge against a fixture set of ~40 real reworded headlines; Groq response validation (partition check, malformed JSON → fallback); recurrence counting against a seeded in-memory Room DB (Robolectric); window-edge math including the overnight window and a time-change edge.

**Instrumented (device):** insert-path UNIQUE constraint; digest pipeline end-to-end with a fake Groq server (Ktor MockEngine); idempotency (run worker twice for one window → one digest).

**Manual protocol (the v0 gate, per PRD §8):** allowlist 3–4 news apps + deliberately exclude one chatty non-news app; run 3 full days; verify per day — all three digests fired on time, zero rows from non-allowlisted packages (SQL spot-check), listener survived overnight (heartbeat log), dedup sanity on at least one multi-source story, archive browsable. Pass = proceed to v1 wiring; the 30-day kill gate then runs exactly as the PRD defines it, with `digests.opened_at` and flag-fire counts providing the PRD's leading indicators for free.

---

## 10. Build plan for Claude Code (phased, each phase runnable)

| Phase | Scope | Done when |
|---|---|---|
| 1 | Project scaffold: Gradle, theme, AppContainer, full Room schema + DAOs + migrations test, settings store | App builds; schema unit tests green |
| 2 | Onboarding + permission flows + allowlist picker | All grants obtainable on the S24 FE; allowlist persists |
| 3 | Listener service + insert path + heartbeat + rebinder | Real notifications from allowlisted apps appear in DB; excluded app provably absent |
| 4 | Scheduler + worker + heuristic-only pipeline + push + digest/archive UI | Three on-time heuristic digests on-device |
| 5 | Groq client + prompt + validation + fallback + entity writes + health panel + JSON export | LLM digests with topic labels; airplane-mode test degrades gracefully → **run v0 gate** |
| 6 (v1) | Recurrence engine + flags + backfill | Flags fire correctly against seeded history |
| 7 (v1) | One-shot query screen | Sensible answers over ≥2 weeks of real archive → **start 30-day kill-gate clock** |

Workflow per phase: plan-first in Claude Code (matches your established practice), implement, run unit tests, manual on-device check, commit. Phases 3–4 are where first-time-Android pain concentrates; budget accordingly.

**Honest effort estimate** (first Android app, agentic-coding workflow, part-time): Phases 1–2 ≈ 3–5 days; Phase 3 ≈ 4–7 days (One UI debugging is the wildcard); Phases 4–5 ≈ 5–8 days; v0 total ≈ **3–4 weeks part-time**, not the one optimistic week the PRD flags. Phases 6–7 ≈ 1–2 weeks. The PRD's timeline-optimism risk is real; this estimate is the planning baseline.

---

## 11. Risk register (engineering view)

| Risk | Likelihood | Mitigation in this design |
|---|---|---|
| One UI kills listener overnight | High | §4.3 trio: unrestricted battery, rebinder job, visible heartbeat gaps |
| Doze delays digest alarms | Medium | Exact-while-idle alarms + hourly catch-up worker; late > lost |
| Groq output malformed | Medium | Strict validation + always-available heuristic path; degradation visible via `pipeline_mode` |
| Entity canonicalization drift ("FII" vs "FII selling") splits recurrence counts | Medium | Canonical-name prompt rules + normalized merge key; accept imperfection in v1, revisit with stored aliases if counts visibly fragment |
| First-Android-build unknowns blow the schedule | High | Phased plan with runnable milestones; risk concentrated early (Phase 3) so slippage is visible in week 1–2, not week 4 |
| Scope creep toward interpretation/synthesis | Medium | Recurrence = counting queries only; query feature is one-shot; feedback loop fenced to a future addendum |
