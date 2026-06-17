# Addendum: Notification interception (supersedes SAD §6 boundary)

**Date:** 2026-06-17 · **Decided by:** Dhairya · **Status:** Approved

## What changed

The original System Architecture Document (§6, boundary table) and PRD (Non-Goal #1)
stated the app is *"a listener, not a manager"* — it *"never modifies, dismisses, or
interacts with other apps' notifications."*

This is **reversed** for News Memory. The capture layer now **intercepts**: after a
notification from an allowlisted app is captured, `cancelNotification(key)` removes it
from the system shade. Allowlisted apps' notifications therefore live *only* inside News
Memory and no longer appear in the phone's notification panel.

## Scope & behaviour

- **Global** for every allowlisted app (no per-app opt-out in this version).
- **No live feed:** intercepted notifications surface only through the three daily digests
  and the Archive — consistent with the deliberate-cadence product thesis. Breaking news
  is not visible in real time; this is intended.
- **Best-effort, not pre-emptive:** Android has no API to prevent a notification from
  posting. We cancel immediately after capture, so a brief visual/sound blip is possible.
- **Fail-open:** if the listener is dead, notifications appear in the shade as normal
  (same limitation as the existing overnight-death case).

## Privacy posture

Unchanged in substance: only allowlisted apps are ever read, and interception only acts
on those same allowlisted apps. Non-allowlisted notifications are still never touched.
The "everything else stays invisible" contract holds.
