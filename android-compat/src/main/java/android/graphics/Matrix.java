package android.graphics;

import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Point2D;

// ZOUKAN -->
// Shadows android.jar's stub Matrix with one backed by java.awt.geom.AffineTransform.
// Perspective is not supported: the perspective row is always [0, 0, 1].
//
// Android's preX(m) is this * m (m applied first) like AffineTransform.concatenate, and postX(m)
// is m * this (m applied last) like AffineTransform.preConcatenate.
public class Matrix {
    public static final int MSCALE_X = 0;
    public static final int MSKEW_X = 1;
    public static final int MTRANS_X = 2;
    public static final int MSKEW_Y = 3;
    public static final int MSCALE_Y = 4;
    public static final int MTRANS_Y = 5;
    public static final int MPERSP_0 = 6;
    public static final int MPERSP_1 = 7;
    public static final int MPERSP_2 = 8;

    public enum ScaleToFit {
        FILL,
        START,
        CENTER,
        END
    }

    private final AffineTransform transform = new AffineTransform();

    public Matrix() {
    }

    public Matrix(Matrix src) {
        if (src != null) {
            transform.setTransform(src.transform);
        }
    }

    /** The affine transform this matrix holds (a copy). */
    AffineTransform toAffineTransform() {
        return new AffineTransform(transform);
    }

    public boolean isIdentity() {
        return transform.isIdentity();
    }

    public boolean isAffine() {
        return true;
    }

    public boolean rectStaysRect() {
        double m00 = transform.getScaleX(), m01 = transform.getShearX();
        double m10 = transform.getShearY(), m11 = transform.getScaleY();
        return (m01 == 0 && m10 == 0 && m00 != 0 && m11 != 0) || (m00 == 0 && m11 == 0 && m01 != 0 && m10 != 0);
    }

    public void set(Matrix src) {
        if (src == null) {
            reset();
        } else {
            transform.setTransform(src.transform);
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Matrix)) return false;
        return transform.equals(((Matrix) obj).transform);
    }

    @Override
    public int hashCode() {
        return transform.hashCode();
    }

    public void reset() {
        transform.setToIdentity();
    }

    public void setTranslate(float dx, float dy) {
        transform.setToTranslation(dx, dy);
    }

    public void setScale(float sx, float sy, float px, float py) {
        transform.setToScale(sx, sy);
        pivot(transform, px, py);
    }

    public void setScale(float sx, float sy) {
        transform.setToScale(sx, sy);
    }

    public void setRotate(float degrees, float px, float py) {
        transform.setToRotation(Math.toRadians(degrees), px, py);
    }

    public void setRotate(float degrees) {
        transform.setToRotation(Math.toRadians(degrees));
    }

    public void setSinCos(float sinValue, float cosValue, float px, float py) {
        transform.setTransform(cosValue, sinValue, -sinValue, cosValue, 0, 0);
        pivot(transform, px, py);
    }

    public void setSinCos(float sinValue, float cosValue) {
        transform.setTransform(cosValue, sinValue, -sinValue, cosValue, 0, 0);
    }

    public void setSkew(float kx, float ky, float px, float py) {
        transform.setToShear(kx, ky);
        pivot(transform, px, py);
    }

    public void setSkew(float kx, float ky) {
        transform.setToShear(kx, ky);
    }

    public boolean setConcat(Matrix a, Matrix b) {
        AffineTransform result = new AffineTransform(a.transform);
        result.concatenate(b.transform);
        transform.setTransform(result);
        return true;
    }

    public boolean preTranslate(float dx, float dy) {
        transform.translate(dx, dy);
        return true;
    }

    public boolean preScale(float sx, float sy, float px, float py) {
        transform.concatenate(pivoted(AffineTransform.getScaleInstance(sx, sy), px, py));
        return true;
    }

    public boolean preScale(float sx, float sy) {
        transform.scale(sx, sy);
        return true;
    }

    public boolean preRotate(float degrees, float px, float py) {
        transform.rotate(Math.toRadians(degrees), px, py);
        return true;
    }

    public boolean preRotate(float degrees) {
        transform.rotate(Math.toRadians(degrees));
        return true;
    }

    public boolean preSkew(float kx, float ky, float px, float py) {
        transform.concatenate(pivoted(AffineTransform.getShearInstance(kx, ky), px, py));
        return true;
    }

    public boolean preSkew(float kx, float ky) {
        transform.shear(kx, ky);
        return true;
    }

    public boolean preConcat(Matrix other) {
        transform.concatenate(other.transform);
        return true;
    }

    public boolean postTranslate(float dx, float dy) {
        transform.preConcatenate(AffineTransform.getTranslateInstance(dx, dy));
        return true;
    }

    public boolean postScale(float sx, float sy, float px, float py) {
        transform.preConcatenate(pivoted(AffineTransform.getScaleInstance(sx, sy), px, py));
        return true;
    }

    public boolean postScale(float sx, float sy) {
        transform.preConcatenate(AffineTransform.getScaleInstance(sx, sy));
        return true;
    }

    public boolean postRotate(float degrees, float px, float py) {
        transform.preConcatenate(AffineTransform.getRotateInstance(Math.toRadians(degrees), px, py));
        return true;
    }

    public boolean postRotate(float degrees) {
        transform.preConcatenate(AffineTransform.getRotateInstance(Math.toRadians(degrees)));
        return true;
    }

    public boolean postSkew(float kx, float ky, float px, float py) {
        transform.preConcatenate(pivoted(AffineTransform.getShearInstance(kx, ky), px, py));
        return true;
    }

    public boolean postSkew(float kx, float ky) {
        transform.preConcatenate(AffineTransform.getShearInstance(kx, ky));
        return true;
    }

    public boolean postConcat(Matrix other) {
        transform.preConcatenate(other.transform);
        return true;
    }

    public boolean setRectToRect(RectF src, RectF dst, ScaleToFit stf) {
        if (dst == null || src == null) {
            throw new NullPointerException();
        }
        if (src.isEmpty()) {
            reset();
            return false;
        }
        if (dst.isEmpty()) {
            transform.setTransform(0, 0, 0, 0, 0, 0);
            return true;
        }
        double sx = dst.width() / (double) src.width();
        double sy = dst.height() / (double) src.height();
        double tx, ty;
        if (stf == ScaleToFit.FILL) {
            tx = dst.left - src.left * sx;
            ty = dst.top - src.top * sy;
        } else {
            double s = Math.min(sx, sy);
            sx = sy = s;
            double diffX = dst.width() - src.width() * s;
            double diffY = dst.height() - src.height() * s;
            if (stf == ScaleToFit.CENTER) {
                diffX /= 2;
                diffY /= 2;
            } else if (stf == ScaleToFit.START) {
                diffX = 0;
                diffY = 0;
            }
            tx = dst.left - src.left * s + diffX;
            ty = dst.top - src.top * s + diffY;
        }
        transform.setTransform(sx, 0, 0, sy, tx, ty);
        return true;
    }

    public boolean setPolyToPoly(float[] src, int srcIndex, float[] dst, int dstIndex, int pointCount) {
        if (pointCount > 4) {
            throw new IllegalArgumentException();
        }
        if (src.length < srcIndex + pointCount * 2 || dst.length < dstIndex + pointCount * 2) {
            throw new ArrayIndexOutOfBoundsException();
        }
        switch (pointCount) {
            case 0:
                reset();
                return true;
            case 1:
                setTranslate(dst[dstIndex] - src[srcIndex], dst[dstIndex + 1] - src[srcIndex + 1]);
                return true;
            case 2: {
                // A similarity: rotation and uniform scale about the first point, then a translation
                double sx = src[srcIndex + 2] - src[srcIndex], sy = src[srcIndex + 3] - src[srcIndex + 1];
                double dx = dst[dstIndex + 2] - dst[dstIndex], dy = dst[dstIndex + 3] - dst[dstIndex + 1];
                double len = sx * sx + sy * sy;
                if (len == 0) return false;
                double cos = (sx * dx + sy * dy) / len;
                double sin = (sx * dy - sy * dx) / len;
                AffineTransform t = new AffineTransform(cos, sin, -sin, cos, 0, 0);
                Point2D p = t.transform(new Point2D.Double(src[srcIndex], src[srcIndex + 1]), null);
                transform.setTransform(cos, sin, -sin, cos, dst[dstIndex] - p.getX(), dst[dstIndex + 1] - p.getY());
                return true;
            }
            case 3: {
                AffineTransform from = triangle(src, srcIndex);
                AffineTransform to = triangle(dst, dstIndex);
                try {
                    from.invert();
                } catch (NoninvertibleTransformException e) {
                    return false;
                }
                to.concatenate(from);
                transform.setTransform(to);
                return true;
            }
            default:
                // Four points need a perspective transform, which this Matrix cannot hold
                return false;
        }
    }

    public boolean invert(Matrix inverse) {
        try {
            AffineTransform inv = transform.createInverse();
            inverse.transform.setTransform(inv);
            return true;
        } catch (NoninvertibleTransformException e) {
            return false;
        }
    }

    public void mapPoints(float[] dst, int dstIndex, float[] src, int srcIndex, int pointCount) {
        transform.transform(src, srcIndex, dst, dstIndex, pointCount);
    }

    public void mapVectors(float[] dst, int dstIndex, float[] src, int srcIndex, int vectorCount) {
        float[] copy = new float[vectorCount * 2];
        System.arraycopy(src, srcIndex, copy, 0, copy.length);
        double[] in = new double[copy.length];
        for (int i = 0; i < copy.length; i++) in[i] = copy[i];
        double[] out = new double[copy.length];
        transform.deltaTransform(in, 0, out, 0, vectorCount);
        for (int i = 0; i < out.length; i++) dst[dstIndex + i] = (float) out[i];
    }

    public void mapPoints(float[] dst, float[] src) {
        if (dst.length != src.length) {
            throw new ArrayIndexOutOfBoundsException();
        }
        mapPoints(dst, 0, src, 0, dst.length >> 1);
    }

    public void mapVectors(float[] dst, float[] src) {
        if (dst.length != src.length) {
            throw new ArrayIndexOutOfBoundsException();
        }
        mapVectors(dst, 0, src, 0, dst.length >> 1);
    }

    public void mapPoints(float[] pts) {
        mapPoints(pts, 0, pts, 0, pts.length >> 1);
    }

    public void mapVectors(float[] vecs) {
        mapVectors(vecs, 0, vecs, 0, vecs.length >> 1);
    }

    public boolean mapRect(RectF dst, RectF src) {
        if (dst == null || src == null) {
            throw new NullPointerException();
        }
        float[] pts = {src.left, src.top, src.right, src.top, src.right, src.bottom, src.left, src.bottom};
        mapPoints(pts);
        float l = Math.min(Math.min(pts[0], pts[2]), Math.min(pts[4], pts[6]));
        float t = Math.min(Math.min(pts[1], pts[3]), Math.min(pts[5], pts[7]));
        float r = Math.max(Math.max(pts[0], pts[2]), Math.max(pts[4], pts[6]));
        float b = Math.max(Math.max(pts[1], pts[3]), Math.max(pts[5], pts[7]));
        dst.set(l, t, r, b);
        return rectStaysRect();
    }

    public boolean mapRect(RectF rect) {
        return mapRect(rect, rect);
    }

    public float mapRadius(float radius) {
        float[] vecs = {radius, 0, 0, radius};
        mapVectors(vecs);
        double d0 = Math.hypot(vecs[0], vecs[1]);
        double d1 = Math.hypot(vecs[2], vecs[3]);
        return (float) Math.sqrt(d0 * d1);
    }

    public void getValues(float[] values) {
        if (values.length < 9) {
            throw new ArrayIndexOutOfBoundsException();
        }
        values[MSCALE_X] = (float) transform.getScaleX();
        values[MSKEW_X] = (float) transform.getShearX();
        values[MTRANS_X] = (float) transform.getTranslateX();
        values[MSKEW_Y] = (float) transform.getShearY();
        values[MSCALE_Y] = (float) transform.getScaleY();
        values[MTRANS_Y] = (float) transform.getTranslateY();
        values[MPERSP_0] = 0f;
        values[MPERSP_1] = 0f;
        values[MPERSP_2] = 1f;
    }

    public void setValues(float[] values) {
        if (values.length < 9) {
            throw new ArrayIndexOutOfBoundsException();
        }
        float w = values[MPERSP_2] == 0f ? 1f : values[MPERSP_2];
        transform.setTransform(
                values[MSCALE_X] / w, values[MSKEW_Y] / w,
                values[MSKEW_X] / w, values[MSCALE_Y] / w,
                values[MTRANS_X] / w, values[MTRANS_Y] / w);
    }

    @Override
    public String toString() {
        return "Matrix{" + toShortString() + "}";
    }

    public String toShortString() {
        float[] values = new float[9];
        getValues(values);
        return "[" + values[0] + ", " + values[1] + ", " + values[2] + "]["
                + values[3] + ", " + values[4] + ", " + values[5] + "]["
                + values[6] + ", " + values[7] + ", " + values[8] + "]";
    }

    /** Makes [t] act about (px, py): translate(p) * t * translate(-p). */
    private static AffineTransform pivoted(AffineTransform t, float px, float py) {
        pivot(t, px, py);
        return t;
    }

    private static void pivot(AffineTransform t, float px, float py) {
        if (px == 0f && py == 0f) return;
        t.preConcatenate(AffineTransform.getTranslateInstance(px, py));
        t.translate(-px, -py);
    }

    /** The transform taking the unit triangle (0,0),(1,0),(0,1) to the three points at [index]. */
    private static AffineTransform triangle(float[] pts, int index) {
        double x0 = pts[index], y0 = pts[index + 1];
        return new AffineTransform(
                pts[index + 2] - x0, pts[index + 3] - y0,
                pts[index + 4] - x0, pts[index + 5] - y0,
                x0, y0);
    }
}
// ZOUKAN <--
