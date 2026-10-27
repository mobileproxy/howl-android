# Android probe ownership and evidence

The application coordinates private URLTestOutbound clients across manual scans,
explicit selection, watchdog health, reserve checks and preferred-route monitoring.
ProbeCoordinator.shared admits at most eight owned requests, at most six non-Active
requests, and at most 64 suspended admission waiters. Active selected-path checks and
explicit selections can use the two reserved places. Waiters do not allocate a native
client or executor work item. Cancellation/deadline removes its waiter.

Both native-call and private-disconnect executors have eight workers and zero queued
jobs. A lease is released only after factory/test AND its own disconnect actually end.
An uncooperative factory/test/disconnect keeps its slot; overload is unavailable
measurement, never proof that a server failed. The older 12-client semaphore remains
a secondary fuse. Cancellation never disconnects a shared core/application client.

The core's independent periodic URLTestGroup checks are outside this application cap.
In pinned sing-box-lx source 4959f2084fd00895fb41914ec407bdaf01300666,
protocol/group/urltest.go uses single-flight and batch concurrency ten per group.
Libbox does not offer a safe global pause/cancel operation for all groups. Eight is
not a global device/native-core cap. Former app background urlTest(selector) sweeps
are replaced with one coalesced current-path request; native periodic groups remain.

Manual location probes retain their existing HTTP targets. Watchdog selected/reserve/
preferred probes use two independent HTTPS controls. URLTestOutbound exposes opaque
success/delay/error only: success means the selected exit responded, not proof of HTTP
status, redirects, all websites, user DNS, every split route or UDP. Typed Android/system
HTTPS separately requires exact HTTP204 and rejects redirects. Its resolver can bypass
VPN according to user routing policy; actual resolver failure is system name resolution,
never inferred profile DNS failure.

No core/TUN reload or application-flow termination frees probe resources. Actual device
load, Doze, native cancellation, concurrent calls/downloads require the physical scenarios
in android-reliability-acceptance.md; those remain NOT_RUN here.
