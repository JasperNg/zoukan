package eu.kanade.tachiyomi

import java.util.Properties

/**
 * Used by extensions. The desktop copy of the app module's AppInfo: the version comes from
 * app/build.gradle.kts at build time (zoukan-app-info.properties).
 */
@Suppress("UNUSED")
object AppInfo {
    private val properties by lazy {
        Properties().apply {
            AppInfo::class.java.getResourceAsStream("/zoukan-app-info.properties")?.use { load(it) }
        }
    }

    /**
     * Version code of the host application.
     *
     * @since extension-lib 1.3
     */
    fun getVersionCode(): Int = properties.getProperty("versionCode")?.toIntOrNull() ?: 0

    /**
     * Version name of the host application.
     *
     * @since extension-lib 1.3
     */
    fun getVersionName(): String = properties.getProperty("versionName") ?: "0"

    /**
     * Image MIME types the desktop runtime can decode.
     *
     * @since extension-lib 1.5
     */
    fun getSupportedImageMimeTypes(): List<String> = listOf("image/gif", "image/jpeg", "image/png", "image/webp")
}
