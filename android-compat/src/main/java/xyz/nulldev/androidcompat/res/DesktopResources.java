package xyz.nulldev.androidcompat.res;

import android.content.res.Resources;
import android.util.DisplayMetrics;
import java.lang.reflect.Field;
import sun.misc.Unsafe;

// ZOUKAN -->
/**
 * Resources for desktop with display metrics: the extension WebView's size (1280x2856, see
 * KcefWebViewProvider's view rect) at Android's baseline density (160 dpi), so view pixels are
 * CSS pixels. Every other method keeps android.jar's stub behaviour.
 *
 * android.jar's Resources and DisplayMetrics constructors throw "Stub!", so the instances are
 * allocated without running a constructor.
 */
public final class DesktopResources extends Resources {
    private static final Unsafe UNSAFE = unsafe();

    public static final DesktopResources INSTANCE = allocate(DesktopResources.class);

    private static final DisplayMetrics METRICS = createMetrics();

    @SuppressWarnings("deprecation")
    private DesktopResources() {
        super(null, null, null);
    }

    @Override
    public DisplayMetrics getDisplayMetrics() {
        return METRICS;
    }

    private static DisplayMetrics createMetrics() {
        DisplayMetrics metrics = allocate(DisplayMetrics.class);
        metrics.widthPixels = 1280;
        metrics.heightPixels = 2856;
        metrics.density = 1f;
        metrics.densityDpi = 160;
        metrics.scaledDensity = 1f;
        metrics.xdpi = 160f;
        metrics.ydpi = 160f;
        return metrics;
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) {
        try {
            return (T) UNSAFE.allocateInstance(type);
        } catch (InstantiationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Unsafe unsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
// ZOUKAN <--
