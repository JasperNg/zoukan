package xyz.nulldev.androidcompat.webkit

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.cef.CefClient
import org.cef.CefSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefJSDialogCallback
import org.cef.callback.CefQueryCallback
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.handler.CefJSDialogHandler
import org.cef.handler.CefJSDialogHandlerAdapter
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefMessageRouterHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.network.CefRequest
import org.junit.jupiter.api.Test
import java.lang.ref.WeakReference

class KcefLifecycleCleanerTest {
    private class FakeOwner {
        var destroyCount = 0
    }

    @Test
    fun `PendingDisposal disposes browser before client exactly once and tolerates browser errors`() {
        val registry = KcefCleanupRegistry<FakeOwner>()
        val events = mutableListOf<String>()
        val disposal = PendingDisposal(
            registry = registry,
            disposeBrowser = {
                events += "browser"
                error("browser dispose failed")
            },
            disposeClient = {
                events += "client"
            },
        )
        registry.enqueuePending(disposal)
        registry.pendingCount shouldBe 1

        disposal.run()
        disposal.isDisposed shouldBe true
        events.shouldContainExactly("browser", "client")
        registry.pendingCount shouldBe 0

        // Second run is a no-op
        disposal.run()
        events.shouldContainExactly("browser", "client")
    }

    @Test
    fun `NativeRefs registers live natives and take drains atomically once`() {
        val registry = KcefCleanupRegistry<FakeOwner>()
        val refs = NativeRefs(registry)
        val browser = mockk<CefBrowser>(relaxed = true)
        val client = mockk<CefClient>(relaxed = true)

        refs.take().shouldBeNull()
        registry.liveNativeCount shouldBe 0

        refs.browser = browser
        refs.client = client
        registry.liveNativeCount shouldBe 1

        val disposal = refs.take().shouldNotBeNull()
        refs.browser.shouldBeNull()
        refs.client.shouldBeNull()
        registry.liveNativeCount shouldBe 0
        refs.take().shouldBeNull()

        disposal.run()
        verify(exactly = 1) { browser.stopLoad() }
        verify(exactly = 1) { browser.setCloseAllowed() }
        verify(exactly = 1) { browser.close(true) }
        verify(exactly = 1) { client.dispose() }
    }

    @Test
    fun `lifecycle state transitions track init destroy and deferred looper-to-AWT disposal`() {
        val looperQueue = ArrayDeque<Runnable>()
        val awtQueue = ArrayDeque<Runnable>()
        val registry = KcefCleanupRegistry<FakeOwner>(scheduleAwt = { awtQueue += it })
        val state = KcefLifecycleState(registry)
        val owner = FakeOwner()
        val browser = mockk<CefBrowser>(relaxed = true)
        val client = mockk<CefClient>(relaxed = true)

        state.ignoredAfterDestroy("loadUrl") shouldBe false
        state.native.client = client
        state.native.browser = browser
        state.markInitialized(owner)

        state.destroyed shouldBe false
        registry.liveOwnerCount shouldBe 1
        registry.liveNativeCount shouldBe 1

        var cleared = false
        state.markDestroyed(
            owner = owner,
            postToLooper = {
                looperQueue += it
                true
            },
            onClear = { cleared = true },
        )

        cleared shouldBe true
        state.destroyed shouldBe true
        state.ignoredAfterDestroy("loadUrl") shouldBe true
        registry.liveOwnerCount shouldBe 0
        registry.liveNativeCount shouldBe 0
        registry.pendingCount shouldBe 1

        // Not disposed until looper drains and AWT runs
        verify(exactly = 0) { client.dispose() }
        looperQueue.removeFirst().run()
        awtQueue.size shouldBe 1
        verify(exactly = 0) { client.dispose() }

        awtQueue.removeFirst().run()
        registry.pendingCount shouldBe 0
        verify(exactly = 1) { browser.close(true) }
        verify(exactly = 1) { client.dispose() }
    }

    @Test
    fun `disposePending destroys live owners drains uncollected natives and runs queued disposals`() {
        val looperQueue = ArrayDeque<Runnable>()
        val awtQueue = ArrayDeque<Runnable>()
        val registry = KcefCleanupRegistry<FakeOwner>(scheduleAwt = { awtQueue += it })

        // Owner 1: still alive and initialized
        val liveOwner = FakeOwner()
        val liveState = KcefLifecycleState(registry)
        val liveBrowser = mockk<CefBrowser>(relaxed = true)
        val liveClient = mockk<CefClient>(relaxed = true)
        liveState.native.client = liveClient
        liveState.native.browser = liveBrowser
        liveState.markInitialized(liveOwner)

        // Owner 2: already lost without destroy(), only NativeRefs left
        val orphanRefs = NativeRefs(registry)
        val orphanBrowser = mockk<CefBrowser>(relaxed = true)
        orphanRefs.browser = orphanBrowser

        registry.disposePending { owner ->
            owner.destroyCount++
            liveState.markDestroyed(owner, postToLooper = { looperQueue += it; true }, onClear = {})
        }

        liveOwner.destroyCount shouldBe 1
        registry.liveOwnerCount shouldBe 0
        registry.liveNativeCount shouldBe 0
        registry.pendingCount shouldBe 0
        verify(exactly = 1) { liveBrowser.close(true) }
        verify(exactly = 1) { liveClient.dispose() }
        verify(exactly = 1) { orphanBrowser.close(true) }

        // Even if the looper/AWT callbacks fire later, disposal was already completed once
        looperQueue.forEach { it.run() }
        awtQueue.forEach { it.run() }
        verify(exactly = 1) { liveClient.dispose() }
    }

    @Test
    fun `UnreachableCleanup disposes once when reachable native exists and no-ops after destroy`() {
        val awtQueue = ArrayDeque<Runnable>()
        val registry = KcefCleanupRegistry<FakeOwner>(scheduleAwt = { awtQueue += it })
        val refs = NativeRefs(registry)
        val browser = mockk<CefBrowser>(relaxed = true)
        refs.browser = browser

        val cleanup = UnreachableCleanup(refs, registry)
        cleanup.run()
        awtQueue.size shouldBe 1
        awtQueue.removeFirst().run()
        verify(exactly = 1) { browser.close(true) }

        // Running again (or after destroy took the handles) is a no-op
        cleanup.run()
        awtQueue.shouldBeEmpty()
    }

    @Test
    fun `disposeBrowserLater replaces old browser without touching client`() {
        val looperQueue = ArrayDeque<Runnable>()
        val awtQueue = ArrayDeque<Runnable>()
        val registry = KcefCleanupRegistry<FakeOwner>(scheduleAwt = { awtQueue += it })
        val oldBrowser = mockk<CefBrowser>(relaxed = true)

        registry.disposeBrowserLater(null) { looperQueue += it; true }
        looperQueue.shouldBeEmpty()

        registry.disposeBrowserLater(oldBrowser) { looperQueue += it; true }
        registry.pendingCount shouldBe 1
        looperQueue.removeFirst().run()
        awtQueue.removeFirst().run()
        registry.pendingCount shouldBe 0
        verify(exactly = 1) { oldBrowser.close(true) }
    }

    @Test
    fun `weak handler forwarders delegate when alive and enforce safe defaults when collected`() {
        val browser = mockk<CefBrowser>(relaxed = true)
        val mainFrame = mockk<CefFrame>(relaxed = true) { every { isMain } returns true }
        val jsRequest = mockk<CefRequest>(relaxed = true) { every { url } returns "javascript:alert(1)" }
        val httpsRequest = mockk<CefRequest>(relaxed = true) { every { url } returns "https://example.org/" }

        // Dead request handler blocks unsafe main-frame URLs and allows https
        val deadRequestHandler = WeakRequestHandler(WeakReference(null))
        deadRequestHandler.onBeforeBrowse(browser, mainFrame, jsRequest, userGesture = false, isRedirect = false) shouldBe true
        deadRequestHandler.onBeforeBrowse(browser, mainFrame, httpsRequest, userGesture = false, isRedirect = false) shouldBe false

        // Alive request handler delegates
        val targetRequestHandler = object : CefRequestHandlerAdapter() {
            override fun onBeforeBrowse(
                browser: CefBrowser,
                frame: CefFrame,
                request: CefRequest,
                userGesture: Boolean,
                isRedirect: Boolean,
            ): Boolean = true
        }
        WeakRequestHandler(targetRequestHandler).onBeforeBrowse(browser, mainFrame, httpsRequest, false, false) shouldBe true

        // Dead JS dialog handler suppresses dialogs and leaves onBeforeUnload
        val suppress = BoolRef()
        val dialogCallback = mockk<CefJSDialogCallback>(relaxed = true)
        val deadDialogHandler = WeakJsDialogHandler(WeakReference(null))
        deadDialogHandler.onJSDialog(
            browser,
            "https://example.org",
            CefJSDialogHandler.JSDialogType.JSDIALOGTYPE_ALERT,
            "hi",
            null,
            dialogCallback,
            suppress,
        ) shouldBe false
        suppress.get() shouldBe true
        deadDialogHandler.onBeforeUnloadDialog(browser, "leave?", false, dialogCallback) shouldBe true
        verify(exactly = 1) { dialogCallback.Continue(true, "") }

        // Alive JS dialog handler delegates
        val aliveDialogCallback = mockk<CefJSDialogCallback>(relaxed = true)
        val aliveDialogHandler = WeakJsDialogHandler(
            object : CefJSDialogHandlerAdapter() {
                override fun onJSDialog(
                    browser: CefBrowser,
                    originUrl: String?,
                    dialogType: CefJSDialogHandler.JSDialogType?,
                    messageText: String?,
                    defaultPromptText: String?,
                    callback: CefJSDialogCallback,
                    suppressMessage: BoolRef,
                ): Boolean = true
            },
        )
        aliveDialogHandler.onJSDialog(
            browser,
            "https://example.org",
            CefJSDialogHandler.JSDialogType.JSDIALOGTYPE_ALERT,
            "hi",
            null,
            aliveDialogCallback,
            BoolRef(),
        ) shouldBe true

        // Dead lifespan, display, and message-router handlers return safe defaults
        WeakLifeSpanHandler(WeakReference(null)).onBeforePopup(browser, mainFrame, "https://example.org", null) shouldBe true
        WeakLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
            override fun onBeforePopup(browser: CefBrowser, frame: CefFrame?, targetUrl: String?, targetFrameName: String?) = false
        }).onBeforePopup(browser, mainFrame, "https://example.org", null) shouldBe false

        WeakDisplayHandler(WeakReference(null)).onConsoleMessage(
            browser,
            CefSettings.LogSeverity.LOGSEVERITY_INFO,
            "msg",
            "src",
            1,
        ) shouldBe false
        WeakDisplayHandler(object : CefDisplayHandlerAdapter() {
            override fun onConsoleMessage(browser: CefBrowser, level: CefSettings.LogSeverity, message: String, source: String, line: Int) = true
        }).onConsoleMessage(browser, CefSettings.LogSeverity.LOGSEVERITY_INFO, "msg", "src", 1) shouldBe true

        val queryCallback = mockk<CefQueryCallback>(relaxed = true)
        WeakMessageRouterHandler(WeakReference(null)).onQuery(browser, mainFrame, 1L, "{}", false, queryCallback) shouldBe false
        WeakMessageRouterHandler(object : CefMessageRouterHandlerAdapter() {
            override fun onQuery(browser: CefBrowser, frame: CefFrame, queryId: Long, request: String, persistent: Boolean, callback: CefQueryCallback) = true
        }).onQuery(browser, mainFrame, 1L, "{}", false, queryCallback) shouldBe true
    }
}
