package io.nekohasekai.sfa.profile

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Base64

class ProfileValidationTest {
    private val source = "https://gethowl.app/sub.html?t=fixture-only"
    private val valid = """{"outbounds":[{"tag":"Howl","type":"selector","outbounds":["node"]},{"tag":"node","type":"vless","server":"example.com"}]}"""
    private val empty = """{"outbounds":[{"tag":"Howl","type":"selector","outbounds":["direct"],"default":"direct"},{"tag":"direct","type":"direct"}],"route":{"final":"Howl"},"inbounds":[{"type":"tun"}],"dns":{"servers":[{"type":"local"}]}}"""
    private val uris = "vless://fixture@example.com:443#fixture"

    @Test fun base64HowlResponseReachesConverterBeforeJsonValidation() {
        val raw = Base64.getEncoder().encodeToString(uris.toByteArray())
        var checks = 0
        val result = ProfileValidation.validate(raw, source, {
            assertEquals(uris, String(Base64.getDecoder().decode(it)))
            valid
        }, { assertEquals(valid, it); checks++ })
        assertEquals(valid, result)
        assertEquals(1, checks)
    }
    @Test fun plainUriResponseCanAlsoBeConverted() {
        assertEquals(valid, ProfileValidation.validate(uris, source, { assertEquals(uris, it); valid }, {}))
    }
    @Test fun jsonProfileIsPreservedAndChecked() {
        assertEquals(valid, ProfileValidation.validate(valid, source, { it }, { assertEquals(valid, it) }))
    }
    @Test fun malformedOrAmbiguousOwnJsonCannotBeRepairedByConverter() {
        for (raw in listOf("{}", valid + "garbage", """{"outbounds":[],"outbounds":[]}""")) {
            assertThrows(Exception::class.java) {
                ProfileValidation.validate(raw, source, { fail("must reject before converter"); valid }, {})
            }
        }
    }
    @Test fun confirmedEmptyStillBlocksBeforeConversion() {
        val error = assertThrows(ProfileFailure::class.java) {
            ProfileValidation.validate(empty, source, { fail("must not convert expired response"); valid }, {})
        }
        assertEquals(ProfileFailure.Kind.EMPTY, error.kind)
    }
    @Test fun convertedEmptyCannotBecomeAWorkingProfile() {
        val error = assertThrows(ProfileFailure::class.java) { ProfileValidation.validate(uris, source, { empty }, {}) }
        assertEquals(ProfileFailure.Kind.EMPTY, error.kind)
    }
    @Test fun convertedGraphAndNativeErrorsStillRejectTheCandidate() {
        assertThrows(Exception::class.java) { ProfileValidation.validate(uris, source, { "{}" }, {}) }
        assertThrows(Exception::class.java) { ProfileValidation.validate(uris, source, { valid }, { error("native rejection") }) }
    }
    @Test fun thirdPartySelectorsAreUnchanged() {
        val thirdParty = valid.replace("Howl", "custom")
        assertEquals(thirdParty, ProfileValidation.validate(uris, "https://example.com/sub", { thirdParty }, {}))
    }
    @Test fun oversizedResponseIsRejectedBeforeConversion() {
        assertThrows(Exception::class.java) {
            ProfileValidation.validate(" ".repeat(ProfileDocument.MAX_BYTES + 1), source,
                { fail("too large"); valid }, {})
        }
    }
    @Test fun rejectedUpdatePreservesWorkingReserveAndDates() {
        val files = mutableMapOf<String, String>()
        val store = SafeProfileStore(File("fixture.safe"), source, { files[it.path] },
            { file, content -> files[file.path] = content },
            { raw -> ProfileValidation.validate(raw, source, { if (it == uris) valid else it }, {}) }, { 100L })
        store.receive(uris)
        val prepared = store.prepare { error("no legacy read") }
        assertTrue(store.markApplied(prepared))
        val before = files.toMap()
        val summary = store.summary()
        assertThrows(Exception::class.java) { store.receive("<html>error</html>") }
        assertEquals(before, files)
        assertEquals(summary, store.summary())
        assertEquals(valid, store.backupOnly().content)
    }
}
