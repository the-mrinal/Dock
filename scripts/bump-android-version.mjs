#!/usr/bin/env node
// Bump versionName + versionCode in app/build.gradle.kts.
//
// Called by semantic-release's exec plugin during prepare:
//     node scripts/bump-android-version.mjs <X.Y.Z>
//
// versionCode is derived deterministically from semver:
//     MAJOR*10000 + MINOR*100 + PATCH
// So 2.0.0 -> 20000, 2.1.3 -> 20103, 12.0.0 -> 120000.
// This way the value is reproducible from the tag alone — no need to
// read or increment a previous versionCode.

import fs from "node:fs";

const version = process.argv[2];
if (!version) {
  console.error("Usage: bump-android-version.mjs <X.Y.Z>");
  process.exit(1);
}

const m = version.match(/^(\d+)\.(\d+)\.(\d+)$/);
if (!m) {
  console.error(`Invalid semver: ${version}`);
  process.exit(1);
}
const [, majorStr, minorStr, patchStr] = m;
const major = Number(majorStr);
const minor = Number(minorStr);
const patch = Number(patchStr);

if (minor > 99 || patch > 99) {
  console.error(
    `versionCode encoding overflows: minor/patch must each be < 100 (got ${minor}.${patch}).`,
  );
  process.exit(1);
}

const versionCode = major * 10000 + minor * 100 + patch;

const file = "app/build.gradle.kts";
const src = fs.readFileSync(file, "utf8");

let next = src;
let codeReplaced = false;
let nameReplaced = false;

next = next.replace(/versionCode = \d+/, (match) => {
  codeReplaced = true;
  return `versionCode = ${versionCode}`;
});

next = next.replace(/versionName = "[^"]+"/, (match) => {
  nameReplaced = true;
  return `versionName = "${version}"`;
});

if (!codeReplaced || !nameReplaced) {
  console.error(
    `Could not find versionCode/versionName markers in ${file} ` +
      `(code=${codeReplaced}, name=${nameReplaced}).`,
  );
  process.exit(1);
}

if (next === src) {
  console.log(`No change: already at versionName="${version}".`);
} else {
  fs.writeFileSync(file, next);
  console.log(`Bumped ${file}: versionName="${version}", versionCode=${versionCode}.`);
}
