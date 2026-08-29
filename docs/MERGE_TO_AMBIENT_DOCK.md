# Merging phairplay → into Ambient Dock (`fire_tv`)

**Status:** Plan only — no code moved yet.
**Direction:** `fire_tv` (Ambient Dock) is the host. phairplay's receiver stack moves in as a subsystem.
**Goal:** One app on the TV. Calm dashboard at rest (clock + calendar + Spotify). When an iPhone or laptop starts an AirPlay/Cast/Miracast session, the dashboard fades and the receiver takes the screen. When the sender disconnects, it fades back.

---

## 1. What we're combining

| | phairplay (source repo) | Ambient Dock (`fire_tv`, this repo) |
|---|---|---|
| Package | `com.phairplay` | `com.ambient.tvclock` |
| LOC (Kotlin) | ~6,700 | ~5,100 |
| Native | C/C++ `playfair` lib via CMake/NDK | none |
| compileSdk / minSdk | 36 / 25 (firetv) · 29 (googletv) | 33 / 26 |
| Flavors | `firetv`, `googletv` | none |
| Entry | `MainActivity` + `PhairPlayService` (foreground) | `MainActivity` + `MediaNotificationListener` |
| UI | Nav panel + Home/Settings fragments + streaming overlay | `ViewPager2` over Home / Calendar / Music pages |
| Theme parent | `Theme.AppCompat.Leanback` | `Theme.AppCompat.NoActionBar` |
| Design language | Google TV Streamer (cards, dots, sidebar) | Ambient/editorial (huge clock, thin rules, blurred art) |

**Overlap:** none functional. The merge is additive.

---

## 2. Target architecture

```
com.ambient.tvclock
├── MainActivity                ← unchanged structure, gains a streaming overlay layer
├── DashboardPage (HOME|CALENDAR|MUSIC)
├── …existing dock files…
├── receiver/                   ← NEW — moved from phairplay
│   ├── ReceiverService         (was PhairPlayService)
│   ├── ReceiverController      (was ServiceController)
│   ├── ReceiverState
│   ├── BootReceiver
│   └── airplay/                (all 14 phairplay/airplay/* files)
│   └── cast/                   (CastReceiver)
│   └── miracast/               (MiracastReceiver)
├── receiver/ui/                ← NEW — replaces phairplay/ui
│   ├── StreamingOverlay        (was StreamingScreen — but no fullscreen activity, it's a View)
│   └── WaitingScreen → DELETED (the dashboard *is* the waiting screen now)
└── settings/                   ← extends existing SettingsActivity
    └── ReceiverPreferences     (toggles for AirPlay/Miracast/Cast, PIN, device name)
```

Activity model stays single-activity. Phairplay's `MainActivity` is **deleted** — the dock's `MainActivity` becomes the only entry point.

---

## 3. State machine — the handoff

Today, phairplay swaps between `WaitingScreen` and `StreamingScreen` inside its own activity. In the merged app the dock owns the screen, so:

```
                ┌────────────────────────────────┐
                │   ReceiverService (foreground) │
                │   AirPlay / Cast / Miracast    │
                └──────────────┬─────────────────┘
                               │ ReceiverState flow
                               ▼
       ┌──────────────────────────────────────────────┐
       │  MainActivity                                 │
       │                                              │
       │  ┌─────────────────────────────────────┐     │
       │  │  streaming_container (View, GONE)   │ ◄── overlay shown when state.connected
       │  └─────────────────────────────────────┘     │
       │  ┌─────────────────────────────────────┐     │
       │  │  ViewPager2: Home / Calendar / Music│ ◄── dock UI, fades out when overlay visible
       │  └─────────────────────────────────────┘     │
       │  ┌─────────────────────────────────────┐     │
       │  │  blurred album-art background       │ ◄── stays; just dims under overlay
       │  └─────────────────────────────────────┘     │
       └──────────────────────────────────────────────┘
```

Transitions:
- **idle → streaming**: 300ms crossfade. Dashboard fades to 0 alpha, streaming overlay fades in. ViewPager input disabled. Remote presses (back/home) end the session — same behavior as today's StreamingScreen.
- **streaming → idle**: reverse crossfade. Dashboard restores to whatever page user was on (default HOME).

This is a *small* amount of new UI code (~one binder file + animator), since both layers already exist.

---

## 4. Build / manifest reconciliation

### 4.1 `app/build.gradle.kts` (dock)

Changes:
- `compileSdk = 36` (was 33) — phairplay's networking needs newer APIs.
- `minSdk = 26` (unchanged for default; flavors override).
- Add `externalNativeBuild { cmake { path = "src/main/cpp/CMakeLists.txt" } }`.
- Add `ndkVersion = "<pin>"` (match phairplay's current pin).
- Add `flavorDimensions += "platform"` with `firetv` (minSdk 25, applicationId `com.ambient.tvclock.firetv`) and `googletv` (minSdk 29, applicationId `com.ambient.tvclock.googletv`).
- Add dependencies from phairplay that aren't already present (most are stdlib; check `libs.versions.toml` after move).
- Keep `spotify.clientId` BuildConfig field.

### 4.2 `AndroidManifest.xml` (dock)

Merge in from phairplay:
- All 10 permissions: `CHANGE_WIFI_MULTICAST_STATE`, `ACCESS_WIFI_STATE`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `CHANGE_WIFI_STATE`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `RECEIVE_BOOT_COMPLETED`, `POST_NOTIFICATIONS`. (`INTERNET` already there.)
- `uses-feature` declarations (`leanback required`, `touchscreen not required`, `wifi/location optional`).
- `<service android:name=".receiver.ReceiverService" foregroundServiceType="connectedDevice" />`
- `<receiver android:name=".receiver.BootReceiver">` with `BOOT_COMPLETED` filter.

Keep from dock:
- `MediaNotificationListener` service (Spotify metadata).
- `SpotifyCallbackActivity` + intent filter.
- `SettingsActivity`, `SpotifyAuthActivity`.

`MainActivity` keeps `LEANBACK_LAUNCHER` category but loses phairplay's `singleTop` (dock doesn't use it; revisit if there's a real bug).

### 4.3 Theme

Adopt phairplay's `Theme.AppCompat.Leanback` parent (better D-pad behavior) but keep the dock's calm palette (off-white on near-black, no Google blue). Net theme:

```xml
<style name="Theme.AmbientDock" parent="Theme.AppCompat.Leanback">
    <item name="android:windowBackground">@android:color/black</item>
    <item name="android:windowFullscreen">true</item>
    <!-- dock's existing colorPrimary/colorAccent -->
</style>
```

Drop both apps' `colors.xml` redundancies. Keep the dock's drawables; delete phairplay's UI-chrome drawables (`btn_control_selector`, `nav_item_selector`, `card_protocol_status`, `focus_ring_selector`, `status_dot`) — they belong to a UI we're throwing away. Keep `ic_airplay`, `ic_cast`, `ic_miracast` (need them for the streaming overlay).

### 4.4 Native (`cpp/`)

Copy `app/src/main/cpp/` verbatim from phairplay to fire_tv. Wire CMake. No code changes needed. JNI entry points stay named `Java_com_phairplay_…` only if we *don't* move the Kotlin package; since we ARE moving to `com.ambient.tvclock.receiver.airplay.*`, the JNI symbols must be renamed too. This is a global rename (~6 native files, the matching `external fun` declarations in `PlayfairDecrypt.kt`, and the CMake `target_name`).

---

## 5. File-by-file move map

### 5.1 Move as-is (rename package only)

All paths relative to `app/src/main/`.

| From (phairplay) | To (ambient dock) |
|---|---|
| `kotlin/com/phairplay/airplay/*.kt` (14 files) | `java/com/ambient/tvclock/receiver/airplay/*.kt` |
| `kotlin/com/phairplay/cast/CastReceiver.kt` | `java/com/ambient/tvclock/receiver/cast/CastReceiver.kt` |
| `kotlin/com/phairplay/miracast/MiracastReceiver.kt` | `java/com/ambient/tvclock/receiver/miracast/MiracastReceiver.kt` |
| `kotlin/com/phairplay/service/PhairPlayService.kt` | `java/com/ambient/tvclock/receiver/ReceiverService.kt` (renamed class) |
| `kotlin/com/phairplay/service/ServiceController.kt` | `java/com/ambient/tvclock/receiver/ReceiverController.kt` |
| `kotlin/com/phairplay/service/ServiceState.kt` | `java/com/ambient/tvclock/receiver/ReceiverState.kt` |
| `kotlin/com/phairplay/service/BootReceiver.kt` | `java/com/ambient/tvclock/receiver/BootReceiver.kt` |
| `kotlin/com/phairplay/settings/AppSettings.kt` | merged into existing `AmbientPreferences.kt` |
| `kotlin/com/phairplay/settings/SettingsRepository.kt` | merged into existing settings layer |
| `kotlin/com/phairplay/util/Logger.kt` | `java/com/ambient/tvclock/util/Logger.kt` (or fold into existing logging if any) |
| `kotlin/com/phairplay/util/NetworkUtils.kt` | `java/com/ambient/tvclock/util/NetworkUtils.kt` |
| `cpp/**` | `cpp/**` (verbatim, but rename JNI symbols) |
| `res/drawable/ic_{airplay,cast,miracast,notification,stop,restart}.xml` | `res/drawable/` (keep) |
| All `test/kotlin/com/phairplay/**` | `test/java/com/ambient/tvclock/receiver/**` (path + import rename) |
| All `androidTest/kotlin/com/phairplay/**` | `androidTest/java/com/ambient/tvclock/**` |
| `res/values-{de,fr}/strings.xml` | merge into dock's i18n if any (dock currently has no translations — keep these) |
| `firetv/res/values/strings.xml` | `src/firetv/res/values/strings.xml` (new flavor source set) |
| `googletv/res/values/strings.xml` | `src/googletv/res/values/strings.xml` |

### 5.2 Transform (don't move verbatim)

- `kotlin/com/phairplay/MainActivity.kt` → **deleted**. Its responsibilities (start service, observe state, swap waiting/streaming screens) are folded into the dock's `MainActivity.kt`.
- `kotlin/com/phairplay/PhairPlayApp.kt` → if it only initializes logging + global state, fold into a new `App.kt` for the dock (dock has no `Application` subclass today). Otherwise inline initialization in `MainActivity.onCreate`.
- `kotlin/com/phairplay/ui/StreamingScreen.kt` → **rewrite as `receiver/ui/StreamingOverlay.kt`**: same MediaCodec + Surface logic, but rendered into a `View` inside the dock's existing layout, not its own activity. ~80 LOC.
- `kotlin/com/phairplay/ui/WaitingScreen.kt` → **deleted**. Dashboard is the waiting screen.
- `kotlin/com/phairplay/ui/HomeFragment.kt` → **deleted**. Service-control UI moves to settings (no need for D-pad-able start/stop tiles since service runs on boot).
- `kotlin/com/phairplay/ui/SettingsFragment.kt` → **merged** into the dock's `SettingsActivity` as new PreferenceScreen sections (`<PreferenceCategory title="Receiver">…</>` with AirPlay/Cast/Miracast toggles, PIN, device name, start-on-boot).
- `res/layout/activity_main.xml` → **deleted**. Dock's `activity_main.xml` is updated to add a `streaming_container` `FrameLayout` on top of its `ViewPager2`.
- `res/layout/{fragment_home,fragment_settings,card_protocol_status,screen_waiting,settings_*}.xml` → **deleted**.
- `res/values/colors.xml` → cherry-pick `protocol_airplay`, `protocol_cast`, `protocol_miracast` (used for the optional badge in the overlay). Drop the rest; dock's palette wins.
- `res/values/themes.xml` → **deleted**.
- `res/values/strings.xml` → cherry-pick strings used by the streaming overlay (`waiting_*`, `service_state_*`); drop UI-chrome strings.

### 5.3 Net deletions in dock

None planned. All existing dock files stay.

---

## 6. Phased PR rollout

Each phase is independently buildable. CI green after every phase. **All phases land in this (`fire_tv`) repo — the phairplay repo is read-only from here on, used only as a source to copy from.**

### Phase 0 — preparation (no code moves)
- Land this plan doc in `fire_tv/docs/`.
- In `fire_tv`: bump `compileSdk` to 36, raise AGP/Kotlin/Gradle to match phairplay if needed. CI must pass.
- In `fire_tv`: add `flavorDimensions + productFlavors { firetv, googletv }`. Empty flavor source sets. CI must pass.
- Add NDK/CMake to `fire_tv` with an empty `cpp/CMakeLists.txt` placeholder. CI must pass.
- Rename `app_name` string → "Dock".

### Phase 1 — receiver subsystem in, but inert
- Copy all "move as-is" files (§5.1) into `fire_tv` under new package paths.
- Run the JNI symbol rename (§4.4).
- Merge manifest permissions, `<service>`, `<receiver>` (§4.2).
- Add `ReceiverController` instantiation in `MainActivity.onCreate`, but **do not start the service yet** — it stays disabled behind a settings flag that's off by default.
- All phairplay tests must pass in the new location.
- App still looks 100% like the dock. AirPlay code is dead-but-present.

### Phase 2 — settings integration
- Extend dock's `SettingsActivity` with the new "Receiver" preference category (§5.2).
- Wire toggles to `AmbientPreferences` (merged settings store).
- "Enable AirPlay receiver" flag now actually starts/stops the foreground service via `ReceiverController`.
- At this point: user can toggle AirPlay on, and an iPhone *can* mirror — but the screen still shows the dashboard underneath. There's no overlay yet.

### Phase 3 — streaming overlay + handoff
- Write `StreamingOverlay.kt` (port of `StreamingScreen.kt`, ~80 LOC).
- Add `streaming_container` to `activity_main.xml`.
- In `MainActivity`, observe `ReceiverState.connected`; crossfade ViewPager ↔ overlay.
- Disable ViewPager input + Spotify polling while streaming (saves CPU; avoids fighting for the surface).
- Ship.

### Phase 4 — polish (optional, post-launch)
- "Casting from <sender>" pill in the overlay corner (matches dock typography).
- Press-and-hold-back to dismiss without disconnecting the iPhone (returns to dashboard but keeps session alive).
- Burn-in protection: shift the protocol indicator by a few px every minute.

---

## 7. Decisions

1. **Repo strategy — DECIDED.** Migrate into the existing `fire_tv` repo. Use `git subtree add` to bring phairplay's history under a tagged prefix so blame on the receiver code survives. No new repo.

2. **App display name — DECIDED.** **Dock.** Only `strings.xml/app_name` and the launcher label change. `applicationId` stays `com.ambient.tvclock` (per-flavor suffixes `.firetv` / `.googletv`) to keep upgrade paths and package identity stable — only the *user-visible* name becomes "Dock." README and onboarding text follow the rename.

3. **Receiver service default — DECIDED.** Off. The service does not start on first launch and does not start on boot. Both require explicit user opt-in via Settings.

4. **Settings activity — DECIDED.** Keep the dock's separate `SettingsActivity`. Receiver toggles live there as a new `<PreferenceCategory>`, not inline on the dashboard.

5. **First-launch onboarding — DECIDED, with a constraint.** Show a brief one-time hint about mirroring. **Must stay subtle** — the dashboard (clock + calendar + music) is the headline; mirroring is a secondary capability. Concretely:
   - No full-screen takeover. A small footer pill on the Home page on first launch: *"Tip: enable phone mirroring in Settings"* with a dismiss affordance.
   - Auto-dismisses after first Settings visit, or after 7 days, or on explicit dismiss.
   - Never re-appears once dismissed.
   - No marketing copy, no logos, no "AirPlay/Cast/Miracast" listed — just "phone mirroring."

---

## 8. Out of scope for this merge

- Redesigning either app's existing visual language. The dock's design wins; phairplay's chrome is deleted.
- Adding new protocols beyond what phairplay already has (AirPlay, Cast, Miracast).
- Multi-window / picture-in-picture for the receiver (TVs don't do this well today).
- Rewriting MediaCodec / RTSP / FairPlay code. It just moves.

---

## 9. Effort estimate

| Phase | Net diff size | Risk |
|---|---|---|
| 0 — prep | small (~50 LOC build files) | low |
| 1 — move | large (~7,000 LOC moved, mostly mechanical) | medium — manifest + NDK reconciliation |
| 2 — settings wire-up | small (~150 LOC) | low |
| 3 — overlay | small-medium (~200 LOC) | medium — crossfade + Surface handoff |
| 4 — polish | small | low |

Realistic calendar time, single contributor: **5–8 working days end-to-end** with Phase 1 alone consuming about half of it.
