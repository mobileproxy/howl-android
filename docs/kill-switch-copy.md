# Android system VPN blocking — candidate, 2026-10-15

Branch: `android/kill-switch-copy`, based on the existing profile safety and diagnostics candidate. No version or release changes.

The instruction screen and settings menu now explain that the button only opens Android settings. Howl does not enable the system block or display its active state here. Always-on VPN and Block connections without VPN are separate options; Always-on alone does not enable blocking.

The description covers the current Android user/profile, manual disconnection, apps excluded from VPN, device/admin differences and the fact that direct routes inside the Howl profile are unchanged. Absolute promises of preventing every IP/DNS leak have been removed. Updated languages: en/ru/de/es/it/fr. The menu generator is kept in sync. Kotlin changes are comments only; settings navigation and VPN service logic are unchanged.

Reference: [Android Developers: Always-on VPN, blocked connections and per-app VPN](https://developer.android.com/develop/connectivity/vpn#blocked_connections).

Validation completed locally: XML parsing, unique resource names, presence of all seven screen/menu keys in six languages, Android quote/apostrophe escaping and formatting, five explicit newline separators in the instruction, `git diff --check`. A source comparison confirms the Kotlin screen is unchanged outside comments.

No local Android SDK/JDK is available. Candidate-only CI is enabled for this branch; its permissions remain contents:read. It runs the existing unit tests and debug assembly without release signing keys, artifact upload, Releases or deployment. Validation concurrency is scoped to the branch. After the initial automatic approval rejection, the owner explicitly authorized this branch push and candidate CI without publication. The branch was pushed and [validation run 38763249598](https://github.com/mobileproxy/howl-android/actions/runs/38763249598) checked source commit `b302ff94f3776b57ce72cee3478985457ed9949e` successfully. `testOtherDebugUnitTest` and `assembleOtherDebug` passed: **51 tests, 0 failures, 0 errors**, 56 Gradle tasks executed. The validation job took 6 minutes 28 seconds. Android resource compilation is now verified; device layout and actual system-setting behavior remain unverified.

No system VPN settings or running VPN connections were changed. Before release, verify the instructions on an Android device, especially vendor-specific setting names and interaction with excluded apps/direct routes. This change explains existing behavior; it does not implement a new kill switch.

The successful run also reported an exception on the KSP AWT event thread and deprecation warnings for CI actions. Gradle still ended with `BUILD SUCCESSFUL`, and all test reports passed. This change does not update KSP or CI action versions. A follow-up may investigate the toolchain warning separately.

Post-validation API checks confirmed unchanged `dev` (`d5464888e2ecbcf2748f15891718051d1929dedd`), latest release `ci-37228493605-1` (ID 413714392) and zero Actions artifacts. No release was published and no budget was changed. This documentation-only follow-up is excluded by the candidate workflow path filter and does not require another application build.
