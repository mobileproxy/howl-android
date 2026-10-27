# Android reliability: evidence, lifecycle, probe load and user preference

## Intent and authority

The owner explicitly requests all five follow-ups to the log analysis, prepared and checked without publication or installation. Base is released Android1.0.7/826, dev473cdaa58226128ebfd5bfc8dbebb6f16548e9ec. The binding existing plan is the parent workspace's tools/support/client-health-20261027/PLAN-REVIEW.md. Preserve its H01–H24 invariants where touched. This design records implementation choices for that authorized scope, not a request to publish.

Goal: a person enables VPN and does not need to interpret ambiguous errors, chase switches or lose their choice. A running service is distinct from a verified new request. Missing evidence remains unknown. No new telemetry or raw-error export.

## Approach and trade-offs

Use small pure Kotlin reducers/policies with thin Android adapters, integrated with the existing service/watchdog/SoftSwitch and Compose UI. Keep the native core and persistent profile formats unchanged. A UI-only wording patch would leave misleading decisions and lifecycle claims; replacing the whole connection engine/core would introduce unrelated routing/signing/migration risk. Those alternatives are rejected by the stability goal. The owner chose the five outcomes; this implementation approach and the numeric defaults below are author choices, open to correction while the candidate is local.

Physical acceptance cannot be completed under the no-install constraint and absent stand. Deliver deterministic traces and an executable collection/checklist kit with real outcomes explicitly NOT_RUN. Source-only branch push/manual read-only candidate CI is allowed as necessary verification; no shared dev update, Release, website deployment, install, device/emulator operation, version bump, new Secrets, signing rotation or Actions cost-policy change.

## Confirmed current facts

- VPN/Proxy services and UI have no separate android:process declaration. Use process-local StateFlow; do not expand AIDL.
- Main Howl dashboard reports connected from Status.Started; notification replaces text with speed. Keep connection-button actions tied to service state, never to a probe result.
- Watchdog currently labels TLS success plus unsuccessful HTTPS as DNS. Native URLTestOutbound supplies a delay and an opaque error string, not a typed DNS diagnosis. Do not parse arbitrary text as cause or attribute an Android resolver failure to profile DNS.
- ServiceGuard currently labels any absent service as killed by the system. Android API30+ provides historical process-exit records, potentially incomplete. Match persisted session/PID/time; never export getDescription or traces, or infer a reason from mere absence. Older API/no matching record is Unknown.
- Pinned native source4959f2084fd00895fb41914ec407bdaf01300666: protocol/group/urltest.go596–638 has per-group single-flight and batch concurrency10; periodic native checks are independent. URLTestOutbound is synchronous/stateless with its own client cancellation; no global pause/cancellation RPC for all native background checks is exposed in this binding.
- Existing app manual probes and watchdog use owned clients with bounded cleanup, but native app worker queue is unbounded; background app remeasurement can request bulk native urlTest. Do not claim a GUI semaphore limits independent native groups.
- Native selector/cache does not distinguish saved manual preference from an emergency selection. No reliable migration can recover which old cached choice was manual.

Primary references: Android ApplicationExitInfo and ActivityManager.getHistoricalProcessExitReasons official API docs; the two pinned native source files above. No dependency/core update is proposed.

## 1. Evidence and honest health

Add pure health models, classification, session reducer and process StateFlow. Keep service lifecycle status separately. Suggested display states: Off, Connecting, Checking, Available, Recovering, NoNetwork, Captive, RequestsFailed, Partial, CheckUnavailable, ProfileProblem, Unverified. Available is presented as "Selected server is responding", not "Internet works": the native probe confirms a current selected-exit response but does not expose its HTTP status or redirect chain. Never invent those fields or claim every application/UDP/site/routing rule works.

Each observation includes owner/session, profile, configuration generation, selector/selected effective exit, physical Network, manual revision, monotonic start/check time and typed findings. No selected target/current observation means Unverified, not green. An unavailable/expired/late probe does not establish server failure. Missing network, captive hint, physical reachability, system name resolution, TLS and HTTPS response are separate evidence. A failed HTTP request never becomes DNS solely because a TLS probe succeeded. Use Unknown/RequestsFailed when the native boundary does not expose a more specific cause. The Android HTTP probe rejects redirects and requires its expected status; this strict-status property cannot be attributed to the opaque native probe. Do not disable TLS/hostname verification.

Confirm the selected exit for all supported protocols with existing owned native probes, using two independent HTTPS control targets for health; manual latency probes retain their existing target policy. No geo/IP inspection background sweep. Success on one independent control target prevents declaring the whole exit dead. Existing old-flow bytes are useful context but never proof of a new selected path. An actual profile error comes from existing profile validation/start failure, not from failed network tests.

Author default: successful health evidence expires after120000ms monotonic time. One expiry job per active service/observation, no frequent background polling or extra wake lock. Rechecking can retain a still-fresh successful display until a new result; profile/network/selection/stop invalidation cannot. UI/notification use the same presenter. With watchdog disabled or unsupported/no unique exit, show "VPN enabled; connection unverified", not permanent connecting or a false error. Preserve the Disconnect action while recovering. Remote-control screens must not show local health as remote evidence. Direct/block/unknown targets cannot be presented as a verified VPN exit.

## 2. Lifecycle and recovery diagnosis

Persist a bounded versioned service-session marker separately from profile data: random session ID, previous PID/process name, start wall/elapsed times and planned stop/start kind. Explicit user stop/revoke/error, config reload, Android sticky start and guard restart are distinct. Treat null restart intent as a trigger, not an explanation of death.

API30+ lookup is bounded (recent records only) and matches previous PID/process/session interval; reject stale/future/unrelated records. Before30, unavailable API, same-process service absence and no match yield Unknown. Store only allowlisted numeric/reason codes; raw OS descriptions, stack traces, URLs, names, tokens and configuration never enter the new diagnostics. No history lookup on Main. No new automatic restart if startedByUser is false; reload must not be counted as process death.

Record one start/stop/restore outcome and recovery duration. For network failures aggregate first/last elapsed time, attempts, result and count for the same scoped cause; flush on transition/recovery/stop with bounded rate. Distinguish service running from network recovered; recovery completes on fresh current successful requests. Include an allowlisted summary in existing sanitized connection diagnostics without changing old fields or leaking raw logs.

## 3. Shared app probe admission and native boundary

One process-wide ProbeCoordinator for all app-owned native measurements. Author defaults:8 real owned probe slots, at most6 non-critical slots, leaving2 for current-exit health and explicit manual selection. Priorities: Active, Recovery, ManualScan, Background. Waiting is cancellable/deadline-bound and does not occupy a worker or create a native client. Never release ownership early when native test/factory/disconnect is still running. Native executors have no unbounded queued work. Keep the existing12-client semaphore as a secondary fuse. Existing manual first-pass/retry semantics and explicit user cancellation remain.

Replace app-triggered full native bulk remeasurement with a coalesced request to check the current path/preference. Do not wake all endpoints merely to refresh background numbers. Explicit manual scans still cover the location list; native auto groups keep their existing periodic behavior. Expose/preserve Busy/Unavailable as a check problem, not NoResponse. Current path and explicit manual selection have reserved admission; no competing healing decisions.

Boundary: independent native automatic groups retain their own single-flight/concurrency10 and reachability gating. The coordinator does NOT impose a single global8 limit on those groups. No new core build, guessed RPC or config rewrite to suspend them. Inventory and acceptance report must state this limitation; real total load/battery needs device measurement.

## 4. Preferred selection versus temporary fallback

Add a versioned bounded selection-preference map keyed by local profile ID and selector. Existing profiles/config/working backups are unchanged. No migration guesses from an old cached runtime choice: missing data is Unknown and preserves1.0.7 behavior. Record preference only on acknowledged explicit LOCAL manual selection (including a click on the already selected item); never from watchdog/group callbacks or remote selection. Store/update atomically and validate the original manual intent before writing. Remove entries on successful profile deletion; invalid/missing targets disable return rather than rewriting a profile or choosing blindly.

Watchdog selections remain temporary runtime choices. Display that distinction without exporting raw tags. On restart retain logical preference while validating the actual running path; do not blindly force a dead preferred node or erase a working reserve. A newer manual choice, disconnect, profile/config/network change or invalid ticket cancels pending return.

Author return default: at least600000ms of continuously fresh successful preferred-path observations, at least3 observations, maximum gap120000ms; use monotonic time. NoResponse, Unavailable, missing sample or network/context change resets the window. Require a fresh successful current physical path and preferred probe immediately before existing soft selection; apply original ticket/current-selection guards. Preserve old flows. Direct/reject/block/dns/unknown targets are not automatic VPN-return candidates. If user preference is auto, validate the group and current configuration without inventing a leaf preference. Do not add a 10-minute timer measured from the last healing action.

## 5. Acceptance and delivery

Deterministic tests cover H01–H24 touched cases: control-site failure versus DNS, stale success/expired health, captive/no network, manual priority, profile/reload/network changes, process reason matching and unknown fallback, explicit stop, native calls ignoring cancellation, admission saturation and preference migration, return stability/clock changes/Doze gaps. Compile the actual watchdog/adapters against inert boundaries locally; real Android build and Compose/Robolectric tests via read-only candidate CI when needed, no artifacts or publication. Local base184/184 already passed.

Provide a ready per-device acceptance matrix and safe collection script (explicit serial/opt-in, no auto install/clear/force-stop/network changes) for cold start, soft failover with active download/call, Wi-Fi/mobile/captive, profile/import/subscription, manual choice, sleep, Always-on/lockdown, battery and a day of normal operation. No physical result, energy improvement or30s end-to-end SLA is claimed without that run. Keep all evidence and handoff draft; do not edit root handoff unless separately requested.
