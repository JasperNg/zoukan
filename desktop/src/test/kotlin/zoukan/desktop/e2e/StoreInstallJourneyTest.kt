package zoukan.desktop.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import mihon.domain.extension.interactor.GetExtensionStores
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import tachiyomi.domain.source.model.StubSource
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import zoukan.desktop.app.DesktopPreferences
import zoukan.desktop.ui.ZoukanContent
import zoukan.desktop.ui.e2eEffectContext
import zoukan.extension.DesktopExtensionLoader
import zoukan.extension.DesktopExtensionManager
import zoukan.extension.ExtensionClassLoader
import zoukan.extension.LoadResult
import java.io.File
import kotlin.time.Duration.Companion.minutes

/**
 * The whole extension path, from a store to an uninstall, with nothing injected: adds a local
 * [FixtureStore] (Keiyoushi's v2 index with its signing key and jar links) from Browse > Stores,
 * installs its JAR-signed extension from the Extensions tab, browses the extension's source (its
 * class loaded from the downloaded jar by the extension class loader), checks that an extension
 * signed with another key installs as untrusted, removes the store (its offers leave the available
 * list) and uninstalls the extension (its source leaves Sources and stays as a stub).
 *
 * Every step is done on screen; the checks behind them (signer, class loader, stored stores, files
 * on disk, stubs) read the app's services.
 *
 *   ./gradlew :desktop:journeyTest --tests "*StoreInstallJourneyTest"
 */
@OptIn(ExperimentalTestApi::class)
class StoreInstallJourneyTest {

    private lateinit var journey: Journey
    private var store: FixtureStore? = null

    @AfterEach
    fun close() {
        store?.close()
        if (::journey.isInitialized) journey.close()
    }

    @Test
    fun addStoreInstallBrowseRemoveStoreAndUninstall() {
        journey = Journey.start("store-install")
        val store = FixtureStore(journey.server).also { this.store = it }
        val preferences = Injekt.get<DesktopPreferences>()
        val manager = Injekt.get<DesktopExtensionManager>()
        val sources = Injekt.get<SourceManager>()
        val extensionsDir = File(journey.dirs.extensions, FixtureStore.TRUSTED_PKG)
        val catalogue = FixtureCatalog.manga.filterNot { it.bonus }.map { it.title }
        var navigator: Navigator? = null
        var sourceId = 0L
        var classLoader: ExtensionClassLoader? = null

        with(journey) {
            runDesktopComposeUiTest(Journey.WIDTH, Journey.HEIGHT, effectContext = e2eEffectContext(), testTimeout = 10.minutes) {
                setContent { ZoukanContent(preferences) { navigator = it } }

                step("Add the fixture store in Browse > Stores") {
                    onAllNodesWithText("Browse").onFirst().performClick()
                    browsePage("Stores")
                    onAllNodes(hasSetTextAction() and hasAnyDescendant(hasText("Store URL", substring = true)), useUnmergedTree = true)
                        .onFirst().performTextInput(store.indexUrl)
                    onAllNodes(hasText("Add") and hasClickAction()).onFirst().performClick()
                    waitFor(hasText(FixtureStore.STORE_NAME))
                    waitFor(hasText(store.indexUrl))
                    snap("1-store-added")
                    val saved = runBlocking { Injekt.get<GetExtensionStores>().get() }.single()
                    saved.indexUrl shouldBe store.indexUrl
                    saved.signingKey shouldBe store.storeKey.fingerprint
                    saved.isLegacy shouldBe false
                }

                step("Its extensions are listed as available") {
                    browsePage("Extensions")
                    waitFor(hasText(FixtureStore.TRUSTED_NAME))
                    waitFor(hasText(FixtureStore.ROGUE_NAME))
                    waitFor(hasText("Available (2)"))
                    manager.availableExtensionsFlow.value.keys shouldContainExactlyInAnyOrder listOf(FixtureStore.TRUSTED_PKG, FixtureStore.ROGUE_PKG)
                    manager.availableExtensionsFlow.value.getValue(FixtureStore.TRUSTED_PKG).jarUrl shouldBe
                        server.baseUrl + store.jarPath(store.trusted)
                    snap("2-available")
                }

                step("Install the extension the store signs: it is trusted and loads from the jar") {
                    searchExtensions(FixtureStore.TRUSTED_NAME)
                    onAllNodes(hasText("Install") and hasClickAction()).onFirst().performClick()
                    waitFor(hasContentDescription("Uninstall"))
                    waitOrDump("for the extension to be installed") { FixtureStore.TRUSTED_PKG in manager.installedExtensionsFlow.value }
                    val installed = manager.installedExtensionsFlow.value.getValue(FixtureStore.TRUSTED_PKG)
                    installed.signatureHash shouldBe store.storeKey.fingerprint
                    installed.storeName shouldBe FixtureStore.STORE_BADGE
                    installed.versionCode shouldBe 1L
                    val source = installed.sources.single().shouldBeInstanceOf<HttpSource>()
                    source.name shouldBe FixtureStore.TRUSTED_NAME
                    source.baseUrl shouldBe server.baseUrl
                    // The class came from the downloaded jar, not the app's class path
                    source.javaClass.name shouldBe FixtureStore.JAR_SOURCE_CLASS
                    classLoader = source.javaClass.classLoader.shouldBeInstanceOf<ExtensionClassLoader>()
                    sourceId = source.id
                    server.requests.count { it == store.jarPath(store.trusted) } shouldBe 1
                    File(extensionsDir, "1.jar").isFile shouldBe true
                    waitOrDump("for the SourceManager to list the source") { sources.get(sourceId) != null }
                    // The installed row names its store as the Stores tab does
                    waitFor(hasText("· ${FixtureStore.STORE_NAME}", substring = true))
                    onAllNodes(hasText("· ${FixtureStore.STORE_BADGE}", substring = true), useUnmergedTree = true).fetchSemanticsNodes().shouldBeEmpty()
                    snap("3-installed")
                    searchExtensions("")
                }

                step("Its source is in Sources and browses the fixture catalogue") {
                    browsePage("Sources")
                    waitFor(hasText(FixtureStore.TRUSTED_NAME))
                    onAllNodesWithText(FixtureStore.TRUSTED_NAME).onFirst().performScrollTo().performClick()
                    waitOrDump("for ${catalogue.size} titles") { onAllNodesWithTag("manga-item").fetchSemanticsNodes().size == catalogue.size }
                    catalogue.forEach { waitFor(hasText(it)) }
                    snap("4-catalogue")
                    runOnUiThread { navigator?.pop() }
                    waitFor(hasText(FixtureStore.TRUSTED_NAME))
                }

                step("An extension signed with another key installs as untrusted, and goes back to available on uninstall") {
                    browsePage("Extensions")
                    searchExtensions(FixtureStore.ROGUE_NAME)
                    // A right-click on an available extension asks first and does not install it on its own
                    // (the search field holds the same text, so skip editable nodes)
                    onAllNodes(hasText(FixtureStore.ROGUE_NAME) and !hasSetTextAction()).onFirst().performMouseInput { rightClick() }
                    waitForIdle()
                    (FixtureStore.ROGUE_PKG in manager.untrustedExtensionsFlow.value) shouldBe false
                    (FixtureStore.ROGUE_PKG in manager.installedExtensionsFlow.value) shouldBe false
                    waitFor(hasText("Install ${FixtureStore.ROGUE_NAME}?"))
                    onAllNodes(hasText("Cancel") and hasClickAction()).onLast().performClick()
                    waitForIdle()
                    onAllNodes(hasText("Install") and hasClickAction()).onFirst().performClick()
                    waitFor(hasText("Untrusted"))
                    waitFor(hasText("Trust") and hasClickAction())
                    val untrusted = manager.untrustedExtensionsFlow.value[FixtureStore.ROGUE_PKG].shouldNotBeNull()
                    untrusted.signatureHash shouldBe store.otherKey.fingerprint
                    (FixtureStore.ROGUE_PKG in manager.installedExtensionsFlow.value) shouldBe false
                    sources.getAll().none { it.name == FixtureStore.ROGUE_NAME } shouldBe true
                    snap("5-untrusted")
                    // Trust asks first and names the signer; cancelling leaves the extension untrusted
                    onAllNodes(hasText("Trust") and hasClickAction()).onFirst().performClick()
                    waitFor(hasText(store.otherKey.fingerprint, substring = true))
                    onAllNodes(hasText("Cancel") and hasClickAction()).onLast().performClick()
                    waitForIdle()
                    (FixtureStore.ROGUE_PKG in manager.untrustedExtensionsFlow.value) shouldBe true
                    (FixtureStore.ROGUE_PKG in manager.installedExtensionsFlow.value) shouldBe false
                    onAllNodes(hasText("Uninstall") and hasClickAction()).onFirst().performClick()
                    waitFor(hasText("Uninstall ${FixtureStore.ROGUE_NAME}?"))
                    // The dialog's button: the row's button is behind it
                    onAllNodes(hasText("Uninstall") and hasClickAction()).onLast().performClick()
                    waitOrDump("for the untrusted extension to be uninstalled") {
                        FixtureStore.ROGUE_PKG !in manager.untrustedExtensionsFlow.value
                    }
                    waitFor(hasText("Install") and hasClickAction())
                    File(journey.dirs.extensions, FixtureStore.ROGUE_PKG).exists() shouldBe false
                    searchExtensions("")
                }

                step("Remove the store: its offers leave the available list") {
                    browsePage("Stores")
                    waitFor(hasText(FixtureStore.STORE_NAME))
                    onAllNodes(hasContentDescription("Remove")).onFirst().performClick()
                    // Removing a store asks first; the store stays until confirmed
                    waitFor(hasText("Remove ${FixtureStore.STORE_NAME}?"))
                    runBlocking { Injekt.get<GetExtensionStores>().get() }.size shouldBe 1
                    onAllNodes(hasText("Remove") and hasClickAction()).onLast().performClick()
                    waitOrDump("for the store to leave the list") { onAllNodesWithText(FixtureStore.STORE_NAME).fetchSemanticsNodes().isEmpty() }
                    runBlocking { Injekt.get<GetExtensionStores>().get() } shouldBe emptyList()
                    waitOrDump("for the available list to empty") { manager.availableExtensionsFlow.value.isEmpty() }
                    browsePage("Extensions")
                    waitFor(hasText("Available (0)"))
                    onAllNodesWithText(FixtureStore.ROGUE_NAME).fetchSemanticsNodes().isEmpty() shouldBe true
                    // Still installed and loaded, but no store offers it any more
                    val installed = manager.installedExtensionsFlow.value.getValue(FixtureStore.TRUSTED_PKG)
                    installed.store.shouldBeNull()
                    installed.hasUpdate shouldBe false
                    waitFor(hasText(FixtureStore.TRUSTED_NAME))
                    snap("6-store-removed")
                    // Its trust came from the store's key: the next load (the next start) asks again
                    val loader = Injekt.get<DesktopExtensionLoader>()
                    val files = loader.installedPackage(FixtureStore.TRUSTED_PKG).shouldNotBeNull()
                    loader.load(files, emptyList()).shouldBeInstanceOf<LoadResult.Untrusted>()
                }

                step("Uninstall the extension: its source leaves Sources and stays as a stub") {
                    onAllNodes(hasContentDescription("Uninstall")).onFirst().performClick()
                    waitFor(hasText("Uninstall ${FixtureStore.TRUSTED_NAME}?"))
                    onAllNodes(hasText("Uninstall") and hasClickAction()).onLast().performClick()
                    waitOrDump("for the extension to be uninstalled") { FixtureStore.TRUSTED_PKG !in manager.installedExtensionsFlow.value }
                    waitOrDump("for the extension to leave the list") { onAllNodesWithText(FixtureStore.TRUSTED_NAME).fetchSemanticsNodes().isEmpty() }
                    extensionsDir.exists() shouldBe false
                    classLoader.shouldNotBeNull().isClosed shouldBe true
                    waitOrDump("for the SourceManager to drop the source") { sources.get(sourceId) == null }
                    val stub = sources.getOrStub(sourceId).shouldBeInstanceOf<StubSource>()
                    stub.name shouldBe FixtureStore.TRUSTED_NAME
                    browsePage("Sources")
                    waitFor(hasText(FixtureSource.NAME))
                    onAllNodesWithText(FixtureStore.TRUSTED_NAME).fetchSemanticsNodes().isEmpty() shouldBe true
                    snap("7-uninstalled")
                }
            }
        }
    }

    /** Opens one of Browse's tabs by its label. */
    private fun ComposeUiTest.browsePage(label: String) {
        val tab: SemanticsMatcher = hasText(label) and hasAnyAncestor(hasTestTag("browse-pages"))
        with(journey) { waitFor(tab) }
        onAllNodes(tab).onFirst().performClick()
    }

    /** A screenshot of the app's window, toasts and dialogs included ([Journey.shot]). */
    private fun ComposeUiTest.snap(title: String) = with(journey) { shot(title) }

    /** Types [query] into the Extensions tab's search box, so one row (and one Install button) shows. */
    private fun ComposeUiTest.searchExtensions(query: String) {
        val field = onAllNodesWithTag("extension-search").onFirst()
        field.performTextClearance()
        if (query.isNotEmpty()) field.performTextInput(query)
        waitForIdle()
    }
}
