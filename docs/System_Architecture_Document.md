# System Architecture Document — Personal News Memory Layer (Android)

**Status:** Approved for build
**Companion documents:** PRD (upstream), Engineering Design Document (downstream — implementation detail)
**Owner:** Dhairya
**Last updated:** 10 June 2026

---

## 1. Purpose of this document

This document defines the system's components, the technology each one is built with, where data lives, how data flows between components, and where the system boundaries sit. It deliberately stays at the structural level. Algorithms, schemas, permission flows, and build sequencing live in the Engineering Design Document (EDD).

---

## 2. Architecture at a glance

The system is a **single Android application with no backend**. Everything — capture, storage, digest assembly, recurrence detection, and the archive — runs on the device. The only external dependency is the **Groq API**, called at most a handful of times per day to cluster headlines, label topics, and (in v1) extract entities and answer one-shot queries. If Groq is unreachable or rate-limited, an on-device heuristic pipeline produces a degraded-but-functional digest.

```
┌─────────────────────────────────────────────────────────────────┐
│                     ANDROID DEVICE (S24 FE)                     │
│                                                                 │
│  Android OS Notification System                                 │
│         │                                                       │
│         ▼                                                       │
│  ┌──────────────────────┐      allowlist check happens HERE,    │
│  │ Capture Layer        │◄──── before anything is read/stored   │
│  │ (NotificationListener│                                       │
│  │  Service)            │                                       │
│  └──────────┬───────────┘                                       │
│             │ raw notification rows                             │
│             ▼                                                   │
│  ┌──────────────────────┐                                       │
│  │ Storage Layer        │  Room / SQLite — the ARCHIVE          │
│  │ (single source of    │  raw items, digests, clusters,        │
│  │  truth)              │  entities, recurrence threads         │
│  └──────────┬───────────┘                                       │
│             │ window's raw items            ┌────────────────┐  │
│             ▼                               │  Groq API      │  │
│  ┌──────────────────────┐   headlines  ───► │  (llama-3.1-   │  │
│  │ Digest Pipeline      │ ◄─── clusters,    │   8b-instant)  │  │
│  │ (WorkManager +       │      labels,      └────────────────┘  │
│  │  AlarmManager)       │      entities      ▲ only external    │
│  └──────────┬───────────┘                      call in system   │
│             │ assembled digest                                  │
│             ├──────────────► single push notification           │
│             ▼                                                   │
│  ┌──────────────────────┐                                       │
│  │ Memory Engine (v1)   │  recurrence counting over archive,    │
│  │                      │  flags injected into digest,          │
│  │                      │  one-shot query answering             │
│  └──────────┬───────────┘                                       │
│             ▼                                                   │
│  ┌──────────────────────┐                                       │
│  │ UI Layer (Compose)   │  onboarding, allowlist picker,        │
│  │                      │  digest view, archive browser,        │
│  │                      │  settings, query box (v1)             │
│  └──────────────────────┘                                       │
└─────────────────────────────────────────────────────────────────┘
```

---

## 3. Technology stack and the reasoning behind each choice

| Layer | Choice | Why (and why not the alternatives) |
|---|---|---|
| Language | **Kotlin** | The native Android language. `NotificationListenerService` is a native Android API; cross-platform frameworks (Flutter, React Native) would still require a native Kotlin module for the most fragile part of the app, adding a bridge layer with zero benefit for an Android-only product. Claude Code generates idiomatic Kotlin reliably. |
| UI framework | **Jetpack Compose + Material 3** | Google's current default UI toolkit. Declarative (conceptually similar to React, which maps to your Next.js experience), far less boilerplate than XML layouts, and the toolkit Claude Code produces cleanest results with today. |
| IDE / build | **Android Studio (free) + Gradle** | The only realistic option; entirely free. Claude Code edits the project files; Android Studio builds, runs the emulator, and produces the sideload APK. |
| Local database | **Room over SQLite** | Room is Android's official persistence library — a thin, type-safe layer over SQLite. The archive is the product's most important artifact and Room gives compile-time-checked queries, migrations, and observability (Flow) for free. No server database needed: a year of headlines is 10–50 MB. |
| Preferences | **Jetpack DataStore** | Stores the allowlist, digest times, and settings. The modern replacement for SharedPreferences. |
| Scheduling | **AlarmManager (exact) + WorkManager** | AlarmManager's `setExactAndAllowWhileIdle` fires the three digest times precisely even in Doze; the alarm then enqueues a WorkManager job that does the actual pipeline work with retry semantics. Detail in EDD §6. |
| Capture | **NotificationListenerService** | The only Android API that grants read access to other apps' notifications. Requires a special user-granted permission. Allowlist filtering is enforced at the first line of the callback (EDD §4). |
| HTTP client | **Ktor Client (or Retrofit + OkHttp)** | For the Groq API call. Either is fine; EDD standardizes on one. |
| LLM | **Groq API — `llama-3.1-8b-instant`** primary | Free tier; extremely fast; the tasks (cluster short headlines, name topics, pull entities) are simple enough that an 8B model is appropriate. `llama-3.3-70b-versatile` is the configured fallback if 8B output quality disappoints — same API, one string change. You've used this exact stack in Marginalia. |
| Dependency injection | **None (manual)** | Hilt/Koin add build complexity and annotation-processing failure modes that aren't worth it for a single-module first Android app. Plain constructor injection from a small `AppContainer`. |
| Distribution | **Sideloaded APK** | Personal use; avoids Play Store's strict review of notification-listener apps entirely. |

**Cost of the entire stack: ₹0.** Android Studio, Kotlin, Room, Compose are free; Groq free tier covers usage by orders of magnitude (3–5 calls/day vs. a 14,400 requests/day free limit).

---

## 4. Components

### 4.1 Capture Layer
A `NotificationListenerService` subclass that the OS binds to once the user grants Notification Access. On every posted notification it performs the **allowlist gate as the very first operation**: if the posting package is not in the user's allowlist, the method returns immediately — the notification's content is never read, logged, or stored. For allowlisted packages, it extracts `{package, title, text, subText, postTime}`, computes a content hash, and inserts a raw row into the archive. Capture is **dumb and fast by design**: no clustering, no network, no LLM at capture time. This makes the fragile component (a service the OS can kill) as small and restartable as possible.

### 4.2 Storage Layer — the Archive
A single Room database, the system's only source of truth. It stores raw captured notifications, assembled digests, clusters (digest items), extracted entities, and recurrence threads. The schema (EDD §5) is designed so that v1's recurrence engine is a set of counting queries over existing tables — no migration or re-architecture between v0 and v1. The PRD names the schema a first-class deliverable; this document treats the Storage Layer as the component every other component is allowed to depend on, while nothing depends on capture or pipeline internals.

### 4.3 Digest Pipeline
Triggered three times a day at user-configured times. Reads all raw items in the closed window, then runs: exact-duplicate collapse → heuristic near-duplicate merge → **Groq call** for clustering, topic labels, and (v1) entity extraction → digest assembly → archive write → one push notification ("Morning Digest is ready"). If the Groq call fails for any reason, the heuristic-only path completes the digest with cruder grouping. The pipeline is idempotent per window: re-running it cannot duplicate a digest.

### 4.4 Memory Engine (v1)
Two functions, both reading only from the archive. **Recurrence surfacing (primary):** during digest assembly, counts entity and thread occurrences over trailing 7- and 30-day windows and injects flags ("4th story on FII selling this week") into qualifying digest items. Pure counting over stored entities — robust to clickbait framing by construction, exactly as the PRD requires. **One-shot query (secondary):** the user types a question; the engine retrieves recent digest items from the archive, sends them with the question to Groq, and renders the single response. No chat history, no multi-turn.

### 4.5 UI Layer
Jetpack Compose screens: onboarding (the two-permission flow), allowlist picker (installed apps with toggles), today's digests, digest detail (headlines grouped by topic, recurrence flags in v1), archive browser (calendar/list of past digests), one-shot query screen (v1), and settings (digest times, allowlist editing). The UI is a pure reader of the archive plus an editor of preferences — it contains no business logic.

---

## 5. Data flow

**Capture (continuous, all day):** OS posts notification → allowlist gate → extract fields → hash → insert raw row. Milliseconds, no network.

**Digest (3×/day):** exact alarm fires → WorkManager job starts → load window's raw rows → dedup → Groq (or heuristic fallback) → write digest + clusters + entities → recurrence counting (v1) → post single push. Seconds; one network call.

**Read (user-initiated):** notification tap → digest detail screen ← Room queries. No network.

**Query (v1, user-initiated):** question → retrieve ~30 days of digest items → Groq → display answer. One network call.

**Data leaving the device — exhaustively:** headline texts from allowlisted apps inside Groq API payloads, and nothing else. No analytics, no crash reporting service, no backend. Notifications from non-allowlisted apps never enter the system at all, so they cannot leave it.

---

## 6. System boundaries and trust

| Boundary | Policy |
|---|---|
| OS → Capture | Only allowlisted packages cross. Enforced in code at the first line of the listener callback, not downstream. |
| Device → Groq | Only headline text and topic labels cross, only at digest time and query time. API key stored in the app's private storage; acceptable for a personal sideloaded build (EDD §9 covers handling). |
| App → Android UI | Only digest-ready pushes and the app's own screens. The app never modifies, dismisses, or interacts with other apps' notifications — it is a listener, not a manager. |

---

## 7. Failure model (what degrades, what never breaks)

The system is designed so that **the archive never loses data silently** and every failure degrades to something sensible:

| Failure | Behaviour |
|---|---|
| Groq down / rate-limited / bad JSON | Heuristic-only digest. Visibly tagged so quality dips are explainable. |
| No network at digest time | Same heuristic fallback; entity extraction backfills on a later run (v1). |
| Listener service killed by One UI | Rebind strategy (EDD §4.3); notifications missed while dead are lost — accepted limitation, mitigated by battery-optimization exemption in onboarding. |
| Device rebooted | BOOT_COMPLETED receiver reschedules alarms; listener rebinds on unlock. |
| Empty window | 0 items → no push, empty digest recorded in archive. 1–3 items → normal digest, push labelled "Quiet window". Never an error, never a confusing empty push. |
| App killed mid-pipeline | WorkManager retries; idempotency key (window ID) prevents duplicate digests. |

---

## 8. Storage footprint

Raw notification row ≈ 300–500 bytes. At a heavy 200 captures/day: ~36 MB/year including digest, cluster, and entity tables plus SQLite overhead. The 1 GB threshold that would have triggered a Supabase backend is unreachable in any realistic usage; the system is **local-only by design**, which is also the strongest version of the PRD's privacy goal. A manual JSON export action (share-sheet) is specified in the EDD as cheap insurance against phone loss.

---

## 9. v0 → v1 → v2 architectural continuity

**v0 builds:** Capture Layer, Storage Layer (full schema including v1 tables), Digest Pipeline (with Groq clustering), UI minus memory surfaces. **v1 adds:** Memory Engine — entity extraction added to the existing Groq call, recurrence counting, digest flags, query screen. *No schema migration and no new components; v1 is new readers of existing data.* **v2 (design-for, don't build):** the graph/tree archive UI reads the same entity and thread tables — the schema already encodes the node/edge structure a graph view needs.
