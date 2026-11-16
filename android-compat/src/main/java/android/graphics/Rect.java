package android.graphics;

import android.os.Parcelable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import android.os.Parcel;

public final class Rect {
    public int left;
    public int top;
    public int right;
    public int bottom;

    private static final class UnflattenHelper {
        private static final Pattern FLATTENED_PATTERN = Pattern.compile(
            "(-?\\d+) (-?\\d+) (-?\\d+) (-?\\d+)");

        static Matcher getMatcher(String str) {
            return FLATTENED_PATTERN.matcher(str);
        }
    }

    public Rect() {
    }

    public Rect(int left, int top, int right, int bottom) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    public Rect(Rect r) {
        if (r == null) {
            this.left = 0;
            this.top = 0;
            this.right = 0;
            this.bottom = 0;
        } else {
            this.left = r.left;
            this.top = r.top;
            this.right = r.right;
            this.bottom = r.bottom;
        }
    }

    public void set(int left, int top, int right, int bottom) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    public void set(Rect r) {
        this.left = r.left;
        this.top = r.top;
        this.right = r.right;
        this.bottom = r.bottom;
    }

    public final int getWidth() {
        return right - left;
    }

    public final int getHeight() {
        return bottom - top;
    }

    // ZOUKAN -->
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Rect r = (Rect) o;
        return left == r.left && top == r.top && right == r.right && bottom == r.bottom;
    }

    @Override
    public int hashCode() {
        int result = left;
        result = 31 * result + top;
        result = 31 * result + right;
        result = 31 * result + bottom;
        return result;
    }

    @Override
    public String toString() {
        return "Rect(" + left + ", " + top + " - " + right + ", " + bottom + ")";
    }

    public final boolean isEmpty() {
        return left >= right || top >= bottom;
    }

    public final int width() {
        return right - left;
    }

    public final int height() {
        return bottom - top;
    }

    public final int centerX() {
        return (left + right) >> 1;
    }

    public final int centerY() {
        return (top + bottom) >> 1;
    }

    public final float exactCenterX() {
        return (left + right) * 0.5f;
    }

    public final float exactCenterY() {
        return (top + bottom) * 0.5f;
    }

    public void setEmpty() {
        left = right = top = bottom = 0;
    }

    public void offset(int dx, int dy) {
        left += dx;
        top += dy;
        right += dx;
        bottom += dy;
    }

    public void offsetTo(int newLeft, int newTop) {
        right += newLeft - left;
        bottom += newTop - top;
        left = newLeft;
        top = newTop;
    }

    public void inset(int dx, int dy) {
        left += dx;
        top += dy;
        right -= dx;
        bottom -= dy;
    }

    public boolean contains(int x, int y) {
        return left < right && top < bottom && x >= left && x < right && y >= top && y < bottom;
    }

    public boolean contains(int left, int top, int right, int bottom) {
        return this.left < this.right && this.top < this.bottom
                && this.left <= left && this.top <= top && this.right >= right && this.bottom >= bottom;
    }

    public boolean contains(Rect r) {
        return contains(r.left, r.top, r.right, r.bottom);
    }

    public boolean intersect(int left, int top, int right, int bottom) {
        if (this.left < right && left < this.right && this.top < bottom && top < this.bottom) {
            if (this.left < left) this.left = left;
            if (this.top < top) this.top = top;
            if (this.right > right) this.right = right;
            if (this.bottom > bottom) this.bottom = bottom;
            return true;
        }
        return false;
    }

    public boolean intersect(Rect r) {
        return intersect(r.left, r.top, r.right, r.bottom);
    }

    public boolean intersects(int left, int top, int right, int bottom) {
        return this.left < right && left < this.right && this.top < bottom && top < this.bottom;
    }

    public static boolean intersects(Rect a, Rect b) {
        return a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom;
    }

    public void union(int left, int top, int right, int bottom) {
        if ((left < right) && (top < bottom)) {
            if ((this.left < this.right) && (this.top < this.bottom)) {
                if (this.left > left) this.left = left;
                if (this.top > top) this.top = top;
                if (this.right < right) this.right = right;
                if (this.bottom < bottom) this.bottom = bottom;
            } else {
                this.left = left;
                this.top = top;
                this.right = right;
                this.bottom = bottom;
            }
        }
    }

    public void union(Rect r) {
        union(r.left, r.top, r.right, r.bottom);
    }

    public void sort() {
        if (left > right) {
            int temp = left;
            left = right;
            right = temp;
        }
        if (top > bottom) {
            int temp = top;
            top = bottom;
            bottom = temp;
        }
    }
    // ZOUKAN <--

    public static Rect unflattenFromString(String str) {
        if (str.isEmpty()) {
            return null;
        }

        Matcher matcher = UnflattenHelper.getMatcher(str);
        if (!matcher.matches()) {
            return null;
        }

        return new Rect(Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3)),
                        Integer.parseInt(matcher.group(4)));
    }

    public String toShortString() {
        return toShortString(new StringBuilder(32));
    }
    
    public String toShortString(StringBuilder sb) {
        sb.setLength(0);
        sb.append('['); sb.append(left); sb.append(',');
        sb.append(top); sb.append("]["); sb.append(right);
        sb.append(','); sb.append(bottom); sb.append(']');
        return sb.toString();
    }

    public String flattenToString() {
        StringBuilder sb = new StringBuilder(32);
        sb.append(left);
        sb.append(' ');
        sb.append(top);
        sb.append(' ');
        sb.append(right);
        sb.append(' ');
        sb.append(bottom);
        return sb.toString();
    }

    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(left);
        out.writeInt(top);
        out.writeInt(right);
        out.writeInt(bottom);
    }

    public static final Parcelable.Creator<Rect> CREATOR = new Parcelable.Creator<Rect>() {
        @Override
        public Rect createFromParcel(Parcel in) {
            Rect r = new Rect();
            r.readFromParcel(in);
            return r;
        }

        @Override
        public Rect[] newArray(int size) {
            return new Rect[size];
        }
    };

    public void readFromParcel(Parcel in) {
        left = in.readInt();
        top = in.readInt();
        right = in.readInt();
        bottom = in.readInt();
    }
}
