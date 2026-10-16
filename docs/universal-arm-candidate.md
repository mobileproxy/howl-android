# Universal ARM APK candidate

## Integration into dev (2026-10-16)
The candidate history below is retained for provenance. Attempt 2 of CI 38809812159 subsequently built version 819 from b9584b1; that universal APK was published on gethowl.app. Device acceptance is still unconfirmed.

Merged current dev (0b72984), retaining its newer-release protection and 13 publication tests. Native ARM filters, AAR/APK validation and universal artifact selection are preserved. Normal build CI now chooses max(VERSION_CODE + GITHUB_RUN_NUMBER, public versionCode + 1), avoiding a collision with separately published 819; malformed/unavailable public metadata blocks the build. The final APK verifier still checks against the public version after compilation. Added four version-number tests; five native checks and 13 publisher tests pass. Resource validation passes in six languages. Kotlin/application sources match the previously built universal candidate; no new APK was built for this integration.

Integration commit uses [skip ci] to suppress automatic build/publication. No manual build or publisher dispatch is part of this merge. Next explicitly initiated release must re-run the full build and signature checks.

Branch: android/universal-arm. Base: 4e729c1 (prepared Android 1.0.1 release candidate, including integrated profile safety/diagnostics/Kill Switch copy). This is not a minimal hotfix on the old public 817 source.

## Change
- Generate a single universal APK with ndk.abiFilters for ARM32 and ARM64; disable ABI splits. Split inclusion alone does not filter universal native libraries (confirmed on the first candidate).
- Validate AAR and APK native library presence, parity, ELF class and machine; reject arm64-only or mislabeled libraries.
- Select universal APK in build artifacts, release compatibility verifier and future publisher. Upload only after successful verification.
- Candidate workflow is named Validate universal ARM APK, not Build Howl APK; it has contents:read and no Release/deployment step. Candidate artifacts are build outputs, not a public app release.
- Independent workflow numbering uses HOWL_VERSION_CODE override based on the current public code + 1; the final verifier rejects stale/colliding codes.

## Core
Existing libbox v1.14.0-lx.29 AAR contains jni/armeabi-v7a/libbox.so and jni/arm64-v8a/libbox.so with matching ELF machines. SHA256 1594af4acf1e652cb33dca3f116e988fcb5610bdab56aebdc3baca68dac22bda matches upstream SHA256SUMS. Release build source targets android (all supported architectures), debug defaults to android/arm64; main API is24. No core migration needed.

## Validation and limits
Five native compatibility tests and eight existing publication safety tests passed locally. Signed candidate CI result and artifact details are recorded separately after completion.
Device acceptance remains required: fresh installation on ARM32 Android10, opening the app and starting the native VPN core; ARM64 upgrade preserving profiles and settings. No real device, user configuration, service or server was changed. No client message sent.
Redmi model/Android version alone does not establish supported ABIs. Confirm device ABI and installer error if the candidate still fails; do not ask users to erase profiles.
First full CI 38808350314 succeeded with 58 unit tests and verified signing, but included x86/x86_64 (119,840,401 bytes). It is superseded by the ARM-only candidate; do not distribute the first artifact. Final source commit b9584b1, CI 38809812159.

## Final result
CI 38809812159 success on b9584b1. 58 application tests, zero failures/skips; five native validation tests and eight publication safety tests passed. Signed Howl 1.0.1 (818), package how.vpn.howl, minSdk24 / targetSdk35, debuggable=false. Final APK has exactly armeabi-v7a and arm64-v8a, four matching native libraries each. Size61,001,473 bytes. SHA256 e731ff22ccb365b6268bc7c5d96e4fb119e51c285b86e43cdf5dcff2b3f7de7a. APK signature verified by apksigner and signer matches public817; versionCode818 exceeds817. Local downloaded hash and AXML independently checked.

No production deployment or GitHub Release from this branch. APK saved locally under tools/support/android-universal/final/other/release in the parent workspace. Candidate CI artifacts saved for retrieval; not linked to auto-update. Device acceptance NOT RUN. Recheck public versionCode before any eventual release: parallel release work may use the same number818. Do not distribute different builds with the same code; rebuild with a fresh code when publishing if needed.
