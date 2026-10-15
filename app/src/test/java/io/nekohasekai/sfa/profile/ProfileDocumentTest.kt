package io.nekohasekai.sfa.profile

import org.junit.Assert.*
import org.junit.Test

class ProfileDocumentTest {
    private val source = "https://gethowl.app/sub.html?t=fixture-token"
    private val valid = """{"outbounds":[{"tag":"Howl","type":"selector","outbounds":["auto"]},{"tag":"auto","type":"urltest","outbounds":["node"]},{"tag":"node","type":"vless","server":"example.com"}]}"""
    private val empty = """{"outbounds":[{"tag":"Howl","type":"selector","outbounds":["direct"],"default":"direct"},{"tag":"direct","type":"direct"}],"route":{"final":"Howl"},"inbounds":[{"type":"tun"}],"dns":{"servers":[{"type":"local"}]}}"""

    @Test fun validHowlGraph() { assertFalse(ProfileDocument.inspect(valid, source)) }
    @Test fun canonicalEmptyIsDistinct() { assertTrue(ProfileDocument.inspect(empty, source)) }
    @Test fun bareObjectIsNotConfirmedExpiry() {
        assertThrows(Exception::class.java) { ProfileDocument.inspect("{}", source) }
    }
    @Test fun missingMemberRejected() {
        assertThrows(Exception::class.java) { ProfileDocument.inspect(valid.replace("outbounds\":[\"node", "outbounds\":[\"missing"), source) }
    }
    @Test fun cycleRejected() {
        assertThrows(Exception::class.java) { ProfileDocument.inspect(valid.replace("outbounds\":[\"node", "outbounds\":[\"Howl"), source) }
    }
    @Test fun duplicatePropertiesRejected() {
        assertThrows(Exception::class.java) { ProfileDocument.inspect("""{"outbounds":[],"outbounds":[]}""", source) }
    }
    @Test fun duplicateTagsRejected() {
        assertThrows(Exception::class.java) { ProfileDocument.inspect(valid.replace("\"tag\":\"node\"", "\"tag\":\"auto\""), source) }
    }
    @Test fun excessiveDepthAndTrailingContentRejected() {
        assertThrows(Exception::class.java) { ProfileDocument.inspect("[".repeat(65) + "0" + "]".repeat(65), source) }
        assertThrows(Exception::class.java) { ProfileDocument.inspect(valid + "garbage", source) }
    }
    @Test fun thirdPartyKeepsItsOwnSelector() {
        assertFalse(ProfileDocument.inspect(valid.replace("Howl", "custom"), "https://example.com/sub"))
    }
    @Test fun oversizedResponseRejected() {
        assertThrows(Exception::class.java) { ProfileDocument.inspect(" ".repeat(ProfileDocument.MAX_BYTES + 1), source) }
    }
}
