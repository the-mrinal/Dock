---
description: Cut a new semantic-release for Dock from main (local CLI, no CI).
---

You are running a release for the Dock Android repo. The convention is documented in `CONTRIBUTING.md`: Conventional Commits drive `semantic-release`, releases happen locally (not in CI), and APK builds are a separate manually-triggered workflow.

Walk through the following steps. **Stop and tell the user** if any check fails — do not paper over a dirty state.

### 1. Verify the workspace

- Confirm `git status` is clean. If not, abort.
- Confirm the current branch is `main`. If not, abort.
- Confirm `main` is up-to-date with `origin/main` (`git fetch && git status -sb`). If behind, run `git pull --ff-only` and re-check.
- Confirm the repo root contains `package.json` and `.releaserc.json`. If not, abort — release tooling isn't set up.

### 2. Verify the baseline tag

Run `git tag --list 'v*'`. If **no** `v*` tag exists yet, this is the first release. Before running `semantic-release`, the baseline tag must exist so semantic-release computes the next version from the right ancestor.

- Default baseline: the version currently in `app/build.gradle.kts` (read `versionName`).
- Offer to run, with the user's confirmation:
  ```bash
  gh release create v<baseline> --target main --generate-notes \
    --title "v<baseline>" --notes "Baseline release for semantic-release."
  ```
- After creating the baseline release, **stop**. Tell the user the first auto-release will happen on the next run of `/release` once they have at least one new conventional commit on main.

### 3. Install dependencies if needed

- If `node_modules/` is missing in the repo root, run `npm install` (use `npm ci` instead if `package-lock.json` exists).

### 4. Dry-run preview

Run:
```bash
npx semantic-release --no-ci --dry-run
```

Report back to the user:
- The next version that will be released, or "No release necessary" if no qualifying commits exist
- The release type (major / minor / patch)
- The first ~20 lines of the generated release notes

### 5. Confirm and release

Ask the user to confirm. **Do not skip this step** — releases push tags and commits to `origin/main`.

If confirmed, run:
```bash
npx semantic-release --no-ci
```

### 6. Post-release

Report:
- The new tag and GitHub Release URL (printed by semantic-release)
- A reminder: "To build and attach APKs to this release, run `gh workflow run package.yml -f tag=<new-tag>` (this consumes CI minutes, so it's manual)."
- The updated `versionName` and `versionCode` in `app/build.gradle.kts`

### Notes

- This skill assumes `GITHUB_TOKEN` is available to `gh` (it is — the user is authed). semantic-release reads it via the `gh` auth helper or the env var.
- Never `--amend` or `--force-push` to fix a botched release; revert the tag with `git push --delete origin <tag>` and re-run.
- If semantic-release fails on `@semantic-release/git` because branch protection blocks the chore-bump commit, surface the error and ask the user whether to temporarily disable protection or to allow themselves as a bypass actor. Do not try to bypass without consent.
