// ZOUKAN: whole file -->
package android.os;

import xyz.nulldev.androidcompat.config.SystemConfigModule;
import xyz.nulldev.ts.config.ConfigManager;
import xyz.nulldev.ts.config.GlobalConfigManager;

/**
 * Android compat class.
 *
 * Reports a plausible Android 14 (API 34) phone, so extensions build sensible user agents and take
 * the modern branch of their SDK_INT checks. The device fields are assigned in static initializers,
 * not as constant expressions, so javac and kotlinc read them at run time instead of inlining them
 * into callers (as on Android, where they come from system properties).
 */
public class Build {
    public static boolean IS_DEBUGGABLE = isDebuggable();

    /** From the android.system config; false before it is registered, so Build.MODEL never fails. */
    private static boolean isDebuggable() {
        try {
            ConfigManager configManager = GlobalConfigManager.INSTANCE;
            SystemConfigModule configModule = configManager.module(SystemConfigModule.class);
            return configModule != null && configModule.isDebuggable();
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static final String UNKNOWN = "unknown";

    public static final String BOARD;
    public static final String BOOTLOADER;
    public static final String BRAND;
    /** @deprecated */
    @Deprecated
    public static final String CPU_ABI;
    /** @deprecated */
    @Deprecated
    public static final String CPU_ABI2;
    public static final String DEVICE;
    public static final String DISPLAY;
    public static final String FINGERPRINT;
    public static final String HARDWARE;
    public static final String HOST;
    public static final String ID;
    public static final String MANUFACTURER;
    public static final String MODEL;
    public static final String PRODUCT;
    /** @deprecated */
    @Deprecated
    public static final String RADIO;
    /** @deprecated */
    @Deprecated
    public static final String SERIAL;
    public static final String[] SUPPORTED_32_BIT_ABIS;
    public static final String[] SUPPORTED_64_BIT_ABIS;
    public static final String[] SUPPORTED_ABIS;
    public static final String TAGS;
    public static final long TIME;
    public static final String TYPE;
    public static final String USER;
    public static final String SOC_MANUFACTURER;
    public static final String SOC_MODEL;
    public static final String SKU;
    public static final String ODM_SKU;

    static {
        BOARD = "husky";
        BOOTLOADER = "ripcurrent-14.0-11012024";
        BRAND = "google";
        CPU_ABI = "arm64-v8a";
        CPU_ABI2 = "";
        DEVICE = "husky";
        DISPLAY = "AP2A.240905.003";
        FINGERPRINT = "google/husky/husky:14/AP2A.240905.003/12231197:user/release-keys";
        HARDWARE = "husky";
        HOST = "abfarm-release-2004-0102";
        ID = "AP2A.240905.003";
        MANUFACTURER = "Google";
        MODEL = "Pixel 8 Pro";
        PRODUCT = "husky";
        RADIO = UNKNOWN;
        SERIAL = UNKNOWN;
        SUPPORTED_32_BIT_ABIS = new String[0];
        SUPPORTED_64_BIT_ABIS = new String[] {"arm64-v8a"};
        SUPPORTED_ABIS = new String[] {"arm64-v8a"};
        TAGS = "release-keys";
        TIME = 1725494400000L;
        TYPE = "user";
        USER = "android-build";
        SOC_MANUFACTURER = "Google";
        SOC_MODEL = "Tensor G3";
        SKU = "husky";
        ODM_SKU = "husky";
    }

    public Build() {
        throw new RuntimeException("This class cannot be instantiated!");
    }

    public static String getRadioVersion() {
        return null;
    }

    public static String getSerial() {
        return UNKNOWN;
    }

    /** Values match android.os.Build.VERSION_CODES; these are compile-time constants on Android too. */
    public static class VERSION_CODES {
        public static final int CUR_DEVELOPMENT = 10000;
        public static final int BASE = 1;
        public static final int BASE_1_1 = 2;
        public static final int CUPCAKE = 3;
        public static final int DONUT = 4;
        public static final int ECLAIR = 5;
        public static final int ECLAIR_0_1 = 6;
        public static final int ECLAIR_MR1 = 7;
        public static final int FROYO = 8;
        public static final int GINGERBREAD = 9;
        public static final int GINGERBREAD_MR1 = 10;
        public static final int HONEYCOMB = 11;
        public static final int HONEYCOMB_MR1 = 12;
        public static final int HONEYCOMB_MR2 = 13;
        public static final int ICE_CREAM_SANDWICH = 14;
        public static final int ICE_CREAM_SANDWICH_MR1 = 15;
        public static final int JELLY_BEAN = 16;
        public static final int JELLY_BEAN_MR1 = 17;
        public static final int JELLY_BEAN_MR2 = 18;
        public static final int KITKAT = 19;
        public static final int KITKAT_WATCH = 20;
        public static final int L = 21;
        public static final int LOLLIPOP = 21;
        public static final int LOLLIPOP_MR1 = 22;
        public static final int M = 23;
        public static final int N = 24;
        public static final int N_MR1 = 25;
        public static final int O = 26;
        public static final int O_MR1 = 27;
        public static final int P = 28;
        public static final int Q = 29;
        public static final int R = 30;
        public static final int S = 31;
        public static final int S_V2 = 32;
        public static final int TIRAMISU = 33;
        public static final int UPSIDE_DOWN_CAKE = 34;
        public static final int VANILLA_ICE_CREAM = 35;
        public static final int BAKLAVA = 36;
    }

    public static class VERSION {
        public static final String BASE_OS;
        public static final String CODENAME;
        public static final String INCREMENTAL;
        public static final int PREVIEW_SDK_INT;
        public static final String RELEASE;
        public static final String RELEASE_OR_CODENAME;
        public static final String RELEASE_OR_PREVIEW_DISPLAY;
        /** @deprecated */
        @Deprecated
        public static final String SDK;
        public static final int SDK_INT;
        public static final String SECURITY_PATCH;
        public static final int MEDIA_PERFORMANCE_CLASS;

        static {
            BASE_OS = "";
            CODENAME = "REL";
            INCREMENTAL = "12231197";
            PREVIEW_SDK_INT = 0;
            RELEASE = "14";
            RELEASE_OR_CODENAME = "14";
            RELEASE_OR_PREVIEW_DISPLAY = "14";
            SDK_INT = VERSION_CODES.UPSIDE_DOWN_CAKE;
            SDK = Integer.toString(SDK_INT);
            SECURITY_PATCH = "2024-09-05";
            MEDIA_PERFORMANCE_CLASS = 0;
        }

        public VERSION() {
            throw new RuntimeException("Stub!");
        }
    }
}
// ZOUKAN: whole file <--
