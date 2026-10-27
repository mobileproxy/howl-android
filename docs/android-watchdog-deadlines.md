# Android watchdog: bounded checks and selected OpenConnect recovery

Prepared on the Android 1.0.6 source baseline. This document does not announce a release.

## Runtime contract

- One watchdog gate owns observations and healing decisions. During the initial 10-second stabilization period, repeated signals coalesce. Afterwards there is one running observation and at most one latest pending signal. Normal/failing periodic intervals remain 90/15 seconds.
- Independent tunnel TLS and domain HTTPS checks run together. A blocking network check has an 8-second outer deadline; a complete observation has a 30-second ceiling. Elapsed time uses `SystemClock.elapsedRealtime`, not the wall clock. These are runtime limits, not a demonstrated real-device recovery SLA.
- Blocking DNS/IO runs on four process-wide workers with no pending work queue. Cancellation schedules closing only that call's registered resources. If work cannot stop, the caller returns `Unavailable` while the real worker remains occupied. Cleanup is also bounded. A saturated or failed check is not evidence that a server is down.
- Shared native probe transports have eight workers and at most twelve owned clients, including clients waiting for native disconnect. A permit is released only after real cleanup. This applies to watchdog and manual probes together; it does not bound the native core's own background urltest jobs.

## Early OpenConnect path

After stabilization, the watchdog confirms the CURRENT selected OpenConnect using an owned LOCAL command client. The existing probe transport tries two independent control URLs. A successful result, including zero milliseconds, prevents early recovery. IPC/factory failures, timeouts and busy executors yield `Unavailable`; arbitrary error messages are not parsed as an endpoint state.

If both targets complete unsuccessfully and a fresh probe through the physical network actually handed to the core succeeds, the watchdog may immediately probe reserves. It need not wait for three long general check cycles. A captive/unvalidated network does not take this early path. Incoming bytes from old application flows do not override this selected-path failure.

Only a fresh successful reserve measurement permits the existing soft selection. No healthy reserve means retain the current selection. An unavailable reserve probe does not penalize an unmeasured reserve. Recovery respects the existing minimum interval and exhausted-pool backoff.

This confirms failure of the selected request path; it does not claim to identify the internal cause of `endpoint is not ready yet`. Native protocol implementations are unchanged.

## Ownership and stale results

Observations carry scope/session identity, profile, selector, selected/effective node, physical network and invalidation generation. The original soft-switch ticket is retained through the decision. Manual choices, disconnect, reload, profile changes, changed physical network and expired observations cannot authorize a delayed selection. Native acknowledgements may change the selection only within the same current session/ticket.

Penalties, exhausted-pool counters and healing backoff belong to a separate recovery state keyed by session, profile, selector, generation, physical network and manual-ticket revision. A new context replaces that object; an old probe can only touch its old object. Stop/start and configuration reload discard it. Our acknowledged automatic selection preserves the same context/history, so normal node rotation does not silently forgive failed nodes.

Network probes and capability checks use the physical `Network` passed to the core, which may differ from Android's system default after fallback. A successful physical-network change requests a new settled observation.

Neither network probes nor reserve selection reload the working core/TUN or close application flows. Private command clients are disconnected after use. Background bulk remeasurement requests are coalesced and their caller/client lifetime is bounded; already accepted core background work has no new cancellation API.

Profiles, last-known-good backups, subscription data, DNS/routing, native core, signing identity, Android rotation lineage and version values are not changed. No new persistent user-preference/automatic-return model is introduced; successful emergency selection uses the existing native selection/cache behavior.

## Verification boundary

The local proof includes ordinary JVM tests, controlled coroutine traces, a real blocked loopback socket, private-client cancellation/cleanup, and the actual watchdog/selection code compiled with inert Android/Libbox boundary stubs. That harness never loads an installed service, APK or native VPN library.

The Android SDK and pinned native AAR are absent locally. Full Gradle APK/Robolectric compilation, device installation, migration, real OpenConnect/VPN traffic, Doze, battery use and the roughly 30-second end-to-end target remain **NOT_RUN**. Android may delay execution while asleep. A native operation already accepted before cancellation can have an uncertain acknowledgement; the existing selection guard and safe diagnostics remain, rather than killing the core.

Publication, installation and CI are separate actions. Actions budget remains $0; this local preparation does not freshly verify billing.
