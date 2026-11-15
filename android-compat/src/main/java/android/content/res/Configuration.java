package android.content.res;

// ZOUKAN -->
// Shadows Suwayomi android.jar's stub of this class, keeping every public member. Members the
// extensions use are implemented; the rest still throw "Stub!" as in android.jar. Generated from
// `javap -public` of android.jar, then edited.
// ZOUKAN <--

public final class Configuration implements android.os.Parcelable, java.lang.Comparable<Configuration> {
    private android.os.LocaleList mLocales;
    public static final int COLOR_MODE_HDR_MASK = 12;
    public static final int COLOR_MODE_HDR_NO = 4;
    public static final int COLOR_MODE_HDR_SHIFT = 2;
    public static final int COLOR_MODE_HDR_UNDEFINED = 0;
    public static final int COLOR_MODE_HDR_YES = 8;
    public static final int COLOR_MODE_UNDEFINED = 0;
    public static final int COLOR_MODE_WIDE_COLOR_GAMUT_MASK = 3;
    public static final int COLOR_MODE_WIDE_COLOR_GAMUT_NO = 1;
    public static final int COLOR_MODE_WIDE_COLOR_GAMUT_UNDEFINED = 0;
    public static final int COLOR_MODE_WIDE_COLOR_GAMUT_YES = 2;
    public static final android.os.Parcelable.Creator<android.content.res.Configuration> CREATOR = null;
    public static final int DENSITY_DPI_UNDEFINED = 0;
    public static final int HARDKEYBOARDHIDDEN_NO = 1;
    public static final int HARDKEYBOARDHIDDEN_UNDEFINED = 0;
    public static final int HARDKEYBOARDHIDDEN_YES = 2;
    public static final int KEYBOARDHIDDEN_NO = 1;
    public static final int KEYBOARDHIDDEN_UNDEFINED = 0;
    public static final int KEYBOARDHIDDEN_YES = 2;
    public static final int KEYBOARD_12KEY = 3;
    public static final int KEYBOARD_NOKEYS = 1;
    public static final int KEYBOARD_QWERTY = 2;
    public static final int KEYBOARD_UNDEFINED = 0;
    public static final int MNC_ZERO = 65535;
    public static final int NAVIGATIONHIDDEN_NO = 1;
    public static final int NAVIGATIONHIDDEN_UNDEFINED = 0;
    public static final int NAVIGATIONHIDDEN_YES = 2;
    public static final int NAVIGATION_DPAD = 2;
    public static final int NAVIGATION_NONAV = 1;
    public static final int NAVIGATION_TRACKBALL = 3;
    public static final int NAVIGATION_UNDEFINED = 0;
    public static final int NAVIGATION_WHEEL = 4;
    public static final int ORIENTATION_LANDSCAPE = 2;
    public static final int ORIENTATION_PORTRAIT = 1;
    public static final int ORIENTATION_SQUARE = 3;
    public static final int ORIENTATION_UNDEFINED = 0;
    public static final int SCREENLAYOUT_LAYOUTDIR_LTR = 64;
    public static final int SCREENLAYOUT_LAYOUTDIR_MASK = 192;
    public static final int SCREENLAYOUT_LAYOUTDIR_RTL = 128;
    public static final int SCREENLAYOUT_LAYOUTDIR_SHIFT = 6;
    public static final int SCREENLAYOUT_LAYOUTDIR_UNDEFINED = 0;
    public static final int SCREENLAYOUT_LONG_MASK = 48;
    public static final int SCREENLAYOUT_LONG_NO = 16;
    public static final int SCREENLAYOUT_LONG_UNDEFINED = 0;
    public static final int SCREENLAYOUT_LONG_YES = 32;
    public static final int SCREENLAYOUT_ROUND_MASK = 768;
    public static final int SCREENLAYOUT_ROUND_NO = 256;
    public static final int SCREENLAYOUT_ROUND_UNDEFINED = 0;
    public static final int SCREENLAYOUT_ROUND_YES = 512;
    public static final int SCREENLAYOUT_SIZE_LARGE = 3;
    public static final int SCREENLAYOUT_SIZE_MASK = 15;
    public static final int SCREENLAYOUT_SIZE_NORMAL = 2;
    public static final int SCREENLAYOUT_SIZE_SMALL = 1;
    public static final int SCREENLAYOUT_SIZE_UNDEFINED = 0;
    public static final int SCREENLAYOUT_SIZE_XLARGE = 4;
    public static final int SCREENLAYOUT_UNDEFINED = 0;
    public static final int SCREEN_HEIGHT_DP_UNDEFINED = 0;
    public static final int SCREEN_WIDTH_DP_UNDEFINED = 0;
    public static final int SMALLEST_SCREEN_WIDTH_DP_UNDEFINED = 0;
    public static final int TOUCHSCREEN_FINGER = 3;
    public static final int TOUCHSCREEN_NOTOUCH = 1;
    public static final int TOUCHSCREEN_STYLUS = 2;
    public static final int TOUCHSCREEN_UNDEFINED = 0;
    public static final int UI_MODE_NIGHT_MASK = 48;
    public static final int UI_MODE_NIGHT_NO = 16;
    public static final int UI_MODE_NIGHT_UNDEFINED = 0;
    public static final int UI_MODE_NIGHT_YES = 32;
    public static final int UI_MODE_TYPE_APPLIANCE = 5;
    public static final int UI_MODE_TYPE_CAR = 3;
    public static final int UI_MODE_TYPE_DESK = 2;
    public static final int UI_MODE_TYPE_MASK = 15;
    public static final int UI_MODE_TYPE_NORMAL = 1;
    public static final int UI_MODE_TYPE_TELEVISION = 4;
    public static final int UI_MODE_TYPE_UNDEFINED = 0;
    public static final int UI_MODE_TYPE_VR_HEADSET = 7;
    public static final int UI_MODE_TYPE_WATCH = 6;
    public int colorMode;
    public int densityDpi;
    public float fontScale;
    public int hardKeyboardHidden;
    public int keyboard;
    public int keyboardHidden;
    public java.util.Locale locale;
    public int mcc;
    public int mnc;
    public int navigation;
    public int navigationHidden;
    public int orientation;
    public int screenHeightDp;
    public int screenLayout;
    public int screenWidthDp;
    public int smallestScreenWidthDp;
    public int touchscreen;
    public int uiMode;
    public Configuration() {
        setToDefaults();
    }
    public Configuration(android.content.res.Configuration p0) {
        setTo(p0);
    }
    public boolean isLayoutSizeAtLeast(int p0) {
        throw new RuntimeException("Stub!");
    }
    public void setTo(android.content.res.Configuration p0) {
        colorMode = p0.colorMode;
        densityDpi = p0.densityDpi;
        fontScale = p0.fontScale;
        hardKeyboardHidden = p0.hardKeyboardHidden;
        keyboard = p0.keyboard;
        keyboardHidden = p0.keyboardHidden;
        locale = p0.locale;
        mLocales = p0.mLocales;
        mcc = p0.mcc;
        mnc = p0.mnc;
        navigation = p0.navigation;
        navigationHidden = p0.navigationHidden;
        orientation = p0.orientation;
        screenHeightDp = p0.screenHeightDp;
        screenLayout = p0.screenLayout;
        screenWidthDp = p0.screenWidthDp;
        smallestScreenWidthDp = p0.smallestScreenWidthDp;
        touchscreen = p0.touchscreen;
        uiMode = p0.uiMode;
    }
    public java.lang.String toString() {
        return "{" + fontScale + " " + getLocales() + " " + screenWidthDp + "x" + screenHeightDp + "dp " + densityDpi + "dpi uiMode=" + uiMode + "}";
    }
    public void setToDefaults() {
        // Desktop defaults: the JVM's locale and the screen of DisplayMetrics.setToDefaults()
        fontScale = 1f;
        mcc = 0;
        mnc = 0;
        setLocales(android.os.LocaleList.getDefault());
        touchscreen = TOUCHSCREEN_NOTOUCH;
        keyboard = KEYBOARD_QWERTY;
        keyboardHidden = KEYBOARDHIDDEN_NO;
        hardKeyboardHidden = HARDKEYBOARDHIDDEN_NO;
        navigation = NAVIGATION_NONAV;
        navigationHidden = NAVIGATIONHIDDEN_UNDEFINED;
        orientation = ORIENTATION_PORTRAIT;
        screenLayout = SCREENLAYOUT_SIZE_NORMAL | SCREENLAYOUT_LONG_YES | SCREENLAYOUT_LAYOUTDIR_LTR | SCREENLAYOUT_ROUND_NO;
        colorMode = COLOR_MODE_UNDEFINED;
        uiMode = UI_MODE_TYPE_NORMAL | UI_MODE_NIGHT_NO;
        screenWidthDp = 1280;
        screenHeightDp = 2856;
        smallestScreenWidthDp = 1280;
        densityDpi = android.util.DisplayMetrics.DENSITY_DEFAULT;
    }
    public int updateFrom(android.content.res.Configuration p0) {
        throw new RuntimeException("Stub!");
    }
    public int diff(android.content.res.Configuration p0) {
        throw new RuntimeException("Stub!");
    }
    public static boolean needNewResources(int p0, int p1) {
        throw new RuntimeException("Stub!");
    }
    public int describeContents() {
        return 0;
    }
    public void writeToParcel(android.os.Parcel p0, int p1) {
        throw new RuntimeException("Stub!");
    }
    public void readFromParcel(android.os.Parcel p0) {
        throw new RuntimeException("Stub!");
    }
    public boolean isNightModeActive() {
        return (uiMode & UI_MODE_NIGHT_MASK) == UI_MODE_NIGHT_YES;
    }
    public int compareTo(android.content.res.Configuration p0) {
        return toString().compareTo(p0.toString());
    }
    public boolean equals(android.content.res.Configuration p0) {
        return p0 != null && (p0 == this || toString().equals(p0.toString()));
    }
    public boolean equals(java.lang.Object p0) {
        return p0 instanceof Configuration && equals((Configuration) p0);
    }
    public int hashCode() {
        return toString().hashCode();
    }
    public android.os.LocaleList getLocales() {
        if (mLocales == null || (locale != null && !locale.equals(mLocales.get(0)))) {
            // The public locale field was written directly
            mLocales = locale == null ? android.os.LocaleList.getEmptyLocaleList() : new android.os.LocaleList(locale);
        }
        return mLocales;
    }
    public void setLocales(android.os.LocaleList p0) {
        mLocales = p0 == null ? android.os.LocaleList.getEmptyLocaleList() : p0;
        locale = mLocales.get(0);
    }
    public void setLocale(java.util.Locale p0) {
        setLocales(p0 == null ? android.os.LocaleList.getEmptyLocaleList() : new android.os.LocaleList(p0));
    }
    public int getLayoutDirection() {
        return (screenLayout & SCREENLAYOUT_LAYOUTDIR_MASK) == SCREENLAYOUT_LAYOUTDIR_RTL ? 1 : 0;
    }
    public void setLayoutDirection(java.util.Locale p0) {
        throw new RuntimeException("Stub!");
    }
    public boolean isScreenRound() {
        return (screenLayout & SCREENLAYOUT_ROUND_MASK) == SCREENLAYOUT_ROUND_YES;
    }
    public boolean isScreenWideColorGamut() {
        throw new RuntimeException("Stub!");
    }
    public boolean isScreenHdr() {
        throw new RuntimeException("Stub!");
    }
}
