package xyz.nulldev.androidcompat.info

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import android.preference.PreferenceManager
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import xyz.nulldev.ts.config.CONFIG_PREFIX
import java.io.File

/**
 * What an extension learns about the device and the app: an Android 14 phone (user agents name a
 * real device, and SDK_INT checks take the modern branch), and the default SharedPreferences named
 * as on Android, with the values earlier versions kept under the package name alone moved over.
 */
class AndroidIdentityTest {

    @TempDir
    lateinit var root: File

    private val property = "$CONFIG_PREFIX.server.rootDir"
    private var previousRoot: String? = null

    @BeforeEach
    fun setRoot() {
        previousRoot = System.getProperty(property)
        System.setProperty(property, root.absolutePath)
    }

    @AfterEach
    fun restoreRoot() {
        previousRoot?.let { System.setProperty(property, it) } ?: System.clearProperty(property)
    }

    @Test
    fun `Build reports an Android 14 device`() {
        Build.VERSION.SDK_INT shouldBe 34
        Build.VERSION.SDK_INT shouldBe Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        Build.VERSION.RELEASE shouldBe "14"
        Build.VERSION.CODENAME shouldBe "REL"
        // As extensions build a user agent
        val ua = "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL}) AppleWebKit/537.36"
        ua shouldNotContain "null"
        listOf(Build.MANUFACTURER, Build.BRAND, Build.DEVICE, Build.PRODUCT, Build.FINGERPRINT, Build.ID)
            .forEach { (it?.isNotBlank() ?: false) shouldBe true }
        Build.SUPPORTED_ABIS.isNotEmpty() shouldBe true
    }

    @Test
    fun `the version codes match Android's`() {
        Build.VERSION_CODES.M shouldBe 23
        Build.VERSION_CODES.N shouldBe 24
        Build.VERSION_CODES.N_MR1 shouldBe 25
        Build.VERSION_CODES.O shouldBe 26
        Build.VERSION_CODES.O_MR1 shouldBe 27
        Build.VERSION_CODES.P shouldBe 28
        Build.VERSION_CODES.Q shouldBe 29
        Build.VERSION_CODES.R shouldBe 30
        Build.VERSION_CODES.S shouldBe 31
        Build.VERSION_CODES.S_V2 shouldBe 32
        Build.VERSION_CODES.TIRAMISU shouldBe 33
        Build.VERSION_CODES.VANILLA_ICE_CREAM shouldBe 35
    }

    @Test
    fun `SDK_INT is read at run time, not inlined`() {
        // A compile-time constant has a ConstantValue attribute and javac copies it into callers;
        // Android's SDK_INT is not one, and extensions compiled against android.jar read the field
        val field = Build.VERSION::class.java.getField("SDK_INT")
        val bytes = Build.VERSION::class.java.getResourceAsStream("Build\$VERSION.class")!!.readAllBytes()
        String(bytes, Charsets.ISO_8859_1).contains("ConstantValue") shouldBe false
        field.getInt(null) shouldBe 34
    }

    /** A context with the package name app.komikku and the app's real SharedPreferences files. */
    private class AppContext : ContextWrapper(null) {
        private val opened = HashMap<String, JavaSharedPreferences>()
        override fun getPackageName() = "app.komikku"
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            opened.getOrPut(name) { JavaSharedPreferences(name) }
        override fun getSharedPreferencesPath(name: String): File = JavaSharedPreferences.fileFor(name)
        override fun deleteSharedPreferences(name: String): Boolean {
            opened.remove(name)?.deleteAll()
            return !getSharedPreferencesPath(name).exists() || getSharedPreferencesPath(name).delete()
        }
    }

    @Test
    fun `default SharedPreferences are app_komikku_preferences, and the old file moves over once`() {
        // What earlier versions wrote under the package name alone
        JavaSharedPreferences("app.komikku").edit()
            .putString("legacy_only", "kept")
            .putString("both", "old")
            .putInt("count", 3)
            .commit()
        val legacyBytes = JavaSharedPreferences.fileFor("app.komikku").readBytes()
        // The app's own settings already in the default file win
        JavaSharedPreferences("app.komikku_preferences").edit().putString("both", "new").commit()

        val context: Context = AppContext()
        PreferenceManager.getDefaultSharedPreferencesName(context) shouldBe "app.komikku_preferences"
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        prefs.getString("legacy_only", null) shouldBe "kept"
        prefs.getString("both", null) shouldBe "new"
        prefs.getInt("count", 0) shouldBe 3
        JavaSharedPreferences.fileFor("app.komikku").exists() shouldBe false
        // The old file is kept byte for byte, including the old value of the conflicting key
        File(JavaSharedPreferences.fileFor("app.komikku").path + ".migrated").readBytes()
            .contentEquals(legacyBytes) shouldBe true
        JavaSharedPreferences.fileFor("app.komikku_preferences").exists() shouldBe true
        // The same file as the app's PreferenceStore reads
        JavaSharedPreferences("app.komikku_preferences").getString("legacy_only", null) shouldBe "kept"
    }

    @Test
    fun `the API 33 typed getters and flag classes behind SDK_INT checks work`() {
        val bundle = android.os.Bundle().apply {
            putSerializable("date", java.util.Date(0))
            putString("text", "x")
        }
        bundle.getSerializable("date", java.util.Date::class.java) shouldBe java.util.Date(0)
        bundle.getSerializable("text", java.util.Date::class.java) shouldBe null
        bundle.getParcelable("missing", android.os.Bundle::class.java) shouldBe null
        val intent = android.content.Intent().putExtra("date", java.util.Date(0))
        intent.getSerializableExtra("date", java.util.Date::class.java) shouldBe java.util.Date(0)
        intent.getParcelableExtra("date", android.os.Bundle::class.java) shouldBe null

        val flags = android.content.pm.PackageManager.PackageInfoFlags.of(128)
        flags.value shouldBe 128L
        android.content.pm.PackageManager.ApplicationInfoFlags.of(0).value shouldBe 0L
        Context.RECEIVER_NOT_EXPORTED shouldBe 4
    }
}
