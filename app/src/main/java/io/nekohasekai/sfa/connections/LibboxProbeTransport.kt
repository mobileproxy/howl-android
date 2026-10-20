package io.nekohasekai.sfa.connections

import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.utils.CommandTarget
import io.nekohasekai.sfa.database.RemoteServer

object LibboxProbeTransport {
    fun create(): ProbeTransport = createFor(CommandTarget.remoteServer)
    fun createLocal(): ProbeTransport = createFor(null)
    fun createFor(remote: RemoteServer?): ProbeTransport {
        // Capture the destination once; a later remote-session change must not redirect a probe.
        return ProbeTransport(factory = {
            val owned = if (remote == null) Libbox.newStandaloneCommandClient()
                else Libbox.newStandaloneRemoteCommandClient(CommandTarget.libboxOptions(remote))
            object : OwnedProbeClient {
                override fun test(tag: String, url: String, timeoutMs: Int): ProbeReply {
                    val result = owned.urlTestOutbound(tag, url, timeoutMs)
                    return ProbeReply.fromCore(result.delay, result.error.orEmpty())
                }
                override fun close() { owned.disconnect() }
            }
        })
    }
}
