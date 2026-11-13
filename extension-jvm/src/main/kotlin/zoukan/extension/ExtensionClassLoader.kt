package zoukan.extension

import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.util.Collections
import java.util.Enumeration
import java.util.zip.ZipInputStream

/**
 * The files of an extension jar, read into memory once. The loader checks these bytes (the
 * signature of a store's jar, or the recorded hash of a converted one) and then runs these same
 * bytes, so a jar swapped on disk after the check never runs. Nothing keeps the jar file open, so
 * on Windows it can be replaced or deleted while the extension is loaded.
 *
 * Directories and the JAR signature files are left out: the class loader has no use for them.
 */
class JarContents(val entries: Map<String, ByteArray>) {

    operator fun get(name: String): ByteArray? = entries[name]

    companion object {
        /** Reads a jar from [bytes] without any signature check (for converted jars, checked by hash). */
        fun fromBytes(bytes: ByteArray): JarContents {
            val entries = LinkedHashMap<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                generateSequence { zip.nextEntry }
                    .filterNot { it.isDirectory || ExtensionSignatures.isSigningRelated(it.name) }
                    .forEach { entry -> entries[entry.name] = zip.readAllBytes() }
            }
            return JarContents(entries)
        }
    }
}

/**
 * Old code of an extension whose class loader was closed (the extension was uninstalled or updated)
 * needed a class or resource it had not loaded yet. An ordinary exception, so the call fails like a
 * network error would; see [ExtensionClassLoader].
 */
class ExtensionUnloadedException(message: String) : IOException(message)

/**
 * Loads an extension's classes and resources from its [JarContents], with the same lookup order as
 * Suwayomi's ChildFirstURLClassLoader that it replaces: the app's class path first (so
 * extensions-lib, OkHttp and the Android compatibility layer are the host's), then the extension's
 * own files.
 *
 * [close] drops the jar's bytes. Classes already defined keep working; a later load of one of the
 * extension's own classes throws [ExtensionUnloadedException], and its resources are not found.
 * Nothing blocks. Closing is idempotent and thread-safe.
 *
 * Old source objects can still be running after the close (a download, the reader, a library
 * update, browsing, an OkHttp interceptor). When such code links a class it had not loaded yet, the
 * JVM calls [loadClass]. A [ClassNotFoundException] there would become a NoClassDefFoundError, an
 * Error that the app's `catch (e: Exception)` blocks miss, so the call could crash or leave a
 * spinner running. Any other exception reaches the calling code unchanged (the JVM wraps only
 * ClassNotFoundException), and a failure that is not a LinkageError is not remembered, so
 * [ExtensionUnloadedException], an IOException, reaches that code as an ordinary failure: the
 * error states, toasts and retries that handle a network error handle it too, and OkHttp reports it
 * to an async call's callback instead of throwing it on its dispatcher thread.
 */
class ExtensionClassLoader(
    contents: JarContents,
    /** For messages and resource addresses: the extension's package name. */
    private val label: String,
) : ClassLoader("extension:$label", null), Closeable {

    @Volatile
    private var contents: JarContents? = contents

    /** For tests: runs once, when the loader is closed. */
    @Volatile
    internal var onClose: (() -> Unit)? = null

    val isClosed: Boolean get() = contents == null

    private val system: ClassLoader = getSystemClassLoader()

    override fun close() {
        val hook = synchronized(this) {
            if (contents == null) return
            contents = null
            onClose.also { onClose = null }
        }
        hook?.invoke()
    }

    /** Shown to the user as the call's error, so it names the extension, not the class or file. */
    private fun closedMessage() =
        "The extension $label was uninstalled or updated; reopen the screen"

    override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
        val c = findLoadedClass(name)
            ?: try {
                system.loadClass(name)
            } catch (_: ClassNotFoundException) {
                null
            }
            ?: findClass(name)
        if (resolve) resolveClass(c)
        c
    }

    override fun findClass(name: String): Class<*> {
        val current = contents ?: throw ExtensionUnloadedException(closedMessage())
        val bytes = current[name.replace('.', '/') + ".class"] ?: throw ClassNotFoundException(name)
        return defineClass(name, bytes, 0, bytes.size)
    }

    override fun getResource(name: String): URL? = system.getResource(name) ?: findResource(name)

    override fun getResources(name: String): Enumeration<URL> {
        val urls = Collections.list(system.getResources(name)) + Collections.list(findResources(name))
        return Collections.enumeration(urls)
    }

    override fun getResourceAsStream(name: String): InputStream? =
        system.getResourceAsStream(name) ?: contents?.get(name.removePrefix("/"))?.let(::ByteArrayInputStream)

    override fun findResource(name: String): URL? {
        val path = name.removePrefix("/")
        if (contents?.get(path) == null) return null
        val handler = object : URLStreamHandler() {
            override fun openConnection(u: URL): URLConnection = object : URLConnection(u) {
                // Looked up when opened, so an address handed out before close() fails then
                private fun bytes(): ByteArray =
                    contents?.get(path) ?: throw ExtensionUnloadedException(closedMessage())
                override fun connect() {
                    bytes()
                }
                override fun getInputStream(): InputStream = ByteArrayInputStream(bytes())
                override fun getContentLengthLong(): Long = contents?.get(path)?.size?.toLong() ?: -1
            }
        }
        return URL.of(URI(SCHEME, null, "/$label/$path", null), handler)
    }

    override fun findResources(name: String): Enumeration<URL> =
        Collections.enumeration(listOfNotNull(findResource(name)))

    private companion object {
        const val SCHEME = "zoukan-extension"
    }
}
