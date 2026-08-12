# News Memory

A personal, single-user Android app that turns the news notifications you already
receive into an archive that *remembers* — and, in v1, notices what keeps coming back.

News apps push fragmented one-line alerts that don't retain. You read forty of them a
day and couldn't tell me on Friday what was building all week. News Memory captures
those notifications, removes them from the shade, and returns them three times a day as
a single digest with near-duplicates merged. The archive underneath is the point: it's
what lets the app count that this is the fourth story on FII selling this week.

**It is not a notification noise-reducer.** That framing was explicitly rejected — muting
apps and setting three alarms replicates it for free. The defensible core is the archive
plus recurrence.

## Status

**v0 has been in daily use since July 2026** on a Samsung Galaxy S24 FE — capture,
interception, encoding, the Almanac UI, LLM clustering and JSON export all work end-to-end.

**v1 — the recurrence engine — is code-complete but not yet verified on device.** Counting
over the archive, recurrence chips on digest items, a live "What's building" card, and an
entity backfill that makes the historical archive countable. 174 unit tests green.

The next step is on-device verification, and then the **30-day kill-gate**: do the recurrence
signals surface anything the owner didn't already know? That is a product question no amount
of code quality settles.

## How it works

```
Android notifications
  → NewsListenerService   allowlist gate, then capture + intercept (cancel from shade)
  → Room DB               "the archive" — the single source of truth
  → DigestPipeline        3×/day: near-dup merge → LLM cluster → assemble → one push
  → Compose UI            digest, calendar archive, allowlist, settings
  → recurrence            counting over entities, never LLM judgement on headlines
```

Three deliberate design commitments, each with a real cost:

- **Deliberate cadence.** Breaking news does *not* reach you in real time — only via the
  three digests. This is a feature, and it is the whole product thesis.
- **Fully on-device.** No backend, no accounts. The only outbound call is ~4×/day to
  Groq for clustering. A year of headlines is ~36 MB.
- **Recurrence is counting, never interpretation.** Headlines are engagement bait; a
  memory layer that let an LLM judge their importance would inherit that bait. Counting
  is the defence.

## Stack

Kotlin 2.1 + Jetpack Compose (Material 3), Room 2.6.1, WorkManager + AlarmManager,
Ktor 3.0.3 for the Groq call, DataStore for prefs. No DI framework — a hand-rolled
`AppContainer`. Single `:app` module, package-by-feature. minSdk 29, targetSdk 35.

Clustering runs on Groq's `openai/gpt-oss-120b`, with a heuristic fallback that always
works and is always visible when it engages (digests tag themselves `HEURISTIC`).
Cost of the whole stack: ₹0.

## Build and run

No Android Studio required. JDK 17, the Android SDK, and Gradle live under
`C:\Users\bandh\android-tools\`.

```bash
# Groq key (gitignored) — the app builds and runs fine without it, staying heuristic
echo "GROQ_API_KEY=gsk_..." >> local.properties

./gradlew.bat testDebugUnitTest     # 174 tests, no device or network needed
./gradlew.bat assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On first launch, onboarding walks through notification access, POST_NOTIFICATIONS,
exact alarms, and battery-unrestricted (Samsung kills bound services otherwise), then
the allowlist picker.

## Documentation

**Read [`PROJECT_MEMORY.md`](PROJECT_MEMORY.md) first** — it's the handoff document for
someone with zero context, and it prioritises the non-obvious: why decisions were made,
what's already been tried and reverted, and which landmines are still live. §8 is the
current state; §5.1 explains why the LLM integration looks the way it does.

| Document | What it is |
|---|---|
| [`PROJECT_MEMORY.md`](PROJECT_MEMORY.md) | **The living record** — decisions, history, gotchas, current state |
| [`tools/README.md`](tools/README.md) | The offline replay harness — read before changing the prompt or model |
| [`docs/PRD_news_memory_layer.md`](docs/PRD_news_memory_layer.md) | *Frozen.* The what and why: problem, goals, non-goals, the gates |
| [`docs/Engineering_Design_Document.md`](docs/Engineering_Design_Document.md) | *Frozen.* The original implementation spec (its header lists every divergence from the shipped app) |
| [`docs/System_Architecture_Document.md`](docs/System_Architecture_Document.md) | *Frozen.* Components, boundaries, data flow |
| [`docs/README.md`](docs/README.md) | The locked "Almanac" UI spec — design tokens, per-screen copy |
| [`docs/ADDENDUM-interception.md`](docs/ADDENDUM-interception.md) | Why the app cancels other apps' notifications |

**`docs/` was frozen on 2026-08-10** — it records design intent at the start of the build,
not the current app. Several parts are deliberately wrong now (the phase list, the
"listener not a manager" boundary, the LLM contract and model). Where they disagree:
`PROJECT_MEMORY.md` beats `docs/`, and the code beats both. Don't change code to match a
frozen spec.

## Privacy

Everything stays on the device. The archive holds your real notification content, so:
`local.properties`, `*.apk`, and notification dumps are gitignored, and archive exports
(Settings → Export archive) are personal data — keep them out of version control.
