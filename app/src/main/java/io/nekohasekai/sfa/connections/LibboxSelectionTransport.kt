package io.nekohasekai.sfa.connections

import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.database.RemoteServer
import io.nekohasekai.sfa.utils.CommandTarget

object LibboxSelectionTransport {
    fun create(remote: RemoteServer?): BoundedSelection = BoundedSelection(factory = {
        // Never disconnect CommandTarget's shared remote client or close user
        // connections. This command connection belongs only to this request.
        val owned = if (remote == null) Libbox.newStandaloneCommandClient()
            else Libbox.newStandaloneRemoteCommandClient(CommandTarget.libboxOptions(remote))
        object : OwnedSelectionClient {
            override fun select(group: String, tag: String) { owned.selectOutbound(group, tag) }
            override fun close() { owned.disconnect() }
        }
    })
}
