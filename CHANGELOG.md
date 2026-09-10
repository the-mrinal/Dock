## [1.3.1](https://github.com/the-mrinal/Dock/compare/v1.3.0...v1.3.1) (2026-09-10)

### Bug Fixes

* keep the notice counter on the card that is actually showing ([#32](https://github.com/the-mrinal/Dock/issues/32)) ([e80cea7](https://github.com/the-mrinal/Dock/commit/e80cea7bc2c81e26430313f0d3c0967b5d6ab457))

## [1.3.0](https://github.com/the-mrinal/Dock/compare/v1.2.0...v1.3.0) (2026-09-10)

### Features

* add a notice board section fed by a feed you host ([#31](https://github.com/the-mrinal/Dock/issues/31)) ([8d2241c](https://github.com/the-mrinal/Dock/commit/8d2241cab6a6a6af50658f1246e30c6fcbb6edef))
* **branding:** new launcher icon, TV banner, and ambient screen background ([d48bb04](https://github.com/the-mrinal/Dock/commit/d48bb04adcfb8924be31192fe9cf3afa73f274ca))
* **calendar:** fetch the personal deck from the Google Calendar API ([4a1d36e](https://github.com/the-mrinal/Dock/commit/4a1d36ec6c189fed6fa85f6e8d6b668f79030d2c))
* grainstorm wallpapers, and a screensaver that shows them ([2e5af4c](https://github.com/the-mrinal/Dock/commit/2e5af4c2ad2b89f754ff5b90f6342e221933a31f))
* **home:** redesign homepage as Split Decks with per-provider calendar decks ([27478c3](https://github.com/the-mrinal/Dock/commit/27478c3e749e734570838e173aa365cf0599a4ff))
* keep the dashboard readable, hand the photo the screen only in ambient ([2f5bc5b](https://github.com/the-mrinal/Dock/commit/2f5bc5bddfc13f581fec91f9b9dce30a7e2cb52f))
* system screensaver (DreamService) ([a82ec8c](https://github.com/the-mrinal/Dock/commit/a82ec8c243fa8bcfaca3df7395c90fb0fdefe8e3))

### Bug Fixes

* anchor the ambient now/next block to the bottom-left corner ([f76645e](https://github.com/the-mrinal/Dock/commit/f76645e5df53bcc31f11c6e3b92b52f6dba12f7a))
* **calendar:** ICS events from Outlook feeds shifted by the zone's full UTC offset ([0fede69](https://github.com/the-mrinal/Dock/commit/0fede695a903d0cbba3b8b91217622528c863810))
* paint cached wallpapers instead of silently dropping them ([f59918c](https://github.com/the-mrinal/Dock/commit/f59918c1548d2d3dbabb9841983b99ed2ba8c872))
* stop the next-up line landing on the wallpaper's quote ([3120271](https://github.com/the-mrinal/Dock/commit/3120271a6990e5627c756d1a5b0cbbf30a36e834))

## [1.2.0](https://github.com/the-mrinal/Dock/compare/v1.1.1...v1.2.0) (2026-08-15)

### Features

* **audio:** inaudible soundbar keep-alive tone when idle ([bf52ab1](https://github.com/the-mrinal/Dock/commit/bf52ab10177749028b82349a5b53b508ec2173c8))
* **dashboard:** Home Lab page with live dashboard and ad-block status card ([652fa1a](https://github.com/the-mrinal/Dock/commit/652fa1a8c2bfd0091e94d65555a9a6287a29ce19))
* **setup:** configure URL settings from a phone/laptop browser ([8aa6e28](https://github.com/the-mrinal/Dock/commit/8aa6e2895df076327b86432adc9098f2548bec55))

### Bug Fixes

* **adblock:** open dashboard in WebView + allow cleartext for LAN ([592cf27](https://github.com/the-mrinal/Dock/commit/592cf27acf27e46aae7d5cdfbdc02ab7ed64732f))
* **dashboard:** crash when returning from Settings kicked user to launcher ([623d20e](https://github.com/the-mrinal/Dock/commit/623d20eeeded7bcbf446b26c2c4446b6114e48e9))

## [1.1.1](https://github.com/the-mrinal/Dock/compare/v1.1.0...v1.1.1) (2026-06-10)

### Bug Fixes

* **i18n:** drop stale de/fr translations that fail release lint ([736b0c4](https://github.com/the-mrinal/Dock/commit/736b0c4996c8a8e4363e2189b06867a473627516))

## [1.1.0](https://github.com/the-mrinal/Dock/compare/v1.0.0...v1.1.0) (2026-06-10)

### Features

* **airplay:** direct video-URL playback + handshake fixes (audio WIP) ([2cefda8](https://github.com/the-mrinal/Dock/commit/2cefda8d329728c7a1d48f465ee46f709333f671)), closes [#14](https://github.com/the-mrinal/Dock/issues/14) [#14](https://github.com/the-mrinal/Dock/issues/14) [#14](https://github.com/the-mrinal/Dock/issues/14)
* **airplay:** event-channel decryption + YouTube mirror probes ([#17](https://github.com/the-mrinal/Dock/issues/17)) ([3505d8b](https://github.com/the-mrinal/Dock/commit/3505d8be4a175bd41d22cfc2a23a8e60951a9f1f))
* **airplay:** Now Playing screen for audio sessions ([2d6203d](https://github.com/the-mrinal/Dock/commit/2d6203d0cf0996979dbc20a564804e2884b37185))
* **background:** configurable backgrounds + Unsplash wallpaper mode ([21a43f7](https://github.com/the-mrinal/Dock/commit/21a43f7a10490c5bab2d480232306bb22ca5c154))
* **music:** browse Spotify playlists and play tracks on a chosen device ([22be453](https://github.com/the-mrinal/Dock/commit/22be4531c15bd9451ffa900576006e4881cbf2c7))
* **music:** loading bar, owned-playlist filter, Liked Songs, blur transition ([9bd4142](https://github.com/the-mrinal/Dock/commit/9bd4142a03605b8d9418696c994489f541610b2c))

### Bug Fixes

* **airplay:** software ALAC decoder for Apple Music / Spotify audio ([e7df337](https://github.com/the-mrinal/Dock/commit/e7df3370537074a8c639f223f58cf2b729cc6645)), closes [#14](https://github.com/the-mrinal/Dock/issues/14)
* **music:** drop Spotify placeholder artwork before binders see it ([fdcf6c8](https://github.com/the-mrinal/Dock/commit/fdcf6c86fe2a814f2caac6393641ce75cdabbadf))
* **music:** play Up Next and Recently Played tracks on click ([ec201e3](https://github.com/the-mrinal/Dock/commit/ec201e3def2580d252c53a3fd046c212a7e918d7))
* **music:** rework Spotify page D-pad navigation and browse UI ([c940767](https://github.com/the-mrinal/Dock/commit/c940767a1329b38b305ce12bc03e0517dd1b66b9))
