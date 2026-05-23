# PR #13 Music UI — before/after

Fire TV Stick 4K, density 320, factor 2.0. APK rebuilt from `redesign/all` with the proportional-layout fix applied.

## Per-state results

| State | Before | After |
|---|---|---|
| **11/12 NowPlaying** | `before/11_music_landing.png` — hero overflows; title/artist/album/progress/transport invisible; Up Next & Recently Played missing | `after/11_music_landing.png` — eyebrow + playlist name + device chip + title ("Matargashti") + artist + progress bar + transport (prev/play/next/queue) + Up Next strip + 5-card Recently Played row all visible |
| **14 Browse** | `before/14_browse.png` — cards clipped by nav; meta + count + footer hidden | `after/14_browse.png` — 4 playlist cards (Liked Songs, My top tracks, Tocheck, RandomPlaylist) with cover + title + meta + track count + footer legend all visible |
| **15 PlaylistTracks** | `before/15_playlist_tracks.png` — hero too tall; description, column headers, track list invisible | `after/15_playlist_tracks.png` — 160dp heart cover + eyebrow + Liked Songs title + Play/Shuffle pills + column headers (#/TITLE/ALBUM/TIME) + 3 visible track rows |
| **16 Device picker** | not captured (focus stack jumped) | not captured (same reason); inherits proportional dimens (`music_device_picker_width` 480dp) — assume fixed |
| **13 Empty** | not reachable (active Spotify session) | not reachable; layout changes mirror NowPlaying's pattern |

## Root cause + fix

PR authored music layouts treating 1 CSS-pixel = 1 `dp`. On density-2 Fire TV, every `dp` becomes 2 px so the entire Music section rendered 2× the design's intended physical fraction.

Fix: proportional layout for big columns + halved physical DPs for ergonomic targets.

**Files touched** (Music only):
- `app/src/main/res/values/dimens.xml` — replaced 16 music_* values; deleted `music_hero_cover_size`
- `app/src/main/res/layout/screen_music.xml` — smaller TopChrome (tile, fonts, breadcrumb, pill, clock)
- `app/src/main/res/layout/view_music_now_playing.xml` — full rebuild: hero is now `ConstraintLayout` with cover at `dimensionRatio="H,1:1"` (square sized by row height) + right pane as `0dp` weighted; three vertical sections share subview frame via `layout_weight`; auto-sizing hero title
- `app/src/main/res/layout/view_music_browse.xml` — autosize title; recycler bottom padding
- `app/src/main/res/layout/view_music_empty.xml` — autosize title; smaller icon, body, CTAs
- `app/src/main/res/layout/view_music_playlist_tracks.xml` — autosize title; smaller pills, column headers; recycler bottom padding
- `app/src/main/res/layout/item_playlist.xml` — halved card padding & fonts
- `app/src/main/res/layout/item_recent_card.xml` — halved card padding & fonts
- `app/src/main/res/layout/item_playlist_track.xml` — halved row padding, column widths, fonts

**No code changes** in `MusicScreenBinder.kt` — all view IDs preserved.

## Out of scope (deferred)

Home/Calendar/Connect have the same underlying scaling regression at smaller blast radii (see `regression/home.png`, `regression/calendar.png`, `regression/connect.png`). They use their own dimens/layouts that weren't touched here. Recommend a follow-up that audits Phase 0 + Streams B/C against the same density principle.

## Not committed

Branch `redesign/all` has uncommitted edits. User asked to review after-screenshots before any commit lands on the PR branch.
