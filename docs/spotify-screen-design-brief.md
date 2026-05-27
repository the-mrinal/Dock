# Spotify Screen — UI Redesign Brief

A self-contained brief you can paste into a design tool (Claude, v0, Figma AI, etc.) to generate a polished UI for the Music / Spotify screen of **Dock**.

---

## 1. Product context

**Dock** is an ambient dashboard app that runs on a TV (10-foot UI). It surfaces the things you want at-a-glance from across the room: a big clock, weather, and a music panel that controls whatever is currently playing on Spotify. The user does not touch the TV — they navigate with a **DPAD remote** (up / down / left / right / center-select). There is no mouse, no scrolling, no swipe.

This brief is for the **Music tab**, specifically the **Spotify panel**. When a user has linked their Spotify account, this is where they:

- See the track that is playing right now.
- Skip / play / pause / change playback device.
- Pick a new playlist to start.
- Re-play something they listened to recently.
- See what is queued up next.

The current implementation is functional but visually cluttered and confusing to navigate with a remote. We want a redesign.

---

## 2. Hard constraints

| Constraint | Detail |
|---|---|
| **Display** | 1080p / 4K TV, viewed from ~3 meters. Type sizes must be readable from the couch. |
| **Input** | DPAD only — up / down / left / right / select / back. Every interactive element must be focusable and the focus order must be obvious. Mouse / hover patterns do not exist. |
| **Focus state** | Each focusable card must have a strong, unambiguous focus highlight (scale up + accent ring + brightness). The user must always be able to glance at the screen and answer "where am I?" |
| **Aspect** | Landscape, 16:9. |
| **Theme** | Dark. Pure black / near-black background. Text is white / off-white. One accent color: Spotify green `#1ED760`. |
| **Density** | Sparse. Fewer items at larger size beats more items at smaller size. |
| **Motion** | Subtle. Focus transitions ~150 ms. Now-playing art can have a slow zoom/parallax background. No flashy entrances. |

---

## 3. Content the screen must show

The redesign must support all five of these. Treat the list as the source of truth; the layout is up to you.

### 3.1 Now Playing (single, hero)

The currently playing Spotify track. The "anchor" of the screen.

- Large album artwork (the visual focal point).
- Track title.
- Artist name.
- Album name (optional / secondary).
- Playback progress bar with elapsed / remaining time.
- Active device name + device-type icon (e.g. "Living Room Echo", "iPhone").

When nothing is playing, this area should show a tasteful empty state ("Nothing playing — start something from your playlists below").

### 3.2 Transport controls

- Previous track
- Play / Pause (visually larger than the others — it is the most-pressed control)
- Next track
- Change playback device (opens a device picker overlay)

These must be reachable in 1–2 DPAD presses from the Now Playing card. Default focus when the screen opens should land on Play/Pause.

### 3.3 Up Next (queue)

The next track Spotify will play. There is exactly **one** Up Next item (not a list).

- Small album art
- Track title
- Artist
- **Clicking it should jump playback to that track** (and continue from there).

### 3.4 Recently Played

The last ~5 tracks the user listened to. Horizontal row of cards, not a vertical list.

- Album art (the dominant visual — bigger than the text)
- Track title (1 line, ellipsised)
- Artist (1 line, smaller, dimmer)
- **Clicking a card should resume that track in the context it came from** (its original playlist / album), not play it in isolation.

### 3.5 Browse Playlists (entry point)

A way for the user to drill into their full Spotify library and start a playlist.

- Should be a single, obvious entry point on the main screen (e.g. a "Browse Playlists" card or button).
- Tapping it opens a **second screen / overlay** showing the user's playlists as a grid of cover-art cards.
- Tapping a playlist opens a **third screen** showing the playlist's tracks as a vertical list.
- Tapping a track plays the playlist from that track.

You may design these as full-screen drill-downs or as a sliding side-panel — your call, but DPAD navigation between levels must be obvious, and Back must always return one level.

---

## 4. The current UI's problems (what to fix)

These are the user-visible issues with the existing screen. Use them as a checklist.

1. **Cluttered.** Up Next, Recently Played, Browse Playlists, and a Now Playing sidebar are all crammed into one screen with no breathing room. It feels like a settings page, not a media surface.
2. **No visual hierarchy.** Now Playing should dominate. Today it occupies a narrow ~44% sidebar on the right with a small 170×170 dp album thumbnail. The album art needs to feel like the centerpiece.
3. **DPAD focus is unclear.** The user can't predict where pressing "right" will take them. The focus ring is too subtle.
4. **Recently Played is a tall vertical list of small rows**, which wastes a TV's horizontal space and makes album art tiny. It should be a horizontal carousel of large-art cards.
5. **Browse Playlists entry is buried** as a small text row above Up Next. It should feel like a clear call-to-action.
6. **Click-to-play is inconsistent** — clicking a Recently Played track plays the track in isolation (and then playback stops), whereas clicking a track inside a playlist resumes the whole playlist. The new design should treat every card as "resume this in its original context", and visually communicate that context (e.g. each Recently Played card could show the playlist / album it came from underneath the title).
7. **No empty / loading / error states.** Today, sections just disappear or show a tiny gray hint. The redesign should have first-class empty states (e.g. "Spotify is paused — press play to resume").

---

## 5. Layout suggestions (non-binding)

Pick one of these as a starting point, or invent your own — but solve the constraints above.

### Option A — Hero + carousels (recommended)

- **Top 60%**: Now Playing as a wide hero. Huge album art on the left (≥ 350 dp), track info to the right, progress bar full-width underneath, transport controls below the progress bar.
- **Middle row**: "Up Next" — a single card, sized between hero and the carousel below it. Has a small "Next" label.
- **Bottom row**: "Recently Played" — horizontal carousel of 4–5 large cover-art cards. Scrolls horizontally with DPAD right.
- **Top-right corner**: a small "Browse Playlists →" pill that takes focus when the user presses up from the carousel.

### Option B — Split with side rail

- **Left 70%**: Now Playing hero + transport controls.
- **Right 30%**: Vertical rail with three sections stacked: Up Next (1 card), Recently Played (3–5 cards), Browse button at top.

### Option C — Pure focus, drill-in for everything else

- **Full screen**: Now Playing dominates the entire screen.
- A single bottom dock with three buttons: "Up Next", "Recently Played", "Browse Playlists". Each opens an overlay carousel when focused / clicked.

---

## 6. Visual style guide

- **Background**: `#0A0A0B` (near-black). Optional: blurred + dimmed album art as a full-screen bleed behind everything, with a 70% dark scrim on top.
- **Text primary**: `#FFFFFF` (titles, hero text)
- **Text secondary**: `#B3B3B3` (artist, subtitles)
- **Text tertiary**: `#6E6E73` (metadata, timestamps)
- **Accent**: `#1ED760` (Spotify green) — use for play button fill, progress bar, focus ring, active-device indicator.
- **Cards**: rounded corners (12–16 dp), subtle 1 px border in `#FFFFFF14`, no heavy shadows. On focus: 1.05× scale, 2 dp green ring, slight brightness boost.
- **Album art**: always square, rounded corners (8–12 dp), no drop shadow except on the hero.
- **Typography**: a clean geometric sans (Inter, Manrope, or SF Pro). Hero title 36–44 sp bold, artist 22 sp regular, card titles 18 sp medium, metadata 14 sp.
- **Iconography**: rounded line icons, 24 dp default, 32 dp for transport. Play/Pause has a filled circular background in accent green.

---

## 7. Deliverable

A single high-fidelity mock (or a short clickable flow: main screen → playlists grid → playlist tracks list) that:

- Solves every problem in §4.
- Shows the focused vs unfocused state of at least one card.
- Shows the screen with content (real-looking track / artist / playlist names, not "Lorem ipsum").
- Shows the empty state for "nothing is playing".

Once we have the mock, we will implement it natively in the Android TV app. The mock should be in dark mode at 1920×1080.

---

## 8. Out of scope

- Login / linking Spotify account (handled on a separate Settings screen).
- Search.
- Lyrics.
- Voice control.
- Anything other than Spotify (no Apple Music / YouTube Music styling needed).
