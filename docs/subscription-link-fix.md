# Android 1.0.2: ordinary subscription links

The profile safety validator attempted to parse every raw Howl response as JSON
before SubscriptionConverter ran. An ordinary subscription URL returns a Base64
URI list, so changing a profile to that URL failed before conversion.

The shared HTTP client now requests format=singbox for the exact Howl subscription
endpoints, including redirects. It preserves the stored source URL, encoded token,
other query parameters and fragment. Third-party URLs are unchanged. This also
retains protocols and settings available only in the complete native profile.

ProfileValidation checks raw Howl JSON before conversion to retain duplicate-key,
graph and confirmed-expiry guards. URI/Base64 payloads reach the existing converter
first. The normalized result still undergoes structural and native core checks.
Imports, refresh and source replacement share this path. Atomic storage and
source-specific reserves are unchanged.

Regression coverage: plain/encoded/duplicate-format URLs, exact host/path scope,
Base64 and URI validation order, JSON preservation, duplicate/malformed JSON,
confirmed expiry, rejected normalized output/native check, size limit, third-party
selectors and preservation of the working reserve/dates after a rejected update.
Pipeline tests inject normalization and native checking; they do not establish
device VPN connectivity. Release CI runs all unit tests and checks the signed
universal ARM32/ARM64 APK. Physical-device acceptance remains separate.

## Published verification (2026-10-16)

- Released as **1.0.2 (820)** from `cf75064119a5de3729f0da68aa757ffbe86dd69d`.
- [CI 38868269127](https://github.com/mobileproxy/howl-android/actions/runs/38868269127): success;
  **75 tests, zero failures and zero skipped** (17 new regression tests).
- Signed/minified universal APK: Android 7+, package `how.vpn.howl`, both ARM ABIs,
  same signing certificate as previous releases. ZIP CRC and manifest checked.
- APK size: **60981829 bytes**, SHA-256:
  `37816061f7ae6971819625e489b607c4853144529af393244b0a31260395a6e0`.
- [GitHub release](https://github.com/mobileproxy/howl-android/releases/tag/v1.0.2)
  and [versioned APK](https://gethowl.app/downloads/howl/Howl-1.0.2-820.apk) published.
  GitHub download plus both public APK URLs were fully downloaded and hash-checked;
  the public update manifest matched staging.
- Physical Android installation, import and VPN connection: **NOT_RUN**.
