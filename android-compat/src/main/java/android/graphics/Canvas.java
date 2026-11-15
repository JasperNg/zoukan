package android.graphics;

import android.annotation.ColorInt;
import android.annotation.ColorLong;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Path;
import android.graphics.RectF;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.List;

public final class Canvas {
    private BufferedImage canvasImage;
    private Graphics2D canvas;
    // ZOUKAN -->
    // save() keeps the clip too (in device space), as Android does
    private static final class SavedState {
        final AffineTransform transform;
        final Shape deviceClip;

        SavedState(AffineTransform transform, Shape deviceClip) {
            this.transform = transform;
            this.deviceClip = deviceClip;
        }
    }

    private List<SavedState> transformStack = new ArrayList<SavedState>();
    // ZOUKAN <--

    private static final String TAG = "Canvas";

    public Canvas(Bitmap bitmap) {
        canvasImage = bitmap.getImage();
        canvas = canvasImage.createGraphics();
    }

    // ZOUKAN -->
    public Canvas() {
    }

    public void setBitmap(@Nullable Bitmap bitmap) {
        if (canvas != null) {
            canvas.dispose();
        }
        transformStack.clear();
        if (bitmap == null) {
            canvasImage = null;
            canvas = null;
        } else {
            canvasImage = bitmap.getImage();
            canvas = canvasImage.createGraphics();
        }
    }

    public boolean isOpaque() {
        return canvasImage != null && !canvasImage.getColorModel().hasAlpha();
    }

    public int getWidth() {
        return canvasImage == null ? 0 : canvasImage.getWidth();
    }

    public int getHeight() {
        return canvasImage == null ? 0 : canvasImage.getHeight();
    }

    public void drawBitmap(@NonNull Bitmap sourceBitmap, @Nullable Rect src, @NonNull Rect dst, @Nullable Paint paint) {
        drawBitmap(sourceBitmap, src, dst.left, dst.top, dst.right, dst.bottom, paint);
    }

    public void drawBitmap(@NonNull Bitmap sourceBitmap, @Nullable Rect src, @NonNull RectF dst, @Nullable Paint paint) {
        drawBitmap(sourceBitmap, src, dst.left, dst.top, dst.right, dst.bottom, paint);
    }

    public void drawBitmap(@NonNull Bitmap sourceBitmap, float left, float top, @Nullable Paint paint) {
        drawImage(sourceBitmap.getImage(), AffineTransform.getTranslateInstance(left, top), paint);
    }

    public void drawBitmap(@NonNull Bitmap sourceBitmap, @NonNull Matrix matrix, @Nullable Paint paint) {
        drawImage(sourceBitmap.getImage(), matrix.toAffineTransform(), paint);
    }

    /**
     * Draws the [src] part of the bitmap (all of it when null) scaled into the destination. Like
     * Android, a source rect reaching outside the bitmap is clipped, with the destination clipped
     * in proportion.
     */
    private void drawBitmap(Bitmap sourceBitmap, Rect src, float dl, float dt, float dr, float db, Paint paint) {
        BufferedImage image = sourceBitmap.getImage();
        int sl = 0, st = 0, sr = image.getWidth(), sb = image.getHeight();
        if (src != null) {
            sl = src.left;
            st = src.top;
            sr = src.right;
            sb = src.bottom;
        }
        if (sr <= sl || sb <= st || dr <= dl || db <= dt) {
            return;
        }
        int cl = Math.max(sl, 0), ct = Math.max(st, 0);
        int cr = Math.min(sr, image.getWidth()), cb = Math.min(sb, image.getHeight());
        if (cr <= cl || cb <= ct) {
            return;
        }
        double sx = (dr - dl) / (double) (sr - sl);
        double sy = (db - dt) / (double) (sb - st);
        AffineTransform at = new AffineTransform(sx, 0, 0, sy, dl + (cl - sl) * sx, dt + (ct - st) * sy);
        drawImage(image.getSubimage(cl, ct, cr - cl, cb - ct), at, paint);
    }

    /** Draws [image] with [at] mapping its pixels onto this canvas's user space. */
    private void drawImage(BufferedImage image, AffineTransform at, Paint paint) {
        ColorFilter filter = paint == null ? null : paint.getColorFilter();
        if (filter instanceof ColorMatrixColorFilter) {
            int w = image.getWidth(), h = image.getHeight();
            int[] pixels = image.getRGB(0, 0, w, h, null, 0, w);
            ((ColorMatrixColorFilter) filter).filter(pixels);
            BufferedImage filtered = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            filtered.setRGB(0, 0, w, h, pixels, 0, w);
            image = filtered;
        }
        Object oldInterpolation = canvas.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        Composite oldComposite = canvas.getComposite();
        boolean filterBitmap = paint != null && paint.isFilterBitmap();
        canvas.setRenderingHint(RenderingHints.KEY_INTERPOLATION, filterBitmap
                ? RenderingHints.VALUE_INTERPOLATION_BILINEAR
                : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        if (paint != null && paint.getAlpha() < 255) {
            canvas.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, paint.getAlpha() / 255f));
        } else {
            canvas.setComposite(AlphaComposite.SrcOver);
        }
        try {
            canvas.drawImage(image, at, null);
        } finally {
            canvas.setComposite(oldComposite);
            if (oldInterpolation != null) {
                canvas.setRenderingHint(RenderingHints.KEY_INTERPOLATION, oldInterpolation);
            }
        }
    }
    // ZOUKAN <--

    public void drawText(@NonNull char[] text, int index, int count, float x, float y,
            @NonNull Paint paint) {
        drawText(new String(text, index, count), x, y, paint);
    }

    public void drawText(@NonNull String str, float x, float y, @NonNull Paint paint) {
        // ZOUKAN -->
        // Honours the paint's text align and scale X, with grayscale anti-aliasing as Android
        // draws into bitmaps (LCD sub-pixel anti-aliasing left colour fringes)
        if (str.isEmpty()) return;
        applyPaint(paint);
        canvas.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, paint.isAntiAlias()
                ? RenderingHints.VALUE_TEXT_ANTIALIAS_ON
                : RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        canvas.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        float left = x - paint.alignOffset(str);
        switch (paint.getStyle()) {
            case FILL: {
                AttributedString text = paint.attributedText(str);
                float scaleX = paint.getTextScaleX();
                if (scaleX == 1.0f) {
                    canvas.drawString(text.getIterator(), left, y);
                } else {
                    AffineTransform saved = canvas.getTransform();
                    canvas.translate(left, y);
                    canvas.scale(scaleX, 1);
                    canvas.drawString(text.getIterator(), 0f, 0f);
                    canvas.setTransform(saved);
                }
                break;
            }
            case STROKE:
                canvas.draw(paint.textOutline(str, left, y));
                break;
            case FILL_AND_STROKE: {
                Shape textShape = paint.textOutline(str, left, y);
                canvas.fill(textShape);
                canvas.draw(textShape);
                break;
            }
        }
        // ZOUKAN <--
    }

    public void drawText(@NonNull String text, int start, int end, float x, float y,
            @NonNull Paint paint) {
        drawText(text.substring(start, end), x, y, paint);
    }

    public void drawText(@NonNull CharSequence text, int start, int end, float x, float y,
            @NonNull Paint paint) {
        String str = text.subSequence(start, end).toString();
        drawText(str, x, y, paint);
    }

    // ZOUKAN -->
    public void drawRoundRect(@NonNull RectF rect, float rx, float ry, @NonNull Paint paint) {
        drawRoundRect(rect.left, rect.top, rect.right, rect.bottom, rx, ry, paint);
    }

    public void drawRoundRect(float left, float top, float right, float bottom, float rx, float ry,
            @NonNull Paint paint) {
        if (rx <= 0 || ry <= 0) {
            drawRect(left, top, right, bottom, paint);
            return;
        }
        drawShape(Path.roundRect(left, top, right, bottom, new float[] {rx, ry, rx, ry, rx, ry, rx, ry}), paint);
    }

    public void drawPath(@NonNull Path path, @NonNull Paint paint) {
        Shape shape = path.toShape();
        if (path.isInverseFillType() && paint.getStyle() != Paint.Style.STROKE) {
            // Inverse fill: everything on the canvas outside the path
            Area area;
            try {
                area = new Area(canvas.getTransform().createInverse()
                        .createTransformedShape(new Rectangle(0, 0, getWidth(), getHeight())));
            } catch (java.awt.geom.NoninvertibleTransformException e) {
                return;
            }
            area.subtract(new Area(shape));
            applyPaint(paint);
            canvas.fill(area);
            if (paint.getStyle() == Paint.Style.FILL_AND_STROKE) {
                canvas.draw(shape);
            }
            return;
        }
        drawShape(shape, paint);
    }

    public void drawRect(@NonNull RectF rect, @NonNull Paint paint) {
        drawRect(rect.left, rect.top, rect.right, rect.bottom, paint);
    }

    public void drawRect(@NonNull Rect r, @NonNull Paint paint) {
        drawRect(r.left, r.top, r.right, r.bottom, paint);
    }

    public void drawRect(float left, float top, float right, float bottom, @NonNull Paint paint) {
        float l = Math.min(left, right), t = Math.min(top, bottom);
        drawShape(new Rectangle2D.Float(l, t, Math.abs(right - left), Math.abs(bottom - top)), paint);
    }

    public void drawCircle(float cx, float cy, float radius, @NonNull Paint paint) {
        if (radius <= 0) return;
        drawShape(new Ellipse2D.Float(cx - radius, cy - radius, radius * 2, radius * 2), paint);
    }

    public void drawOval(@NonNull RectF oval, @NonNull Paint paint) {
        drawOval(oval.left, oval.top, oval.right, oval.bottom, paint);
    }

    public void drawOval(float left, float top, float right, float bottom, @NonNull Paint paint) {
        drawShape(new Ellipse2D.Float(left, top, right - left, bottom - top), paint);
    }

    public void drawArc(@NonNull RectF oval, float startAngle, float sweepAngle, boolean useCenter,
            @NonNull Paint paint) {
        drawArc(oval.left, oval.top, oval.right, oval.bottom, startAngle, sweepAngle, useCenter, paint);
    }

    public void drawArc(float left, float top, float right, float bottom, float startAngle,
            float sweepAngle, boolean useCenter, @NonNull Paint paint) {
        Arc2D arc = Path.arc(left, top, right, bottom, startAngle, sweepAngle);
        arc.setArcType(useCenter ? Arc2D.PIE : (paint.getStyle() == Paint.Style.STROKE ? Arc2D.OPEN : Arc2D.CHORD));
        drawShape(arc, paint);
    }

    /** Lines are always stroked, whatever the paint's style. */
    public void drawLine(float startX, float startY, float stopX, float stopY, @NonNull Paint paint) {
        applyPaint(paint);
        canvas.draw(new Line2D.Float(startX, startY, stopX, stopY));
    }

    public void drawLines(@NonNull float[] pts, int offset, int count, @NonNull Paint paint) {
        applyPaint(paint);
        for (int i = offset; i + 3 < offset + count; i += 4) {
            canvas.draw(new Line2D.Float(pts[i], pts[i + 1], pts[i + 2], pts[i + 3]));
        }
    }

    public void drawLines(@NonNull float[] pts, @NonNull Paint paint) {
        drawLines(pts, 0, pts.length, paint);
    }

    public void drawPaint(@NonNull Paint paint) {
        applyPaint(paint);
        AffineTransform saved = canvas.getTransform();
        canvas.setTransform(new AffineTransform());
        canvas.fillRect(0, 0, getWidth(), getHeight());
        canvas.setTransform(saved);
    }

    public boolean clipRect(float left, float top, float right, float bottom) {
        canvas.clip(new Rectangle2D.Float(left, top, right - left, bottom - top));
        return !isClipEmpty();
    }

    public boolean clipRect(int left, int top, int right, int bottom) {
        return clipRect((float) left, (float) top, (float) right, (float) bottom);
    }

    public boolean clipRect(@NonNull Rect rect) {
        return clipRect(rect.left, rect.top, rect.right, rect.bottom);
    }

    public boolean clipRect(@NonNull RectF rect) {
        return clipRect(rect.left, rect.top, rect.right, rect.bottom);
    }

    public boolean clipPath(@NonNull Path path) {
        canvas.clip(path.toShape());
        return !isClipEmpty();
    }

    public boolean isClipEmpty() {
        Shape clip = canvas.getClip();
        return clip != null && clip.getBounds2D().isEmpty();
    }

    public void concat(@Nullable Matrix matrix) {
        if (matrix != null) {
            canvas.transform(matrix.toAffineTransform());
        }
    }

    public void setMatrix(@Nullable Matrix matrix) {
        canvas.setTransform(matrix == null ? new AffineTransform() : matrix.toAffineTransform());
    }

    public void skew(float sx, float sy) {
        canvas.shear(sx, sy);
    }

    private void drawShape(Shape shape, Paint paint) {
        applyPaint(paint);
        switch (paint.getStyle()) {
            case FILL:
                canvas.fill(shape);
                break;
            case STROKE:
                canvas.draw(shape);
                break;
            case FILL_AND_STROKE:
                canvas.fill(shape);
                canvas.draw(shape);
                break;
        }
    }
    // ZOUKAN <--

    public void translate(float dx, float dy) {
        if (dx == 0.0f && dy == 0.0f) return;
        // TODO: check this, should translations stack?
        canvas.translate(dx, dy);
    }

    public void scale(float sx, float sy) {
        if (sx == 1.0f && sy == 1.0f) return;
        canvas.scale(sx, sy);
    }

    public final void scale(float sx, float sy, float px, float py) {
        if (sx == 1.0f && sy == 1.0f) return;
        translate(px, py);
        scale(sx, sy);
        translate(-px, -py);
    }

    public void rotate(float degrees) {
        if (degrees == 0.0f) return;
        // ZOUKAN -->
        // Graphics2D.rotate takes radians
        canvas.rotate(Math.toRadians(degrees));
        // ZOUKAN <--
    }

    public final void rotate(float degrees, float px, float py) {
        if (degrees == 0.0f) return;
        // ZOUKAN -->
        canvas.rotate(Math.toRadians(degrees), px, py);
        // ZOUKAN <--
    }

    public int getSaveCount() {
        return transformStack.size();
    }

    public int save() {
        // ZOUKAN -->
        Shape clip = canvas.getClip();
        Shape deviceClip = clip == null ? null : canvas.getTransform().createTransformedShape(clip);
        transformStack.add(new SavedState(canvas.getTransform(), deviceClip));
        // ZOUKAN <--
        return getSaveCount();
    }

    public void restoreToCount(int saveCount) {
        if (saveCount < 1) {
            throw new IllegalArgumentException(
                    "Underflow in restoreToCount - more restores than saves");
        }
        if (saveCount > getSaveCount()) {
            throw new IllegalArgumentException("Overflow in restoreToCount");

        }
        // ZOUKAN -->
        SavedState state = transformStack.get(saveCount - 1);
        canvas.setTransform(new AffineTransform());
        canvas.setClip(state.deviceClip);
        canvas.setTransform(state.transform);
        // ZOUKAN <--
        while (transformStack.size() >= saveCount) {
            transformStack.remove(transformStack.size() - 1);
        }
    }

    public void restore() {
        restoreToCount(getSaveCount());
    }

    public boolean getClipBounds(@NonNull Rect bounds) {
        Rectangle r = canvas.getClipBounds();
        if (r == null) {
            bounds.left = 0;
            bounds.top = 0;
            bounds.right = canvasImage.getWidth();
            bounds.bottom = canvasImage.getHeight();
            return true;
        }
        bounds.left = r.x;
        bounds.top = r.y;
        bounds.right = r.x + r.width;
        bounds.bottom = r.y + r.height;
        return r.width != 0 && r.height != 0;
    }

    public void drawColor(@ColorInt int colorInt) {
        java.awt.Color color = Color.valueOf(colorInt).toJavaColor();
        // ZOUKAN -->
        fillDevice(color);
        // ZOUKAN <--
    }

    public void drawColor(@ColorLong long colorLong) {
        java.awt.Color color = Color.valueOf(colorLong).toJavaColor();
        // ZOUKAN -->
        fillDevice(color);
        // ZOUKAN <--
    }

    // ZOUKAN -->
    /** Fills the whole clip, whatever the matrix, as drawColor does on Android. */
    private void fillDevice(java.awt.Color color) {
        AffineTransform saved = canvas.getTransform();
        canvas.setTransform(new AffineTransform());
        canvas.setComposite(AlphaComposite.SrcOver);
        canvas.setColor(color);
        canvas.fillRect(0, 0, canvasImage.getWidth(), canvasImage.getHeight());
        canvas.setTransform(saved);
    }
    // ZOUKAN <--

    public void drawPoint(float x, float y, Paint paint) {
        applyPaint(paint);
        Shape shape = paintToShape(paint, x, y);
        if (paint.getStyle() == Paint.Style.FILL) {
            canvas.fill(shape);
        } else {
            canvas.draw(shape);
        }
    }

    private void applyPaint(Paint paint) {
        canvas.setFont(paint.getTypeface().getFont());
        // ZOUKAN -->
        java.awt.Color color = Color.valueOf(paint.getColorLong()).toJavaColor();
        ColorFilter filter = paint.getColorFilter();
        if (filter instanceof ColorMatrixColorFilter) {
            color = new java.awt.Color(((ColorMatrixColorFilter) filter).filter(color.getRGB()), true);
        }
        canvas.setColor(color);
        canvas.setComposite(AlphaComposite.SrcOver);
        canvas.setStroke(new BasicStroke(paint.getStrokeWidth(), paintToStrokeCap(paint),
                paintToStrokeJoin(paint), Math.max(1f, paint.getStrokeMiter())));
        // ZOUKAN <--
        if (paint.isAntiAlias()) {
            canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        } else {
            canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        }
        if (paint.isDither()) {
            canvas.setRenderingHint(RenderingHints.KEY_DITHERING, RenderingHints.VALUE_DITHER_ENABLE);
        } else {
            canvas.setRenderingHint(RenderingHints.KEY_DITHERING, RenderingHints.VALUE_DITHER_DISABLE);
        }
        // TODO: use more from paint?
    }

    // ZOUKAN -->
    private static int paintToStrokeJoin(Paint paint) {
        switch (paint.getStrokeJoin()) {
            case ROUND:
                return BasicStroke.JOIN_ROUND;
            case BEVEL:
                return BasicStroke.JOIN_BEVEL;
            default:
                return BasicStroke.JOIN_MITER;
        }
    }
    // ZOUKAN <--

    private static int paintToStrokeCap(Paint paint) {
        switch (paint.getStrokeCap()) {
            case BUTT:
                return BasicStroke.CAP_BUTT;
            case SQUARE:
                return BasicStroke.CAP_SQUARE;
            case ROUND:
                return BasicStroke.CAP_ROUND;
            default:
                throw new UnsupportedOperationException("Stroke cap " + paint.getStrokeCap() + " not supported");
        }
    }

    private static Shape paintToShape(Paint paint, float x, float y) {
        int width = (int)(paint.getStrokeWidth() * 2);
        if (width <= 0) width = 1;
        int upLeftX = (int) (x - (float) width / 2);
        int upLeftY = (int) (y - (float) width / 2);
        switch (paint.getStrokeCap()) {
            case BUTT:
                return new Rectangle((int)x, (int)y, 1, 1);
            case SQUARE:
                return new Rectangle(upLeftX, upLeftY, width, width);
            case ROUND:
                return new Ellipse2D.Float(upLeftX, upLeftY, width, width);
            default:
                throw new UnsupportedOperationException("Stroke cap " + paint.getStrokeCap() + " not supported");
        }
    }
}
