package zoukan.desktop.security

import eu.kanade.tachiyomi.core.security.SecurityPreferences
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import xyz.nulldev.ts.config.CONFIG_PREFIX
import zoukan.desktop.secrets.SecretStore
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime

class AppLockTest {

    private class MemorySecrets : SecretStore {
        val map = mutableMapOf<String, String>()
        override val name = "memory"
        override fun get(key: String) = map[key]
        override fun set(key: String, value: String) {
            map[key] = value
        }
        override fun delete(key: String) {
            map.remove(key)
        }
    }

    // Not @TempDir: the preferences file is written in the background and may outlive the test
    private val root: File = Files.createTempDirectory("app-lock-test").toFile()

    private val preferences by lazy {
        System.setProperty("$CONFIG_PREFIX.server.rootDir", root.absolutePath)
        SecurityPreferences(DesktopPreferenceStore(JavaSharedPreferences("app_lock_test_${System.nanoTime()}")))
    }
    private val lock by lazy { AppLock(preferences, MemorySecrets()) }

    @Test
    fun `locks at start and unlocks with the password only`() {
        lock.setPassword("secret")
        preferences.useAuthenticator().set(true)
        lock.onStart()
        lock.locked.value shouldBe true
        lock.unlock("wrong") shouldBe false
        lock.locked.value shouldBe true
        lock.unlock("secret") shouldBe true
        lock.locked.value shouldBe false
    }

    @Test
    fun `locks again after the idle time`() {
        lock.setPassword("secret")
        preferences.useAuthenticator().set(true)
        preferences.lockAppAfter().set(5)
        lock.onStart()
        lock.unlock("secret")
        lock.onBackground()
        lock.onForeground(now = System.currentTimeMillis() + 60_000)
        lock.locked.value shouldBe false
        lock.onBackground()
        lock.onForeground(now = System.currentTimeMillis() + 6 * 60_000)
        lock.locked.value shouldBe true
    }

    @Test
    fun `honours the days and time ranges`() {
        // 2026-11-09 is a Monday
        val mondayNight = LocalDateTime.of(2026, 11, 9, 23, 0)
        preferences.authenticatorDays().set(AppLock.LOCK_MONDAY)
        lock.canLockNow(mondayNight) shouldBe true
        lock.canLockNow(mondayNight.plusDays(1)) shouldBe false
        preferences.authenticatorTimeRanges().set(setOf("${22 * 60},${23 * 60 + 30}"))
        lock.canLockNow(mondayNight) shouldBe true
        lock.canLockNow(mondayNight.withHour(8)) shouldBe false
    }
}
