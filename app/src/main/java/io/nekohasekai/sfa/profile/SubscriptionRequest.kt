package io.nekohasekai.sfa.profile

import java.net.URI
import java.net.URLDecoder

object SubscriptionRequest {
    /** Keep the stored source URL unchanged; request the complete native Howl profile. */
    fun url(source: String): String {
        if (!ProfileDocument.isOwnSource(source)) return source
        val uri = URI(source)
        val query = uri.rawQuery.orEmpty().split('&').filter { part ->
            part.isNotEmpty() && URLDecoder.decode(part.substringBefore('='), "UTF-8") != "format"
        }.plus("format=singbox").joinToString("&")
        return "${uri.scheme}://${uri.rawAuthority}${uri.rawPath}?$query" +
            (uri.rawFragment?.let { "#$it" } ?: "")
    }
}
