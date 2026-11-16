// ZOUKAN -->
package android.util;

/** Immutable width and height in pixels, as Android's android.util.Size (shadows the android.jar stub). */
public final class Size {
    private final int width;
    private final int height;

    public Size(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) return false;
        if (this == obj) return true;
        if (obj instanceof Size) {
            Size other = (Size) obj;
            return width == other.width && height == other.height;
        }
        return false;
    }

    @Override
    public String toString() {
        return width + "x" + height;
    }

    @Override
    public int hashCode() {
        // Same as Android: height ^ (width rotated by half an int)
        return height ^ ((width << (Integer.SIZE / 2)) | (width >>> (Integer.SIZE / 2)));
    }

    /** Parses "WxH" or "W*H"; throws NumberFormatException when it is neither. */
    public static Size parseSize(String string) throws NumberFormatException {
        if (string == null) throw new NullPointerException("string must not be null");
        int sep = string.indexOf('*');
        if (sep < 0) sep = string.indexOf('x');
        if (sep < 0) throw new NumberFormatException("Invalid Size: \"" + string + "\"");
        try {
            return new Size(Integer.parseInt(string.substring(0, sep)), Integer.parseInt(string.substring(sep + 1)));
        } catch (NumberFormatException e) {
            throw new NumberFormatException("Invalid Size: \"" + string + "\"");
        }
    }
}
// ZOUKAN <--
