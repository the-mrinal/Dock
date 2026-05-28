# Dock TV — full UI redesign plan

Reference design: `/Users/mrinalchandra/Documents/index.html` (the "Dock — TV design" React prototype, 16 artboards).
Target: every existing UI surface in this app, ordered by complexity, parallelizable across 2–3 coding agents.

---

## 0. Mapping (HTML artboard → app surface)

| # | Artboard | App surface today | File(s) | Gap |
|---|---|---|---|---|
| 00 | Nav system (spec) | 4-dot text indicator + arrow-swipe pager | `activity_main.xml`, `MainActivity.kt` | Need bottom-nav capsule + focusable D-pad target |
| 01 | Home — Active (multi-now) | Home page | `screen_home.xml`, `HomeScreenBinder.kt` | Visual reskin + widget restructure |
| 02 | Home — Active (ending soon) | Same screen, different state | same | New state in TodayWidget |
| 03 | Home — Ambient (idle) | `homeAmbientRow` | same | Restructure as horizontal "Tickler" capsule, drive transition |
| 04 | Calendar — Day w/ NOW band | Calendar page | `screen_calendar.xml`, `item_calendar_event.xml` | Add NOW pill, time column, eyebrow, legend |
| 05 | Calendar — partial-overlap focused | — | — | **Missing**: band rows with multi-card overlap, partial tag |
| 06 | Calendar — Empty day | — | — | **Missing**: empty state |
| 07 | Connect — Idle | Status page | `screen_status.xml`, `StatusScreenBinder.kt` | Restructure to symmetric ServiceColumn pair |
| 08 | Connect — Active | Status + `StreamingOverlay` | + `StreamingOverlay.kt` | Add pulsing halo rings |
| 09 | Settings — Landing | `SettingsActivity` (Android Preference UI) | `xml/preferences.xml` | **Full rebuild** — rail + content, custom typography |
| 10 | Settings — LAN .conf drop | VPN pair screen | `activity_config_import.xml` | Restyle: green status dot + drop H1 + QR/URL split |
| 11 | Music — NowPlaying landing | Music right pane | `screen_music.xml#panelNowPlaying` | Promote to full surface, kill 56/44 split |
| 12 | Music — Recently Played focused | Music left pane | `screen_music.xml#viewDefaultPanel` | Move to a row under NowPlaying |
| 13 | Music — Empty state | — | — | **Missing**: empty state |
| 14 | Browse Playlists | Music left pane drill-in | `view_browse_playlists.xml` | Promote to full-surface 4-col grid |
| 15 | Playlist — Deep Focus tracks | — | — | **Missing**: tap-playlist drill-in |
| 16 | Change playback device | AlertDialog | `SpotifyDevicePicker.kt` | Custom DialogFragment, blurred underlay |

---

## 1. Architecture deltas (what changes beyond paint)

1. **Bottom nav.** Replace the 4-dot text indicator + arrow-swipe with the centered translucent capsule (`Connect / Home / Calendar / Music`). The capsule is focusable; D-pad DOWN from a page focuses nav, LEFT/RIGHT picks destination, OK switches pager page. Active tab accent is sourced from `NAV_ACCENTS`.
2. **Music layout.** Music page goes from a 56/44 split to a stacked single surface. Browse and Playlist-tracks become drill-ins, not side panels. A small in-screen state machine (`MusicNavController`) routes between `now-playing | empty | browse | playlist-tracks`.
3. **Settings.** Replace `PreferenceFragmentCompat` with a custom `activity_settings.xml` (320dp rail + content frame) hosting six fragments. The system preference DSL goes away.
4. **Typography.** Ship Manrope (200/300/400/500/600/700) + JetBrains Mono (400/500/600/700) in `res/font/`. Add `TextAppearance.Dock.*` styles covering the HTML scale (HeroDisplay 200/260sp, HeroTitle 200/96sp, Title 200/68sp, Eyebrow JBMono 13sp UPPERCASE +0.16em, MonoData, etc.).
5. **ArtWash.** Add a shared `ArtWashView` (custom View): blurred radial gradient sampled from the cover palette + grain. Sits behind every page, intensity prop varies (0.10 Settings → 0.55 NowPlaying).
6. **Cover generator.** Port the HTML's deterministic SVG cover generator to Kotlin (`CoverDrawable` + `CoverPalette`). Real Spotify art is preferred when available; fallback uses the generator so the visual language is consistent on cold start / placeholders.
7. **Focus model.** Add a `FocusableContainer` view (FrameLayout) that draws the HTML focus ring (2dp accent border + halo + scale) on `onFocusChanged`. Replaces the ad-hoc focus selectors.
8. **Streaming overlay.** Stays as-is conceptually (full-bleed video surface), but its corner pill is restyled to the new design language. The 2-column Connect screen is *not* hidden when streaming — it shows the active state (artboard 08) and the overlay sits in front of it.

---

## 2. Phasing

Foundation is sequential (blocks everything). After that, three streams run in parallel — Music is the heaviest, Settings is a full rebuild, Home+Cal+Connect is grouped because each is lighter.

```
Phase 0  Foundation                            (1 agent, ~1.5–2 days)
        ↓ blocks
Phase 1  Stream A  Music         (artboards 11–16)
         Stream B  Settings      (artboards 09–10)
         Stream C  Home + Cal + Connect (01–08)
        ↓ all three in parallel, ~3–4 days each
Phase 2  Integration + polish + screenshot review against HTML
```

---

## 3. Phase 0 — Foundation (sequential, blocks all streams)

Goal: ship the shared visual primitives so the parallel streams compose, not collide.

### 3.1 Type & color tokens

- `app/src/main/res/font/` — add `manrope_thin.ttf` (200), `manrope_light.ttf` (300), `manrope_regular.ttf` (400), `manrope_medium.ttf` (500), `manrope_semibold.ttf` (600), `manrope_bold.ttf` (700); same for `jetbrains_mono_*`. Create `font/manrope.xml` and `font/jetbrains_mono.xml` font-family resources with weight bindings.
- `app/src/main/res/values/colors.xml` — append the HTML `T` palette as `dock_*` colors: `dock_bg #0A0A0B`, `dock_bg_elev #15151A`, `dock_card 0A%`, `dock_card_focus 14%`, `dock_border 14%`, `dock_text_pri/sec/ter/quat`, `dock_accent_chartreuse #C8F25C`, `dock_accent_chartreuse_dim`, `nav_accent_connect #5AC8FA`, `nav_accent_home #FFFFFF`, `nav_accent_calendar #8AB4F8`, `nav_accent_music #C8F25C`, `c_airplay`, `c_vpn`, `c_amber`, `cal_personal_dot/text/tag`, `cal_work_dot/text/tag`.
- `app/src/main/res/values/type.xml` (new) — `TextAppearance.Dock.HeroDisplay` (Manrope 200, 260sp, tabularNums, -0.04em), `.HeroTitle` (200/96sp/-0.035em), `.Title.Large` (200/68sp), `.Title.Medium` (300/44sp), `.Body.Large` (400/22sp), `.Body.Medium` (400/18sp), `.Label.Large` (500/17sp), `.Eyebrow` (JBMono 500/13sp, uppercase, letterSpacing 0.16), `.Eyebrow.Tracking` (0.42em), `.MonoData` (JBMono 500/16sp, tabularNums).

### 3.2 Shared views

- `app/src/main/java/com/ambient/tvclock/ui/ArtWashView.kt` — custom View. Inputs: seed string (cover id) + intensity float. Draws blurred radial gradient layered on `dock_bg`, plus a 1px grain overlay. Implemented with `RenderEffect.createBlurEffect` on API 31+ and a pre-blurred Bitmap fallback. Exposed via `setSeed`/`setIntensity` with crossfade.
- `app/src/main/java/com/ambient/tvclock/ui/CoverPalette.kt` — port `hashStr` (FNV-1a), `COVER_PALETTES` (10 palettes), `COVER_LAYOUTS` (8 layouts), `pick`, `rnd`, `coverBg`. Deterministic from a seed string.
- `app/src/main/java/com/ambient/tvclock/ui/CoverDrawable.kt` — `Drawable` that renders the cover (one of 8 layouts × 10 palettes) for a given seed. Used as ImageView fallback when Spotify art is null.
- `app/src/main/java/com/ambient/tvclock/ui/FocusableContainer.kt` — FrameLayout that takes `accentColor` and `focusScale` attrs. On `onFocusChanged` it animates `scaleX/scaleY` + draws a 2dp accent border outside its bounds + 6dp halo + 12dp drop shadow. Replaces the ad-hoc `state_focused` selectors. Easing: 150ms `cubic-bezier(.2,.7,.3,1)`.
- `app/src/main/java/com/ambient/tvclock/ui/BottomNavCapsuleView.kt` — horizontal LinearLayout child views, 4 destinations, accent-aware active state, full focus support. Public API: `setActive(page: DashboardPage)`, callback `onPageSelected`. Backdrop blur via `RenderEffect` on API 31+ (fallback: solid translucent black).
- `app/src/main/java/com/ambient/tvclock/ui/PulseHaloView.kt` — two concentric circles animating `scale 1 → 3.2`, `opacity 0.7 → 0`, 2.2s loop, second ring offset 1.1s. Used by Connect-active halos and the LAN listening indicator.
- `app/src/main/java/com/ambient/tvclock/ui/VuBarsView.kt` — the animated `Ic.Bars` (4 bars with staggered heights). Used in NowPlaying eyebrow, Music widget, Connect "14m left", Calendar now row, playing track row.
- `app/src/main/java/com/ambient/tvclock/ui/PillButton.kt` — capsule button styled to HTML spec (chartreuse-filled when focused/active, outlined when resting). Takes `accentColor`. Replaces the ad-hoc Material buttons.
- `app/src/main/java/com/ambient/tvclock/ui/EyebrowView.kt` — small composite (optional leading icon + Eyebrow text). Used everywhere.

### 3.3 Shell integration

- `app/src/main/res/layout/activity_main.xml` — wrap pager in a `FrameLayout`. Add `ArtWashView` at `index 0` (behind pager). Replace the 4-dot indicator with `BottomNavCapsuleView`. Remove the old indicator drawables.
- `MainActivity.kt` — drive `ArtWashView.setSeed/intensity` per page (Home 0.40, Music 0.55, Calendar 0.22, Connect 0.18 idle / 0.32 active). Wire `BottomNavCapsuleView.onPageSelected` to `pager.setCurrentItem`. Re-route D-pad LEFT/RIGHT so it stays on a page; D-pad DOWN focuses the nav.

### 3.4 Out-of-scope for Phase 0

- Do not redesign any page yet. Drop new components in but leave existing page contents intact — Phase 1 streams take it from there.

### 3.5 Acceptance

- App builds. Pager works. New bottom-nav capsule is focusable, switches pages, accents flip per page.
- Manrope renders on a Hello-World text in any one screen.
- ArtWash visibly tints the background and crossfades on swipe.

---

## 4. Stream A — Music redesign (artboards 11–16)

**Highest complexity.** Owner: Agent A. Branch: `redesign/music`.

### 4.1 Restructure

Replace `screen_music.xml` with a single-column stack:
1. `TopChrome` row: music tile + eyebrow "MUSIC" + breadcrumb · LAN+wifi · tabular clock · "Browse Playlists" pill.
2. `HeroNowPlaying`: 472dp cover (left) | right pane (eyebrow "Playing from playlist · Deep Focus" + device chip + Manrope-200 96sp title + 400/34sp artist + album+year + 6dp progress bar + transport row Prev/Play/Next/Queue).
3. `UpNextStrip`: full-width card with cover + title/artist + "Press OK to jump" pill.
4. `RecentlyPlayedRow`: horizontal 5-col grid of `RecentCard`s.

### 4.2 New layouts

- `view_music_now_playing.xml` (renamed from `panelNowPlaying`, restructured)
- `view_music_browse.xml` (replaces `view_browse_playlists.xml`; eyebrow + Manrope-200 76sp "Playlists" title + 4-col grid + bottom legend)
- `view_music_playlist_tracks.xml` (**new**; 300dp cover + eyebrow + Manrope-200 88sp title + Play/Shuffle pills + tracks RecyclerView)
- `view_music_empty.xml` (**new**; 220dp dashed music glyph tile + eyebrow + Manrope-200 84sp "Quiet for now." + CTA row)
- `item_playlist.xml` — restyle: square cover + 22sp title + secondary line + tabular count
- `item_playlist_track.xml` (**new**) — grid `54 80 1fr 1.1fr 80`: index OR animated `VuBarsView` + title + album + duration (mono)
- `item_recent_card.xml` (**new**) — square cover + title + artist + mono context line
- `dialog_device_picker.xml` (**new**) — 760dp card, eyebrow + Manrope-300 44sp "Play on…" + 4 `DeviceRow`s

### 4.3 Navigation

Add `MusicNavController` (state machine) inside `MusicScreenBinder.kt`:
- States: `NowPlaying` (default) → `Empty` (when no session) | `Browse` (BrowsePlaylists pressed) → `PlaylistTracks(id)` (playlist clicked).
- BACK pops: PlaylistTracks → Browse → NowPlaying.
- Transitions: 150ms crossfade between sub-views inside the Music page.
- Persist last-visible sub-view in `SavedStateHandle`.

### 4.4 Device picker

Replace `SpotifyDevicePicker.kt` AlertDialog with `SpotifyDevicePickerDialogFragment.kt`:
- Backdrop = blurred (20dp) screenshot of underlying NowPlaying + 0.5 black overlay.
- Card: `dock_bg_elev` 92%, 20dp radius, 50dp drop shadow, RenderEffect blur on backdrop.
- Active row shows VuBars + "Playing"; inactive rows show chevron right.

### 4.5 Files

| File | Action |
|---|---|
| `screen_music.xml` | rewrite (single-column stack) |
| `view_browse_playlists.xml` | delete (replaced by `view_music_browse.xml`) |
| `view_music_now_playing.xml` | new |
| `view_music_browse.xml` | new |
| `view_music_playlist_tracks.xml` | new |
| `view_music_empty.xml` | new |
| `item_playlist.xml` | restyle |
| `item_playlist_track.xml` | new |
| `item_recent_card.xml` | new |
| `item_queue_track.xml` | restyle (Up Next track row) |
| `dialog_device_picker.xml` | new |
| `MusicScreenBinder.kt` | major rewrite — adds `MusicNavController`, drives new layouts, empty-state detection |
| `PlaylistAdapter.kt` | update for new item layout |
| `QueueTrackAdapter.kt` | restyle |
| `PlaylistTracksAdapter.kt` | new |
| `RecentCardAdapter.kt` | new |
| `SpotifyDevicePicker.kt` | rewrite as `DialogFragment` |
| `PlaybackProgressBar.kt` | restyle (6dp track + 16dp thumb halo) |

### 4.6 Acceptance

- Artboards 11, 12, 13, 14, 15, 16 reproduce at 1920×1080 with focus visiting every interactive element.
- BACK from PlaylistTracks pops to Browse; BACK from Browse pops to NowPlaying.
- Real Spotify art renders in covers; `CoverDrawable` is the fallback when art is null.
- Device picker dialog blurs the underlying screen.

---

## 5. Stream B — Settings redesign (artboards 09–10)

**Full rebuild.** Owner: Agent B. Branch: `redesign/settings`.

### 5.1 Replace Preference UI

`SettingsActivity` currently extends `AppCompatActivity` and hosts `SettingsFragment : PreferenceFragmentCompat` reading `xml/preferences.xml`. The HTML design is a custom rail+content layout — the system Preference UI cannot reproduce it. Plan:

- New layout `activity_settings.xml`: ConstraintLayout grid `320dp | 1fr`. Left = `RecyclerView` (`SettingsRailAdapter`). Right = `FragmentContainerView`. Top: eyebrow "DOCK · SETTINGS" + Manrope-200 56sp current-group title. Bottom: legend row + version footer "Dock v0.9.3 · Apache 2.0".
- `SettingsRailAdapter.kt` + `item_settings_rail.xml` — 6 items (Calendar / Music / Phone mirroring / VPN / Display / About). Active item shows a 2dp white left bar; row uses `FocusableContainer`.
- `SettingsActivity` is rewritten as a host: handles rail selection, swaps fragments in the content container with a 150ms fade.

### 5.2 Six setting fragments

Each fragment shows: eyebrow ("CALENDAR FEEDS"), Manrope-200 56sp title, description copy, then a vertical stack of `PrefRow` views. Per-fragment content maps directly to the existing `preferences.xml` entries:

| Fragment | Existing prefs (from `preferences.xml`) |
|---|---|
| `CalendarSettingsFragment` | iCal URL Personal/Work, refresh interval, two toggles, "Reset" danger row |
| `MusicSettingsFragment` | Notification access status, "Open Notification Access" action |
| `PhoneMirroringSettingsFragment` | Master receiver toggle, per-protocol AirPlay/Cast/Miracast toggles, PIN, boot toggle |
| `VpnSettingsFragment` | Toggle, status, "Receive config" action (launches `ConfigImportActivity`), Clear, killswitch, overlay pill |
| `DisplaySettingsFragment` | Ambient delay, inactivity timeout |
| `AboutSettingsFragment` | Version, license link, source-code link, support email |

### 5.3 Custom controls

- `PrefRow.kt` (custom View, FocusableContainer): label + value + optional action. Uses Manrope/JBMono tokens.
- `PrefToggle.kt`: rounded-rect switch styled to HTML spec.
- `PrefDangerRow.kt`: same shape as PrefRow but accent = `c_work` (#F28B82) for destructive actions.

### 5.4 VPN .conf drop screen (artboard 10)

- `activity_config_import.xml` rewrite: green status dot + "WAITING FOR CONFIG · LAN ENDPOINT LIVE" eyebrow + Manrope-200 64sp "Drop a .conf on Dock." + 420dp white-padded `QrPatternView` (left) | URL card with mono `http://192.168.4.118:8731/drop` + helper copy + amber `PulseHaloView` listening indicator + timeout countdown (right). Cancel pill bottom-left + reassurance line bottom-right.
- `QrPatternView.kt` — if zxing is already used to render the LAN URL into a real QR, keep that and style the white frame. If the existing code uses a placeholder, port the HTML's deterministic 25×25 hash pattern.
- `ConfigImportActivity.kt` — wire the existing LAN listener + countdown to the new views.

### 5.5 Files

| File | Action |
|---|---|
| `activity_settings.xml` | new |
| `xml/preferences.xml` | delete (replaced by fragments) |
| `SettingsActivity.kt` | rewrite as host |
| `SettingsFragment.kt` | delete |
| `CalendarSettingsFragment.kt` | new |
| `MusicSettingsFragment.kt` | new |
| `PhoneMirroringSettingsFragment.kt` | new |
| `VpnSettingsFragment.kt` | new |
| `DisplaySettingsFragment.kt` | new |
| `AboutSettingsFragment.kt` | new |
| `item_settings_rail.xml` | new |
| `SettingsRailAdapter.kt` | new |
| `PrefRow.kt` / `PrefToggle.kt` / `PrefDangerRow.kt` | new |
| `activity_config_import.xml` | rewrite |
| `ConfigImportActivity.kt` | restyle bindings |
| `QrPatternView.kt` | new (or restyle existing QR) |
| `fragment_settings_*.xml` × 6 | new |

### 5.6 Acceptance

- Artboards 09 + 10 reproduce. Settings reachable from MENU key (same as today) and the existing "Open settings" footer link.
- All existing preferences round-trip through the new fragments.
- VPN config import still functions end-to-end (LAN listener, .conf parse, kill-switch).

---

## 6. Stream C — Home + Calendar + Connect (artboards 01–08)

Owner: Agent C. Branch: `redesign/shell`. Lighter per-screen change but covers 8 artboards.

### 6.1 Home (artboards 01–03)

Layout rewrites in `screen_home.xml`:
- HeroClock: Manrope 200 / 260sp main + 80sp ":29" sub-numeral + "Tuesday, May 19" subtitle. ArtWash seed = current NowPlaying id, intensity 0.40.
- Widget grid (2 cols, gap 32dp):
  - `home_widget_today.xml` (accent `nav_accent_calendar` blue): NOW · PERSONAL pill, +1 ALSO NOW chip when multiple events overlap, ending-soon amber state ("ENDS IN 7M · 12:00").
  - `home_widget_music.xml` (accent `dock_accent_chartreuse`): cover 56 + `VuBarsView` + title/artist + Up Next teaser line.
- Ambient layout: HeroClock grows to 300sp, dims to 78%, nav fades to opacity 0.18. Single horizontal "Tickler" capsule below clock: NowPlaying block | divider | NOW event block. Backdrop-blur 20dp. Top-right caption "Idle · any key wakes".
- `HomeScreenBinder.kt` drives the ambient transition (translateY -40dp on clock, 600ms opacity tweens).

Files: `screen_home.xml` (rewrite), `home_widget_today.xml` (new), `home_widget_music.xml` (new), `home_ambient_tickler.xml` (new), `HomeScreenBinder.kt` (update bindings + ambient state machine).

### 6.2 Calendar (artboards 04–06)

- `screen_calendar.xml`: eyebrow blue + Manrope-200 68sp date + meta line ("5 events · 3 with conflicts · 4h 15m booked") + legend chips top-right + bottom JBMono hint row.
- Event row redesign. Two row types:
  - `item_calendar_event.xml` (single): grid `160 6 1fr auto` — time block / colored rail / title block / right meta.
  - `item_calendar_band.xml` (**new**, overlap band): same outer grid; inner = 2+ `BandCard` children. Left border tinted by tag (work coral / personal blue), `Attending`/`Declined`/`Starts 15:00` chips per card. Amber `PARTIAL OVERLAP` tag in the time column when partial. `BandCard` is a custom View.
  - "NOW · 2 events" white pill notched onto a band; "14m left" with `VuBarsView` on the now row.
- Empty state layout `screen_calendar_empty.xml` (**new**): dashed calendar tile + eyebrow "NOTHING ON THE BOOKS" + Manrope-200 84sp "A whole day to yourself." + supporting copy. ArtWash 0.12.
- `CalendarScreenBinder.kt` + `CalendarEventAdapter.kt` updated to:
  - Group overlapping events into bands at the model layer (new `CalendarRow` sealed type: `Single | Band`).
  - Detect "partial" overlap (events that share some but not all time inside the band).
  - Emit an empty-state placeholder when events list is empty for the day.

Files: `screen_calendar.xml` (rewrite), `item_calendar_event.xml` (restyle), `item_calendar_band.xml` (new), `BandCard.kt` (new), `screen_calendar_empty.xml` (new), `CalendarScreenBinder.kt` + `CalendarEventAdapter.kt` (update).

### 6.3 Connect (artboards 07–08)

- `screen_status.xml`: two-column ConstraintLayout `1fr | 1dp | 1fr` with vertical hairline divider. Each column = a `ServiceColumnView` (custom composite):
  - Eyebrow letterSpacing 0.42em (`AIRPLAY` / `VPN`)
  - 160dp `PulseHaloView` (idle = subtle white; active = service color with two rings)
  - 22dp center dot
  - Manrope-200 64sp state title ("Off" / "Disconnected" idle; "Streaming" / "Frankfurt" active)
  - Detail line under the title ("From Mrinal's iPhone · 1080p · 7m 22s")
  - Capsule action button ("Stop" / "Disconnect" / "Receive config")
- Top chrome: tracking title "CONNECT" letterSpacing 0.5em.
- `StatusScreenBinder.kt` updated: state machine for AirPlay (Off / Ready / Streaming) + VPN (Disconnected / Connecting / Connected) drives `ServiceColumnView.setState(...)`.
- `StreamingOverlay.kt`: keep functionality, restyle the corner pill to the new design language (Manrope eyebrow + JBMono session length).

Files: `screen_status.xml` (rewrite), `ServiceColumnView.kt` (new), `StatusScreenBinder.kt` (update), `StreamingOverlay.kt` (pill restyle only).

### 6.4 Acceptance

- Artboards 01–08 reproduce.
- Calendar empty-state shows when feeds return no events for the current day.
- Connect halos pulse when AirPlay/VPN are active.

---

## 7. Cross-cutting concerns

### 7.1 D-pad focus order

Each page declares a single "home focus" (e.g., NowPlaying = Play button, Calendar = first event row). D-pad DOWN past the page's last row focuses the bottom-nav capsule; UP from the capsule returns to the previous focus. `nextFocus*` attrs are set explicitly on each focusable.

### 7.2 Backwards compatibility

- The pager keeps the same `DashboardPage` enum and indices — no behavior change to existing intent-based deep links.
- Spotify auth/callback activities are untouched (only restyled if time permits — Phase 2).
- VPN protocols + receiver protocols are untouched at the service layer.

### 7.3 Risks / open questions

1. **Manrope license.** Manrope is SIL Open Font License — safe to bundle. Confirm `MANROPE_OFL.txt` is shipped in `res/raw/` for compliance.
2. **`RenderEffect` blur is API 31+.** Fire TV / Android TV devices may run older system images. Need a pre-blurred bitmap fallback for `ArtWashView` and the Device Picker backdrop.
3. **QR code in artboard 10.** HTML uses an algorithmic decorative pattern; the real app likely needs a scannable QR. Decision: render a real QR (zxing) inside the white frame; keep the decorative outer ring exactly per design.
4. **PlaylistTracksAdapter row count.** Spotify playlists can be 1000+ tracks. Use paging-3 or a windowed adapter to avoid loading everything.
5. **Settings rewrite & data migration.** No data migration needed — the existing SharedPreferences keys stay; only the UI changes.
6. **Connect+Streaming compositing.** The HTML's "Connect active" artboard shows the 2-column screen with active halos. The current app swaps to a full-screen overlay. We will: keep the overlay for the *video surface*, but ensure that when the overlay is dismissed via long-BACK, the underlying Connect screen is already in its active state.

### 7.4 Tests / verification

- Per stream: take a 1920×1080 screenshot of each artboard equivalent in the app and side-by-side it with the HTML render. Diff catalog kept under `docs/redesign-screenshots/` (gitignored beyond a manifest).
- Existing instrumentation: none significant for UI. No new tests required unless the user asks.

---

## 8. Suggested running order

1. Phase 0 lands → unblocks parallel work.
2. Spawn three coding agents in parallel, each on its own branch:
   - Agent A → `redesign/music`
   - Agent B → `redesign/settings`
   - Agent C → `redesign/shell`
3. Each agent opens a draft PR when its branch is screenshot-complete.
4. Merge order: Phase 0 → Stream C (cleanest reskins, lowest risk) → Stream B (Settings, isolated activity) → Stream A (Music, largest behavioral change) — minimizes rebase pain.
5. Phase 2: a single agent does final integration polish, fixes cross-screen focus order, takes final screenshot diff, opens the umbrella PR.

---

## 9. What lands when

| Phase | Branch | Deliverable | Approx LOC |
|---|---|---|---|
| 0 | `redesign/foundation` | tokens, fonts, shared views, BottomNav, ArtWash | ~1,200 |
| 1A | `redesign/music` | Music single-surface + drill-ins + dialog + 2 new screens | ~2,500 |
| 1B | `redesign/settings` | Settings rebuild + VPN .conf drop redesign | ~1,800 |
| 1C | `redesign/shell` | Home, Calendar (incl. bands+empty), Connect | ~2,000 |

Total ≈ 7,500 LOC across ~40 new/modified files. None of this is in the receiver/VPN service layer — only UI.
