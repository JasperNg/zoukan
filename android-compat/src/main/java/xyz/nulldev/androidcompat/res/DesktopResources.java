package xyz.nulldev.androidcompat.res;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.DisplayMetrics;

// ZOUKAN -->
/**
 * Resources for desktop: Context.getResources() and Resources.getSystem() both return
 * {@link #INSTANCE}. Its display metrics are DisplayMetrics.setToDefaults() (the extension
 * WebView's size, 1280x2856, see KcefWebViewProvider's view rect, at Android's baseline density of
 * 160 dpi, so view pixels are CSS pixels) and its configuration is the default one (the JVM's
 * locale). Every other method keeps android.jar's stub behaviour.
 */
public final class DesktopResources extends Resources {
    /** The extension screen size: the extension WebView's size at 160 dpi. One place for the display, the metrics and the WebView rect. */
    public static final int SCREEN_WIDTH_PIXELS = 1280;
    public static final int SCREEN_HEIGHT_PIXELS = 2856;

    public static final DesktopResources INSTANCE = new DesktopResources();

    @SuppressWarnings("deprecation")
    private DesktopResources() {
        super(null, defaultMetrics(), new Configuration());
    }

    private static DisplayMetrics defaultMetrics() {
        DisplayMetrics metrics = new DisplayMetrics();
        metrics.setToDefaults();
        return metrics;
    }
}
// ZOUKAN <--
