// ZOUKAN -->
package android.view;

/** A display of the desktop's offscreen WebView host; nothing is shown on it. */
public final class Display {
    public static final int DEFAULT_DISPLAY = 0;

    private final int mDisplayId;
    private final String mName;
    private final int mWidth;
    private final int mHeight;

    public Display(int displayId, String name, int width, int height) {
        mDisplayId = displayId;
        mName = name;
        mWidth = width;
        mHeight = height;
    }

    public int getDisplayId() {
        return mDisplayId;
    }

    public String getName() {
        return mName;
    }

    public boolean isValid() {
        return true;
    }

    public int getWidth() {
        return mWidth;
    }

    public int getHeight() {
        return mHeight;
    }

    public float getRefreshRate() {
        return 60f;
    }
}
// ZOUKAN <--
