package android.util;

// ZOUKAN: whole file. android.jar's AndroidException is a stub whose constructors throw "Stub!", so
// PackageManager.NameNotFoundException (and the other exceptions built on it) could not be thrown

/** Base class for the checked exceptions the Android framework throws, as on Android. */
public class AndroidException extends Exception {
    public AndroidException() {
    }

    public AndroidException(String name) {
        super(name);
    }

    public AndroidException(String name, Throwable cause) {
        super(name, cause);
    }

    public AndroidException(Exception cause) {
        super(cause);
    }
}
