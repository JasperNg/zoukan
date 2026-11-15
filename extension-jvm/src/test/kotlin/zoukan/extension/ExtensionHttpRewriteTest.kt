package zoukan.extension

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import suwayomi.tachidesk.manga.impl.util.BytecodeEditor
import xyz.nulldev.androidcompat.network.ExtensionHttp
import java.io.File
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Collections
import javax.tools.ToolProvider

/**
 * An extension's own `new OkHttpClient()` / `new OkHttpClient.Builder()` would resolve names with
 * the system's DNS, leaking each host name when DNS over HTTPS is on. The class loader rewrites
 * them into ExtensionHttp calls, whose clients use the app's DNS and are otherwise fresh clients.
 */
class ExtensionHttpRewriteTest {
    @TempDir
    lateinit var dir: File

    @AfterEach
    fun resetDns() = ExtensionHttp.setDnsProvider(null)

    private val source = """
        package gen;

        import okhttp3.OkHttpClient;

        public class Clients {
            public static class Holder {
                public final OkHttpClient client;
                public final String label;

                public Holder(OkHttpClient client, String label) {
                    this.client = client;
                    this.label = label;
                }
            }

            public static OkHttpClient plain() {
                return new OkHttpClient();
            }

            public static OkHttpClient built() {
                return new OkHttpClient.Builder().followRedirects(false).build();
            }

            // Constructor calls nested in another constructor's arguments
            public static Holder nested() {
                return new Holder(new OkHttpClient(), String.valueOf(new OkHttpClient.Builder().build().followRedirects()));
            }

            public static OkHttpClient derived(OkHttpClient base) {
                return base.newBuilder().build();
            }

            public static int discarded() {
                new OkHttpClient();
                return 1;
            }
        }
    """.trimIndent()

    /** The classes javac makes from [source], by jar path. */
    private fun compile(): Map<String, ByteArray> {
        val src = File(dir, "src/gen/Clients.java").apply {
            parentFile.mkdirs()
            writeText(source)
        }
        val out = File(dir, "classes").apply { mkdirs() }
        val compiler = ToolProvider.getSystemJavaCompiler().shouldNotBeNull()
        val result = compiler.run(
            null, null, null,
            "-classpath", System.getProperty("java.class.path"), "-d", out.path, "--release", "11", src.path,
        )
        result shouldBe 0
        return out.walkTopDown().filter { it.isFile }
            .associate { it.relativeTo(out).invariantSeparatorsPath to it.readBytes() }
    }

    private class RecordingDns : Dns {
        val lookups: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override fun lookup(hostname: String): List<InetAddress> {
            lookups += hostname
            throw UnknownHostException(hostname)
        }
    }

    private fun loadClients(): Class<*> {
        val rewritten = compile().mapValues { (_, bytes) -> BytecodeEditor.fixAndroidClass(bytes) }
        // No constructor call of either class is left
        rewritten.values.forEach { bytes ->
            val node = ClassNode().also { ClassReader(bytes).accept(it, 0) }
            node.methods.flatMap { it.instructions.toArray().toList() }.forEach { insn ->
                if (insn is TypeInsnNode && insn.opcode == Opcodes.NEW) {
                    insn.desc.startsWith("okhttp3/OkHttpClient") shouldBe false
                }
                if (insn is MethodInsnNode && insn.name == "<init>") {
                    insn.owner.startsWith("okhttp3/OkHttpClient") shouldBe false
                }
            }
        }
        return ExtensionClassLoader(JarContents(rewritten), "gen").loadClass("gen.Clients")
    }

    @Test
    fun `an extension's own clients resolve names with the app's DNS`() {
        val hook = RecordingDns()
        ExtensionHttp.setDnsProvider { hook }
        val clients = loadClients()

        val plain = clients.getMethod("plain").invoke(null) as OkHttpClient
        plain.dns shouldBeSameInstanceAs ExtensionHttp.DNS
        runCatching { plain.dns.lookup("plain.example") }
        // Otherwise a fresh client: none of the app's interceptors, cookies or cache
        plain.interceptors shouldBe emptyList()
        plain.networkInterceptors shouldBe emptyList()
        plain.cookieJar shouldBeSameInstanceAs CookieJar.NO_COOKIES
        plain.cache shouldBe null
        plain.followRedirects shouldBe true

        val built = clients.getMethod("built").invoke(null) as OkHttpClient
        built.dns shouldBeSameInstanceAs ExtensionHttp.DNS
        built.followRedirects shouldBe false
        runCatching { built.dns.lookup("built.example") }

        val holder = clients.getMethod("nested").invoke(null)
        val nested = holder.javaClass.getField("client").get(holder) as OkHttpClient
        nested.dns shouldBeSameInstanceAs ExtensionHttp.DNS
        holder.javaClass.getField("label").get(holder) shouldBe "true"
        runCatching { nested.dns.lookup("nested.example") }

        clients.getMethod("discarded").invoke(null) shouldBe 1

        hook.lookups shouldContainExactly listOf("plain.example", "built.example", "nested.example")
    }

    @Test
    fun `the app's own client passed to an extension keeps its DNS`() {
        val appDns = RecordingDns()
        val base = OkHttpClient.Builder().dns(appDns).build()
        val derived = loadClients().getMethod("derived", OkHttpClient::class.java).invoke(null, base) as OkHttpClient
        derived.dns shouldBeSameInstanceAs appDns
    }

    @Test
    fun `without the app's DNS the system's is used`() {
        ExtensionHttp.current() shouldBeSameInstanceAs Dns.SYSTEM
        ExtensionHttp.setDnsProvider { null }
        ExtensionHttp.current() shouldBeSameInstanceAs Dns.SYSTEM
    }

    @Test
    fun `the rewrite is idempotent`() {
        val once = compile().mapValues { (_, bytes) -> BytecodeEditor.fixAndroidClass(bytes) }
        val twice = once.mapValues { (_, bytes) -> BytecodeEditor.fixAndroidClass(bytes) }
        twice.keys shouldBe once.keys
        twice.forEach { (name, bytes) -> bytes.contentEquals(once.getValue(name)) shouldBe true }
    }
}
