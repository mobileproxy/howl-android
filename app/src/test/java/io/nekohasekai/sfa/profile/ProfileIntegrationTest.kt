package io.nekohasekai.sfa.profile

import io.nekohasekai.sfa.diagnostics.ConnectionReport
import io.nekohasekai.sfa.diagnostics.EventSummary
import io.nekohasekai.sfa.diagnostics.NodeSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

/** Shared storage/validation/start/report scenarios; no native core, Android service or network. */
class ProfileIntegrationTest {
    private val source = "https://gethowl.app/sub.html?t=fixture-only"
    private val files = mutableMapOf<String, String>()
    private var clock = 100L

    private fun document(tag: String, protocol: String = "vless") = JSONObject()
        .put("outbounds", org.json.JSONArray()
            .put(JSONObject().put("tag", "Howl").put("type", "selector")
                .put("outbounds", org.json.JSONArray().put(tag)))
            .put(JSONObject().put("tag", tag).put("type", protocol).put("server", "example.com")))
        .toString()

    private fun store(url: String = source) = SafeProfileStore(
        File("fixture.safe"), url, { files[it.path] }, { file, content -> files[file.path] = content },
        { content ->
            if (ProfileDocument.inspect(content, url)) throw ProfileFailure(ProfileFailure.Kind.EMPTY)
            content
        }, { ++clock },
    )

    private fun receive(store: SafeProfileStore, content: String) {
        val decoded = ProfilePayload.read(ByteArrayInputStream(content.toByteArray(Charsets.UTF_8)))
        store.receive(decoded)
    }

    private fun working(store: SafeProfileStore, content: String): SafeProfileStore.Prepared {
        receive(store, content)
        return store.prepare { error("unexpected legacy read") }.also { assertTrue(store.markApplied(it)) }
    }

    private fun node(content: String): NodeSnapshot {
        val outbound = JSONObject(content).getJSONArray("outbounds").getJSONObject(1)
        return NodeSnapshot.of(outbound.getString("tag"), outbound.getString("type"))
    }

    @Test fun legacyMigrationIsPromotedOnlyAfterAcceptedStart() = runBlocking {
        val store = store()
        val content = document("legacy-fixture")
        val prepared = store.prepare { content }
        assertEquals(0L, store.summary().appliedAt)
        val accepted = ProfileLauncher.launch(prepared, prepared.content, { store.backupOnly() }, { it },
            { ProfileDocument.inspect(it, source) }, { assertEquals(0L, store.summary().appliedAt) })
        assertTrue(store.markApplied(accepted.prepared))
        assertEquals(content, store.backupOnly().content)
        assertFalse(store.summary().pending)
    }

    @Test fun rejectedSubscriptionLeavesTheWorkingEnvelopeAndDatesUntouched() {
        val store = store()
        working(store, document("old-fixture"))
        val before = files.toMap()
        val summary = store.summary()
        for (raw in listOf("<html>fixture</html>", "{}", document("new-fixture") + "garbage")) {
            assertThrows(Exception::class.java) { receive(store, raw) }
            assertEquals(before, files)
            assertEquals(summary, store.summary())
        }
    }

    @Test fun failedCandidateRestoresWorkingProfileAndReportUsesTheAcceptedNode() = runBlocking {
        val store = store()
        val old = document("fixture-PRIVATE-old", "vless")
        val fresh = document("fixture-PRIVATE-new", "hysteria2")
        working(store, old)
        val appliedAt = store.summary().appliedAt
        receive(store, fresh)
        val prepared = store.prepare { error("legacy") }
        val starts = mutableListOf<String>()
        val accepted = ProfileLauncher.launch(prepared, fresh, { store.backupOnly() }, { it },
            { ProfileDocument.inspect(it, source) }, {
                starts.add(it)
                if (it == fresh) error("fixture start failure")
            })
        assertEquals(listOf(fresh, old), starts)
        assertTrue(accepted.prepared.backup)
        assertTrue(store.markApplied(accepted.prepared))
        assertEquals(appliedAt, store.summary().appliedAt)
        assertTrue(store.summary().pending)
        val report = ConnectionReport("fixture", "fixture", 35, 1, 2,
            node(old), node(accepted.assembled), listOf(10L, null, 30L), 0, null,
            EventSummary.from(listOf("EVENT [ядро] $source $fresh"))).json()
        val json = JSONObject(report)
        assertEquals("vless", json.getJSONObject("after").getString("protocol"))
        assertFalse(json.getBoolean("nodeChanged"))
        assertFalse(report.contains("PRIVATE"))
        assertFalse(report.contains("fixture-only"))
        assertFalse(report.contains("example.com"))
    }

    @Test fun lateStartCannotOverwriteTheReserveOfANewerReceivedCandidate() = runBlocking {
        val store = store()
        val old = document("old-fixture")
        working(store, old)
        receive(store, document("first-fixture"))
        val first = store.prepare { error("legacy") }
        val accepted = ProfileLauncher.launch(first, first.content, { store.backupOnly() }, { it }, {}, {
            receive(store, document("second-fixture"))
        })
        assertFalse(store.markApplied(accepted.prepared))
        assertEquals(old, store.backupOnly().content)
        assertEquals(document("second-fixture"), store.prepare { error("legacy") }.content)
    }

    @Test fun returnedSubscriptionCannotRestoreAnExpiredReserveOnFailedStart() = runBlocking {
        val store = store()
        working(store, document("expired-fixture"))
        store.blockEmpty()
        receive(store, document("returned-fixture"))
        val candidate = store.prepare { error("legacy") }
        val starts = mutableListOf<String>()
        try {
            ProfileLauncher.launch(candidate, candidate.content, { store.backupOnly() }, { it }, {}, {
                starts.add(it)
                error("fixture start failure")
            })
            fail("expired reserve must not be restored")
        } catch (e: ProfileFailure) {
            assertEquals(ProfileFailure.Kind.INVALID, e.kind)
        }
        assertEquals(listOf(candidate.content), starts)
        assertTrue(store.summary().pending)
        assertThrows(ProfileFailure::class.java) { store.backupOnly() }
    }

    @Test fun replacingSubscriptionCannotRollbackToAnotherSourcesReserve() = runBlocking {
        working(store(), document("source-a-fixture"))
        val changed = store(source + "-b")
        receive(changed, document("source-b-fixture"))
        val candidate = changed.prepare { error("legacy") }
        var starts = 0
        try {
            ProfileLauncher.launch(candidate, candidate.content, { changed.backupOnly() }, { it }, {}, {
                starts++
                error("fixture start failure")
            })
            fail("another source must not be restored")
        } catch (_: ProfileFailure) { }
        assertEquals(1, starts)
        assertEquals(0L, changed.summary().appliedAt)
    }

    @Test fun cancellationDoesNotPromoteCandidateOrTryTheReserve() = runBlocking {
        val store = store()
        val old = document("old-fixture")
        working(store, old)
        receive(store, document("new-fixture"))
        val candidate = store.prepare { error("legacy") }
        val before = files.toMap()
        var backups = 0
        try {
            ProfileLauncher.launch(candidate, candidate.content, { backups++; store.backupOnly() }, { it }, {}, {
                throw CancellationException("fixture")
            })
            fail("expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(0, backups)
        assertEquals(before, files)
        assertEquals(old, store.backupOnly().content)
    }
}
