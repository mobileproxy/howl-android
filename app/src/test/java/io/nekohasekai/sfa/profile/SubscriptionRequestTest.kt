package io.nekohasekai.sfa.profile

import org.junit.Assert.*
import org.junit.Test

class SubscriptionRequestTest {
    @Test fun plainHowlLinkRequestsJson() {
        assertEquals("https://gethowl.app/sub.html?t=fixture&format=singbox",
            SubscriptionRequest.url("https://gethowl.app/sub.html?t=fixture"))
    }
    @Test fun existingFormatIsReplacedWithoutDuplicates() {
        assertEquals("https://vpn.how/sub.html?t=fixture&format=singbox",
            SubscriptionRequest.url("https://vpn.how/sub.html?format=base64&t=fixture&format=singbox"))
    }
    @Test fun encodedParameterNamesCannotLeaveAConflictingFormat() {
        assertEquals("https://gethowl.app/sub.html?t=a%2Bb%2F%3D&format=singbox#name",
            SubscriptionRequest.url("https://gethowl.app/sub.html?t=a%2Bb%2F%3D&%66ormat=base64#name"))
    }
    @Test fun additionalParametersAndAuthorityArePreserved() {
        assertEquals("https://GETHOWL.APP:443/sub.html?x=1&t=fixture&x=2&format=singbox",
            SubscriptionRequest.url("https://GETHOWL.APP:443/sub.html?x=1&t=fixture&x=2"))
    }
    @Test fun alreadyNormalizedLinkIsIdempotent() {
        val source = "https://gethowl.app/sub.html?t=fixture&format=singbox"
        assertEquals(source, SubscriptionRequest.url(SubscriptionRequest.url(source)))
    }
    @Test fun unrelatedEndpointsAndHostsAreUntouched() {
        for (source in listOf("https://example.com/sub.html?t=fixture", "https://gethowl.app/downloads/howl/latest.json",
            "https://gethowl.app.example.com/sub.html?t=fixture", "https://example.com/?next=https://gethowl.app/sub.html")) {
            assertEquals(source, SubscriptionRequest.url(source))
        }
    }
    @Test fun missingQueryIsSupported() {
        assertEquals("https://gethowl.app/sub.html?format=singbox", SubscriptionRequest.url("https://gethowl.app/sub.html"))
    }
}
