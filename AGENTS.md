# Repository Guidelines

## Project Structure & Module Organization

DiPlay is a Gradle-based Android project with four modules. `common/` contains the shared Kotlin/Jetpack Compose application logic and most JVM tests. `mobile/` builds the phone-style BYD head-unit APK, while `automotive/` builds the Android Automotive variant. `shared/` contains the native/JNI CarPlay stack and its assets. Production code lives under each module's `src/main/`; unit tests use `src/test/`, and debug-only utilities belong in `src/debug/`. Documentation is in `docs/`, public-site sources are in `site/`, and maintenance scripts are in `scripts/`.

## Build, Test, and Development Commands

Use JDK 25, Android SDK 37, NDK `28.2.13676358`, and the checked-in Gradle wrapper.

- `./gradlew :mobile:assembleDebug` builds the source-only debug APK.
- `./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest` runs native-facing and application JVM tests.
- `./gradlew :mobile:lintDebug` runs Android lint.
- `./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug` reproduces the main CI check.
- `python3 scripts/check_public_tree.py` rejects credentials and distribution artifacts from tracked files.
- `python3 scripts/build_site.py` regenerates localized website pages.

See `docs/BUILD.md` before creating release or standalone car-test APKs; those workflows require explicit local environment variables.

## Coding Style & Naming Conventions

Follow existing Kotlin style: four-space indentation, trailing commas in multiline calls, `PascalCase` for types and Compose functions, `camelCase` for methods/properties, and `UPPER_SNAKE_CASE` for constants. Keep packages under `com.shilapi.xcertplay`. Name resources with lowercase snake case, such as `ic_dp_navigation.xml`. Preserve SPDX headers where present. There is no separate formatter configuration; Android lint and nearby code are the source of truth.

## Testing Guidelines

Tests use JUnit 4 and Robolectric. Place tests beside their owning module under `src/test/java/...`, name classes `*Test`, and use behavior-focused method names. Add regression coverage for bug fixes and run the full CI command before opening a PR. For hardware behavior, follow `docs/TESTING.md` and report the head unit, Android/DiLink version, iPhone/iOS version, connection type, and exact reproduction steps.

## Commit & Pull Request Guidelines

Recent commits use short, imperative summaries such as `Send wheel speed for tunnels`; release commits use `Prepare DiPlay X.Y.Z release`. Keep each commit focused. PRs should explain the user-visible change, link relevant issues, list automated and parked-car validation, and include screenshots or recordings for UI/display changes. Never attach unreviewed diagnostics or commit credentials, signing keys, APKs, or AABs.
