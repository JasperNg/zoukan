package zoukan.desktop.network

import eu.kanade.tachiyomi.network.interceptor.ClearanceUserAgents
import io.kotest.matchers.shouldBe
import okhttp3.Cookie
import org.junit.jupiter.api.Test

/** Which user agent a request to a host with a cf_clearance cookie is sent with. */
class ClearanceUserAgentsTest {
    private val extensionUa = "Mozilla/5.0 (Linux; Android 10; K) Mobile"
    private val chromiumUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/152.0.0.0"

    private class MapStore : ClearanceUserAgents.Store {
        val map = mutableMapOf<String, String>()
        override fun get(key: String): String? = map[key]
        override fun put(key: String, value: String) {
            map[key] = value
        }
    }

    private fun clearance(value: String, domain: String = "example.org") =
        Cookie.Builder().name("cf_clearance").value(value).domain(domain).build()

    @Test
    fun `a clearance solved with the request's user agent keeps it`() {
        ClearanceUserAgents.overrideFor(extensionUa, extensionUa, chromiumUa) shouldBe null
    }

    @Test
    fun `a clearance solved with Chromium's user agent replaces the request's`() {
        ClearanceUserAgents.overrideFor(extensionUa, chromiumUa, chromiumUa) shouldBe chromiumUa
    }

    @Test
    fun `a clearance solved with another extension's user agent replaces the request's`() {
        ClearanceUserAgents.overrideFor(chromiumUa, extensionUa, chromiumUa) shouldBe extensionUa
    }

    @Test
    fun `a clearance of unknown origin is taken as solved with Chromium's user agent`() {
        ClearanceUserAgents.overrideFor(extensionUa, null, chromiumUa) shouldBe chromiumUa
        ClearanceUserAgents.overrideFor(chromiumUa, null, chromiumUa) shouldBe null
        // Without Chromium nothing is replaced
        ClearanceUserAgents.overrideFor(extensionUa, null, null) shouldBe null
    }

    @Test
    fun `challenges try the request's user agent first, then Chromium's`() {
        ClearanceUserAgents.solveOrder(extensionUa, chromiumUa) shouldBe listOf(extensionUa, chromiumUa)
        ClearanceUserAgents.solveOrder(chromiumUa, chromiumUa) shouldBe listOf(chromiumUa)
        ClearanceUserAgents.solveOrder(extensionUa, null) shouldBe listOf(extensionUa)
    }

    @Test
    fun `a clearance obtained in the browser window keeps the window's user agent`() {
        val agents = ClearanceUserAgents(MapStore())
        val cookie = clearance("solved-by-hand")
        agents.recordIfUnknown(cookie, extensionUa) // the window ran with the source's user agent
        ClearanceUserAgents.overrideFor(extensionUa, agents.userAgentOf(cookie), chromiumUa) shouldBe null
    }

    @Test
    fun `a browser window does not replace a known record`() {
        val agents = ClearanceUserAgents(MapStore())
        val cookie = clearance("solved-headless")
        agents.record(cookie, chromiumUa)
        agents.recordIfUnknown(cookie, extensionUa)
        agents.userAgentOf(cookie) shouldBe chromiumUa
    }

    @Test
    fun `the recorded user agent belongs to the cookie it was recorded for`() {
        val store = MapStore()
        val records = ClearanceUserAgents(store)
        val cookie = clearance("first")
        records.userAgentOf(cookie) shouldBe null

        records.record(cookie, extensionUa)
        records.userAgentOf(cookie) shouldBe extensionUa
        // Survives a restart: a new instance over the same store
        ClearanceUserAgents(store).userAgentOf(cookie) shouldBe extensionUa

        // A new clearance (expired, cleared, solved again elsewhere) has no record yet
        records.userAgentOf(clearance("second")) shouldBe null
        // Nor does another domain's
        records.userAgentOf(clearance("first", domain = "other.example")) shouldBe null

        records.record(clearance("second"), chromiumUa)
        records.userAgentOf(clearance("second")) shouldBe chromiumUa
        records.userAgentOf(cookie) shouldBe null
        // The cookie's value is not stored in the clear
        store.map.values.any { "second" in it } shouldBe false
    }
}
