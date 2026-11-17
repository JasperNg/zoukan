package android.graphics;

import android.os.Parcel;
import android.os.Parcelable;

// ZOUKAN -->
// Shadows android.jar's stub Point with a plain implementation of the public API.
public class Point implements Parcelable {
    public int x;
    public int y;

    public Point() {
    }

    public Point(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public Point(Point src) {
        if (src != null) {
            x = src.x;
            y = src.y;
        }
    }

    public void set(int x, int y) {
        this.x = x;
        this.y = y;
    }

    public final void negate() {
        x = -x;
        y = -y;
    }

    public final void offset(int dx, int dy) {
        x += dx;
        y += dy;
    }

    public final boolean equals(int x, int y) {
        return this.x == x && this.y == y;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Point)) return false;
        Point p = (Point) o;
        return x == p.x && y == p.y;
    }

    @Override
    public int hashCode() {
        return 31 * x + y;
    }

    @Override
    public String toString() {
        return "Point(" + x + ", " + y + ")";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(x);
        out.writeInt(y);
    }

    public void readFromParcel(Parcel in) {
        x = in.readInt();
        y = in.readInt();
    }

    public static final Parcelable.Creator<Point> CREATOR = new Parcelable.Creator<Point>() {
        @Override
        public Point createFromParcel(Parcel in) {
            Point p = new Point();
            p.readFromParcel(in);
            return p;
        }

        @Override
        public Point[] newArray(int size) {
            return new Point[size];
        }
    };
}
// ZOUKAN <--
