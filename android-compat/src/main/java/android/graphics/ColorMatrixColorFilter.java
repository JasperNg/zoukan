package android.graphics;

// ZOUKAN -->
// Shadows android.jar's stub ColorMatrixColorFilter. Canvas applies it to the colors it draws.
public class ColorMatrixColorFilter extends ColorFilter {
    private final ColorMatrix mMatrix = new ColorMatrix();

    public ColorMatrixColorFilter(ColorMatrix matrix) {
        mMatrix.set(matrix);
    }

    public ColorMatrixColorFilter(float[] array) {
        if (array.length < 20) {
            throw new ArrayIndexOutOfBoundsException();
        }
        mMatrix.set(array);
    }

    public void getColorMatrix(ColorMatrix colorMatrix) {
        colorMatrix.set(mMatrix);
    }

    /** [color] (non-premultiplied ARGB) through the matrix. */
    int filter(int color) {
        float[] m = mMatrix.getArray();
        int a = color >>> 24, r = (color >> 16) & 0xff, g = (color >> 8) & 0xff, b = color & 0xff;
        int nr = clamp(m[0] * r + m[1] * g + m[2] * b + m[3] * a + m[4]);
        int ng = clamp(m[5] * r + m[6] * g + m[7] * b + m[8] * a + m[9]);
        int nb = clamp(m[10] * r + m[11] * g + m[12] * b + m[13] * a + m[14]);
        int na = clamp(m[15] * r + m[16] * g + m[17] * b + m[18] * a + m[19]);
        return (na << 24) | (nr << 16) | (ng << 8) | nb;
    }

    /** Filters non-premultiplied ARGB [pixels] in place. */
    void filter(int[] pixels) {
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = filter(pixels[i]);
        }
    }

    private static int clamp(float v) {
        int i = (int) (v + 0.5f);
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }
}
// ZOUKAN <--
