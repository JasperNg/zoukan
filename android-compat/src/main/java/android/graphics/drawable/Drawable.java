package android.graphics.drawable;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.BitmapFactory;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Insets;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.Region;
import android.util.AttributeSet;
import android.util.TypedValue;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

// ZOUKAN -->
// Shadows android.jar's Drawable, whose constructor throws "Stub!", so that subclasses such as
// ColorDrawable can be created. Bounds, state, level, visibility and the callback work as on
// Android; tinting is ignored; loading drawables from streams or XML, outlines and optical insets
// stay stubs.
public abstract class Drawable {
    private static final Rect ZERO_BOUNDS_RECT = new Rect();
    private static final int[] EMPTY_STATE_SET = new int[0];

    private int[] mStateSet = EMPTY_STATE_SET;
    private int mLevel = 0;
    private int mChangingConfigurations = 0;
    private Rect mBounds = ZERO_BOUNDS_RECT;
    private WeakReference<Callback> mCallback = null;
    private boolean mVisible = true;
    private int mLayoutDirection;

    public interface Callback {
        void invalidateDrawable(@NonNull Drawable who);

        void scheduleDrawable(@NonNull Drawable who, @NonNull Runnable what, long when);

        void unscheduleDrawable(@NonNull Drawable who, @NonNull Runnable what);
    }

    public static abstract class ConstantState {
        public ConstantState() {
        }

        public abstract @NonNull Drawable newDrawable();

        public @NonNull Drawable newDrawable(@Nullable Resources res) {
            return newDrawable();
        }

        public @NonNull Drawable newDrawable(@Nullable Resources res, @Nullable Resources.Theme theme) {
            return newDrawable(res);
        }

        public abstract int getChangingConfigurations();

        public boolean canApplyTheme() {
            return false;
        }
    }

    public Drawable() {
    }

    public abstract void draw(@NonNull Canvas canvas);

    public void setBounds(int left, int top, int right, int bottom) {
        Rect oldBounds = mBounds;
        if (oldBounds == ZERO_BOUNDS_RECT) {
            oldBounds = mBounds = new Rect();
        }
        if (oldBounds.left != left || oldBounds.top != top
                || oldBounds.right != right || oldBounds.bottom != bottom) {
            mBounds.set(left, top, right, bottom);
            onBoundsChange(mBounds);
        }
    }

    public void setBounds(@NonNull Rect bounds) {
        setBounds(bounds.left, bounds.top, bounds.right, bounds.bottom);
    }

    public final void copyBounds(@NonNull Rect bounds) {
        bounds.set(mBounds);
    }

    @NonNull
    public final Rect copyBounds() {
        return new Rect(mBounds);
    }

    @NonNull
    public final Rect getBounds() {
        if (mBounds == ZERO_BOUNDS_RECT) {
            mBounds = new Rect();
        }
        return mBounds;
    }

    @NonNull
    public Rect getDirtyBounds() {
        return getBounds();
    }

    public void setChangingConfigurations(int configs) {
        mChangingConfigurations = configs;
    }

    public int getChangingConfigurations() {
        return mChangingConfigurations;
    }

    @Deprecated
    public void setDither(boolean dither) {
    }

    public void setFilterBitmap(boolean filter) {
    }

    public boolean isFilterBitmap() {
        return false;
    }

    public final void setCallback(@Nullable Callback cb) {
        mCallback = cb != null ? new WeakReference<>(cb) : null;
    }

    @Nullable
    public Callback getCallback() {
        return mCallback != null ? mCallback.get() : null;
    }

    public void invalidateSelf() {
        final Callback callback = getCallback();
        if (callback != null) {
            callback.invalidateDrawable(this);
        }
    }

    public void scheduleSelf(@NonNull Runnable what, long when) {
        final Callback callback = getCallback();
        if (callback != null) {
            callback.scheduleDrawable(this, what, when);
        }
    }

    public void unscheduleSelf(@NonNull Runnable what) {
        final Callback callback = getCallback();
        if (callback != null) {
            callback.unscheduleDrawable(this, what);
        }
    }

    public int getLayoutDirection() {
        return mLayoutDirection;
    }

    public final boolean setLayoutDirection(int layoutDirection) {
        if (mLayoutDirection != layoutDirection) {
            mLayoutDirection = layoutDirection;
            return onLayoutDirectionChanged(layoutDirection);
        }
        return false;
    }

    public boolean onLayoutDirectionChanged(int layoutDirection) {
        return false;
    }

    public abstract void setAlpha(int alpha);

    public int getAlpha() {
        return 0xFF;
    }

    public abstract void setColorFilter(@Nullable ColorFilter colorFilter);

    @Deprecated
    public void setColorFilter(int color, @NonNull PorterDuff.Mode mode) {
        throw new RuntimeException("Stub!");
    }

    /** Tinting is not supported: ignored. */
    public void setTint(int tintColor) {
    }

    /** Tinting is not supported: ignored. */
    public void setTintList(@Nullable ColorStateList tint) {
    }

    /** Tinting is not supported: ignored. */
    public void setTintMode(@Nullable PorterDuff.Mode tintMode) {
    }

    /** Tinting is not supported: ignored. */
    public void setTintBlendMode(@Nullable BlendMode blendMode) {
    }

    @Nullable
    public ColorFilter getColorFilter() {
        return null;
    }

    public void clearColorFilter() {
        setColorFilter(null);
    }

    public void setHotspot(float x, float y) {
    }

    public void setHotspotBounds(int left, int top, int right, int bottom) {
    }

    public void getHotspotBounds(@NonNull Rect outRect) {
        outRect.set(getBounds());
    }

    public boolean isProjected() {
        return false;
    }

    public boolean isStateful() {
        return false;
    }

    public boolean setState(@NonNull final int[] stateSet) {
        if (!Arrays.equals(mStateSet, stateSet)) {
            mStateSet = stateSet;
            return onStateChange(stateSet);
        }
        return false;
    }

    @NonNull
    public int[] getState() {
        return mStateSet;
    }

    public void jumpToCurrentState() {
    }

    @NonNull
    public Drawable getCurrent() {
        return this;
    }

    public final boolean setLevel(int level) {
        if (mLevel != level) {
            mLevel = level;
            return onLevelChange(level);
        }
        return false;
    }

    public final int getLevel() {
        return mLevel;
    }

    public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = mVisible != visible;
        if (changed) {
            mVisible = visible;
            invalidateSelf();
        }
        return changed;
    }

    public final boolean isVisible() {
        return mVisible;
    }

    public void setAutoMirrored(boolean mirrored) {
    }

    public boolean isAutoMirrored() {
        return false;
    }

    public void applyTheme(@NonNull Resources.Theme t) {
    }

    public boolean canApplyTheme() {
        return false;
    }

    @Deprecated
    public abstract int getOpacity();

    public static int resolveOpacity(int op1, int op2) {
        if (op1 == op2) {
            return op1;
        }
        if (op1 == PixelFormat.UNKNOWN || op2 == PixelFormat.UNKNOWN) {
            return PixelFormat.UNKNOWN;
        }
        if (op1 == PixelFormat.TRANSLUCENT || op2 == PixelFormat.TRANSLUCENT) {
            return PixelFormat.TRANSLUCENT;
        }
        if (op1 == PixelFormat.TRANSPARENT || op2 == PixelFormat.TRANSPARENT) {
            return PixelFormat.TRANSPARENT;
        }
        return PixelFormat.OPAQUE;
    }

    @Nullable
    public Region getTransparentRegion() {
        return null;
    }

    protected boolean onStateChange(@NonNull int[] state) {
        return false;
    }

    protected boolean onLevelChange(int level) {
        return false;
    }

    protected void onBoundsChange(@NonNull Rect bounds) {
    }

    public int getIntrinsicWidth() {
        return -1;
    }

    public int getIntrinsicHeight() {
        return -1;
    }

    public int getMinimumWidth() {
        final int intrinsicWidth = getIntrinsicWidth();
        return intrinsicWidth > 0 ? intrinsicWidth : 0;
    }

    public int getMinimumHeight() {
        final int intrinsicHeight = getIntrinsicHeight();
        return intrinsicHeight > 0 ? intrinsicHeight : 0;
    }

    public boolean getPadding(@NonNull Rect padding) {
        padding.set(0, 0, 0, 0);
        return false;
    }

    @NonNull
    public Insets getOpticalInsets() {
        throw new RuntimeException("Stub!");
    }

    public void getOutline(@NonNull Outline outline) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    public Drawable mutate() {
        return this;
    }

    public static Drawable createFromStream(InputStream is, String srcName) {
        throw new RuntimeException("Stub!");
    }

    public static Drawable createFromResourceStream(Resources res, TypedValue value, InputStream is, String srcName) {
        throw new RuntimeException("Stub!");
    }

    public static Drawable createFromResourceStream(Resources res, TypedValue value, InputStream is, String srcName,
            BitmapFactory.Options opts) {
        throw new RuntimeException("Stub!");
    }

    public static Drawable createFromXml(Resources r, XmlPullParser parser) throws XmlPullParserException, IOException {
        throw new RuntimeException("Stub!");
    }

    public static Drawable createFromXml(Resources r, XmlPullParser parser, Resources.Theme theme)
            throws XmlPullParserException, IOException {
        throw new RuntimeException("Stub!");
    }

    public static Drawable createFromXmlInner(Resources r, XmlPullParser parser, AttributeSet attrs)
            throws XmlPullParserException, IOException {
        throw new RuntimeException("Stub!");
    }

    public static Drawable createFromXmlInner(Resources r, XmlPullParser parser, AttributeSet attrs, Resources.Theme theme)
            throws XmlPullParserException, IOException {
        throw new RuntimeException("Stub!");
    }

    public static Drawable createFromPath(String pathName) {
        throw new RuntimeException("Stub!");
    }

    public void inflate(@NonNull Resources r, @NonNull XmlPullParser parser, @NonNull AttributeSet attrs)
            throws XmlPullParserException, IOException {
        throw new RuntimeException("Stub!");
    }

    public void inflate(@NonNull Resources r, @NonNull XmlPullParser parser, @NonNull AttributeSet attrs,
            @Nullable Resources.Theme theme) throws XmlPullParserException, IOException {
        throw new RuntimeException("Stub!");
    }

    @Nullable
    public ConstantState getConstantState() {
        return null;
    }
}
// ZOUKAN <--
