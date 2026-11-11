// ZOUKAN -->
package android.view;

/** A drawing surface; the desktop's WebViews render headless, so nothing draws on it. */
public class Surface {
    private volatile boolean mValid = true;

    public Surface() {
    }

    public boolean isValid() {
        return mValid;
    }

    public void release() {
        mValid = false;
    }
}
// ZOUKAN <--
