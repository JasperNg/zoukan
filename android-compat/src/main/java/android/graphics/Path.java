package android.graphics;

import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.FlatteningPathIterator;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

// ZOUKAN -->
// Shadows android.jar's stub Path with one backed by java.awt.geom.Path2D. isConvex and isRect
// stay stubs. Android angles are in degrees and clockwise on screen (y down); Arc2D angles are
// counter-clockwise on screen, so they are negated.
public class Path {
    public enum Direction {
        CW,
        CCW
    }

    public enum FillType {
        WINDING,
        EVEN_ODD,
        INVERSE_WINDING,
        INVERSE_EVEN_ODD
    }

    public enum Op {
        DIFFERENCE,
        INTERSECT,
        UNION,
        XOR,
        REVERSE_DIFFERENCE
    }

    private Path2D.Float path = new Path2D.Float(Path2D.WIND_NON_ZERO);
    private FillType fillType = FillType.WINDING;
    /** Where the open contour started, for an implicit moveTo after close(). */
    private float contourStartX;
    private float contourStartY;

    public Path() {
    }

    public Path(Path src) {
        if (src != null) {
            set(src);
        }
    }

    /** The path as an AWT shape, with its winding rule (inverse fill types are the caller's job). */
    Shape toShape() {
        Path2D.Float copy = new Path2D.Float(path);
        copy.setWindingRule(isEvenOdd() ? Path2D.WIND_EVEN_ODD : Path2D.WIND_NON_ZERO);
        return copy;
    }

    /** Appends [shape] as new contours. */
    void addShape(Shape shape) {
        path.append(shape, false);
        updateContourStart();
    }

    private boolean isEvenOdd() {
        return fillType == FillType.EVEN_ODD || fillType == FillType.INVERSE_EVEN_ODD;
    }

    public void reset() {
        path.reset();
        fillType = FillType.WINDING;
        contourStartX = contourStartY = 0;
    }

    public void rewind() {
        path.reset();
        contourStartX = contourStartY = 0;
    }

    public void set(Path src) {
        if (this == src) return;
        path = new Path2D.Float(src.path);
        fillType = src.fillType;
        contourStartX = src.contourStartX;
        contourStartY = src.contourStartY;
    }

    public boolean op(Path path, Op op) {
        return op(this, path, op);
    }

    public boolean op(Path path1, Path path2, Op op) {
        Area a = new Area(path1.toShape());
        Area b = new Area(path2.toShape());
        switch (op) {
            case DIFFERENCE:
                a.subtract(b);
                break;
            case INTERSECT:
                a.intersect(b);
                break;
            case UNION:
                a.add(b);
                break;
            case XOR:
                a.exclusiveOr(b);
                break;
            case REVERSE_DIFFERENCE:
                b.subtract(a);
                a = b;
                break;
        }
        this.path = new Path2D.Float(a);
        this.path.setWindingRule(Path2D.WIND_NON_ZERO);
        this.fillType = FillType.WINDING;
        updateContourStart();
        return true;
    }

    public boolean isConvex() {
        throw new RuntimeException("Stub!");
    }

    public FillType getFillType() {
        return fillType;
    }

    public void setFillType(FillType ft) {
        fillType = ft;
    }

    public boolean isInverseFillType() {
        return fillType == FillType.INVERSE_WINDING || fillType == FillType.INVERSE_EVEN_ODD;
    }

    public void toggleInverseFillType() {
        switch (fillType) {
            case WINDING:
                fillType = FillType.INVERSE_WINDING;
                break;
            case EVEN_ODD:
                fillType = FillType.INVERSE_EVEN_ODD;
                break;
            case INVERSE_WINDING:
                fillType = FillType.WINDING;
                break;
            case INVERSE_EVEN_ODD:
                fillType = FillType.EVEN_ODD;
                break;
        }
    }

    public boolean isEmpty() {
        return path.getCurrentPoint() == null;
    }

    public boolean isRect(RectF rect) {
        throw new RuntimeException("Stub!");
    }

    public void computeBounds(RectF bounds, boolean exact) {
        if (isEmpty()) {
            bounds.set(0, 0, 0, 0);
            return;
        }
        Rectangle2D r = exact ? path.getBounds2D() : controlBounds();
        bounds.set((float) r.getMinX(), (float) r.getMinY(), (float) r.getMaxX(), (float) r.getMaxY());
    }

    /** The bounds of all points including control points, as Android computes when not exact. */
    private Rectangle2D controlBounds() {
        Rectangle2D r = null;
        float[] c = new float[6];
        for (PathIterator it = path.getPathIterator(null); !it.isDone(); it.next()) {
            int n = pointCount(it.currentSegment(c));
            for (int i = 0; i < n; i++) {
                if (r == null) {
                    r = new Rectangle2D.Float(c[i * 2], c[i * 2 + 1], 0, 0);
                } else {
                    r.add(c[i * 2], c[i * 2 + 1]);
                }
            }
        }
        return r == null ? new Rectangle2D.Float() : r;
    }

    public void incReserve(int extraPtCount) {
    }

    public void moveTo(float x, float y) {
        path.moveTo(x, y);
        contourStartX = x;
        contourStartY = y;
    }

    public void rMoveTo(float dx, float dy) {
        Point2D p = lastPoint();
        moveTo((float) p.getX() + dx, (float) p.getY() + dy);
    }

    public void lineTo(float x, float y) {
        ensureMove();
        path.lineTo(x, y);
    }

    public void rLineTo(float dx, float dy) {
        Point2D p = lastPoint();
        lineTo((float) p.getX() + dx, (float) p.getY() + dy);
    }

    public void quadTo(float x1, float y1, float x2, float y2) {
        ensureMove();
        path.quadTo(x1, y1, x2, y2);
    }

    public void rQuadTo(float dx1, float dy1, float dx2, float dy2) {
        Point2D p = lastPoint();
        float x = (float) p.getX(), y = (float) p.getY();
        quadTo(x + dx1, y + dy1, x + dx2, y + dy2);
    }

    public void cubicTo(float x1, float y1, float x2, float y2, float x3, float y3) {
        ensureMove();
        path.curveTo(x1, y1, x2, y2, x3, y3);
    }

    public void rCubicTo(float x1, float y1, float x2, float y2, float x3, float y3) {
        Point2D p = lastPoint();
        float x = (float) p.getX(), y = (float) p.getY();
        cubicTo(x + x1, y + y1, x + x2, y + y2, x + x3, y + y3);
    }

    public void arcTo(RectF oval, float startAngle, float sweepAngle, boolean forceMoveTo) {
        arcTo(oval.left, oval.top, oval.right, oval.bottom, startAngle, sweepAngle, forceMoveTo);
    }

    public void arcTo(RectF oval, float startAngle, float sweepAngle) {
        arcTo(oval.left, oval.top, oval.right, oval.bottom, startAngle, sweepAngle, false);
    }

    public void arcTo(float left, float top, float right, float bottom, float startAngle,
            float sweepAngle, boolean forceMoveTo) {
        Arc2D arc = arc(left, top, right, bottom, startAngle, sweepAngle);
        boolean connect = !forceMoveTo && !isEmpty();
        path.append(arc, connect);
        if (!connect) {
            Point2D start = arc.getStartPoint();
            contourStartX = (float) start.getX();
            contourStartY = (float) start.getY();
        }
    }

    public void close() {
        if (!isEmpty()) {
            path.closePath();
        }
    }

    public void addRect(RectF rect, Direction dir) {
        addRect(rect.left, rect.top, rect.right, rect.bottom, dir);
    }

    public void addRect(float left, float top, float right, float bottom, Direction dir) {
        moveTo(left, top);
        if (dir == Direction.CCW) {
            path.lineTo(left, bottom);
            path.lineTo(right, bottom);
            path.lineTo(right, top);
        } else {
            path.lineTo(right, top);
            path.lineTo(right, bottom);
            path.lineTo(left, bottom);
        }
        path.closePath();
    }

    public void addOval(RectF oval, Direction dir) {
        addOval(oval.left, oval.top, oval.right, oval.bottom, dir);
    }

    public void addOval(float left, float top, float right, float bottom, Direction dir) {
        addContour(new Ellipse2D.Float(left, top, right - left, bottom - top), dir);
    }

    public void addCircle(float x, float y, float radius, Direction dir) {
        addOval(x - radius, y - radius, x + radius, y + radius, dir);
    }

    public void addArc(RectF oval, float startAngle, float sweepAngle) {
        addArc(oval.left, oval.top, oval.right, oval.bottom, startAngle, sweepAngle);
    }

    public void addArc(float left, float top, float right, float bottom, float startAngle,
            float sweepAngle) {
        arcTo(left, top, right, bottom, startAngle, sweepAngle, true);
    }

    public void addRoundRect(RectF rect, float rx, float ry, Direction dir) {
        addRoundRect(rect.left, rect.top, rect.right, rect.bottom, rx, ry, dir);
    }

    public void addRoundRect(float left, float top, float right, float bottom, float rx, float ry,
            Direction dir) {
        addRoundRect(left, top, right, bottom, new float[] {rx, ry, rx, ry, rx, ry, rx, ry}, dir);
    }

    public void addRoundRect(RectF rect, float[] radii, Direction dir) {
        if (rect == null) {
            throw new NullPointerException("need rect parameter");
        }
        addRoundRect(rect.left, rect.top, rect.right, rect.bottom, radii, dir);
    }

    public void addRoundRect(float left, float top, float right, float bottom, float[] radii,
            Direction dir) {
        if (radii.length < 8) {
            throw new ArrayIndexOutOfBoundsException("radii[] needs 8 values");
        }
        addContour(roundRect(left, top, right, bottom, radii), dir);
    }

    public void addPath(Path src, float dx, float dy) {
        addShape(AffineTransform.getTranslateInstance(dx, dy).createTransformedShape(src.path));
    }

    public void addPath(Path src) {
        addShape(new Path2D.Float(src.path));
    }

    public void addPath(Path src, Matrix matrix) {
        addShape(matrix.toAffineTransform().createTransformedShape(src.path));
    }

    public void offset(float dx, float dy, Path dst) {
        Path target = dst != null ? dst : this;
        if (target != this) target.set(this);
        target.transformInPlace(AffineTransform.getTranslateInstance(dx, dy));
    }

    public void offset(float dx, float dy) {
        offset(dx, dy, null);
    }

    public void setLastPoint(float dx, float dy) {
        if (isEmpty()) {
            moveTo(dx, dy);
            return;
        }
        Path2D.Float rebuilt = new Path2D.Float(path.getWindingRule());
        float[] c = new float[6];
        int lastIndex = -1, index = 0;
        for (PathIterator it = path.getPathIterator(null); !it.isDone(); it.next(), index++) {
            if (it.currentSegment(c) != PathIterator.SEG_CLOSE) lastIndex = index;
        }
        index = 0;
        for (PathIterator it = path.getPathIterator(null); !it.isDone(); it.next(), index++) {
            int type = it.currentSegment(c);
            if (index == lastIndex) {
                int n = pointCount(type);
                c[n * 2 - 2] = dx;
                c[n * 2 - 1] = dy;
            }
            append(rebuilt, type, c);
        }
        path = rebuilt;
    }

    public void transform(Matrix matrix, Path dst) {
        Path target = dst != null ? dst : this;
        if (target != this) target.set(this);
        target.transformInPlace(matrix.toAffineTransform());
    }

    public void transform(Matrix matrix) {
        transform(matrix, null);
    }

    public float[] approximate(float acceptableError) {
        if (acceptableError < 0) {
            throw new IllegalArgumentException("AcceptableError must be greater than or equal to 0");
        }
        // Points along the path as (fraction of length, x, y)
        List<float[]> points = new ArrayList<>();
        float[] c = new float[6];
        float length = 0, lastX = 0, lastY = 0, startX = 0, startY = 0;
        PathIterator it = new FlatteningPathIterator(path.getPathIterator(null), Math.max(acceptableError, 0.0001));
        for (; !it.isDone(); it.next()) {
            int type = it.currentSegment(c);
            if (type == PathIterator.SEG_MOVETO) {
                points.add(new float[] {length, c[0], c[1]});
                lastX = startX = c[0];
                lastY = startY = c[1];
            } else {
                float x = type == PathIterator.SEG_CLOSE ? startX : c[0];
                float y = type == PathIterator.SEG_CLOSE ? startY : c[1];
                length += (float) Math.hypot(x - lastX, y - lastY);
                points.add(new float[] {length, x, y});
                lastX = x;
                lastY = y;
            }
        }
        if (points.isEmpty()) {
            return new float[] {0, 0, 0, 1, 0, 0};
        }
        if (points.size() == 1) {
            float[] p = points.get(0);
            return new float[] {0, p[1], p[2], 1, p[1], p[2]};
        }
        float[] result = new float[points.size() * 3];
        for (int i = 0; i < points.size(); i++) {
            float[] p = points.get(i);
            result[i * 3] = length == 0 ? (i == points.size() - 1 ? 1 : 0) : p[0] / length;
            result[i * 3 + 1] = p[1];
            result[i * 3 + 2] = p[2];
        }
        return result;
    }

    private void transformInPlace(AffineTransform at) {
        path.transform(at);
        float[] start = {contourStartX, contourStartY};
        at.transform(start, 0, start, 0, 1);
        contourStartX = start[0];
        contourStartY = start[1];
    }

    /** Android starts a contour at the last moveTo (or the origin) when a line comes first. */
    private void ensureMove() {
        if (path.getCurrentPoint() == null) {
            path.moveTo(0, 0);
            contourStartX = contourStartY = 0;
        } else if (lastSegmentIsClose()) {
            path.moveTo(contourStartX, contourStartY);
        }
    }

    private boolean lastSegmentIsClose() {
        float[] c = new float[6];
        int last = -1;
        for (PathIterator it = path.getPathIterator(null); !it.isDone(); it.next()) {
            last = it.currentSegment(c);
        }
        return last == PathIterator.SEG_CLOSE;
    }

    private Point2D lastPoint() {
        Point2D p = path.getCurrentPoint();
        return p != null ? p : new Point2D.Float(0, 0);
    }

    private void updateContourStart() {
        float[] c = new float[6];
        for (PathIterator it = path.getPathIterator(null); !it.isDone(); it.next()) {
            if (it.currentSegment(c) == PathIterator.SEG_MOVETO) {
                contourStartX = c[0];
                contourStartY = c[1];
            }
        }
    }

    /** Appends one closed contour, reversed if its winding does not match [dir]. */
    private void addContour(Shape shape, Direction dir) {
        List<float[]> segments = new ArrayList<>();
        List<Integer> types = new ArrayList<>();
        float[] c = new float[6];
        for (PathIterator it = shape.getPathIterator(null); !it.isDone(); it.next()) {
            int type = it.currentSegment(c);
            if (type == PathIterator.SEG_CLOSE) continue;
            types.add(type);
            segments.add(c.clone());
        }
        if (segments.isEmpty()) return;
        // Signed area of the polygon through all points: positive is clockwise on screen (y down)
        double area = 0;
        float px = segments.get(0)[0], py = segments.get(0)[1];
        for (int i = 1; i < segments.size(); i++) {
            int n = pointCount(types.get(i));
            float[] s = segments.get(i);
            for (int k = 0; k < n; k++) {
                area += px * s[k * 2 + 1] - s[k * 2] * py;
                px = s[k * 2];
                py = s[k * 2 + 1];
            }
        }
        area += px * segments.get(0)[1] - segments.get(0)[0] * py;
        boolean clockwise = area >= 0;
        if (clockwise == (dir != Direction.CCW)) {
            float[] first = segments.get(0);
            moveTo(first[0], first[1]);
            for (int i = 1; i < segments.size(); i++) {
                append(path, types.get(i), segments.get(i));
            }
        } else {
            // Walk the segments backwards: each segment ends where the previous one ended
            int last = segments.size() - 1;
            float[] end = endPoint(types.get(last), segments.get(last));
            moveTo(end[0], end[1]);
            for (int i = last; i >= 1; i--) {
                float[] s = segments.get(i);
                float[] to = endPoint(types.get(i - 1), segments.get(i - 1));
                switch (types.get(i)) {
                    case PathIterator.SEG_LINETO:
                        path.lineTo(to[0], to[1]);
                        break;
                    case PathIterator.SEG_QUADTO:
                        path.quadTo(s[0], s[1], to[0], to[1]);
                        break;
                    case PathIterator.SEG_CUBICTO:
                        path.curveTo(s[2], s[3], s[0], s[1], to[0], to[1]);
                        break;
                }
            }
        }
        path.closePath();
    }

    private static float[] endPoint(int type, float[] c) {
        int n = pointCount(type);
        return new float[] {c[n * 2 - 2], c[n * 2 - 1]};
    }

    private static int pointCount(int type) {
        switch (type) {
            case PathIterator.SEG_MOVETO:
            case PathIterator.SEG_LINETO:
                return 1;
            case PathIterator.SEG_QUADTO:
                return 2;
            case PathIterator.SEG_CUBICTO:
                return 3;
            default:
                return 0;
        }
    }

    private static void append(Path2D.Float target, int type, float[] c) {
        switch (type) {
            case PathIterator.SEG_MOVETO:
                target.moveTo(c[0], c[1]);
                break;
            case PathIterator.SEG_LINETO:
                target.lineTo(c[0], c[1]);
                break;
            case PathIterator.SEG_QUADTO:
                target.quadTo(c[0], c[1], c[2], c[3]);
                break;
            case PathIterator.SEG_CUBICTO:
                target.curveTo(c[0], c[1], c[2], c[3], c[4], c[5]);
                break;
            case PathIterator.SEG_CLOSE:
                target.closePath();
                break;
        }
    }

    static Arc2D arc(float left, float top, float right, float bottom, float startAngle, float sweepAngle) {
        return new Arc2D.Float(left, top, right - left, bottom - top, -startAngle, -sweepAngle, Arc2D.OPEN);
    }

    /** A clockwise round rect; radii are (rx, ry) for top-left, top-right, bottom-right, bottom-left. */
    static Path2D.Float roundRect(float l, float t, float r, float b, float[] radii) {
        float w = r - l, h = b - t;
        float[] rad = radii.clone();
        // Scale the radii down so that adjacent corners do not overlap, as Skia does
        float scale = 1f;
        scale = Math.min(scale, fit(w, rad[0] + rad[2]));
        scale = Math.min(scale, fit(w, rad[4] + rad[6]));
        scale = Math.min(scale, fit(h, rad[1] + rad[7]));
        scale = Math.min(scale, fit(h, rad[3] + rad[5]));
        for (int i = 0; i < 8; i++) rad[i] = Math.max(0, rad[i] * scale);
        final float k = 0.5522848f;
        Path2D.Float p = new Path2D.Float();
        p.moveTo(l + rad[0], t);
        p.lineTo(r - rad[2], t);
        p.curveTo(r - rad[2] + rad[2] * k, t, r, t + rad[3] - rad[3] * k, r, t + rad[3]);
        p.lineTo(r, b - rad[5]);
        p.curveTo(r, b - rad[5] + rad[5] * k, r - rad[4] + rad[4] * k, b, r - rad[4], b);
        p.lineTo(l + rad[6], b);
        p.curveTo(l + rad[6] - rad[6] * k, b, l, b - rad[7] + rad[7] * k, l, b - rad[7]);
        p.lineTo(l, t + rad[1]);
        p.curveTo(l, t + rad[1] - rad[1] * k, l + rad[0] - rad[0] * k, t, l + rad[0], t);
        p.closePath();
        return p;
    }

    private static float fit(float size, float sum) {
        return sum > size && sum > 0 ? size / sum : 1f;
    }
}
// ZOUKAN <--
