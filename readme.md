# TV Awake Clock (Fire TV)

Ambient clock app for Amazon Fire TV / Android TV that keeps the display awake while visible, drifts the clock to reduce burn-in, and optionally auto-exits after a configurable period of remote inactivity.

## Features

- **Stay awake:** `keepScreenOn` on the layout plus `FLAG_KEEP_SCREEN_ON` while the app is in the foreground.
- **Burn-in protection:** Repositions the clock every 60 seconds.
- **Configurable sleep timer:** Auto-exit after no remote input for a duration you choose (default 3 hours), or disable the timer entirely.
- **Now playing (Spotify / media):** When Spotify or another media app is playing, shows album art, title, artist, and a small clock (Android Auto style). Returns to the large clock when playback stops.

## Now playing (Spotify)

The app reads **active media sessions** from the system (same mechanism used by Android Auto). Spotify on Fire TV must be playing in the background or foreground.

1. Open **Settings** in the app (Menu on the remote).
2. Under **Now playing**, keep **Show Spotify / media while playing** enabled.
3. Grant **Media access permission** once. On Fire TV this is usually done via ADB:

```bash
adb shell settings put secure enabled_notification_listeners com.ambient.tvclock/com.ambient.tvclock.MediaNotificationListener
adb shell cmd notification allow_listener com.ambient.tvclock/com.ambient.tvclock.MediaNotificationListener
```

On Fire TV, **both** commands are required — `settings put` alone does not connect the listener.

4. Start Spotify and play a track, then open **TV Awake Clock** (or leave it open). You should see album art, track, artist, and a compact clock. When playback pauses, the normal large clock returns.

## Configuring the inactivity timer

1. Launch the app on your Fire TV.
2. Press **Menu** (or **Settings**) on the remote to open **Settings**.
3. Choose **Auto-exit after inactivity**:

| Option | Behavior |
|--------|----------|
| 30 minutes | Exits after 30 min without remote input |
| 1 hour | Exits after 1 hour |
| 2 hours | Exits after 2 hours |
| 3 hours (default) | Exits after 3 hours |
| 6 hours | Exits after 6 hours |
| Never | No auto-exit; screen stays awake until you leave the app |

Any D-pad or remote key on the clock screen resets the timer. Changing the setting takes effect when you return to the clock (or immediately on resume).

Preferences are stored in `SharedPreferences` under the key `inactivity_timeout_ms`. Default values live in `app/src/main/res/values/arrays.xml` if you want to change the presets at build time.

## Dev environment (macOS CLI)

Install once with Homebrew:

```bash
brew install openjdk@17 android-platform-tools
brew install --cask android-commandlinetools
source scripts/dev-env.sh
yes | sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-33" "build-tools;33.0.2"
```

Create `local.properties` (or copy from a machine that already built):

```properties
sdk.dir=/opt/homebrew/share/android-commandlinetools
```

Before each terminal session:

```bash
source scripts/dev-env.sh
```

Optional: add the exports in `scripts/dev-env.sh` to your `~/.zshrc` so `java` and `adb` work in every shell.

## Build & install on Fire TV

```bash
cd /path/to/fire_tv
source scripts/dev-env.sh
./gradlew assembleDebug
adb connect <FIRE_TV_IP>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Enable **Developer options** and **ADB debugging** on the Fire TV first (Settings → My Fire TV → About → click device name 7 times).

## Project layout

- `MainActivity.kt` — clock, pixel drift, inactivity watchdog
- `SettingsActivity.kt` — preference UI for the sleep timer
- `TimeoutPreferences.kt` — reads the configured timeout in milliseconds (`0` = disabled)
