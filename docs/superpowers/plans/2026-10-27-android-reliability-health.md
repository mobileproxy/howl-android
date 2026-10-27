# Android reliability health Implementation Plan

> Use superpowers:executing-plans inline, TDD per deliverable and one fresh whole-branch read-only review at the end. Checkbox steps track actual evidence.

**Goal:** Implement all five authorized Android reliability follow-ups while preserving routing, profile data, manual priority and bounded resource ownership.
**Architecture:** Pure evidence/lifecycle/preference reducers with thin Android service, watchdog, UI and storage adapters. Shared owned-probe admission; no native core replacement.
**Tech:** Existing Kotlin/coroutines/JUnit/Compose/Robolectric/Room settings, AndroidAPI24–36; no new dependencies.
**Spec:** docs/superpowers/specs/2026-10-27-android-reliability-health-design.md; parent tools/support/client-health-20261027/PLAN-REVIEW.md.

## Global Constraints

- Base473cdaa58226128ebfd5bfc8dbebb6f16548e9ec, branch android/reliability-health-20261027, existing isolated worktree.
- No shared dev push/merge, Release/site publication, version bump, device/emulator operation or installation. Candidate branch/manual CI only for needed verification; no Actions artifact upload. Actions$0/block and signing/Secrets/native core remain.
- Preserve AIDL, profiles/configs/working backups/subscriptions, split DNS/routes/Kill Switch, core/TUN/old flows and mobile-cost policy.
- Health freshness120000ms, automatic return600000ms with minimum3 samples/max gap120000ms, monotonic time. Manual preference is not inferred for migration.
- App probe ownership8, non-critical6, reserved Active2; no unbounded real jobs/queues. Native groups independently single-flight/concurrency10, explicitly not capped by app coordinator.
- All new user text in six existing locales; diagnostics strictly allowlisted, no private tags/addresses/URLs/config/raw exceptions/OS trace.
- Item5 physical/VPN/Doze/battery/day/SLA remains NOT_RUN without a stand. Do not confuse local/inert/Robolectric proof with it.

## Review Focus

1. Old flows or a bypass/control-only success must not claim universal health, DNS cause or validate a stale new selection; Off/Connecting/Remote/unverified must remain truthful.
2. User Disconnect/manual choice/profile/reload/network/reboot interleaved with probe/return/start must win; no late success can revive or overwrite new intent.
3. Hung native factory/test/cleanup and concurrent manual scans/health/recovery must retain finite actual ownership and allow foreground priority, not just free a coroutine slot.
4. Old/unknown OS exit records, wall-clock changes, service-only absence or config reload must not be labelled a system kill/crash or leak private diagnostics.
5. Preference migration, invalid targets, interrupted settings writes and missing/slow preferred probes must preserve working data and never return after merely waiting10min since a heal.

### Task1: Evidence-based health and honest UI

Files: new health/ConnectionHealth.kt, health/HealthTracker.kt, health/HealthRuntime.kt and tests; modify bg/ConnectivityWatchdog.kt, bg/WatchdogRecoveryPolicy.kt, connections/ProbeTransport.kt and LibboxProbeTransport.kt for configurable health targets; new compose/component/ConnectionHealthText.kt; integrate DashboardScreen.kt/HowlHome.kt/ServiceStatusBar.kt and ServiceNotification.kt; six strings.xml and resource validator.

Interfaces: HealthContext(session:Any, profile:Long, generation:Long, selector:String?, selected:String?, network:Any?, manualRevision:Long); enum HealthState/HealthCause; HealthEvidence with typed physical/system-resolver/TLS/HTTPS/selected findings; HealthTracker.begin(context), observe(context,evidence,nowMs), recovering(context,cause,nowMs), stop(), view(nowMs), StateFlow<HealthSnapshot>. HealthRuntime is process-owned wrapper; presenter combines service lifecycle with fresh health, no action semantics change.

- [x] RED: TLS success + HTTPS failure is not DNS; missing selected probe not Verified; one control resource failure is not total failure; stale/profile/network/stop results discarded; fresh success expires, old traffic not proof, watchdog-off/remote are Unverified/local-neutral.
- [x] Add typed stage findings and selected HTTPS confirmation; implement pure classifier/tracker and integrate actual watchdog observation/guards. Preserve finite owned probes.
- [x] RED/UI: running-but-recovering retains Disconnect action; dashboard and notification share label/cause; expired state never green. Implement localized presentation and notifications without high-frequency background polling.
- [x] Focused JVM/actual-watchdog boundary GREEN, resources all6, existing suite GREEN. Commit task.

### Task2: Service lifecycle and recovery diagnosis

Files: new health/ServiceRecoveryLedger.kt, health/AndroidLifecycleDiagnostics.kt, health/RecoveryDiagnostics.kt and pure tests; modify BoxService.kt/ServiceGuard.kt; Settings.kt/SettingsKey.kt marker string; diagnostics ConnectionReport/ConnectionDiagnostics and tests only for allowlisted additive summary.

Interfaces: ServiceSessionMarker(sessionId,pid,processName,startedWallMs,startedElapsedMs,plannedStop); ProcessExitRecord(pid,processName,timestamp,reasonCode); ServiceRecoveryLedger.match(previous,records,nowWallMs) -> ExitAssessment, lifecycle events with fixed codes and bounded durations; RecoveryDiagnostics.transition(context,cause,outcome,nowMs) -> bounded summaries, never raw messages.

- [x] RED: no matching historical record/old API/same-process absence=>Unknown; stale/future/wrong PID/process rejected; user stop/revoke/reload distinct; no resurrection after explicit stop; recovered only after current successful request; repeated failure aggregates count/first/last.
- [x] Implement bounded API30+ adapter on IO, session persistence and lifecycle hooks; remove unjustified "killed by system" messages. Do not weaken startedByUser or SoftSwitch guards. Verify any discovered stop/start race before changing it.
- [x] Add privacy tests for secret-laden errors/config/names; extend sanitized report with codes/counters only. Focused and full local suite GREEN; commit.

### Task3: Shared app probe admission and quiet prevention

Files: new connections/ProbeCoordinator.kt and tests; modify ProbeTransport.kt/LibboxProbeTransport.kt, ConnectionChecks.kt/controller and Watchdog remeasurement/health/recovery factories. Add native-inventory document.

Interfaces: ProbePriority {Active,Recovery,ManualScan,Background}; ProbeCoordinator.acquire(priority): suspend OwnedLease; idempotent close; max8 actual owned requests/non-critical6, priority waiters and cancel/deadline cleanup. Transport takes coordinator/priority and holds lease until actual factory/test/disconnect ownership ends, independent of coroutine return.

- [x] RED: manual saturation leaves Active capacity; cancellation waiting never starts a client; hung disconnect keeps slot; rejected executor/late factory releases correctly; manual selection gets priority; no growing queued native jobs.
- [x] Implement coordinator/zero-queue workers with retained ownership, keep secondary native-client fuse. Preserve first-pass/retry/cancel behavior; Busy is not node failure.
- [x] RED actual watchdog: repeat prevention signals coalesce and no full native URLTest sweep starts; replace bulk app prevention with current-path check request. Document native single-flight/10-per-group boundary honestly.
- [x] Focused/transport/full suite GREEN; commit.

### Task4: Persist manual intent and guarded return

Files: new health/ProfileSelectionPreferences.kt, health/PreferredReturnPolicy.kt, health/SelectionPreferenceRuntime.kt and tests; Settings/SettingsKey new versioned string; GroupsViewModel manual acknowledgment, ProfileManager deletion hooks; Watchdog auto-return integration and UI distinction.

Interfaces: preferenceStore.read(profile,selector), recordManual(profile,selector,target,stillCurrent), removeProfile(profile); missing/corrupt/unsupported version fails closed without erasing old data. PreferredReturnPolicy.observe(context,preferenceRevision,result,nowMs) -> decision only after600000ms/min3/maxgap120000ms; runtime keeps old logical preference while temporary selector differs.

- [x] RED: legacy empty state does not infer an emergency node; manual current-item click records intent; failed/stale/remote choices never overwrite it; profiles independent; missing target/failed write preserves prior values.
- [x] Implement atomic bounded versioned storage and explicit local acknowledgment hooks; no profile schema/config/subscription/backup rewrite.
- [x] RED: failed/unknown/missing sample/network/Doze gap resets stability;10min since heal alone never returns; fresh preferred/physical/current ticket required; later manual change/Disconnect cancels; own fallback does not erase preference; Direct/Block not automatic VPN return.
- [x] Integrate existing guarded soft selection and same UI health distinction; focused/actual-watchdog/full GREEN, commit.

### Task5: Acceptance kit and combined candidate verification

Files: docs/android-reliability-acceptance.md, tools/collect-reliability-acceptance.py plus focused fixture tests; expand deterministic event traces; candidate workflow only if needed for full SDK/minified build; reports in parent tools/support/android-reliability-20261027.

- [x] Add H-scenario traces covering cold start/offline/captive/DNS/HTTPS/partial/new path/old flows/manual/profile/network/reload/pressure/restarts/stable-return; mark actual device scenarios NOT_RUN.
- [x] Provide safe explicit-serial/opt-in diagnostics collector and day-long/battery/call/download checklist. No automatic install/clear/force-stop/route operations; fixture mode has no ADB.
- [x] Run full local JVM/boundary, tools/pipeline/resource checks. If local SDK/native AAR absent, use a dedicated candidate branch/read-only manual CI for full Gradle/Robolectric/debug+minified checks without artifacts/publication/emulator install. Keep version/core/signing/budget unchanged.
- [x] Freeze source SHA/whole diff, obtain one fresh whole-branch review. Critical/Important fixed in one author TDD pass, full suite GREEN; ledger all decisions/declined external properties.
- [x] Preserve clean candidate branch, test commands/logs/hashes, REPORT and HANDOFF draft; report exactly what is implemented versus physical pending. No public release or device operation.


## Completed candidate verification

All available implementation/preparation checkboxes above are complete. Physical device
acceptance is explicitly NOT_RUN: no stand, publication or installation in this task.
Tested code commit: f4e9609d1349d47a128e1e68270a2f6b27b113cc.
Local JVM/actual-watchdog inert-boundary suite350/350; actual lifecycle adapter9/9;
tools34/34,collector19/19,pipeline52/52; health resources38 keys in six locales.
Final full SDK/Robolectric CI40578194875:346 tests,0 errors/failures/skips; release/R8,
ARM,existing compatible signatures/rotation and branding succeeded; release staging
skipped and Actions artifacts0. Dev473cdaa/latest Android1.0.7 remain unchanged.
One independent review of61460c2 had7 Important/1 Minor, resolved by the one author
correction pass with focused RED/GREEN; no second independent review is claimed.
Detailed proof and device limits: parent tools/support/android-reliability-20261027.
Budget$0/block unchanged; no live billing re-audit. This final plan update is docs only.
