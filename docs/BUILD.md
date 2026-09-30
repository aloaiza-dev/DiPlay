# Building DiPlay

Requirements: JDK 25, Android SDK 37, NDK 28.2.13676358 and the included Gradle wrapper.

## Source and CI builds

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug
```

The resulting source-only APK contains no accessory identity. Standalone CarPlay requires runtime authentication provisioning. Tests generate synthetic identities at runtime; no test private-key files are tracked.

## Local release packaging

Provide an external asset directory using `DIPLAY_AUTH_ASSETS_DIR`. The directory must contain exactly the intended runtime files under `offline-mfi/identity.pk8` and `offline-mfi/certificate.p7b`. Neither file belongs in Git. The build permits those two files only when this explicit input is set and rejects unexpected credential containers elsewhere in APK assets.

Set `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD` locally for your Android signing key. Never commit these values or the keystore. Different signing keys cannot update an existing project-signed installation.

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintRelease :mobile:assembleRelease
```

Output: `mobile/build/outputs/apk/release/mobile-release.apk`. The release APK deliberately contains the experimental identity described in the notices; it is extractable by recipients. The separate Android signing key is not included. The retired build-beta.py helper is not used; this Gradle workflow uses explicit environment inputs.

The public release source archive corresponds to the tagged source and excludes runtime identities, signing keys, local configuration and build output.

## Standalone car-test APK

Use `:mobile:assembleStandaloneDebug` for a test APK that must connect to an iPhone:

```sh
DIPLAY_AUTH_ASSETS_DIR=/absolute/path/to/runtime-assets ./gradlew :mobile:assembleStandaloneDebug
```

This task refuses missing or empty runtime inputs. `assembleDebug` remains an identity-free
source/CI build when the explicit asset input is absent; do not install that output as a
standalone car-test package. Before delivery, verify both `assets/offline-mfi/identity.pk8`
and `assets/offline-mfi/certificate.p7b` in the APK against the selected local inputs.
Update the existing test app without uninstalling it to preserve its settings.

### Reusing the preview APK identity

The preview APK intentionally contains its experimental iAP2 accessory identity. For local testing,
extract only the two expected assets into an ignored directory:

```sh
mkdir -p .private/diplay-auth
unzip -q DiPlay-0.2.8.apk \
  assets/offline-mfi/identity.pk8 \
  assets/offline-mfi/certificate.p7b \
  -d .private/diplay-auth
chmod 600 .private/diplay-auth/assets/offline-mfi/*

DIPLAY_AUTH_ASSETS_DIR="$PWD/.private/diplay-auth/assets" \
  ANDROID_HOME=/absolute/path/to/Android/sdk \
  ./gradlew :mobile:assembleStandaloneDebug
```

Confirm that the resulting APK contains byte-identical copies of both assets before testing. The
debug output uses package `com.shihab.diplay.hudtest` and the local Android debug signature, so it
can coexist with the release app but cannot update it. The release-signing private key is never
contained in an APK; rebuilding an update for `com.shihab.diplay` requires the original Android
keystore and the four `ANDROID_KEYSTORE_*` inputs described above.

## Keeping the fork current

Keep `main` aligned with the original project and maintain the XPENG/Now Playing changes on
`xpeng-now-playing`. Configure the original repository once and disable accidental pushes to it:

```sh
git remote add upstream https://github.com/shihabal3amri/DiPlay.git
git remote set-url --push upstream DISABLED
git fetch upstream --prune
```

Before updating, commit or stash tracked work. Fast-forward the fork's `main`, then replay the
feature commits on top:

```sh
git switch main
git fetch upstream --prune
git merge --ff-only upstream/main
git push origin main

git switch xpeng-now-playing
git rebase main
```

Resolve any rebase conflicts, keeping both upstream behavior and the iAP2 artwork path. Run the
full source check before publishing:

```sh
ANDROID_HOME=/absolute/path/to/Android/sdk \
  ./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest \
  :mobile:lintDebug :mobile:assembleDebug
python3 scripts/check_public_tree.py
git push --force-with-lease origin xpeng-now-playing
```

Use `--force-with-lease`, never an unconditional force push: rebasing changes commit IDs, while
the lease prevents overwriting unexpected remote work. Rebuild `assembleStandaloneDebug` with the
external authentication directory only after the source checks pass.
