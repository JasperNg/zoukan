// ZOUKAN -->
package org.cef.browser;

import org.cef.CefClient;
import org.cef.callback.CefDragData;
import org.cef.handler.CefRenderHandler;
import org.cef.handler.CefScreenInfo;

import java.awt.Component;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * An off-screen browser that never draws: it has no window and no OpenGL canvas, and passes its
 * paint calls to a render handler that may ignore them.
 *
 * Zoukan's WebView for extensions runs on this. JetBrains' JCEF, which Suwayomi uses, has
 * CefRendering.CefRenderingWithHandler for this; upstream JCEF (jcefmaven) only offers an
 * off-screen browser that paints into a JOGL canvas, so this class takes its place. It lives in
 * org.cef.browser because CefBrowser_N is package-private.
 */
public class CefBrowserHeadless extends CefBrowser_N implements CefRenderHandler {
    private final CefRenderHandler handler;

    public CefBrowserHeadless(CefClient client, String url, CefRenderHandler handler) {
        super(client, url, null, null, null, null);
        this.handler = handler;
    }

    /** Sends a mouse event to the page, as if the user clicked; WebView touch events use this. */
    public void sendMouse(java.awt.event.MouseEvent event) {
        sendMouseEvent(event);
    }

    @Override
    public void createImmediately() {
        if (getNativeRef("CefBrowser") == 0) {
            createBrowser(getClient(), 0, getUrl(), true, false, null, getRequestContext());
        }
    }

    @Override
    public Component getUIComponent() {
        return null;
    }

    @Override
    public CefRenderHandler getRenderHandler() {
        return this;
    }

    @Override
    protected CefBrowser_N createDevToolsBrowser(
        CefClient client, String url, CefRequestContext context, CefBrowser_N parent, Point inspectAt) {
        return null;
    }

    @Override
    public CompletableFuture<BufferedImage> createScreenshot(boolean nativeResolution) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Headless browser"));
    }

    @Override
    public Rectangle getViewRect(CefBrowser browser) {
        return handler.getViewRect(browser);
    }

    @Override
    public boolean getScreenInfo(CefBrowser browser, CefScreenInfo screenInfo) {
        Rectangle rect = getViewRect(browser);
        screenInfo.Set(1.0, 32, 8, false, rect, rect);
        return true;
    }

    @Override
    public Point getScreenPoint(CefBrowser browser, Point viewPoint) {
        return viewPoint;
    }

    @Override
    public void onPopupShow(CefBrowser browser, boolean show) {
        handler.onPopupShow(browser, show);
    }

    @Override
    public void onPopupSize(CefBrowser browser, Rectangle size) {
        handler.onPopupSize(browser, size);
    }

    @Override
    public void onPaint(
        CefBrowser browser, boolean popup, Rectangle[] dirtyRects, ByteBuffer buffer, int width, int height) {
        handler.onPaint(browser, popup, dirtyRects, buffer, width, height);
    }

    @Override
    public void addOnPaintListener(Consumer<CefPaintEvent> listener) {}

    @Override
    public void setOnPaintListener(Consumer<CefPaintEvent> listener) {}

    @Override
    public void removeOnPaintListener(Consumer<CefPaintEvent> listener) {}

    @Override
    public boolean onCursorChange(CefBrowser browser, int cursorType) {
        return false;
    }

    @Override
    public boolean startDragging(CefBrowser browser, CefDragData dragData, int mask, int x, int y) {
        return false;
    }

    @Override
    public void updateDragCursor(CefBrowser browser, int operation) {}
}
// ZOUKAN <--
