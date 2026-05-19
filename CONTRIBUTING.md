# Contributing to Fire TV Dock

Thanks for thinking about contributing. This document covers the conventions you'll need to follow so your changes can land smoothly.

## Ground rules

- Be kind, be specific, be patient.
- One PR per logical change. If a refactor and a feature live in the same diff, split them.
- Keep the dashboard calm. This project optimises for the *resting* state of the screen — every new affordance should consider what it looks like when nothing is happening.

## Development setup

```bash
source scripts/dev-env.sh
cp local.properties.example local.properties   # set sdk.dir and spotify.clientId
./gradlew assembleFiretvDebug
adb install -r app/build/outputs/apk/firetv/debug/app-firetv-debug.apk
```

Targets: `firetv` (default) and `googletv`. Same code, different `applicationId` and `minSdk` — both can be installed side-by-side.

## Branching model

- `main` is the only long-lived branch and is always releasable.
- Feature work happens on short-lived branches named `feat/<thing>`, `fix/<thing>`, `chore/<thing>`, `docs/<thing>`, or `refactor/<thing>`.
- Open a PR against `main`. Squash-merge is the default.

## Commit messages — Conventional Commits

Every commit (and every PR title, since we squash) follows [Conventional Commits](https://www.conventionalcommits.org/). This is how the release tooling figures out the next version number, so non-conforming titles will be rejected.

### Types

| Type | When to use | Triggers |
|---|---|---|
| `feat:` | New user-facing capability | minor bump (`2.0.0` → `2.1.0`) |
| `fix:` | Bug fix | patch bump (`2.0.0` → `2.0.1`) |
| `perf:` | Performance improvement with no behaviour change | patch bump |
| `refactor:` | Internal cleanup, no behaviour change | no release |
| `docs:` | Documentation only | no release |
| `test:` | Test-only change | no release |
| `chore:` | Tooling, deps, CI, repo hygiene | no release |
| `build:` | Gradle, signing, packaging changes | no release |

A breaking change is signalled by `!` after the type (or a `BREAKING CHANGE:` footer) and triggers a major bump:

```
feat!: drop API 25 (Fire TV Stick gen 1) support
```

### Scope (optional)

Scope is a noun naming the area of the codebase:

```
feat(music): add device-switcher long-press to set default device
fix(calendar): handle ICS feeds with CRLF-only line endings
fix(vpn): clear stale tunnel handle on app cold-start
```

### Body

Use the body to explain **why**, not what. The diff already shows what.

### Examples

```
feat(connect): add Connect dashboard page with AirPlay + VPN cards

Brings receiver + tunnel status onto the main pager so users don't
have to dig through Settings to confirm a session is live. The page
auto-hides when both features are disabled.
```

```
fix(mirror): preserve source aspect ratio for portrait phones

AirPlay senders advertise their natural orientation; the receiver was
always letterboxing into a 16:9 box, which squished portrait video.
Now we read the sender's session attributes and size the SurfaceView
to match.
```

## Pull requests

- Open against `main` with a Conventional Commit-formatted title.
- Use the PR template — fill in *every* section, especially the screenshots and testing notes for any UI change.
- One green review is required. Force-pushes are fine on feature branches.
- We squash-merge, so the PR title becomes the commit on `main`.

## Releases

Releases are cut **after** a PR is merged to `main`, using the [`semantic-release`](https://semantic-release.gitbook.io/semantic-release) CLI run locally:

```bash
npx semantic-release --no-ci
```

(Once the release tooling lands in a follow-up PR, a `/release` shortcut will wrap this.)

Tags follow `vMAJOR.MINOR.PATCH`. The CLI bumps `versionName` and `versionCode` in `app/build.gradle.kts`, writes `CHANGELOG.md`, pushes the tag, and creates a GitHub Release with notes generated from the commits.

**Building and attaching APKs to a release is a separate, manually-triggered step** to avoid burning CI minutes on every merge:

```bash
gh workflow run package.yml -f tag=vX.Y.Z
```

Reasoning: releases (tags + notes) are cheap and should happen often. APK builds are expensive and should only happen when you actually want to ship binaries.

## Reporting bugs / asking for features

Use the issue templates in [`.github/ISSUE_TEMPLATE`](.github/ISSUE_TEMPLATE). For security-sensitive reports, please email the maintainer (address in the GitHub profile) instead of opening a public issue.

## What I'm unlikely to merge

- New cloud dependencies. The dock is intentionally local-only.
- New analytics, crash reporting, or telemetry.
- Features that demand the screen's attention (notifications, modals, popovers). The whole point is that the dock *doesn't* interrupt you.
- Compose or RN rewrites of existing screens. Plain views stay snappy on cheap Fire TV hardware; that's a feature.

If you're unsure whether something is in scope, open an issue first and ask.
