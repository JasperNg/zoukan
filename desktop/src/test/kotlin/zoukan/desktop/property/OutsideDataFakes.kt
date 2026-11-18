package zoukan.desktop.property

import eu.kanade.tachiyomi.network.NetworkHelper
import io.mockk.every
import io.mockk.mockk
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.atomic.AtomicInteger

/** An HTTP client that never touches the network: [answer] gives each request's body, or null for a 404. */
class FakeHttp(private val answer: (request: Request, count: Int) -> ByteArray?) {
    val requests = AtomicInteger()

    val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val body = answer(chain.request(), requests.incrementAndGet())
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(if (body == null) 404 else 200)
                .message(if (body == null) "Not Found" else "OK")
                .body((body ?: ByteArray(0)).toResponseBody("application/octet-stream".toMediaType()))
                .build()
        }
        .build()

    val network: NetworkHelper = mockk { every { client } returns this@FakeHttp.client }
}

/** Valid documents of each outside format, to break in [Mutations]. */
object ValidDocuments {
    val comicInfo: String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <ComicInfo xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
          <Title>Chapter 12.5</Title>
          <Series>A Title &amp; More</Series>
          <Number>12.5</Number>
          <Summary>Line one
        line two</Summary>
          <Writer>Someone</Writer>
          <Penciller>A, B</Penciller>
          <Genre>Action, Drama</Genre>
          <Translator>Group</Translator>
          <ty:PublishingStatusTachiyomi xmlns:ty="http://www.w3.org/2001/XMLSchema">Ongoing</ty:PublishingStatusTachiyomi>
        </ComicInfo>
    """.trimIndent()

    val storeIndex: String = """
        {"name":"Store","badgeLabel":"ST","signingKey":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
         "contact":{"website":"https://store.test","discord":null},
         "extensionList":{"extensions":[{"name":"Tachiyomi: Example","packageName":"eu.kanade.tachiyomi.extension.en.example",
           "resources":{"apkUrl":"https://store.test/apk/example.apk","iconUrl":"https://store.test/icon/example.png","jarUrl":"https://store.test/jar/example.jar"},
           "extensionLib":"1.4","versionCode":12,"versionName":"1.4.12","contentWarning":"CONTENT_WARNING_SAFE",
           "sources":[{"id":1234567890123,"name":"Example","language":"en","homeUrl":"https://example.test","mirrorUrls":["https://m.example.test"]}]}]},
         "extensionListUrl":null}
    """.trimIndent()

    val legacyRepo: String = """
        {"meta":{"name":"Store","shortName":"ST","website":"https://store.test","signingKeyFingerprint":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"}}
    """.trimIndent()

    val legacyIndex: String = """
        [{"name":"Tachiyomi: Example","pkg":"eu.kanade.tachiyomi.extension.en.example","apk":"example-v1.4.12.apk","lang":"en","code":12,
          "version":"1.4.12","nsfw":0,"sources":[{"id":1234567890123,"lang":"en","name":"Example","baseUrl":"https://example.test"}]}]
    """.trimIndent()

    val releases: String = """
        [{"tag_name":"desktop-v9.9.9","name":"Zoukan 9.9.9","body":"Notes","draft":false,"prerelease":false,
          "html_url":"https://github.test/o/r/releases/tag/desktop-v9.9.9",
          "assets":[{"name":"Zoukan-9.9.9.msi","url":"https://api.github.com/repos/o/r/releases/assets/1","size":123,"digest":"sha256:00ff"},
                    {"name":"Zoukan-9.9.9.msi.sig","url":"https://api.github.com/repos/o/r/releases/assets/2","size":64},
                    {"name":"Zoukan-9.9.9-arm64.dmg","url":"https://api.github.com/repos/o/r/releases/assets/3","size":456}]},
         {"tag_name":"desktop-v1.0","draft":true,"assets":[]}]
    """.trimIndent()

    val release: String = """
        {"tag_name":"desktop-v9.9.9","name":"Zoukan 9.9.9","body":"Notes","html_url":"https://github.test/o/r/releases/tag/desktop-v9.9.9","assets":[]}
    """.trimIndent()
}
