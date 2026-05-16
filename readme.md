# TV Awake Clock (Fire TV)

Ambient clock app for Amazon Fire TV / Android TV: three-screen dashboard (Home, Calendar, Music), burn-in drift on Home, configurable sleep timer, personal Google Calendar (iCal), and hybrid Spotify (MediaSession + optional Web API queue).

## Screens (D-pad Left / Right)

| Screen | Content |
|--------|---------|
| **Home** | Large clock, today calendar widget, compact now playing widget |
| **Calendar** | Full list of today’s events (personal; work when URL added) |
| **Music** | Full now playing, transport controls, up next (5) + recently played (5) via Spotify API |

Press **Menu** for Settings.

## Setup

### 1. Build

```bash
source scripts/dev-env.sh
cp local.properties.example local.properties   # set sdk.dir and spotify.clientId
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`spotify.clientId` comes from the [Spotify Developer Dashboard](https://developer.spotify.com/dashboard). Redirect URI: `com.ambient.tvclock://spotify-callback`. Add your Spotify account under **User Management** (Development Mode).

### 2. Personal calendar (Google iCal)

1. Google Calendar → your calendar → **Integrate calendar** → **Secret address in iCal format**.
2. On the TV: **Settings → Personal calendar URL** — paste the link (or use the script below).

Optional — paste URL from your Mac:

```bash
./scripts/set-calendar-urls.sh 192.168.1.4:5555 "https://calendar.google.com/calendar/ical/.../basic.ics"
```

Work Outlook ICS can be added later under **Work calendar URL**.

### 3. Media sessions (Spotify on device)

Grant notification listener (Fire TV needs both):

```bash
adb shell settings put secure enabled_notification_listeners com.ambient.tvclock/com.ambient.tvclock.MediaNotificationListener
adb shell cmd notification allow_listener com.ambient.tvclock/com.ambient.tvclock.MediaNotificationListener
```

Or use `./scripts/install-firetv.sh`.

### 4. Spotify API queue (optional)

1. Add `spotify.clientId` to `local.properties` and rebuild.
2. **Settings → Connect Spotify** — sign in on the TV (WebView).
3. Play Spotify on the same account; Fire TV must be the active Connect device for queue data (Premium).
4. After an app update, use **Disconnect** then **Connect Spotify** once (scopes: queue, recently played, play/skip).
5. On **Music**, focus transport buttons or a track row and press **OK** — play, pause, next, previous, or play a listed track.
6. Focus **Playing on … · OK to switch** and press **OK** to move playback to another Spotify Connect device (phone, speaker, TV, etc.).

## Features

- **Stay awake** while the app is visible
- **Burn-in protection** on Home (clock drifts every 60s)
- **Sleep timer** — Settings → auto-exit after inactivity
- **Calendar** — polls iCal feeds every 15 minutes
- **Now playing** — MediaSession from Spotify TV app; queue + recently played via Web API when connected

## Project layout

- `MainActivity.kt` — dashboard pager, drift, watchdog
- `HomeScreenBinder.kt` / `CalendarScreenBinder.kt` / `MusicScreenBinder.kt`
- `CalendarPoller.kt` / `IcalParser.kt` — calendar feeds
- `SpotifyApiClient.kt` / `SpotifyAuthActivity.kt` — OAuth PKCE + queue
- `NowPlayingPoller.kt` — MediaSession (unchanged core)
