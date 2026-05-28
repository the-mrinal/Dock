# Dock — Shell & Remaining Screens Design Brief

A companion to [`spotify-screen-design-brief.md`](./spotify-screen-design-brief.md). That brief covers the **Music** screen. This brief covers **everything else**: the global navigation, the **Home** dashboard, the **Calendar** day view, the **Connect** screen (AirPlay + VPN), and the **Settings** surface.

The visual system being established by the Music brief is the source of truth. **Inherit it.** Do not redefine colors, type ramps, focus treatment, card radii, motion timings, or icon style here — extend them. If something is ambiguous in this brief, default to whatever the Music screen does.

---

## 1. Product context (one-paragraph recap)

**Dock** is an ambient dashboard that runs on an Android TV stick plugged into a spare monitor. It's a third screen — not the laptop, not the phone — that sits there and tells you the time, what's next on your calendar, what's playing, and quietly hosts services like AirPlay/Cast/Miracast mirroring and a WireGuard VPN tunnel. The user is on a couch with a **DPAD remote**: up / down / left / right / center / back / menu. No touch, no mouse. The TV is glanced at from across the room *and* interacted with up close — both modes matter.

The Music screen is being redesigned now. This brief is to bring the rest of the app up to the same bar with a coherent system.

---

## 2. Hard constraints (inherited)

The full constraints table from the Music brief applies here verbatim (1080p/4K TV, ~3m viewing distance, DPAD-only, focus must be unmistakable, landscape 16:9, dark theme, sparse density, ≤150ms focus transitions, no flashy entrances). Don't restate them in mocks — just respect them.

Two additions specific to the shell:

| Constraint | Detail |
|---|---|
| **Ambient mode** | After 90s idle, every screen has to gracefully transition into a calm "ambient" state — clock-forward, dimmed chrome, no focus rings visible. Any keypress brings the active state back. Mocks should show **both** active and ambient states. |
| **Burn-in safety** | The app runs unattended for hours. Avoid bright fixed-position UI persisting in ambient mode. Plan for a few-pixel-per-minute drift on persistent elements (clock, status pills) — the design should anticipate this, not fight it. |

---

## 3. The single biggest problem to fix: navigation

**The current "nav" is a row of dot-separated text labels at the bottom of every page** — `Status · Home · Calendar · Music`. The active label is white and bold, the others are gray. There is no icon, no real affordance, no clear focus ring, and no way to navigate it directly — you swipe through pages with DPAD left/right from anywhere on the current page. New users don't know it exists. Returning users forget what's where. From across the room it reads as decorative typography, not navigation.

We want this replaced with a proper **TV bottom nav**. Requirements:

- **Four destinations**, in this order, left-to-right: **Connect · Home · Calendar · Music**. Home is the implicit default and lives in the visual centre.
- Each destination has an **icon + label** (icons earn the across-the-room glance, labels remove ambiguity for new users).
- **Always visible in active state**, fades to near-invisible in ambient mode.
- **Directly focusable**: pressing DPAD down from page content drops focus onto the nav, left/right moves between destinations, DPAD up returns to the page's default focus.
- The current page is unmistakably indicated — not just a color shift, but enough mass (underline, pill, capsule, glow — your call) that it reads at 3m.
- Hint that **menu opens Settings** — there's no fifth tab for it, but the nav is the natural place to surface the affordance (e.g. a small "≡ Settings" hint at the far right, or a menu-button glyph at the corner of the nav bar).
- Must coexist with the **album-art wash background** that bleeds behind every screen — so it can't be a solid opaque bar; think translucent / scrim / floating capsule.

Propose **one primary direction** plus one alternate. Show the focused vs. unfocused vs. active-page state for each destination.

---

## 4. Surfaces to design

Four screens plus the nav above. Each is a top-level destination reachable from the bottom nav. Treat the content lists as the source of truth — layout is yours to solve.

### 4.1 Home (the dashboard) — the resting face of Dock

This is what people see most of the time. It is the screensaver, the at-a-glance summary, and the entry to everything else. It has two distinct states.

**Active state** (someone is on the remote):

- **Hero clock**: huge, thin, beautifully kerned time (current renders at ~80sp). Includes seconds (smaller, dimmer), AM/PM, and a "Monday, January 1" date line beneath it. The clock is the centerpiece — everything else orbits it.
- **Two widget cards** side-by-side beneath the clock:
  - **Today card** — what's happening *now* (with a "NOW" badge if a meeting is in progress) and the time + title of the next event. One line of meta (calendar tag — "Personal" / "Work" — colour-coded) and a teaser ("3 more today").
  - **Now Playing card** — small album art, track title, artist. Quiet "Up next: ..." line beneath if a queue exists. Tasteful empty state when nothing is playing.
- These cards are **read-only previews**. They are not the place to control playback or scroll the calendar — they hint, and clicking them jumps the user to the full Music or Calendar screen.

**Ambient state** (90s idle):

- Widget cards fade out.
- Clock drifts to the upper portion and slightly enlarges, owning the screen.
- A single thin horizontal strip appears centred below the clock:
  - Music column (left): tiny album art, "NOW PLAYING" label, track + artist.
  - Vertical hairline divider.
  - Calendar column (right): "NOW" or "NEXT" label, event title.
- The whole strip fades to near-zero if neither music nor calendar has content.
- Background album-art wash dims further; bottom nav fades out almost entirely (a faint trace is OK as a hint).

Default focus on this page: doesn't matter much — there's nothing critical to act on. The nav is the natural focus when entering Home.

Pain points to fix:
1. **The two widgets feel like generic dashboard tiles.** They could carry more personality and a clearer information hierarchy (e.g. NOW vs NEXT, time of next event is the lede, not the title).
2. **The ambient strip is the only design that's "right"** — but it's only visible after 90s. Active-state Home should feel just as composed, not noisier.
3. **Onboarding pill** ("Enable phone mirroring") shows up above the nav for the first 7 days. It's bolted on. Design a proper home for first-launch nudges.

### 4.2 Calendar — the day, on one screen

The full timeline for today (and tomorrow if it fits). Merged from personal (Google ICS) and work (Outlook ICS) feeds, colour-tagged.

Content:

- Screen title ("Calendar") and a date subline ("Tuesday, 19 May").
- A **scrollable vertical list** of today's events. Each row:
  - Start time (large-ish, e.g. `09:30`) and duration ("45m").
  - Title (2 lines max, ellipsised).
  - Calendar source pill (`PERSONAL` blue / `WORK` red) — colors come from the inherited palette.
  - "NOW" highlight on the row that is currently happening (a subtle inset glow, not a screaming pill).
  - Optional: location / attendees line in tertiary text, single line.
- Empty state when no events: a tasteful "Nothing on the books today" with the date — *not* an empty page.
- Footer line: "Updated 2 min ago · pull right on remote to refresh" or similar — small, tertiary.

DPAD: up/down scrolls the list. The current/next event should be the default focus when the screen opens. Left/right at the boundary of the list moves to the adjacent page (Home / Music). Down at the end of the list drops to the bottom nav.

Pain points to fix:
1. The current list is a flat vertical list with little visual rhythm — it reads like a settings list, not a day. The day should feel like a **timeline**, with the present moment clearly anchored.
2. Long-meeting blocks vs. 15-minute slots all look the same height. The eye can't tell at a glance how much of the day is committed.
3. Today's footer is afterthought-y. Either make it useful (last sync, refresh affordance) or remove it.

### 4.3 Connect — AirPlay + VPN, side by side

A status page for the two background services Dock hosts: phone mirroring (AirPlay / Cast / Miracast) and WireGuard VPN. Today it's two stacked status cards, each with a circular halo + dot, a status word, a one-line detail, and an action button. It works, but it's bland.

Content for each card:

- **Service name** ("AirPlay", "VPN") in a small all-caps label.
- **Status indicator** — visual (halo + dot or equivalent), color-coded:
  - `READY` / `IDLE` — neutral gray.
  - `CONNECTING` / `PENDING` — warm amber.
  - `ACTIVE` / `CONNECTED` — green for VPN, the **AirPlay blue `#5AC8FA`** for mirroring (the multi-protocol palette is already in colors.xml — extend it, don't replace it).
  - `ERROR` — red.
- **Big status word** (e.g. "Connected", "Streaming from iPhone", "Disconnected") — thin, large (~28sp now, probably right).
- **Detail line** — what the user needs to know in two lines max. Examples: "AirPlay receiver advertising as 'Dock — Living Room'", "Connected · Frankfurt · 4d uptime", "Drop a `.conf` from your laptop to start".
- **Single primary action button** ("Turn on" / "Turn off" / "Connect" / "Disconnect"). DPAD-focusable, with the unambiguous focus ring inherited from the system.
- Optional: a tertiary detail row beneath the button — e.g. for VPN, the kill-switch state ("Kill-switch: on" / "off") with a hint that it's configured in Settings.
- A subtle protocol icon strip for AirPlay (AirPlay / Cast / Miracast — three small icons that light up individually when each is enabled).

Cards sit side-by-side with a thin vertical hairline between them. Screen has a quiet "CONNECT" all-caps page label up top, a footer hint that deeper config lives in Settings.

Pain points to fix:
1. **Both cards are visually identical** — same halo, same dot, same word. A glance should let you tell which is which without reading. Lean on the protocol/service iconography or accent color to differentiate.
2. **The action button is the only obvious interactive element** — but for AirPlay there's a real per-protocol toggle (AirPlay / Cast / Miracast) buried in Settings. Surface it here, even if Settings still owns the full control.
3. When **streaming is live**, this screen is hidden behind the full-bleed video overlay anyway — design specifically for the *idle* state. That's when this screen exists.

### 4.4 Settings — the only screen that isn't 10-foot

This is the one screen where the user is up close, holding the remote, configuring things. It is currently a long vertical list of grouped preferences. It does *not* need to look like the rest of the app — it needs to look like **a TV settings page that's pleasant to use with a DPAD**.

Content (grouping):

- **Calendar**: personal ICS URL, work ICS URL, refresh interval.
- **Music / Spotify**: connect / disconnect Spotify, current account, "active device" state.
- **Phone mirroring**: master toggle, device name, per-protocol toggles (AirPlay / Cast / Miracast).
- **VPN**: receive config from laptop (LAN), current tunnel, kill-switch shortcut, indicator overlay toggle.
- **Display**: idle timeout (default 90s), sleep timer, ambient drift.
- **About**: version, license, "Dock on GitHub" link.

Interaction model:

- **Left rail** with category labels (5–6 items). Vertical, DPAD-navigable.
- **Right pane** shows the selected category's preferences. DPAD right enters the pane; DPAD left returns to the rail.
- Each preference is a single focusable row with a clear right-aligned value/toggle.
- Long values (ICS URLs) truncate gracefully, expand to a focused modal when activated.

Pain points to fix:
1. **Today is a single scroll**: high scroll cost on a DPAD, no sense of where you are. The left-rail split fixes this.
2. **The "Receive config from laptop" flow is the most magical thing in the app** — it spins up a LAN endpoint and shows a URL/QR. Design it as a moment, not a buried button. When active, the right pane should show the QR code huge, the URL big and readable, and a clear "Cancel" affordance.
3. **No first-time empty/wizard mode** — fresh installs land on a settings page with nothing connected. Design a gentle empty state per section ("No calendars yet — paste an iCal URL to start").

---

## 5. Design system extensions (delta from the Music brief)

The Music brief establishes: dark base, white/gray text ramp, Spotify-green accent for the Music screen, geometric sans, card radii 12–16dp, soft borders, focus = 1.05× + accent ring + brightness boost, transitions ~150ms.

These additions/clarifications need to come out of this brief:

**Per-screen accent system.** Each top-level screen has its own accent color so the user knows which surface they're on without reading. Inherit the existing palette from `colors.xml`:

| Surface | Accent | Hex | Used for |
|---|---|---|---|
| Connect — AirPlay | AirPlay blue | `#5AC8FA` | Status active, protocol icons, focus on action button |
| Connect — VPN | Green | `#1DB954` | "Connected" state, focus on action button |
| Home | Pure white | `#FFFFFF` | Clock + minimal accent — Home is neutral and the others "colour in around it" |
| Calendar | Personal blue `#8AB4F8` / Work red `#F28B82` | (already in palette) | Source tags and event-now highlight |
| Music | Spotify green | `#1ED760` | (owned by the Music brief) |

The focus-ring color should track the screen's accent. The bottom nav uses **white** when active (the universal chrome), but the active-page indicator can pick up the accent.

**Type ramp** (extending the Music brief, codifying what's working in the current build):

| Role | Size | Weight | Notes |
|---|---|---|---|
| Hero clock | 96–120sp | Thin | Slight tracking. The single biggest thing on screen. |
| Page hero (e.g. Calendar title) | 36–44sp | Light | One per screen, top-left or top-centre. |
| Status word (Connect cards) | 28sp | Thin | Big and quiet. |
| Card title | 18–20sp | Medium | Track titles, event titles, primary preferences. |
| Body / detail | 13–14sp | Regular / Light | Artist, event meta, status detail. |
| Eyebrow / label | 10–12sp | Medium, all-caps, 0.16–0.24 tracking | Section labels, "NOW PLAYING", page name in Connect. |
| Tertiary footnote | 10–12sp | Light | Last-updated, footer hints. |

**Card recipe** (one card, repeated everywhere):

- Background: `#181818`-ish, **or** transparent with a 1px `#FFFFFF14` border when it sits directly on the album-art wash. Pick one rule per screen and stick to it.
- Radius: 16–18dp.
- Padding: 24dp.
- Focus state: 1.04× scale, 2dp accent ring (screen-accent), subtle brightness boost. No drop shadow.
- Disabled / empty: 60% opacity, no border.

**Status pill** (the "NOW" badge, the protocol tag, the calendar source tag): all-caps, 10sp, medium weight, 0.16 tracking, 6dp horizontal padding, 3dp vertical padding, 4dp radius. Colour comes from semantic palette.

**Motion**:

- Page transitions (DPAD left/right): horizontal crossfade + 16dp slide, 220ms ease-out.
- Active → ambient: 600ms cross-dissolve. Ambient → active on keypress: 200ms.
- Focus transitions: 150ms (inherited from Music brief).
- Clock drift (ambient mode): 1px every 60s, total radius ~16px.

---

## 6. What this brief is NOT asking for

- Don't redesign the Music screen — that's the Music brief's job.
- Don't reinvent the colour ramp, the focus treatment, the type family, the card radii — extend them.
- Don't add features (no voice control, no weather, no widgets-marketplace).
- Don't propose a light mode.
- Don't replace album-art wash backgrounds — they're load-bearing for Dock's identity.

---

## 7. Deliverable

A single high-fidelity figma/mock set in dark mode at **1920×1080**, covering:

1. **Bottom nav** — primary direction + one alternate, with active / focused / unfocused states for each destination, plus the "Settings" affordance.
2. **Home — active state** with both widgets populated (real-looking event title and track title, not Lorem).
3. **Home — ambient state** (90s idle), clock anchored, single horizontal strip.
4. **Calendar — day view** with one in-progress event highlighted, mixed personal + work tags, ~6 events.
5. **Calendar — empty state** ("Nothing today").
6. **Connect — idle state** with both cards (AirPlay READY, VPN DISCONNECTED).
7. **Connect — active state** (AirPlay streaming from "iPhone — Mrinal", VPN CONNECTED · Frankfurt).
8. **Settings — landing** with left rail + right pane, "Calendar" category open.
9. **Settings — LAN config import moment** (the QR/URL magic moment for VPN config drop).

For each: show the focused element with the inherited focus ring. Show one screen in ambient mode at minimum (Home is the obvious choice).

Once the mocks land, they'll be implemented natively in the Android TV app on plain Android views (no Compose). Keep that in mind for elaborate effects — gradients, blurs, scrims are fine; particle systems and CSS-only tricks are not.

---

## 8. Out of scope

- The Music screen (covered separately).
- Onboarding / first-launch wizard (will follow once this system is set).
- The streaming overlay (full-bleed video from sender) — that's just black + a small pill, no design work needed.
- Localisation layout variants (German / French copy is longer; the design should tolerate +30% string length but doesn't need separate mocks).
- Android phone / portrait layouts. This app is TV-only.
