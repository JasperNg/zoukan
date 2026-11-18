package zoukan.desktop.ui.whatsnew

import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.network.PREF_DOH_CLOUDFLARE
import eu.kanade.tachiyomi.network.PREF_DOH_QUAD9
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.DesktopPreferenceStore
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import xyz.nulldev.androidcompat.io.sharedprefs.JavaSharedPreferences
import zoukan.desktop.ui.whatsnew.DohNotice.Decision
import java.lang.reflect.Proxy

class DohNoticeTest {
    private fun store(): PreferenceStore = DesktopPreferenceStore(JavaSharedPreferences("doh_notice_test_${System.nanoTime()}"))

    private fun network(store: PreferenceStore) = NetworkPreferences(store, defaultDohProvider = PREF_DOH_QUAD9)

    @Test
    fun `an existing user on the new default is told`() {
        DohNotice.decide(handled = false, dohProviderStored = false, updatedFromEarlierVersion = true, onboardingDone = true) shouldBe Decision.SHOW
    }

    @Test
    fun `a stored choice, a new install or an unfinished setup is never told`() {
        DohNotice.decide(handled = false, dohProviderStored = true, updatedFromEarlierVersion = true, onboardingDone = true) shouldBe Decision.SKIP
        DohNotice.decide(handled = false, dohProviderStored = false, updatedFromEarlierVersion = false, onboardingDone = true) shouldBe Decision.SKIP
        DohNotice.decide(handled = false, dohProviderStored = false, updatedFromEarlierVersion = true, onboardingDone = false) shouldBe Decision.SKIP
    }

    @Test
    fun `once handled, nothing more happens`() {
        listOf(true, false).forEach { flag ->
            DohNotice.decide(handled = true, dohProviderStored = flag, updatedFromEarlierVersion = !flag, onboardingDone = true) shouldBe Decision.NOTHING
        }
    }

    @Test
    fun `it shows once after an update and records that in app state`() {
        val store = store()
        DohNotice.check(store, network(store), updatedFromEarlierVersion = true, onboardingDone = true) shouldBe true
        DohNotice.handled(store).get() shouldBe true
        Preference.isAppState(DohNotice.handled(store).key()) shouldBe true
        // The next launch (or the next update) does not show it again
        DohNotice.check(store, network(store), updatedFromEarlierVersion = true, onboardingDone = true) shouldBe false
    }

    @Test
    fun `a new install is not told now or after its next update`() {
        val store = store()
        DohNotice.check(store, network(store), updatedFromEarlierVersion = false, onboardingDone = true) shouldBe false
        DohNotice.check(store, network(store), updatedFromEarlierVersion = true, onboardingDone = true) shouldBe false
    }

    @Test
    fun `a stored provider, off included, means no notice`() {
        listOf(-1, PREF_DOH_CLOUDFLARE, PREF_DOH_QUAD9).forEach { provider ->
            val store = store()
            network(store).dohProvider().set(provider)
            DohNotice.check(store, network(store), updatedFromEarlierVersion = true, onboardingDone = true) shouldBe false
            DohNotice.handled(store).get() shouldBe true
        }
    }

    @Test
    fun `settings that cannot be read mean no notice, not a crash`() {
        val broken = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(PreferenceStore::class.java)) { _, _, _ ->
            throw IllegalStateException("store unavailable")
        } as PreferenceStore
        DohNotice.check(broken, network(broken), updatedFromEarlierVersion = true, onboardingDone = true) shouldBe false
    }
}
