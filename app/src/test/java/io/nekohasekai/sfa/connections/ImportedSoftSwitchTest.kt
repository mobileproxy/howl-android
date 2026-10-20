package io.nekohasekai.sfa.connections

import io.nekohasekai.sfa.subscription.SubscriptionConverter
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Test

class ImportedSoftSwitchTest {
    @Test fun importedLinksCreateAConnectionPreservingSelectorWithoutNativeLoading() {
        val build = SubscriptionConverter::class.java.getDeclaredMethod("build", List::class.java).apply { isAccessible = true }
        val node = JSONObject().put("type", "socks").put("tag", "fixture")
            .put("server", "example.test").put("server_port", 1080)
        val json = build.invoke(SubscriptionConverter, listOf(node)) as String
        assertFalse(JSONObject(json).getJSONArray("outbounds").getJSONObject(0).getBoolean("interrupt_exist_connections"))
    }
}
