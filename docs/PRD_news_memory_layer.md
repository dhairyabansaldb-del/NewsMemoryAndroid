# PRD — Personal News Memory Layer (Android)

**Status:** Draft for engineering design hand-off
**Owner:** Dhairya
**Document type:** Product Requirements (the *what* and *why*). Architecture, stack, data schema design, and build estimates are explicitly deferred to the downstream Engineering Design Document and System Architecture Document.
**Last updated:** 10 June 2026

---

## 0. Framing note (read this first)

This product was reframed during exploration and the reframe is load-bearing. It is **not** a notification noise-reducer. It is a **personal news memory layer**: an Android app that ingests push notifications from a user-curated set of news apps, stores them in a structured archive, and surfaces *recurrence and through-lines* across days.

The noise-reduction behaviour (three batched digests a day instead of a constant stream) is real and useful, but it is the **delivery mechanism and data pipe**, not the value proposition. The value is the archive plus the ability to answer "what has been building this week" — primarily by **pushing** that signal into the digest, not by waiting for the user to query it.

A pure noise-reducer was considered and rejected: free behaviour changes (muting news apps, three alarms, opening an existing aggregator) replicate most of its value, and OS makers (Pixel, Samsung, iOS) ship notification summarization natively with deeper access than any third-party app can have. The memory layer is the defensible core and the portfolio hook.

---

## 1. Problem Statement

The user consumes news across many apps and newsletters and is bombarded with notifications throughout the day, which fragments attention and crowds out presence. More importantly, signal does not *retain*: stories arrive as isolated one-line pushes, the same story repeats across sources, low-signal stories occupy mental space, and there is no persistence — if a day is missed, the thread breaks and the through-line is lost. Existing tools (Inshorts, Google Briefings, curated newsletters, manually categorized email) deliver and lightly bundle news but do not *remember* it or surface what is genuinely building over time.

**Who experiences it:** Initially a single user (the builder). Secondarily, the same archetype — information-saturated news consumers who want trends, not feeds.

**Cost of not solving:** Continued attention fragmentation and an inability to detect slow-moving, multi-day signals (e.g. a market decline compounding across a week) without manually holding the thread in one's head.

---

## 2. Product Goals

1. **Reduce news interruptions to a deliberate cadence** — replace a continuous notification stream from selected apps with at most three batched digests per day (morning, evening, night).
2. **Persist news signal in a structured, queryable archive** — every digest survives so that cross-day reasoning becomes possible.
3. **Surface recurrence and through-lines automatically** — the system proactively tells the user what is *building* (e.g. "4th story on FII selling this week"), rather than requiring the user to ask.
4. **Strictly contain privacy exposure** — the app only ever observes notifications from apps the user explicitly allowlists; no other notification is ever read, stored, or processed.
5. **Serve as a portfolio-grade artifact** — the shippable, demoable version is the v1 memory layer, designed professionally.

---

## 3. Non-Goals

1. **Not a general notification manager.** It does not manage, snooze, or act on notifications outside the allowlist. (Privacy containment is the whole point.)
2. **Not an article reader or content fetcher in v0/v1.** It reasons over notification *text* only. It does not crawl, fetch, or summarize underlying articles. If the user becomes curious, they leave the app to search the web themselves. (Headlines are sufficient for recurrence detection, which is the core value; full-article fetch is a much larger product and is deferred.)
3. **Not a deep-synthesis engine.** Memory features lean on *counting, recurrence, and threading* — robust to clickbait framing — not on nuanced interpretation of individual headlines, which thin and engagement-baited input cannot reliably support.
4. **Not multi-category in v0/v1.** News only. No Slack, Linear, deal alerts, or social. Adding categories would break the one-sentence product definition and is out of scope.
5. **Not a free-form personalization engine at launch.** The feedback/taste-learning loop is scoped deliberately (see P1) and is not a v0 must-have.

---

## 4. Target User & Motivation

- **Primary user:** The builder, personal daily use.
- **Secondary purpose:** Portfolio piece. The portfolio demo is the **v1** product (memory + recurrence surfacing + visual layer), not v0.
- **Default tie-breaker for design conflicts:** When personal-use-you and portfolio-you disagree, the product is designed professionally (portfolio standard), but scope is judged against personal real-world use (the daily-use scenario in §5 is the arbiter).

---

## 5. Core Use Scenario (the design tie-breaker)

The user is employed and busy. Three discrete reads per day, none competing with deep work or the moment of waking:

- **Morning (at the office):** First notification — "Morning Digest is ready." Opens, reads everything that transpired overnight, closes. Under five minutes.
- **Evening (leaving the office):** "Evening Digest is ready." Reads, goes home.
- **Night (before sleep):** "Nightly Digest is ready." Reads, sleeps.

No notifications between these windows. This scenario is the explicit arbiter for scope and design tradeoffs.

---

## 6. User Stories

**Ingestion & privacy**
- As a user, I want to select exactly which apps the reader listens to, so that sensitive notifications (OTPs, bank alerts, password resets) are never seen by it.
- As a user, I want all notifications from non-selected apps to remain completely invisible to the app, so that I trust it with notification access.

**Digest (v0 spine)**
- As a user, I want notifications from my selected apps captured in the background and held until a scheduled time, so that I am not interrupted continuously.
- As a user, I want three scheduled digests a day delivered as a single push each, so that my attention is tuned to the outside world only at deliberate intervals.
- As a user, I want headlines grouped by topic within a digest, so that I can scan today's signal quickly.
- As a user, I want near-duplicate stories from multiple sources merged into one entry, so that repetition does not waste my attention.
- As a user, I want every digest archived automatically, so that nothing is lost and a memory layer can be built on top later.

**Memory (v1 core value)**
- As a user, I want the digest itself to flag when a story is recurring ("4th FII-selling story this week"), so that I notice multi-day trends without doing any work — *this is the primary memory surface*.
- As a user, I want to optionally ask the archive a question ("biggest recurring AI story this week?") and get an answer mapped across recent logs, so that I can retrieve a through-line on demand — *secondary to the push*.

**Edge / boundary**
- As a user, if I do not open a digest before the next one arrives, I want clear, predictable behaviour for what happens to the unread one (see Open Questions).
- As a user, I want the app to behave sensibly if no notifications arrived in a window, so that I do not get an empty or confusing digest.

---

## 7. Requirements

### v0 — "The Pit Stop" (data pipe + archive + digest)

v0's real job is **to get useful, structured data into the archive so v1 has something to reason over.** Clustering does not need to be excellent; ingestion and a clean archive are what matter.

**Must-Have (P0)**
- **Allowlist-only notification capture.** The app listens only to user-selected apps. All other notifications are never read or stored.
  - Given the user has selected apps A and B, when a notification arrives from app C, then the app neither stores nor processes it.
- **Background capture that survives.** Notifications from allowlisted apps are reliably captured while the app runs in the background across a full day/overnight.
- **Scheduled batched digests, 3x/day**, each delivered as a single push notification ("[Time] Digest is ready").
- **Digest view: headlines grouped by topic**, with near-duplicate merging across sources into single entries.
- **Persistent archive.** Every digest is written to a durable, structured store. The archive schema is the substrate for v1 and is treated as a first-class deliverable, not an afterthought.
- **Empty/low-volume handling.** A window with no/few notifications produces a sensible digest, not an error or empty push.

**Out of scope for v0:** memory queries, recurrence surfacing, feedback loop, graph view. (Scaffolding for these — chiefly the archive schema — is in scope; the features are not.)

### v1 — "The Product" (memory layer)

**Must-Have (P0)**
- **Push-based recurrence surfacing.** Within each digest, the system flags recurring/threading stories ("Nth story on X this week," "X mentioned in N sources"). Built on counting/recurrence over the archive, robust to clickbait framing. *This is the core value of the entire product.*
- **Archive-backed query** (secondary). A query interface ("what's been building in Indian markets this month?") that maps across recent logs and returns a spotlight-style answer (e.g. "Sarvam mentioned in 8 sources; FIIs noted volatility over recent weeks; Ola announced a partnership").

**Nice-to-Have (P1)**
- **Feedback / taste loop.** Per-story input to correct mis-categorization (e.g. a low-signal story wrongly placed as high-signal). The system adapts to reduce repeat mistakes. Working assumption: meaningful attunement around ~50 corrections; the learning mechanism (system-prompt modification vs. stored rules vs. preference embedding) is a technical decision deferred to the design doc. Scope carefully — high risk of swallowing the schedule.

### v2 / Future Considerations (P2 — design for, do not build)

- **Visual memory layer (graph/tree).** The archive itself rendered as an Obsidian-style growing topic graph — branches per topic that expand over time — integrated with core functionality rather than bolted on. Honestly labelled: strong portfolio/demo value, uncertain daily-use value. Build it *as the archive UI*, not as a standalone feature nobody revisits.
- **Full-article fetch / web crawl** for deeper synthesis, if headline-only proves too lossy.
- **Multi-category ingestion** beyond news.

---

## 8. Success Metrics & Validation Gates

The original validation gate ("unhappy with clustering after 10 days → no v1") was **rejected** during pressure-testing: it tested the wrong thing (clustering quality on thin input, in a first Android build) and would likely have killed the project before the valuable part existed. It is replaced below.

**v0 gate (proceed to active v1 work):**
- v0 reliably captures and archives notifications from selected apps for a short live test (~3 days), producing a clean, structured archive. The bar is *adequate ingestion and a usable archive schema*, not great clustering.

**v1 gate (the real kill switch — protect it from your own enthusiasm):**
- After **~30 days of silent accumulation**, the recurrence pushes must surface things the user did **not already know / had not already registered themselves.**
- **Death condition:** if, after ~30 days, every recurrence push is something the user would have noticed unaided, the memory layer adds nothing over the user's own recall, and the project should be declared dead. This is the single gate that replaces all soft criteria.

**Leading indicators:** digest open rate across the three daily windows; proportion of digests where a recurrence flag fired; (v1, P1) feedback corrections submitted per week.

**Lagging indicators:** does the user keep using it past 30 days; does the memory layer change what the user pays attention to.

---

## 9. Known Risks Carried Into the Build

These are *known weather*, not open questions — surfaced during pressure-testing and accepted.

1. **Timeline optimism.** This is the builder's first Android app (non-tech vibe-coder; prior ships are web, none on Android). `NotificationListenerService` (special-access permission), reliable background execution under Android Doze / battery optimization, and clustering thin headlines are all first-time unknowns. The optimistic one-week estimate is unverified; every week of slippage reactivates the motivation-drop and platform-displacement risks below.
2. **No kill gate until the 30-day memory test.** The plan builds v1 on faith after a 3-day v0 test. This is acceptable only because the meaningful gate genuinely lives at day ~30 (§8). Protect that test; do not let enthusiasm skip it.
3. **Clickbait inheritance.** Headlines are engagement bait, not neutral data. This is *managed only so long as memory stays in counting-and-threading territory.* If memory drifts toward deep interpretation of individual headlines, it will inherit the very noise the product was meant to escape.
4. **Platform displacement (portfolio-irrelevant, personal-use-relevant).** OS-native notification AI (Pixel Now Brief, Samsung Galaxy AI, iOS stacking) has deeper access. Fine for a portfolio piece; a real question for long-run personal use if the build drags.

---

## 10. Open Questions (for design / build)

Blocking (resolve before building the relevant feature):
- **[Design] Unread-digest behaviour:** if a digest is not opened before the next arrives, does it roll forward, get replaced, archive silently, or stack? (Affects digest data model and notification logic.)
- **[Eng/Data] Archive schema:** the schema is the v1 substrate and the most important v0 artifact — what structure best supports later recurrence counting and threading? (Design-doc decision; flagged here as first-class.)

Non-blocking (resolve during implementation / by prototyping):
- **[Design] Digest unit:** headline-list-grouped-by-topic vs. a few sentences of synthesized prose per cluster. Tentatively headlines; settle by prototyping and feel.
- **[Design] Memory surface balance:** push-first is decided; how prominent is the secondary query box, if present at all in v1?
- **[Eng] Feedback-loop learning mechanism:** system-prompt modification vs. stored rules vs. preference embedding; and the real correction count to attunement (working assumption ~50).
- **[Eng] "Web itself" push sources:** browser/web push behaves differently from app push and through different channels; confirm whether web push is in v0 scope or deferred.

---

## 11. Phasing Summary

| Phase | What it is | Ships? | Gate to advance |
|---|---|---|---|
| **v0** | Allowlist capture + 3x/day digest + persistent archive | Internal milestone ("pit stop"), ~3-day live test | Clean, structured archive from reliable ingestion |
| **v1** | Push-based recurrence surfacing + archive query + (P1) feedback loop | **Yes — this is the portfolio/ship version** | After ~30 days, recurrence pushes surface non-obvious signal |
| **v2** | Visual graph/tree archive UI; possible article fetch; possible multi-category | Future | TBD |

---

*Scope reminder for downstream docs: this PRD deliberately stops at product requirements. Architecture, data-model design, technology choices, permission-flow implementation, and effort estimates belong in the Engineering Design Document and System Architecture Document.*
