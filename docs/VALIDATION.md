# DiPlay 0.2.9 — 2026-10-02

- 420 unit tests passed: 109 common, 307 shared, and 4 Home sample tests, with zero failures, errors or skipped tests.
- Mobile release lint and the production-signed release build passed; lint warnings remain.
- Package `com.shihab.diplay`, version `0.2.9`, version code `28`. Signing certificate matches the published 0.2.8 APK.
- Ten floating-map gesture tests include stable initial contact, both size limits, pointer changes, persistence, and enlarging a reopened minimum-sized card.
- Public-tree and source-archive scans exclude runtime identities, signing keys, and build output. Runtime authentication assets in the APK match the explicitly selected local inputs; the Android signing key is excluded.
- The test variant was installed on DiLink 5.1 and user feedback drove the floating-map fixes. The production APK has not had a separate on-car test. Broader vehicle checks remain documented in [release notes](RELEASE-NOTES-0.2.9.md).

# Restored 0.1.0 release — 2026-09-25

- Built from the current public source with explicitly selected external authentication assets and the existing local Android signing key.
- 172 JVM/Robolectric tests passed; zero failures/errors. Release lint and signed release build passed.
- Public-tree credential scan passed. Source tests generate identities at runtime; no credential containers or private-key blocks are tracked.
- Verified that the APK contains the intended runtime accessory identity and no Android signing keystore.
- Signing certificate SHA-256: `87b38b12788dcb202a961215f2572e30ec2dc9d8ef4bc070d05f77e49291a363` (unchanged).
- Package `com.shihab.diplay`, version `0.1.0`, version code `10`; restoration changes packaging and public documentation, not app behavior.
- Existing USB-only TLS trust-manager warnings and unused-resource warning remain; this is not a completed security audit.
- No fresh physical-car validation was performed for the restored artifact. Previous emulator and private-build testing do not establish universal compatibility.

# XPENG Now Playing test build — 2026-09-30

- Extracted `identity.pk8` and `certificate.p7b` from the 0.2.8 preview APK into the ignored local `.private/` directory; no credential files were added to Git.
- Verified that the PKCS#8 P-256 private key matches the single certificate in the PKCS#7 bundle.
- Built `:mobile:assembleStandaloneDebug` with `DIPLAY_AUTH_ASSETS_DIR` and verified that both packaged assets are byte-identical to the selected inputs.
- Shared/common unit tests, Android lint and debug assembly passed with the Now Playing metadata and iAP2 artwork implementation.
- The test APK uses package `com.shihab.diplay.hudtest` and an Android debug signature. It can coexist with, but cannot update, the release-signed APK.
- Physical testing on an XPENG head unit confirmed that the rebuilt APK connects to CarPlay and displays Now Playing metadata and album art. The exact vehicle, head-unit, iPhone/iOS and connection details have not yet been recorded, so other configurations remain unverified.

## Credential and signing fingerprints

These are public certificate fingerprints. The iAP2 identity and Android package signer are
independent: the former authenticates the accessory to the iPhone, while the latter controls
Android installation and updates.

| Role | Certificate identity | SHA-256 fingerprint |
| --- | --- | --- |
| Embedded iAP2 accessory | `IPA_24ACBB48646F8077F7463523085B60D2` | `58d50d668e5fb0a249dfcec0997019b8d28db3afcc3b6dad31ae1cf41e895a16` |
| Original 0.2.8 APK signer | `CN=DiPlay, OU=Private Beta, O=DiPlay` | `87b38b12788dcb202a961215f2572e30ec2dc9d8ef4bc070d05f77e49291a363` |
| Local XPENG test APK signer | `C=US, O=Android, CN=Android Debug` | `3bea7607be58fe271440ec3f73bad18e5f436f31ab139af7d9466df0a0b3625d` |

The accessory private key is intentionally not printed or tracked. Its match to the embedded
certificate was checked locally by comparing their derived EC public keys.
