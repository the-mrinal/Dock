# PR #13 Music UI audit — findings

Device: Fire TV Stick 4K (model `kara`/`AFTKA`), 1920×1080, **density 320 (factor 2.0)**, 1dp = 2px.

## Root cause

All Stream-A music layouts and dimens were authored as if 1dp = 1 design-CSS-pixel (the React prototype's canvas pixels). On a density-2 TV, every `dp` value renders 2× the intended physical fraction of the screen. The PR appears to have been visually validated on a density-1 device (mdpi emulator or a TV that mis-reports density 1.0). The same problem touches Home, Calendar, and Connect to a lesser extent but the structural overflow is most acute on Music because three vertically-stacked sections (hero, Up Next, Recently Played) all have fixed/intrinsic heights that exceed the available subview frame.

**Available canvas after activity padding and TopChrome**: 1696×650 px = 848×325 dp at density 2.0.

**Fixed dimens in the redesign**:
- `music_hero_cover_size = 472dp` → would consume 472dp height — **145% of available 325dp**. Hero alone overruns the subview frame; UpNext and Recently Played get pushed off-screen entirely.

## Per-screen observations

### 11/12 — NowPlaying (`view_music_now_playing.xml`)

`before/11_music_landing.png`, `before/11c_music_focus_top.png`

| Element | Design | Observed |
|---|---|---|
| Hero cover | 472css-px square (~25% of width), 18px radius, shadow | 944px wide, ~660px tall (clipped at parent bottom), ~49% of width, no shadow |
| Right pane eyebrow row | Eyebrow "Playing from playlist" + playlist name (left) + device chip (right) | Eyebrow column collapsed to 31px wide vertical strip; only VU bars icon visible mid-height; device chip stranded at top-right alone |
| Track title (Manrope 200 / 96sp) | Below eyebrow row, takes ~110px tall | Not rendered (pushed off-screen because parent vertical LL is full) |
| Artist, Album·Year | 34sp / 18sp lines under title | Not rendered |
| Progress bar (6px track + 16px thumb) | Bottom of hero | Not rendered |
| Transport row (76/96/76dp circles + Queue pill) | Below progress | Not rendered |
| Up Next strip | Card with cover + title + hint | Not in hierarchy at all (sibling pushed off-screen) |
| Recently Played row | 5-column grid of 14dp-radius cards with covers | Not in hierarchy at all |

### 14 — Browse Playlists (`view_music_browse.xml`)

`before/14_browse.png`

| Element | Design | Observed |
|---|---|---|
| TopChrome breadcrumb | "Music › Browse Playlists" | Renders ✓ |
| "YOUR LIBRARY" eyebrow + "Playlists" 76dp title | Left side | Renders, but font/spacing oversized |
| Tab pills ("All", "By you", "By Dock", "Recent") | Top-right of section | Not visible — pushed off-screen or layout shifted |
| Playlist card grid (4 columns) | Cards ~310dp wide × ~430dp tall (cover + title + sub + count) | First row of 4 cards visible but **clipped at bottom by nav capsule** — only top half of each card shown; sub-text & count never visible |
| Footer hint row ("OK Open · BACK Now Playing · ↑↓←→ Navigate") | Above the nav | Completely hidden behind nav capsule |

### 15 — Playlist tracks (`view_music_playlist_tracks.xml`)

`before/15_playlist_tracks.png`

| Element | Design | Observed |
|---|---|---|
| Hero cover (300dp) + playlist meta | Left side | Cover is 600px wide on screen (way too big); occupies ~31% of viewport |
| "PLAYLIST · 130 TRACKS" eyebrow | Above title | ✓ |
| Playlist title (Manrope 200/76dp) | Right of cover | ✓ |
| Description text | Below title | Not visible — pushed off-screen |
| "Play playlist" + "Shuffle" pills | Below desc, focused state lime | Renders, lime-stroked Play focused ✓ |
| Track list (rows: # / Title / Album / Duration) | Below pills, scrollable | **Not visible at all** — clipped by nav |
| Footer hint | Above nav | Hidden behind nav |

### 16 — Device picker

`before/16_device_picker.png`

Did not open via my keyevent script (focus stack jumped wrong). The dialog is `SpotifyDevicePickerDialogFragment` with a 760dp width and 20dp radius — at density 2.0 it would render 1520px wide (79% of screen) and risk overflow vertically. Likely shares the same root cause but not directly captured.

### 13 — Empty

Not reachable: device has an active Spotify session (Amazon FireTV Stick 4K). The binder routes to NowPlaying when a session exists, so Empty cannot be triggered without disconnecting. Would need a Spotify-off path to capture. Same root cause expected.

## Regression check (non-Music screens)

`regression/home.png`, `regression/connect.png`, `regression/calendar.png`

- **Home**: Clock dominates correctly but the seconds (`:35`) and `PM` are positioned as tiny labels at oversized offset — Home widgets (Today + Music) **not visible** below the clock. The hero clock is sized in `sp` so density-scaling is partially correct; what's broken is the widget grid below (likely same overflow issue).
- **Connect**: "AIRPLAY" / "VPN" two-column header renders correctly with status dots. But the underlying "Off" / "Disconnected" labels are huge and **bleed into the nav capsule** — same dp-as-css-px problem.
- **Calendar**: Top header (eyebrow + "Saturday, May 23" + count + legend + updated-line) renders, but the **calendar bands grid (the main content) is missing** — pushed off-screen.

So all four PR pages have the same dp-as-css-px scaling regression, but the user has flagged Music as the priority. Fixing Music first sets the pattern for the rest as follow-up.

## Proposed fix — proportional layout for Music

User has chosen **proportional layout** over scalar dimen halving. The fix:

### 1. `screen_music.xml`
- TopChrome height: replace `@dimen/music_top_chrome_height` (56dp = 112px) with `wrap_content` + an explicit minHeight in DP that is TV-ergonomic (e.g. `minHeight=56dp` to fit fonts; rely on font sizes for actual height).
- TopChrome margin-bottom: shrink from 28dp to 12dp.
- `musicSubviewFrame` already uses `layout_weight=1` ✓ — leave that.

### 2. `view_music_now_playing.xml` — full structural rewrite of hero
Replace the fixed-height hero LinearLayout with a `ConstraintLayout` (or weighted LinearLayout) so:
- **Album cover**: sized as a square via aspect-ratio constraint locked to right-pane height (or % of width). Use `ConstraintLayout` + `dimensionRatio="1:1"` and constrain `app:layout_constraintWidth_percent="0.27"` (or use a `Guideline` at 27% width). On density-2 1920×1080 this yields ~518px wide cover — visually matching the 472/1920=24.6% design ratio.
- **Right pane**: occupies remaining width with `layout_constraintStart_toEndOf` of cover + gap of `24dp`. Vertical LinearLayout inside, with `Space` weighted child to push progress + transport to bottom.
- **Hero block height**: instead of fixed `@dimen/music_hero_cover_size`, set `layout_height=0dp` with `layout_weight=1.6` (or use ConstraintLayout vertical-percent). Then Up Next gets `wrap_content` and Recently Played gets `layout_height=0dp` with `layout_weight=1.0`. This makes the three sections share the subview frame proportionally.
- **Text sizes**: keep the hero title at `TextAppearance.Dock.HeroTitle` but verify the 96sp doesn't overflow on density-2; clamp to `maxLines=2` (already set ✓) and check `autoSizeTextType=uniform` is needed.

### 3. `view_music_browse.xml`
- Playlist card grid: switch from fixed card dimensions to weighted grid via `RecyclerView` + `GridLayoutManager` with adaptive column widths. Add bottom padding equal to the nav capsule height so the last row clears the nav.
- Tab pills row: ensure it stays in the same row as the H1, not below it (likely a wrap issue with the H1 at 76dp font).

### 4. `view_music_playlist_tracks.xml`
- Hero cover: use a square with `app:layout_constraintWidth_percent` like NowPlaying, OR set to a sensible DP (e.g. `160dp` square) instead of 300dp.
- Track list `RecyclerView`: must use `layout_height=0dp` + `layout_weight=1` so it consumes whatever is left, and add `paddingBottom` to clear the nav.

### 5. Dimens cleanup
- `music_hero_cover_size`, `music_tracks_cover`, `music_empty_tile_size`, `music_device_picker_width` — delete (replaced by proportional sizing).
- `music_hero_gap` → keep but reduce to 24dp.
- `music_transport_primary` (96 → 56dp), `music_transport_secondary` (76 → 44dp) — these are physical D-pad-focus targets so keep small DPs.
- `music_progress_thumb` (16 → 10dp), `music_progress_track` (6 → 4dp) — keep physical, scale slightly down.
- `music_top_chrome_height`/`bottom`/`section_gap` — halve (28/14/18 dp).
- `music_recent_card_radius`/`playlist_card_radius` — halve (7/8 dp).

### 6. Inline `dp` margins/paddings in music layouts
Halve the design-pixel-as-dp inline values: 14→7, 16→8, 22→11, 28→14, etc. Or convert margin gaps to constraint margins on a proportional grid.

### 7. Subview frame must reserve space for the bottom nav
Currently `pageIndicatorGroup` overlaps the subviewFrame. Add `android:paddingBottom` on `musicScreenRoot` (or `marginBottom` on `musicSubviewFrame`) equal to the nav capsule height + spacing (~80dp at density-2 = ~40dp logical) so Browse/Tracks/Empty contents stop clipping into the nav.

## Out of scope / follow-up

- Home/Calendar/Connect have analogous regressions (see screenshots) — defer per user's "music first" directive.
- Device picker dialog not captured live; assume same scaling issue and fix in pass.
- Empty state path not directly verified; cover by reading layout XML.
