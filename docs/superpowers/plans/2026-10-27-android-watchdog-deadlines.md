# Android watchdog deadlines Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans in this session; one fresh whole-branch review at the end. Steps use checkbox syntax.

**Goal:** Bound watchdog network checks and recover from a persistently unready selected OpenConnect without reloading the core or closing application flows.

**Architecture:** Keep the existing watchdog and soft selection. Add a small bounded owned-resource probe runner and testable recovery policy. Serialize decisions, coalesce requests, and discard observations from superseded sessions. The selected endpoint is confirmed using a private LOCAL command client; only a fresh successful reserve probe permits selection.

**Tech Stack:** Existing Kotlin 2.3.10 / coroutines 1.10.2 / JUnit 4, Android API 24+ other flavor, pinned libbox. No new dependencies.

**Spec:** Owner-authorized Android slice of `tools/support/client-health-20261027/PLAN-REVIEW.md` in the parent workspace. Its H01–H19/H21–H24 invariants apply where touched; this is not implementation of the whole parity roadmap.

## Global Constraints

- Base: released Android 1.0.6 `e40d62c7553ca8b087eeaa7fb990c2210812bae6`; remote dev was read and matches. Work only on `android/watchdog-deadlines-20261027` in the existing linked worktree.
- No publication, push, CI, installation, version bump, signing/key/lineage changes or budget change. Actions $0/block retained; no fresh billing claim.
- Preserve profiles, backups, subscription comparison, DNS/routing, Kill Switch, manual priority, native protocol binaries and live application flows. No log text containing private tags/URLs/tokens is added.
- Initial stabilization remains at least 10 seconds. One bounded observation and at most one current pending request; no unbounded queue of checks or blocking calls.
- The roughly 30-second recovery target is an awake-device acceptance goal with physical Internet and an allowed healthy reserve; device/VPN/Doze acceptance remains NOT_RUN here.

## Review Focus

1. Blocking DNS/native calls ignore cancellation: coroutine must return and real workers/owned resources remain bounded.
2. Old application flows are receiving bytes while selected OpenConnect is not ready: old bytes must not override two fresh readiness failures.
3. Manual selection, reload, network or profile changes during observation/selection: no stale commit or penalties in a new session.
4. Captive portal, missing physical Internet, saturated probe executor or a healthy-but-slow endpoint: no speculative selection or core restart.
5. Rapid event/alarm bursts and background measurement: no competing healing decisions or repeated bulk measurements.

### Task 1: Owned blocking probe deadlines

**Files:** new `bg/WatchdogProbeRunner.kt`, tests `bg/WatchdogProbeRunnerTest.kt`; modify `bg/ConnectivityWatchdog.kt` at its private probe and network routines.

**Interfaces:** Produces a cancellable bounded runner with per-call owned closeables and explicit success/no-response/unavailable outcomes. Callers retain ownership of application sockets and command clients.

- [x] Reproduce the old private `probe` running beyond its timeout with a real blocking operation, using an isolated JVM boundary harness; compilation errors do not count as RED.
- [x] Add behavioral tests for deadline, cancellation/late success, resource closure, bounded workers, rejected execution and ordinary success/failure.
- [x] Implement owned cancellation, global bounded workers and cleanup; adapt actual TLS/HTTP/physical probes and run independent initial probes together.
- [x] Run focused tests and existing JVM suite; commit the coherent task and record exact command/output in the ledger.

### Task 2: Bounded, current recovery from unready OpenConnect

**Files:** new `bg/WatchdogRecoveryPolicy.kt` and `bg/WatchdogCheckGate.kt` with their tests; modify `bg/ConnectivityWatchdog.kt`, `connections/ProbeTransport.kt` and targeted transport tests only as needed. Also expose the already tracked physical Network through a read-only `DefaultNetworkMonitor.currentNetwork` accessor.

**Interfaces:** Consume Task 1 outcomes. Confirm local selected endpoint readiness on two independent targets. Preserve the original soft-switch ticket/expected selection through recovery; normal unknown failures retain conservative confirmation.

- [x] Add tests showing persistent not-ready recovers without the old three long check cycles; healthy, unknown/busy, captive/offline, startup grace and superseded cases do not take the fast path.
- [x] Add coalescing/cancellation tests and current-session tests for disconnect, network/profile/selector/manual changes.
- [x] Integrate the fast path with owned LOCAL command clients, fresh reserve probes and existing soft selection. No raw error text is exported; generic failures are not guessed as endpoint-not-ready.
- [x] Bound repeated remeasurement commands; do not alter native auto-selection policy or user routing. Record any remaining limits.
- [x] Run focused transport/recovery/integration checks and existing JVM suite; commit and record results.

### Task 3: Candidate verification and delivery

**Files:** regression tests/documentation only if evidence identifies a gap; reports and lab classes live outside the repository in `tools/support/android-watchdog-20261027`.

- [x] Run all available JVM tests and repository Python checks; compile the actual changed watchdog against the isolated boundary harness. State explicitly if Android SDK/native AAR prevent a full APK build.
- [x] Freeze the complete branch diff/source SHA and obtain one fresh read-only review. Fix important findings in one TDD pass; record decisions and deferred minor findings.
- [x] Verify no version/dependency/workflow/signature/profile changes, tracked tree clean, remote dev/release untouched, and installed Windows files/processes not controlled.
- [x] Save REPORT, HANDOFF draft, exact commands/counts and untested physical scenarios. Keep the branch locally; do not publish/install or mark physical acceptance complete.

**Result:** 184 JVM tests, 34 repository Python tests, 69 resource keys in six locales. One read-only whole-branch review of d45e8c5 found Important singleton recovery history leaking across profiles/sessions. Corrected actual-code RED: 13 tests, 3 failures; fixed by context-owned RecoveryState, final full suite GREEN184/184. No second review. Source/evidence verification and handoff insertion draft are in the parent workspace proof directory. APK/device/30-second end-to-end acceptance remains NOT_RUN; no CI or external changes.
