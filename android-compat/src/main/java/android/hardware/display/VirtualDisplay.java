// ZOUKAN -->
package android.hardware.display;

import android.view.Display;
import android.view.Surface;

/** A virtual display for an offscreen WebView host; see DisplayManager. */
public final class VirtualDisplay {
    public abstract static class Callback {
        public void onPaused() {
        }

        public void onResumed() {
        }

        public void onStopped() {
        }
    }

    private final Display mDisplay;
    private Surface mSurface;

    VirtualDisplay(Display display, Surface surface) {
        mDisplay = display;
        mSurface = surface;
    }

    public Display getDisplay() {
        return mDisplay;
    }

    public Surface getSurface() {
        return mSurface;
    }

    public void setSurface(Surface surface) {
        mSurface = surface;
    }

    public void resize(int width, int height, int densityDpi) {
    }

    public void release() {
    }
}
// ZOUKAN <--
