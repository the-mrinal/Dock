<h1 align="center">Fire TV Dock</h1>

<p align="center">
  <em>The always-on ambient screen your desk was missing.</em>
</p>

<p align="center">
  <a href="https://github.com/the-mrinal/fire_tv/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/the-mrinal/fire_tv?display_name=tag&style=for-the-badge&color=FFB23A&labelColor=000"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/github/license/the-mrinal/fire_tv?style=for-the-badge&color=66D9FF&labelColor=000"></a>
  <a href="https://github.com/the-mrinal/fire_tv/stargazers"><img alt="GitHub stars" src="https://img.shields.io/github/stars/the-mrinal/fire_tv?style=for-the-badge&color=FF5DA2&labelColor=000"></a>
  <img alt="Platform" src="https://img.shields.io/badge/platform-Fire%20TV%20%7C%20Google%20TV-FFFFFF?style=for-the-badge&labelColor=000">
</p>

<p align="center">
  <strong><a href="https://firetv-dock.example.com">Website</a></strong>
  · <a href="https://github.com/the-mrinal/fire_tv/releases/latest">Download APK</a>
  · <a href="CONTRIBUTING.md">Contributing</a>
  · <a href="#how-it-works">How it works</a>
</p>

<p align="center">
  <img src="screenshots/home.png" alt="The ambient dashboard: clock, today's calendar, now playing">
</p>

> Plug a Fire TV into a spare monitor and turn that dusty second screen into the calmest, most useful surface in your setup.

You already have a laptop for the thing you're focused on. You already have a phone for the world reaching in. **You're missing the third screen** — the one that just *sits there* and tells you the time, what meeting is next, and what's playing, without ever asking for a click.

This is that screen.

---

## Contents

- [Features](#features)
- [Screenshots](#screenshots)
- [Quick start](#quick-start)
- [Configuration](#configuration)
- [How it works](#how-it-works)
- [Releasing](#releasing)
- [Contributing](#contributing)
- [License](#license)
- [Acknowledgements](#acknowledgements)

---

## Features

### A clock you actually want to look at

A huge, thin, beautifully kerned time display — with seconds that quietly fade away after 90 seconds of stillness so the resting face is just `7:00 PM`. When idle, the whole dashboard melts into a true-black screensaver and the clock drifts a few pixels every minute so your panel never burns in.

### Your calendar, at the exact moment you need it

The dashboard shows what's happening *now* and what's next. Walk past the screen and you instantly know: *"keep coding"* or *"stand up, meeting in five."* Tap into the **Calendar** page for the full timeline — personal + work feeds merged from your Google / Outlook iCal URLs, colour-tagged.

### A remote control for Spotify, on your wall

The **Music** page is a full-blown Spotify Connect remote: album art, transport buttons, your live queue, your recently played tracks — driven by the actual MediaSession on your TV plus the Spotify Web API. Skip from across the room with the Fire TV remote. Switch playback to your headphones, kitchen speaker, or living-room TV from one focus ring.

### Phone mirroring — AirPlay, Cast, Miracast

Flip the switch in Settings and the Fire TV starts advertising itself on your network as a wireless display target for **AirPlay** (iPhone, iPad, Mac), **Google Cast** (Chrome, Android), and **Miracast** (Windows, Android). The dashboard stays calm at rest; the moment a sender connects, it crossfades out and your phone or laptop takes the screen edge-to-edge. A small *"Casting from {device} via AirPlay"* pill rests in the corner, drifting a few pixels every minute (same burn-in protection as the clock). Each protocol is independently toggleable.

### WireGuard VPN, baked in

The dock ships with a native WireGuard tunnel. Drop a `.conf` from your laptop over the LAN, tap **Connect** on the new **Connect** page, and every byte the TV sends goes through the tunnel — Netflix, Plex, the launcher, everything. A discreet country pill confirms the tunnel is up. The system "Always-on / kill-switch" hook is one tap away so non-VPN traffic gets blocked at the kernel.

### A background that breathes

Every screen carries a softly blurred wash of the current track's album art — Gaussian-quality (pyramid downsample + 3-pass box blur, plus GPU `RenderEffect` on API 31+). When you stop touching the remote, the wash fades to black so the clock owns the room.

### Quietly good behaviour

- **Stay awake** while visible — no screen-off mid-meeting
- **Sleep timer** so the dock gracefully exits when you go to bed
- **Burn-in protection** for OLED panels — clock drifts in ambient mode
- **Fire TV native** — uses the remote, D-pad, and media keys exactly as you'd expect
- **No cloud, no telemetry, no account required** — Spotify is optional and PKCE-based

---

## Screenshots

<table>
  <tr>
    <td align="center" width="50%">
      <a href="screenshots/home.png"><img src="screenshots/home.png" alt="Home dashboard"></a><br>
      <sub><b>Home</b> — clock, today's events, now-playing, pager dots</sub>
    </td>
    <td align="center" width="50%">
      <a href="screenshots/connect.png"><img src="screenshots/connect.png" alt="Connect: AirPlay + VPN"></a><br>
      <sub><b>Connect</b> — AirPlay receiver and WireGuard VPN, side by side</sub>
    </td>
  </tr>
  <tr>
    <td align="center" width="50%">
      <a href="screenshots/calendar.png"><img src="screenshots/calendar.png" alt="Calendar day view"></a><br>
      <sub><b>Calendar</b> — the day on one screen, with personal/work tags</sub>
    </td>
    <td align="center" width="50%">
      <a href="screenshots/music.png"><img src="screenshots/music.png" alt="Music page with transport and queue"></a><br>
      <sub><b>Music</b> — transport, up next, recently played</sub>
    </td>
  </tr>
  <tr>
    <td align="center" colspan="2">
      <a href="screenshots/settings-vpn.png"><img src="screenshots/settings-vpn.png" alt="Settings — VPN section" width="80%"></a><br>
      <sub><b>Settings · VPN</b> — config import over LAN, kill-switch shortcut, indicator overlay</sub>
    </td>
  </tr>
</table>

---

## Quick start

### Option 1 — install the pre-built APK (5 minutes)

1. Grab the latest APK for your platform from the [Releases](https://github.com/the-mrinal/fire_tv/releases/latest) page (`app-firetv-release-*.apk` or `app-googletv-release-*.apk`).
2. Enable ADB on your Fire TV (Settings → My Fire TV → Developer Options → ADB debugging).
3. From your laptop:
   ```bash
   adb connect <fire-tv-ip>:5555
   adb install -r app-firetv-release-vX.Y.Z.apk
   ```
4. Launch **Dock** from the Apps row.

> APK builds are produced manually per release (see [Releasing](#releasing) for the why) — not every release has APKs attached. If yours doesn't, build from source below or run `gh workflow run package.yml -f tag=<latest>` on your fork.

### Option 2 — build from source

```bash
source scripts/dev-env.sh
cp local.properties.example local.properties   # set sdk.dir and spotify.clientId
./gradlew assembleFiretvDebug
adb install -r app/build/outputs/apk/firetv/debug/app-firetv-debug.apk
```

Fire TV is the default; for Google TV / Android TV use `assembleGoogletvDebug` — same code, separate `minSdk` + `applicationId` so both can coexist on one device.

`spotify.clientId` comes from the [Spotify Developer Dashboard](https://developer.spotify.com/dashboard). Redirect URI: `com.ambient.tvclock://spotify-callback`. Add your Spotify account under **User Management** (Development Mode).

---

## Configuration

### Calendar

Google Calendar → your calendar → **Integrate calendar** → **Secret address in iCal format**. On the TV: **Settings → Personal calendar URL** — paste the link. Or, from your Mac:

```bash
./scripts/set-calendar-urls.sh 192.168.1.4:5555 "https://calendar.google.com/calendar/ical/.../basic.ics"
```

Work Outlook ICS goes under **Work calendar URL**.

### Spotify

Grant the notification listener so the dock can read MediaSession metadata from the Spotify TV app:

```bash
LISTENER=com.ambient.tvclock.firetv/com.ambient.tvclock.MediaNotificationListener
adb shell settings put secure enabled_notification_listeners $LISTENER
adb shell cmd notification allow_listener $LISTENER
```

Or just run `./scripts/install-firetv.sh` — it does all three steps in one shot.

For **Premium** users who want queue + remote transport:

1. Add `spotify.clientId` to `local.properties` and rebuild
2. **Settings → Connect Spotify** — sign in on the TV (WebView)
3. Play Spotify on the same account; the Fire TV must be the active Connect device for queue data
4. Focus a transport button or track row and press **OK**
5. Focus **Playing on … · OK to switch** to move playback to another device

### Phone mirroring

**Settings → Phone mirroring (beta) → Enable mirroring receiver.** Set a device name if you want a custom one in the AirPlay picker. Each protocol (AirPlay / Cast / Miracast) is independently toggleable. The dock starts a small foreground service that advertises on the LAN; flip it off and everything goes back to sleep.

### WireGuard VPN

The dock spins up a short-lived LAN endpoint that accepts a `.conf` upload from your laptop's browser:

```
Settings → VPN → Receive config from laptop (LAN)
```

To activate, head to the **Connect** page and press **OK** on the VPN card. The kill-switch lives one tap away under **Settings → VPN → Always-on / kill-switch (system)**.

### Inputs

| Input | Where | What it does |
|---|---|---|
| D-pad Left / Right | Anywhere | Switch between Connect, Home, Calendar, Music |
| D-pad Up / Down | Calendar | Scroll the day |
| D-pad / OK | Music, Connect | Focus + activate transport, tracks, devices, AirPlay, VPN |
| Media keys | Music | Play/pause, skip, previous (works with the Fire TV remote media buttons) |
| Menu | Anywhere | Settings |

After your configured idle window (default 90s) the dashboard fades into ambient mode — clock plus a single horizontal music × calendar strip below it. Any keypress brings it back.

---

## How it works

```mermaid
flowchart TD
    MA[MainActivity<br/>pager + drift + ambient watchdog] --> DP[DashboardPagerAdapter]
    DP --> C[Connect<br/>StatusScreenBinder]
    DP --> H[Home<br/>HomeScreenBinder]
    DP --> CA[Calendar<br/>CalendarScreenBinder]
    DP --> M[Music<br/>MusicScreenBinder]
    DP --> BG[BlurredBackgroundBinder<br/>AlbumArtBlur]

    H --> CP[CalendarPoller<br/>IcalParser]
    CA --> CP

    H --> NP[NowPlayingPoller<br/>MediaSession]
    M --> NP
    M --> SC[SpotifyApiClient<br/>OAuth PKCE]

    C --> RS[ReceiverService<br/>AirPlay / Cast / Miracast]
    C --> WG[WireGuardTunnel<br/>VPN service]

    RS --> SO[StreamingOverlay<br/>SurfaceView crossfade]
```

- `MainActivity.kt` — dashboard pager, drift, ambient watchdog, input routing, streaming-overlay crossfade
- `Home/Calendar/Music/StatusScreenBinder.kt` — per-page view binders
- `BlurredBackgroundBinder.kt` + `AlbumArtBlur.kt` — full-bleed artwork wash (pyramid downsample + 3-pass box blur ≈ Gaussian, GPU `RenderEffect` pass on API 31+)
- `CalendarPoller.kt` / `IcalParser.kt` — iCal feed polling, every 15 min
- `SpotifyApiClient.kt` / `SpotifyAuthActivity.kt` — OAuth PKCE + queue / recently-played
- `NowPlayingPoller.kt` — MediaSession bridge
- `receiver/ReceiverService.kt` — foreground service hosting AirPlay / Google Cast / Miracast, plus the RTSP + MediaCodec pipeline for the AirPlay video stream
- `receiver/ui/StreamingOverlay.kt` — full-bleed SurfaceView the dashboard crossfades into when a sender connects
- `vpn/` — WireGuard tunnel manager + the LAN config-import endpoint

Built on plain Android views (no Compose, no React Native) so it stays buttery on older Fire TV hardware.

---

## Releasing

This project uses [semantic-release](https://semantic-release.gitbook.io/semantic-release) driven by [Conventional Commits](https://www.conventionalcommits.org/).

- Every PR title is a Conventional Commit (`feat:`, `fix:`, `perf:`, …)
- Releases are cut **locally** with the `/release` Claude Code skill or `npx semantic-release --no-ci` — no CI cost
- Building and attaching APKs is a separate `workflow_dispatch` step: `gh workflow run package.yml -f tag=vX.Y.Z`

See [`CONTRIBUTING.md`](CONTRIBUTING.md) for the full release flow and commit conventions.

---

## Contributing

Pull requests welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening one — there's a small but firm set of conventions around commit messages, scope, and "what won't be merged" (cloud deps, telemetry, interruptive features). For bugs and feature ideas, use the [issue templates](.github/ISSUE_TEMPLATE).

---

## License

[Apache License 2.0](LICENSE). The patent grant matters for the WireGuard, AirPlay, and codec code paths — please don't strip the LICENSE if you fork.

---

## Acknowledgements

- [WireGuard for Android](https://git.zx2c4.com/wireguard-android/) — `com.wireguard.android:tunnel`
- [dd-plist](https://github.com/3breadt/dd-plist) — Apple plist parsing for AirPlay session attributes
- [OkHttp](https://square.github.io/okhttp/) and [Bouncy Castle](https://www.bouncycastle.org/) — networking + crypto
- [Timber](https://github.com/JakeWharton/timber) — logging
- [Spotify Web API](https://developer.spotify.com/documentation/web-api) — queue / device control
- The screen-saver photo frames of the world — for being almost-but-not-quite what I wanted

---

<p align="center">
  <sub>If you've got a spare monitor and a Fire TV stick in a drawer somewhere, give this five minutes.</sub><br>
  <sub>It's the kind of small infrastructure you don't realise was missing from your desk until it's there.</sub>
</p>
