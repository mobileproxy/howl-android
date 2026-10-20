package io.nekohasekai.sfa.connections

import io.nekohasekai.sfa.subscription.ConfigHardening
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SoftSwitchConfigTest {
    private fun profile() = JSONObject().put("outbounds", JSONArray()
        .put(JSONObject().put("type", "selector").put("tag", "Howl")
            .put("outbounds", JSONArray().put("auto").put("A")).put("default", "A")
            .put("interrupt_exist_connections", true))
        .put(JSONObject().put("type", "urltest").put("tag", "auto")
            .put("outbounds", JSONArray().put("A")).put("interrupt_exist_connections", true))
        .put(JSONObject().put("type", "selector").put("tag", "nested")
            .put("outbounds", JSONArray().put("A")).put("interrupt_exist_connections", true))
        .put(JSONObject().put("type", "direct").put("tag", "A")))
        .put("route", JSONObject().put("final", "Howl"))

    @Test fun runtimePreservesExistingConnectionsInEverySelectorAndUrltest() {
        val runtime = JSONObject(ConfigHardening.apply(profile().toString())).getJSONArray("outbounds")
        for (i in 0..2) assertFalse(runtime.getJSONObject(i).getBoolean("interrupt_exist_connections"))
    }

    @Test fun missingFlagsAlsoGetAnExplicitSoftPolicy() {
        val source = profile()
        for (i in 0..2) source.getJSONArray("outbounds").getJSONObject(i).remove("interrupt_exist_connections")
        val runtime = JSONObject(ConfigHardening.apply(source.toString())).getJSONArray("outbounds")
        for (i in 0..2) assertFalse(runtime.getJSONObject(i).getBoolean("interrupt_exist_connections"))
    }

    @Test fun sourceProfileAndRouteChoiceStayUnchanged() {
        val source = profile(); val original = source.toString()
        val runtime = JSONObject(ConfigHardening.apply(original))
        assertEquals(original, source.toString())
        assertTrue(source.getJSONArray("outbounds").getJSONObject(0).getBoolean("interrupt_exist_connections"))
        assertEquals("A", runtime.getJSONArray("outbounds").getJSONObject(0).getString("default"))
        assertEquals(source.getJSONObject("route").getString("final"), runtime.getJSONObject("route").getString("final"))
        assertFalse(runtime.getJSONArray("outbounds").getJSONObject(3).has("interrupt_exist_connections"))
    }

    @Test fun hardeningIsIdempotentAndKeepsMalformedInputUntouched() {
        val first = ConfigHardening.apply(profile().toString())
        assertEquals(first, ConfigHardening.apply(first))
        assertEquals("fixture-not-json", ConfigHardening.apply("fixture-not-json"))
    }
}
