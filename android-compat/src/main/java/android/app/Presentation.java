// ZOUKAN -->
package android.app;

import android.content.Context;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;

/**
 * A presentation on a (virtual) display. On desktop it only gives its content view a parent, as
 * a window would: extensions host an offscreen WebView this way (Comix's reader), and the
 * desktop's WebViews render headless.
 */
public class Presentation {
    private final Context mContext;
    private final Display mDisplay;
    private final ViewGroup mHost;
    private boolean mShowing;

    public Presentation(Context outerContext, Display display) {
        this(outerContext, display, 0);
    }

    public Presentation(Context outerContext, Display display, int theme) {
        mContext = outerContext;
        mDisplay = display;
        mHost = new ViewGroup(outerContext) {
            @Override
            protected void onLayout(boolean changed, int l, int t, int r, int b) {
            }
        };
    }

    public Display getDisplay() {
        return mDisplay;
    }

    public final Context getContext() {
        return mContext;
    }

    public void setContentView(View view) {
        setContentView(view, null);
    }

    public void setContentView(View view, ViewGroup.LayoutParams params) {
        while (mHost.getChildCount() > 0) mHost.removeView(mHost.getChildAt(0));
        mHost.addView(view, params);
    }

    public void show() {
        mShowing = true;
    }

    public boolean isShowing() {
        return mShowing;
    }

    public void dismiss() {
        mShowing = false;
    }

    public void cancel() {
        dismiss();
    }
}
// ZOUKAN <--
