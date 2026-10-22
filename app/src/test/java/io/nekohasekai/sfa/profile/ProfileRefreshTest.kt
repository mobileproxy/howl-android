package io.nekohasekai.sfa.profile

import io.nekohasekai.sfa.subscription.ConfigHardening
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** The source envelope must compare the effective profile without storing runtime overrides. */
class ProfileRefreshTest {
    private val files = mutableMapOf<String, String>()
    private var time = 100L
    private val original = """{"outbounds":[{"type":"selector","tag":"Howl","outbounds":["A","B"],"default":"A","interrupt_exist_connections":true},{"type":"socks","tag":"A","server":"a.example.test","server_port":1080},{"type":"socks","tag":"B","server":"b.example.test","server_port":1080}],"route":{"final":"Howl"}}"""

    private fun store() = SafeProfileStore(
        File("fixture.safe"), "https://example.test/profile", { files[it.path] },
        { file, content -> files[file.path] = content },
        { content -> ProfileDocument.inspect(content, "https://example.test/profile"); content },
        { ++time },
    )

    private fun working(content: String = original): SafeProfileStore {
        val store = store()
        store.receive(content)
        val prepared = store.prepare { error("legacy read") }
        // Launch assembly changes only the runtime; markApplied receives the source preparation.
        val runtime = JSONObject(ConfigHardening.apply(prepared.content))
        assertFalse(runtime.getJSONArray("outbounds").getJSONObject(0).getBoolean("interrupt_exist_connections"))
        assertTrue(store.markApplied(prepared))
        return store
    }

    @Test fun unchangedSourceAfterRuntimeAssemblyDoesNotBecomePending() {
        val store = working()
        val appliedAt = store.summary().appliedAt
        time += 60 * 60_000L // The next update can be delivered after a long idle period.
        store.receive(original)
        assertFalse(ProfileChanges.requiresReload(original, original))
        assertFalse(store.summary().pending)
        assertEquals(appliedAt, store.summary().appliedAt)
        assertEquals(original, store.backupOnly().content)
    }

    @Test fun formattingOnlyRefreshDoesNotBecomePendingOrReplaceTheWorkingReserve() {
        val store = working()
        val appliedAt = store.summary().appliedAt
        val received = JSONObject(original).toString(2)
        assertFalse(ProfileChanges.requiresReload(original, received))
        assertEquals(received, store.receive(received))
        assertEquals(received, store.prepare { error("legacy read") }.content)
        assertFalse(store.summary().pending)
        assertEquals(appliedAt, store.summary().appliedAt)
        assertEquals(original, store.backupOnly().content)
    }

    @Test fun objectKeyOrderOnlyRefreshDoesNotBecomePending() {
        val store = working()
        val reordered = original.replace("\"type\":\"socks\",\"tag\":\"A\"", "\"tag\":\"A\",\"type\":\"socks\"")
        assertFalse(ProfileChanges.requiresReload(original, reordered))
        store.receive(reordered)
        assertFalse(store.summary().pending)
    }

    @Test fun runtimeSoftPolicyOnlyRefreshDoesNotBecomePending() {
        for (candidate in listOf(
            original.replace("\"interrupt_exist_connections\":true", "\"interrupt_exist_connections\":false"),
            original.replace(",\"interrupt_exist_connections\":true", ""),
        )) {
            val store = working()
            assertFalse(ProfileChanges.requiresReload(original, candidate))
            store.receive(candidate)
            assertFalse(store.summary().pending)
            assertEquals(candidate, store.prepare { error("legacy read") }.content)
            assertEquals(original, store.backupOnly().content)
        }
    }

    @Test fun urltestSoftPolicyOnlyRefreshDoesNotBecomePending() {
        val urltest = original.replace("\"selector\"", "\"urltest\"").replace("\"default\":\"A\",", "")
        val store = working(urltest)
        val candidate = urltest.replace(",\"interrupt_exist_connections\":true", "")
        assertFalse(ProfileChanges.requiresReload(urltest, candidate))
        store.receive(candidate)
        assertFalse(store.summary().pending)
        assertEquals(urltest, store.backupOnly().content)
    }

    @Test fun serverChangesStillBecomePendingAndKeepThePreviousReserve() {
        val store = working()
        val changed = original.replace("a.example.test", "updated.example.test")
        assertTrue(ProfileChanges.requiresReload(original, changed))
        store.receive(changed)
        assertTrue(store.summary().pending)
        assertEquals(changed, store.prepare { error("legacy read") }.content)
        assertEquals(original, store.backupOnly().content)
    }

    @Test fun selectionAndArrayOrderChangesStillBecomePending() {
        for (candidate in listOf(
            original.replace("\"default\":\"A\"", "\"default\":\"B\""),
            original.replace("[\"A\",\"B\"]", "[\"B\",\"A\"]"),
        )) {
            val store = working()
            assertTrue(ProfileChanges.requiresReload(original, candidate))
            store.receive(candidate)
            assertTrue(store.summary().pending)
        }
    }

    @Test fun dnsRouteAndGroupMembershipChangesStillBecomePending() {
        for (candidate in listOf(
            original.replace("\"route\":", "\"dns\":{\"servers\":[{\"type\":\"https\",\"tag\":\"dns\",\"server\":\"dns.example.test\"}]},\"route\":"),
            original.replace("\"final\":\"Howl\"", "\"final\":\"B\""),
            original.replace("[\"A\",\"B\"]", "[\"A\"]"),
        )) {
            val store = working()
            assertTrue(ProfileChanges.requiresReload(original, candidate))
            store.receive(candidate)
            assertTrue(store.summary().pending)
        }
    }

    @Test fun equivalentCandidateStillNeedsItsExactIntegrityHash() {
        val store = working()
        val candidate = original.replace(",\"interrupt_exist_connections\":true", "")
        store.receive(candidate)
        val envelope = JSONObject(files.getValue("fixture.safe"))
        envelope.put("received", JSONObject(candidate).toString(2))
        files["fixture.safe"] = envelope.toString()
        assertTrue(store.summary().pending)
        val prepared = store.prepare { error("legacy read") }
        assertTrue(prepared.backup)
        assertEquals(original, prepared.content)
    }
}
