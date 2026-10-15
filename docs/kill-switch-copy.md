# Android system VPN blocking — candidate, 2026-10-15

Branch: `android/kill-switch-copy`, based on the existing profile safety and diagnostics candidate. No version or release changes.

The instruction screen and settings menu now explain that the button only opens Android settings. Howl does not enable the system block or display its active state here. Always-on VPN and Block connections without VPN are separate options; Always-on alone does not enable blocking.

The description covers the current Android user/profile, manual disconnection, apps excluded from VPN, device/admin differences and the fact that direct routes inside the Howl profile are unchanged. Absolute promises of preventing every IP/DNS leak have been removed. Updated languages: en/ru/de/es/it/fr. The menu generator is kept in sync. Kotlin changes are comments only; settings navigation and VPN service logic are unchanged.

Reference: [Android Developers: Always-on VPN, blocked connections and per-app VPN](https://developer.android.com/develop/connectivity/vpn#blocked_connections).

Validation completed locally: XML parsing, unique resource names, presence of all seven screen/menu keys in six languages, Android quote/apostrophe escaping and formatting, five explicit newline separators in the instruction, `git diff --check`. A source comparison confirms the Kotlin screen is unchanged outside comments.

No local Android SDK/JDK is available. A candidate-only CI trigger is prepared for this branch; its permissions remain contents:read. It runs the existing unit tests and debug assembly without release signing keys, artifact upload, Releases or deployment. Validation concurrency is scoped to the branch. The attempted push was rejected by automatic approval review because explicit permission for the external push was required. The branch was not sent and CI was not started. Android assembly, AAPT compilation and device layout remain unverified until that check is authorized and succeeds.

No system VPN settings or running VPN connections were changed. Before release, verify the instructions on an Android device, especially vendor-specific setting names and interaction with excluded apps/direct routes. This change explains existing behavior; it does not implement a new kill switch.
