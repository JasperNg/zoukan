package zoukan.desktop.browser

import eu.kanade.tachiyomi.network.DOH_PROVIDERS
import eu.kanade.tachiyomi.network.PREF_DOH_360
import eu.kanade.tachiyomi.network.PREF_DOH_ADGUARD
import eu.kanade.tachiyomi.network.PREF_DOH_ALIDNS
import eu.kanade.tachiyomi.network.PREF_DOH_CLOUDFLARE
import eu.kanade.tachiyomi.network.PREF_DOH_CONTROLD
import eu.kanade.tachiyomi.network.PREF_DOH_DNSPOD
import eu.kanade.tachiyomi.network.PREF_DOH_GOOGLE
import eu.kanade.tachiyomi.network.PREF_DOH_MULLVAD
import eu.kanade.tachiyomi.network.PREF_DOH_NJALLA
import eu.kanade.tachiyomi.network.PREF_DOH_QUAD101
import eu.kanade.tachiyomi.network.PREF_DOH_QUAD9
import eu.kanade.tachiyomi.network.PREF_DOH_SHECAN
import eu.kanade.tachiyomi.network.doh360
import eu.kanade.tachiyomi.network.dohAdGuard
import eu.kanade.tachiyomi.network.dohAliDNS
import eu.kanade.tachiyomi.network.dohCloudflare
import eu.kanade.tachiyomi.network.dohControlD
import eu.kanade.tachiyomi.network.dohDNSPod
import eu.kanade.tachiyomi.network.dohGoogle
import eu.kanade.tachiyomi.network.dohMullvad
import eu.kanade.tachiyomi.network.dohNajalla
import eu.kanade.tachiyomi.network.dohProviderUrl
import eu.kanade.tachiyomi.network.dohQuad9
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The DNS over HTTPS preference as Chromium gets it. */
class ChromiumDnsTest {

    @Test
    fun `each provider's template is the server OkHttp uses`() {
        val okHttp: Map<Int, OkHttpClient.Builder.() -> OkHttpClient.Builder> = mapOf(
            PREF_DOH_CLOUDFLARE to { dohCloudflare() },
            PREF_DOH_GOOGLE to { dohGoogle() },
            PREF_DOH_ADGUARD to { dohAdGuard() },
            PREF_DOH_QUAD9 to { dohQuad9() },
            PREF_DOH_ALIDNS to { dohAliDNS() },
            PREF_DOH_DNSPOD to { dohDNSPod() },
            PREF_DOH_360 to { doh360() },
            PREF_DOH_MULLVAD to { dohMullvad() },
            PREF_DOH_CONTROLD to { dohControlD() },
            PREF_DOH_NJALLA to { dohNajalla() },
        )
        okHttp.forEach { (provider, configure) ->
            val dns = OkHttpClient.Builder().configure().build().dns as DnsOverHttps
            dohProviderUrl(provider) shouldBe dns.url.toString()
        }
        okHttp.keys.toList() shouldBe DOH_PROVIDERS
        // The removed providers (Quad101, Shecan) are read as Cloudflare, never as off
        dohProviderUrl(PREF_DOH_QUAD101) shouldBe dohProviderUrl(PREF_DOH_CLOUDFLARE)
        dohProviderUrl(PREF_DOH_SHECAN) shouldBe dohProviderUrl(PREF_DOH_CLOUDFLARE)
        dohProviderUrl(-1) shouldBe null
        dohProviderUrl(99) shouldBe null
    }

    @Test
    fun `a provider sets secure mode with its template`() {
        val updated = ChromiumDns.updateLocalState(null, "https://dns.google/dns-query")!!
        val group = Json.parseToJsonElement(updated).jsonObject["dns_over_https"]!!.jsonObject
        group["mode"]!!.jsonPrimitive.content shouldBe "secure"
        group["templates"]!!.jsonPrimitive.content shouldBe "https://dns.google/dns-query"
    }

    @Test
    fun `other local state is kept`() {
        val localState = """{"browser":{"enabled_labs_experiments":[]},"dns_over_https":{"other":1},"n":"123"}"""
        val updated = Json.parseToJsonElement(
            ChromiumDns.updateLocalState(localState, "https://dns.quad9.net/dns-query")!!,
        ).jsonObject
        updated["browser"] shouldBe Json.parseToJsonElement(localState).jsonObject["browser"]
        updated["n"]!!.jsonPrimitive.content shouldBe "123"
        updated["dns_over_https"]!!.jsonObject["other"]!!.jsonPrimitive.content shouldBe "1"

        // Turning DoH off again leaves only what Chromium had
        val off = Json.parseToJsonElement(ChromiumDns.updateLocalState(updated.toString(), null)!!)
        off shouldBe Json.parseToJsonElement(localState)
    }

    @Test
    fun `off removes the group it leaves empty, and nothing changes when nothing differs`() {
        val on = ChromiumDns.updateLocalState("{}", "https://dns.google/dns-query")!!
        Json.parseToJsonElement(ChromiumDns.updateLocalState(on, null)!!) shouldBe JsonObject(emptyMap())
        ChromiumDns.updateLocalState(on, "https://dns.google/dns-query") shouldBe null
        ChromiumDns.updateLocalState("{}", null) shouldBe null
        ChromiumDns.updateLocalState(null, null) shouldBe null
    }

    @Test
    fun `an unreadable local state is left alone`() {
        ChromiumDns.updateLocalState("not json", "https://dns.google/dns-query") shouldBe null
        ChromiumDns.updateLocalState("[1]", "https://dns.google/dns-query") shouldBe null
    }

    @Test
    fun `apply with DoH on and a non-JSON Local State still sets secure mode`(@TempDir dir: File) {
        File(dir, "Local State").writeText("{ damaged")
        ChromiumDns.apply(dir, PREF_DOH_CLOUDFLARE)
        val group = Json.parseToJsonElement(File(dir, "Local State").readText()).jsonObject["dns_over_https"]!!.jsonObject
        group["mode"]!!.jsonPrimitive.content shouldBe "secure"
        group["templates"]!!.jsonPrimitive.content shouldBe "https://cloudflare-dns.com/dns-query"
        // The damaged file is kept beside it, not deleted
        File(dir, "Local State.zoukan-bad").readText() shouldBe "{ damaged"
    }

    @Test
    fun `apply with DoH off leaves a non-JSON Local State alone`(@TempDir dir: File) {
        File(dir, "Local State").writeText("{ damaged")
        ChromiumDns.apply(dir, -1)
        File(dir, "Local State").readText() shouldBe "{ damaged"
        File(dir, "Local State.zoukan-bad").exists() shouldBe false
    }

    @Test
    fun `apply writes the Local State file`(@TempDir dir: File) {
        ChromiumDns.apply(dir, PREF_DOH_CLOUDFLARE)
        val file = File(dir, "Local State")
        Json.parseToJsonElement(file.readText()).jsonObject["dns_over_https"]!!.jsonObject["templates"]!!
            .jsonPrimitive.content shouldBe "https://cloudflare-dns.com/dns-query"
        ChromiumDns.apply(dir, -1)
        Json.parseToJsonElement(file.readText()) shouldBe JsonObject(emptyMap())
        dir.listFiles()!!.map { it.name } shouldBe listOf("Local State")
    }
}
