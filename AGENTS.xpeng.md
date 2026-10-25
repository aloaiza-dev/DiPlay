# AGENTS.xpeng.md

Instructions for coding agents working on the `xpeng` branch of DiPlay.
This branch is **local-only** and must never be pushed to, or PR'd against,
`shihabal3amri/DiPlay` (upstream). It exists only in this worktree
(`~/Development/Personal/DiPlay-xpeng`).

Base `AGENTS.md` (upstream rules: settings categorization, CI commands,
conventional commits) still applies. This file adds the XPENG-only layer.

## What this branch is

An XPENG-only variant of DiPlay, built as a Gradle product flavor
(`vendor` dimension: `upstream` / `xpeng`) on top of upstream, not a fork.
Rationale: stay rebaseable onto `upstream/main`, never duplicate the whole repo.

- `:mobile` and `:common` both declare `flavorDimensions += "vendor"`.
- `xpeng` flavor: `applicationIdSuffix = ".xpeng"`, `versionNameSuffix = "-xpeng"`,
  signed with the `xpeng` signingConfig (own keystore, see Signing below).
- `upstream` flavor: identical to original DiPlay, signed with the generic
  `release` signingConfig.
- XPENG-only source goes in `common/src/xpeng/...` / `mobile/src/xpeng/...`
  (source sets wired, currently empty — no XPENG-only code exists yet beyond
  flavor config).

## Hard rules

1. **Never push this branch, or any commit on it, to the upstream remote.**
   Upstream PRs come from fresh branches off `upstream/main` in a different
   worktree.
2. **Never print or log a keystore password, Keychain secret, or the
   `offline-mfi` private key material.** Agents MAY run `scripts/xpeng-release-build.sh`
   themselves (the owner decided this on 2026-10-25: always run the signed build when
   asked for an APK). The script reads the password from Keychain inside its own
   shell. Do not run `security find-generic-password` yourself outside that script,
   do not echo, redirect or store its value, and do not paste build output that could
   contain it.
3. **Never let the `offline-mfi` identity ship in a non-standalone build.**
   `rejectBundledCredentials` and `verifyStandaloneAuthentication` (in
   `mobile/build.gradle.kts`) enforce this at build time — do not weaken or
   bypass them.
4. **Do not gate upstream features behind `isBydHeadUnit()`/XPENG checks
   unless asked.** Most BYD-only sections (`BYD_ADB`, `BYD_NAVIGATION`,
   cluster map zoom) are already runtime-gated by existing capability checks
   (`CarHotspotSetup.isBydHeadUnit()`, `wheelMapZoomAvailable()`,
   `BydOutputSettings.fceClusterAvailable()`). Check before adding new gating —
   it's likely already handled, and adding redundant gates breaks existing
   tests (`ClusterSettingsConsentTest`, `ClusterSpeedometerSettingsTest`,
   `DiLink3ClusterRouteSettingsTest` all assume `CLUSTER_MAP` is visible
   without BYD detection in the Robolectric test environment).

## Gradle signing gotcha

A `buildTypes { release { signingConfig = ... } }` assignment always
**overrides** a per-flavor `signingConfig` assignment in AGP. Signing MUST be
set per-flavor (`productFlavors { xpeng { signingConfig = ... } }`), never in
`buildTypes.release`, or the xpeng flavor silently signs with the wrong key
(or fails looking for a keystore that doesn't exist).

## PKCS12 password gotcha

The xpeng keystore is PKCS12: store password and key password are the same
value. `keyPassword` defaults to `storePassword` when `XPENG_KEY_PASSWORD`
isn't set. Don't reintroduce a separately-defaulted empty key password —
that produces `KeytoolException: Given final block not properly padded`
(symptom of a wrong/empty key password, not a corrupt keystore).

## Building a signed car-test APK

When asked for a car-test APK, run `scripts/xpeng-release-build.sh` yourself and
report the result. Check the output APK: signer `O=DiPlay XPENG` and two
`offline-mfi` entries (`apksigner verify --print-certs`, `unzip -l | grep offline-mfi`).

The script expects:
- `XPENG_KEYSTORE_PATH` (defaults to `~/.android/diplay-xpeng.jks`)
- Keychain service `diplay-xpeng-keystore` holding the password
- `DIPLAY_AUTH_ASSETS_DIR` pointed at the main checkout's `.private`
  (this worktree's own `.private` is untracked/empty)
- `ANDROID_HOME` (defaults to `~/Library/Android/sdk`)

Output APK: `mobile/build/outputs/apk/xpeng/release/mobile-xpeng-release.apk`.

## Tests / CI for this branch

Both flavors must stay green. Run, per flavor:

```sh
./gradlew :common:testUpstreamDebugUnitTest :common:testXpengDebugUnitTest
./gradlew :mobile:assembleUpstreamDebug :mobile:assembleXpengDebug
```

`CarPlayHostClusterStartupTest` has known test-order flakiness (passes in
isolation, occasionally fails in the full suite) — rerun once before treating
a failure there as a real regression. Suspected cause, not yet fixed:
`XpengMapSettingsScreenshotsTest` calls `XpengMapWindow.saveEnabled(activity, true)`
with no `@After` teardown, possibly leaking SharedPreferences state across
tests sharing a JVM worker.

## Known gaps (not yet ported from `.private/xp-build.patch`)

- The `optimized` build type (R8-enabled car-test build for updating a
  debug-signed install without reinstalling).
- Manifest/string overrides from the old patch flow (app label, debug
  applicationIdSuffix tweaks) — current flavor only overrides release.

Do not add these speculatively; port them only when the human asks.
