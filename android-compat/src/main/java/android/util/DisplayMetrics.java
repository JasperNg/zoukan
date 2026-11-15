package android.util;

// ZOUKAN -->
// Shadows Suwayomi android.jar's stub of this class, keeping every public member. Members the
// extensions use are implemented; the rest still throw "Stub!" as in android.jar. Generated from
// `javap -public` of android.jar, then edited.
// The defaults are a desktop screen: 1280x2856 pixels (the extension WebView's size, as in
// DesktopResources) at Android's baseline density of 160 dpi, so 1 dp = 1 px.
// ZOUKAN <--

public class DisplayMetrics {
    public static final int DENSITY_140 = 140;
    public static final int DENSITY_180 = 180;
    public static final int DENSITY_200 = 200;
    public static final int DENSITY_220 = 220;
    public static final int DENSITY_260 = 260;
    public static final int DENSITY_280 = 280;
    public static final int DENSITY_300 = 300;
    public static final int DENSITY_340 = 340;
    public static final int DENSITY_360 = 360;
    public static final int DENSITY_400 = 400;
    public static final int DENSITY_420 = 420;
    public static final int DENSITY_440 = 440;
    public static final int DENSITY_450 = 450;
    public static final int DENSITY_560 = 560;
    public static final int DENSITY_600 = 600;
    public static final int DENSITY_DEFAULT = 160;
    public static final int DENSITY_DEVICE_STABLE = DENSITY_DEFAULT;
    public static final int DENSITY_HIGH = 240;
    public static final int DENSITY_LOW = 120;
    public static final int DENSITY_MEDIUM = 160;
    public static final int DENSITY_TV = 213;
    public static final int DENSITY_XHIGH = 320;
    public static final int DENSITY_XXHIGH = 480;
    public static final int DENSITY_XXXHIGH = 640;
    public float density;
    public int densityDpi;
    public int heightPixels;
    public float scaledDensity;
    public int widthPixels;
    public float xdpi;
    public float ydpi;
    public DisplayMetrics() {
    }

    public void setTo(DisplayMetrics o) {
        if (this == o) {
            return;
        }
        density = o.density;
        densityDpi = o.densityDpi;
        heightPixels = o.heightPixels;
        scaledDensity = o.scaledDensity;
        widthPixels = o.widthPixels;
        xdpi = o.xdpi;
        ydpi = o.ydpi;
    }

    public void setToDefaults() {
        widthPixels = 1280;
        heightPixels = 2856;
        density = 1f;
        densityDpi = DENSITY_DEFAULT;
        scaledDensity = 1f;
        xdpi = DENSITY_DEFAULT;
        ydpi = DENSITY_DEFAULT;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof DisplayMetrics && equals((DisplayMetrics) o);
    }

    public boolean equals(DisplayMetrics other) {
        return other != null
                && widthPixels == other.widthPixels
                && heightPixels == other.heightPixels
                && density == other.density
                && densityDpi == other.densityDpi
                && scaledDensity == other.scaledDensity
                && xdpi == other.xdpi
                && ydpi == other.ydpi;
    }

    @Override
    public int hashCode() {
        return widthPixels * heightPixels * densityDpi;
    }

    @Override
    public String toString() {
        return "DisplayMetrics{density=" + density + ", width=" + widthPixels
                + ", height=" + heightPixels + ", scaledDensity=" + scaledDensity
                + ", xdpi=" + xdpi + ", ydpi=" + ydpi + "}";
    }
}
