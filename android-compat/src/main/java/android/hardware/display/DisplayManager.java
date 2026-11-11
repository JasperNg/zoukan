// ZOUKAN -->
package android.hardware.display;

import android.os.Handler;
import android.view.Display;
import android.view.Surface;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Virtual displays for extensions that host a WebView offscreen (Comix's reader). The desktop's
 * WebViews render headless, so a virtual display only has to exist.
 */
public final class DisplayManager {
    public static final int VIRTUAL_DISPLAY_FLAG_PUBLIC = 1;
    public static final int VIRTUAL_DISPLAY_FLAG_PRESENTATION = 2;
    public static final int VIRTUAL_DISPLAY_FLAG_SECURE = 4;
    public static final int VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY = 8;
    public static final int VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR = 16;

    public static final DisplayManager INSTANCE = new DisplayManager();

    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Display defaultDisplay = new Display(Display.DEFAULT_DISPLAY, "Built-in screen", 1280, 2856);

    private DisplayManager() {
    }

    public Display getDisplay(int displayId) {
        return displayId == Display.DEFAULT_DISPLAY ? defaultDisplay : null;
    }

    public Display[] getDisplays() {
        return new Display[] { defaultDisplay };
    }

    public VirtualDisplay createVirtualDisplay(String name, int width, int height, int densityDpi, Surface surface, int flags) {
        return new VirtualDisplay(new Display(nextId.getAndIncrement(), name, width, height), surface);
    }

    public VirtualDisplay createVirtualDisplay(String name, int width, int height, int densityDpi, Surface surface, int flags,
            VirtualDisplay.Callback callback, Handler handler) {
        return createVirtualDisplay(name, width, height, densityDpi, surface, flags);
    }
}
// ZOUKAN <--
