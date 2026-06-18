# Handoff: News Memory — "Almanac" UI

## Overview
News Memory is a notification-listening news reader. A background listener captures
notifications from an allowlisted set of news apps, then merges and de-duplicates them
into three scheduled **Digests** per day (Morning / Evening / Night). The user reads
clustered "stories" (multiple raw notifications merged into one card), tracks building
signals ("what's building"), and controls which apps are listened to.

This handoff covers the **Almanac** visual direction — the locked, approved design —
applied across the four core screens, in both a **light** and an **after-dark** theme.

## About the Design Files
The files in this bundle are **design references created in HTML/React-via-Babel** —
prototypes that show intended look and behavior. They are **not** production code to
copy directly. The task is to **recreate these designs inside the Cloud Core codebase**
using its existing environment, component library, navigation, and patterns. If a piece
of UI (button, card, list row, toggle) already exists in Cloud Core, use the existing
component and apply the visual tokens below. Build new components only where Cloud Core
has no equivalent.

- `Visualizer.html` — a self-contained, offline-openable render of all four screens in
  light + dark. Open it in any browser to see the target. Use ⤢ on a frame for
  fullscreen and ←/→ to walk the flow.

## Fidelity
**High-fidelity.** Colors, typography, spacing, radii, and copy below are final and
exact. Recreate the UI to match using Cloud Core's existing libraries and layout
primitives. The phone bezel / status bar / gesture pill in the visualizer are **mockup
chrome only** — do not build them; they stand in for the real device frame.

---

## Design Tokens

### Type
| Role | Family | Notes |
|---|---|---|
| Display / headings | **Newsreader** (serif), weight 500 | letter-spacing −0.01em; used for titles, big numerals, settings values |
| UI / body | **Instrument Sans** (sans), 400–700 | all labels, body, meta |
| Source monogram | **Space Grotesk**, 600 | the small app initials chips (MC, RT…) |

Display sizes in use: 54px (hero numeral), 34px ("Today"), 30px (next-read time),
27px (screen title), 18px (story title). Body/meta range 10–14px. Eyebrows are 10.5px,
weight 600, letter-spacing 0.14em, uppercase.

### Color — Light ("Almanac")
| Token | Hex | Use |
|---|---|---|
| `bg` | `#F6F0E4` | app background (warm cream) |
| `card` | `#FCF9F0` | cards, nav bar, inputs |
| `tintA` | `#EEE3CD` | "What's building" panel fill |
| `tintB` | `#E4E8D7` | three-reads timeline fill |
| `ink` | `#1A150F` | primary text |
| `inkMed` | `rgba(26,21,15,0.60)` | secondary text |
| `inkLow` | `rgba(26,21,15,0.42)` | tertiary text / chevrons |
| `faint` | `rgba(26,21,15,0.26)` | dots, faint labels |
| `line` | `rgba(26,21,15,0.12)` | hairline borders / dividers |
| `line2` | `rgba(26,21,15,0.20)` | chip outline |
| `hero` | `#F2C75B` | marigold hero block + highlighter |
| `heroTag` | `#B8431F` | "READY" tag on hero |
| `blockB` | `#A9C8DD` | sky-blue "Next read" block |
| `accent` | `#B8431F` | terracotta — active states, links, selected |
| `accentInk` | `#FCF9F0` | text on accent |
| `highlight` / `highlightBg` | `#F2C75B` | highlighter swipe behind key phrases |
| `good` | `#5E7C3F` | health "OK" green |
| `chipActiveBg` | `#1A150F` | active topic chip fill (ink) |
| `chipActiveInk` | `#FCF9F0` | active topic chip text |

### Color — After Dark ("Almanac after dark")
| Token | Hex | Use |
|---|---|---|
| `bg` | `#1A1611` | app background |
| `card` | `#241E17` / `card2` `#2E261C` | cards |
| `tintA` | `#2A2316` / `tintB` `#242820` | panel fills |
| `ink` | `#F4ECDC` | primary text |
| `inkMed` | `rgba(244,236,220,0.62)` | secondary |
| `inkLow` | `rgba(244,236,220,0.42)` | tertiary |
| `faint` | `rgba(244,236,220,0.24)` | dots |
| `line` | `rgba(244,236,220,0.10)` / `line2` `rgba(244,236,220,0.16)` | borders |
| `hero` | `#E0B24E` (heroInk `#1A1611`) | hero block |
| `blockB` | `#7FA0B8` | next-read block |
| `accent` | `#E8A24E` (accentInk `#1A1611`) | active/links |
| `good` | `#9CBE7A` | health green |
| `highlightBg` | `rgba(240,199,91,0.30)` | highlighter |
| `chipActiveBg` | `#E8A24E` | active chip |

Theme is selected in Settings → Appearance (Light / Dark / Auto). "Auto" follows
system; two of three daily reads land after sunset, so dark must be first-class.

### Topic color-coding
Each story topic gets a coordinated pastel. `{ light, ink (text on light), dark }`:
- Markets `#A9C8DD` / `#3C6B8C` / `#86AAC4`
- AI & Agents `#F2C75B` / `#946E12` / `#E0B24E`
- IPO `#CBD8A4` / `#5E7C3F` / `#A6C283`
- Policy `#EAB79B` / `#B5562F` / `#DA9A78`
- Tech `#CFC1E6` / `#6E5AA0` / `#B6A4DE`
- default `#D8CDBA` / `#7A6A4F` / `#BCAE93`

Topic pill: light theme uses `light` bg + `ink` text. Dark theme uses `dark`+`26` (15%
alpha) bg + `dark` text. Pill is 10px/700/0.1em uppercase, padding 4×9, radius 20.

### Shape & spacing
- Radii: big blocks/cards **22**, story cards **18**, panels **20**, inputs/timeline **12–14**, chips **20**, source monogram 7–12 (scales with size), check badge 9.
- Screen horizontal padding: **16px** (Home) / **18px** (Digest, Allowlist, Settings).
- Card inner padding: 15–16px. Grid gaps: 9–11px.
- Bento grid (Home): 2 columns, `gap: 11px`. Hero + Next-read are 1 col each (minHeight 156); "What's building" and three-reads span both columns.
- No drop shadows in content — depth comes from fills + hairline borders only.

### Source monogram chip (stand-in for app logos)
Rounded square, `bg = color + 24` (14% alpha), `1px solid color+40` border, text =
color, Space Grotesk 600 at 0.36×size. **Do not ship these** — replace with the real
app icons in Cloud Core. Reference colors: MC `#3FA37A`, GN `#5B8DEF`, RT `#D85B4A`,
IN `#E0A23B`, ET `#C2476B`, RD `#E8702A`, BB `#8C6BD8`, LM `#3AA6B9`, TH `#6B7280`,
NDTV `#C0392B`.

### Icons
Lucide-style 24×24 stroke icons, 1.75 stroke (2 when active). Set in use: home, archive,
settings, search, back, chevron right/down, bell, clock, sun, moon, dusk, layers,
download, check, trend, shield, plus. Use Cloud Core's existing icon set / Lucide.

---

## Screens / Views

### 1. Home / Today
**Purpose:** daily landing — what's ready to read, what's next, what's building.
**Layout:** vertical; header row, topic-chip rail, then a 2-col bento, then a listening
strip pinned under content, then bottom nav.

- **Header:** left = "Today" (Newsreader 34) + "Monday, 16 June" (12.5, inkMed) under
  it; right = bell icon in a 36px circle, 1.5px ink border.
- **Topic chips** (horizontal scroll, right-edge fade mask): `All` (active),
  `Markets 5`, `AI & Agents 3`, `IPO 2`, `Policy 2`. Active chip = `chipActiveBg`
  fill + `chipActiveInk` text, no border. Inactive = transparent, `line2` border,
  inkMed text, a colored topic dot. Count uses Newsreader. Chip: 7×13 padding, radius 20.
- **Hero block** (marigold `hero`): sun icon top-left, "READY" tag top-right
  (`heroTag`, 9.5/700/0.1em). Big "**12**" (Newsreader 54, line-height 0.85) bottom,
  then "stories in your Morning Digest" (12.5, 0.85 opacity). Tapping → opens Morning Digest.
- **Next read block** (`blockB` sky): clock icon top, then "Next read" (12, 0.7),
  "**6:00 PM**" (Newsreader 30; "PM" 16/0.7), "Evening Digest" (12.5).
- **What's building** (spans 2 cols, `tintA` fill, `line` border): eyebrow
  "WHAT'S BUILDING" + faint "V1 · MEMORY" tag. Row: a 7×4 dot-matrix sparkline
  (active dots r3 in `accent`, rest r1.5 in `line2`), then "**FII selling**"
  (Newsreader 15.5/700 with a `highlight` highlighter swipe behind it) + "4th day ·
  8 stories this week" (12, inkMed), then a chevron-right. Tap → the building signal's story cluster.
- **Three reads timeline** (spans 2 cols, `tintB` fill): 3 equal cells split by `line`
  dividers — Morning (sun, "Read" + check, state done), Evening (dusk, "6:00 PM",
  state next → cell tinted `accent+14`), Night (moon, "10:30 PM", state soon → inkLow icon).
- **Listening strip:** 4 overlapped source monograms (−7px) + "Listening to 6 sources"
  (12, inkMed) + chevron. Tap → Allowlist.
- **Bottom nav:** Today (active) · Archive · Settings. Active item = icon in a
  `accent+22` pill (56×26, radius 13) + accent icon + ink label; inactive = inkMed
  icon, inkLow label. 10.5px labels.

### 2. Digest detail (Morning Digest)
**Purpose:** read the digest — story cards that expand to show the merged raw notifications.
**Layout:** header, then a scrolling list of story cards.

- **Header (AlHead):** top row = back chevron (left) + search (right). Then sun icon
  (accent) + "Morning Digest" (Newsreader 27). Sub: "Monday, 16 June · 12 stories · 4 sources" (12.5, inkMed).
- **Story card (collapsed):** `card` fill, `line` border, radius 18. Topic pill
  (top-left) + optional **rec tag** (top-right): trend icon + text (e.g. "4th this
  week", "6 sources") on a `highlightBg` highlighter band, 11/600. Title in Newsreader
  18, line-height 1.25. Footer row: overlapped source monograms (22px, −6px) +
  "N sources merged" (11.5, inkMed) + chevron-right.
- **Story card (expanded):** border becomes `accent+66`; chevron flips to down and
  footer text becomes "Hide sources" in accent/600. A drawer opens (fill `bg`, top
  `line` border): eyebrow "RAW NOTIFICATIONS · N", then each raw notification row:
  source monogram (30px) + app name (12.5/600) + dot + time (11, inkLow), headline
  (13), optional snippet (11.5, inkMed). Rows divided by `line`.
- **Sample content (build with this):**
  - *Indian Markets* — "FIIs offload ₹4,200 cr as Sensex slips below 81,000", sources
    MC/RT/ET, rec "4th this week", **expanded** with 3 raw notifications (Moneycontrol
    8:41, Reuters 8:09, ET Markets 7:52 — copy in the visualizer).
  - *AI & Funding* — "Anthropic in talks to raise at a $180 B valuation", BB/RT/GN, rec "6 sources".
  - *Policy* — "RBI holds repo rate, flags monsoon inflation risk", GN/ET, 2 sources.

### 3. Allowlist — "Listening to"
**Purpose:** choose which installed apps the listener may read; everything else stays invisible.
**Layout:** header, privacy note, search field, "On" grid, "Available" grid.

- **Header:** "Listening to" (no icon).
- **Privacy note:** `card` card, shield icon (accent) + "This reader only sees
  notifications from apps switched on here. **Everything else stays invisible.**"
  (11.5, inkMed; bold clause in ink).
- **Search field:** `card`, search icon + "Search installed apps" (13, inkLow).
- **On · N sources** eyebrow with "Clear all" (accent/600) on the right. Then a
  **4-col grid** (gap 9) of source tiles: monogram (38px) + name (11/500) centered,
  `card` fill, **1.5px accent border**, and a 17px accent check badge top-right.
  On set: Moneycontrol, Google News, Reuters, Inshorts, ET Markets, Reddit.
- **Available** eyebrow, then 4-col grid of off tiles (1px `line` border, 0.66 opacity,
  no badge): Livemint, Bloomberg, NDTV, The Hindu. Tapping a tile toggles on/off.

### 4. Settings + Health
**Purpose:** appearance, digest schedule, sources/data, and listener health.
**Layout:** header + grouped sections (AlGroup = eyebrow + content).

- **Appearance:** segmented control of 3 equal pills — Light / Dark / Auto. Selected =
  `accent` fill + `accentInk` text; others = `card` + `line` border + inkMed.
- **Digest times:** rows (icon + label + value in Newsreader + chevron): Morning 08:30
  (sun), Evening 18:00 (dusk), Night 22:30 (moon). Tapping a row opens a time picker.
- **Sources & data:** "Listening to" → "6 apps" (layers icon, → Allowlist);
  "Export archive (JSON)" (download icon).
- **Health:** a `card` card of status rows — each is a `good` 7px dot + label (inkMed) +
  value (Newsreader 600, in `good`): Listener service "Active", Last capture "2 min ago",
  Battery "Unrestricted", Overnight gap "None". Values are live status, color-coded
  green when healthy (use a warn/error color if a check fails).

A settings row (`AlSetRow`): 13px vertical padding, icon (17, inkMed) + label (14, ink,
flex) + optional value (13, inkMed, Newsreader) + optional chevron, divided by `line`.

---

## Interactions & Behavior
- **Navigation:** bottom nav switches Today / Archive / Settings. Header back chevron
  pops the current detail. Header search opens search over captured stories.
- **Story expand/collapse:** tapping a collapsed story card expands its raw-notification
  drawer (and vice-versa); accent border + chevron rotation signal state. Single-open or
  multi-open is fine — match Cloud Core's list/accordion convention. Animate height +
  chevron rotation ~180–220ms ease.
- **Hero / Next-read / building / listening strip** are all tappable → their detail.
- **Allowlist tiles** toggle on tap: border 1px line ↔ 1.5px accent, opacity 0.66 ↔ 1,
  check badge in/out. "Clear all" turns every On tile off.
- **Appearance segmented control** switches theme immediately; "Auto" binds to system
  color scheme.
- **Digest time rows** open a time picker; saving updates the schedule + Home "Next read".
- Hit targets ≥ 44px. Respect `prefers-reduced-motion` (skip the expand animation).

## State Management
- `theme`: 'light' | 'dark' | 'auto' (persisted; 'auto' derives from system).
- `activeTab`: today | archive | settings.
- `activeTopicFilter`: All | Markets | AI & Agents | IPO | Policy (filters Home/Digest).
- `expandedStoryIds`: which story cards show their raw drawer.
- `allowlist`: map of appId → on/off (drives Digest "N sources", Home listening strip,
  Settings "N apps").
- `digestTimes`: { morning, evening, night }.
- Data: digests (per slot) → stories (clustered) → raw notifications; plus the
  "what's building" signal (name, day-count, story-count, sparkline series) and listener
  health status. These come from the notification-capture service already in Cloud Core.

## Assets
- **No bundled raster/vector assets.** Icons are Lucide-style strokes — use Cloud Core's
  icon set. Source monogram chips are placeholders — **replace with real app icons**.
- Fonts: Newsreader, Instrument Sans, Space Grotesk (Google Fonts). Self-host or use
  Cloud Core's font pipeline; substitute house equivalents if brand requires.

## Files
- `Visualizer.html` — self-contained render of all four Almanac screens, light + dark
  (the visual source of truth).
- Source prototypes (in the wireframes project, for reference): `News Memory - Almanac.html`
  with `screens-almanac.jsx` (Digest/Allowlist/Settings), `screens-paper.jsx` (Home +
  theme tokens), `screens-shell.jsx` (icons, source chips, primitives).
