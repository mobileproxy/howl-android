package io.nekohasekai.sfa.bg

import io.nekohasekai.sfa.subscription.NodeSites
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Правила сторожа и карусели без телефона и сети — тот же расклад, что на Windows 1.0.27
 * (tests/Howl.Tests/WatchdogTests.cs): куда уводить с залипшего узла и когда проверять снова.
 */
class WatchdogRulesTest {

    private val deVless = "🛡 DE Франкфурт · VLESS"
    private val deHy2 = "⚡ DE Франкфурт · Hysteria2"
    private val deSs = "🌑 DE Франкфурт · Shadowsocks"
    private val deStls = "🎭 DE Франкфурт · ShadowTLS"
    private val deOc = "🔐 DE Франкфурт · OpenConnect"
    private val deAwg = "🐺 DE Франкфурт · AmneziaWG"
    private val fiVless = "🛡 FI Хельсинки · VLESS"
    private val fiHy2 = "⚡ FI Хельсинки · Hysteria2"
    private val fiSs = "🌑 FI Хельсинки · Shadowsocks"
    private val fiAwg = "🐺 FI Хельсинки · AmneziaWG"
    private val bgSs = "🌑 BG София · Shadowsocks"

    private fun out(type: String, tag: String, server: String? = null, detour: String? = null) =
        JSONObject().put("type", type).put("tag", tag).apply {
            if (server != null) put("server", server)
            if (detour != null) put("detour", detour)
        }

    /** Устройство живой подписки 04.10.2026: три локации, у ShadowTLS — скрытый набиратель. */
    private fun liveLikeConfig(): JSONObject {
        val de = "212.147.228.168"
        val fi = "80.47.225.51"
        val bg = "38.60.247.91"
        return JSONObject()
            .put(
                "outbounds",
                JSONArray()
                    .put(out("selector", "Howl"))
                    .put(out("urltest", "auto"))
                    .put(out("vless", deVless, de))
                    .put(out("hysteria2", deHy2, de))
                    .put(out("shadowsocks", deSs, de))
                    .put(out("shadowsocks", deStls, detour = "shadowtls-7734"))
                    .put(out("vless", fiVless, fi))
                    .put(out("hysteria2", fiHy2, fi))
                    .put(out("shadowsocks", fiSs, fi))
                    .put(out("shadowsocks", bgSs, bg))
                    .put(out("shadowtls", "shadowtls-7734", de))
                    .put(out("direct", "direct")),
            )
            .put(
                "endpoints",
                JSONArray()
                    .put(out("openconnect", deOc, "7734.vpn.how:738"))
                    .put(
                        JSONObject().put("type", "wireguard").put("tag", deAwg)
                            .put("peers", JSONArray().put(JSONObject().put("address", de).put("port", 51820))),
                    )
                    .put(
                        JSONObject().put("type", "wireguard").put("tag", fiAwg)
                            .put("peers", JSONArray().put(JSONObject().put("address", fi).put("port", 51820))),
                    ),
            )
    }

    private val sites by lazy { NodeSites.parse(liveLikeConfig()) }

    // ── Где стоит каждый выход ──

    @Test
    fun vlessHasServerAddress() {
        assertEquals(NodeSites.Site("212.147.228.168", "vless"), sites[deVless])
    }

    @Test
    fun shadowTlsTakesDialerAddressAndType() {
        assertEquals(NodeSites.Site("212.147.228.168", "shadowtls"), sites[deStls])
    }

    @Test
    fun openConnectHostnameMapsToLocationAddress() {
        assertEquals("212.147.228.168", sites[deOc]?.host)
    }

    @Test
    fun amneziaWgAddressFromPeers() {
        assertEquals(NodeSites.Site("212.147.228.168", "wireguard"), sites[deAwg])
    }

    @Test
    fun serviceOutboundsAreSkipped() {
        assertFalse(sites.containsKey("Howl"))
        assertFalse(sites.containsKey("auto"))
        assertFalse(sites.containsKey("direct"))
    }

    @Test
    fun hostOnlyAndLabel() {
        assertEquals("::1", NodeSites.hostOnly("[::1]:443"))
        assertEquals("2001:db8::1", NodeSites.hostOnly("2001:db8::1"))
        assertEquals("h.example", NodeSites.hostOnly("https://h.example:738/x"))
        assertEquals("DE Франкфурт", NodeSites.label(deVless))
        assertEquals("", NodeSites.label("plain tag"))
        assertTrue(NodeSites.isIpLiteral("80.47.225.51"))
        assertFalse(NodeSites.isIpLiteral("7734.vpn.how"))
        assertFalse(NodeSites.isIpLiteral("300.1.1.1"))
    }

    // ── Карусель: сперва другой сервер, потом другой протокол ──

    @Test
    fun stuckVlessGoesToAnotherServerEvenIfNeighbourIsFaster() {
        // 04.10 11:43 (Windows): залип VLESS Франкфурт, самым быстрым был сосед по адресу.
        val pick = WatchdogRules.order(
            listOf(deSs to 253, deHy2 to 300, fiHy2 to 400, fiVless to 380),
            sites,
            listOf(deVless),
        )
        assertEquals(fiHy2, pick[0])
        assertTrue(pick.indexOf(fiHy2) < pick.indexOf(fiVless))
        assertEquals(4, pick.size)
        assertTrue(pick.indexOf(deSs) >= 2 && pick.indexOf(deHy2) >= 2)
    }

    @Test
    fun failedProtocolGoesAfterOtherProtocols() {
        // Второй уход того дня: Shadowsocks Франкфурт → Shadowsocks Хельсинки.
        val pick = WatchdogRules.order(
            listOf(fiSs to 152, fiHy2 to 400, bgSs to 300),
            sites,
            listOf(deVless, deSs),
        )
        assertEquals(fiHy2, pick[0])
    }

    @Test
    fun vlessAndAmneziaWgAreDifferentProtocols() {
        // Старая карусель сравнивала первый символ значка: 🛡 и 🐺 начинаются с одной половинки
        // суррогатной пары — для неё VLESS и AmneziaWG были одним протоколом.
        val pick = WatchdogRules.order(listOf(fiVless to 100, fiAwg to 300), sites, listOf(deVless))
        assertEquals(fiAwg, pick[0])
    }

    @Test
    fun onlyNeighboursAliveTakesFastest() {
        val pick = WatchdogRules.order(listOf(deHy2 to 900, deSs to 200), sites, listOf(deVless))
        assertEquals(deSs, pick[0])
    }

    @Test
    fun withoutSiteInfoOrdersByDelayUnmeasuredLast() {
        val pick = WatchdogRules.order(listOf("b" to 300, "a" to 100, "c" to 0), emptyMap(), listOf("x"))
        assertEquals(listOf("a", "b", "c"), pick)
    }

    @Test
    fun blindRoundGoesToAnotherServerFirst() {
        val pick = WatchdogRules.order(listOf(deHy2 to 0, fiHy2 to 0, deSs to 0), sites, listOf(deVless))
        assertEquals(fiHy2, pick[0])
    }

    // ── Промежутки между проверками ──

    @Test
    fun firstCheckNotEarlierThanTenSeconds() {
        assertEquals(9_000L, WatchdogRules.firstCheckWaitMs(1_000L))
        assertEquals(0L, WatchdogRules.firstCheckWaitMs(15_000L))
    }

    @Test
    fun checksAreSpacedWhileFailing() {
        // Сторож только что запущен — пропускаем.
        assertTrue(WatchdogRules.skipCheck(2_000L, 60_000L, 60_000L, failing = false))
        // Идёт сбой, прошлая проверка закончилась секунду назад — пропускаем.
        assertTrue(WatchdogRules.skipCheck(60_000L, 20_000L, 1_000L, failing = true))
        // Идёт сбой, прошло 12 с — проверяем.
        assertFalse(WatchdogRules.skipCheck(60_000L, 30_000L, 12_000L, failing = true))
        // Связь бодрая, новая сеть — проверяем сразу (если не совпало с другой проверкой).
        assertFalse(WatchdogRules.skipCheck(60_000L, 20_000L, 1_000L, failing = false))
        // Совпали будильник и цикл — второй прогон пропускаем.
        assertTrue(WatchdogRules.skipCheck(60_000L, 2_000L, 60_000L, failing = false))
    }
}
