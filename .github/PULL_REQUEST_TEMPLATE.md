<!--
PR title MUST follow Conventional Commits. Examples:
  feat(music): add device-switcher long-press
  fix(vpn): clear stale tunnel handle on cold-start
  feat!: drop API 25 support
Anything else will block the next release. See CONTRIBUTING.md.
-->

## What

<!-- One sentence: what does this PR change from the user's point of view? -->

## Why

<!-- Context, motivation, what problem this solves. Link issues with "Closes #123". -->

## Screenshots / video

<!--
For any UI change, attach a before + after.
Capture on-device with: adb exec-out screencap -p > shot.png
For motion, record with: adb shell screenrecord /sdcard/clip.mp4 (Ctrl-C to stop, then adb pull)
Drag the file into this textarea.
-->

| Before | After |
|---|---|
|  |  |

## Testing notes

<!-- How did you verify this? Which flavor (firetv / googletv)? Which device (Fire TV gen?, Google TV, Onn, Chromecast, emulator)? -->

- [ ] `./gradlew assembleFiretvDebug` passes
- [ ] `./gradlew lintFiretvDebug` clean (or new warnings are acknowledged below)
- [ ] Verified on real Android TV hardware (state which device + flavor in the notes above)
- [ ] Ambient mode and idle behaviour still calm (no new wake-ups, no new draw-on-idle)

## Release impact

<!-- Confirm one. The release tooling reads the PR title to figure this out, but call it out here too. -->

- [ ] `feat:` — minor bump
- [ ] `fix:` / `perf:` — patch bump
- [ ] `refactor:` / `docs:` / `test:` / `chore:` / `build:` — no release
- [ ] Breaking change (`!` in title, or `BREAKING CHANGE:` footer) — major bump
