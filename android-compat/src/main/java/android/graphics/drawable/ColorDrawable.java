package android.graphics.drawable;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.BlendMode;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.util.AttributeSet;
import java.io.IOException;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

// ZOUKAN -->
// Shadows android.jar's stub ColorDrawable: fills its bounds with a color, modulated by its alpha
// and through its color filter. Tinting is ignored; outlines and inflating from XML stay stubs.
public class ColorDrawable extends Drawable {
    private int mBaseColor;
    private int mUseColor;
    private ColorFilter mColorFilter;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ColorDrawable() {
    }

    public ColorDrawable(int color) {
        setColor(color);
    }

    @Override
    public int getChangingConfigurations() {
        return super.getChangingConfigurations();
    }

    @NonNull
    @Override
    public Drawable mutate() {
        return this;
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if ((mUseColor >>> 24) != 0 || mColorFilter != null) {
            mPaint.setColor(mUseColor);
            mPaint.setColorFilter(mColorFilter);
            canvas.drawRect(getBounds(), mPaint);
        }
    }

    public int getColor() {
        return mUseColor;
    }

    public void setColor(int color) {
        if (mBaseColor != color || mUseColor != color) {
            mBaseColor = mUseColor = color;
            invalidateSelf();
        }
    }

    @Override
    public int getAlpha() {
        return mUseColor >>> 24;
    }

    @Override
    public void setAlpha(int alpha) {
        alpha += alpha >> 7;   // make it 0..256
        final int baseAlpha = mBaseColor >>> 24;
        final int useAlpha = baseAlpha * alpha >> 8;
        final int useColor = (mBaseColor << 8 >>> 8) | (useAlpha << 24);
        if (mUseColor != useColor) {
            mUseColor = useColor;
            invalidateSelf();
        }
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        mColorFilter = colorFilter;
    }

    @Nullable
    @Override
    public ColorFilter getColorFilter() {
        return mColorFilter;
    }

    @Override
    public void setTintList(@Nullable ColorStateList tint) {
    }

    @Override
    public void setTintBlendMode(@Nullable BlendMode blendMode) {
    }

    @Override
    public boolean isStateful() {
        return false;
    }

    @Override
    public int getOpacity() {
        if (mColorFilter != null) {
            return PixelFormat.TRANSLUCENT;
        }
        switch (mUseColor >>> 24) {
            case 255:
                return PixelFormat.OPAQUE;
            case 0:
                return PixelFormat.TRANSPARENT;
        }
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public void getOutline(@NonNull Outline outline) {
        throw new RuntimeException("Stub!");
    }

    @Override
    public void inflate(@NonNull Resources r, @NonNull XmlPullParser parser, @NonNull AttributeSet attrs,
            @Nullable Resources.Theme theme) throws XmlPullParserException, IOException {
        throw new RuntimeException("Stub!");
    }

    @Override
    public boolean canApplyTheme() {
        return false;
    }

    @Override
    public void applyTheme(@NonNull Resources.Theme theme) {
    }

    @Override
    public ConstantState getConstantState() {
        final int color = mBaseColor;
        final int configurations = getChangingConfigurations();
        return new ConstantState() {
            @NonNull
            @Override
            public Drawable newDrawable() {
                return new ColorDrawable(color);
            }

            @Override
            public int getChangingConfigurations() {
                return configurations;
            }
        };
    }
}
// ZOUKAN <--
