// ZOUKAN -->
package android.media;

import android.os.Handler;
import android.view.Surface;

/**
 * An image reader that never receives frames: the desktop's WebViews render headless, so a
 * WebView hosted on its surface (Comix's offscreen reader) works without one.
 */
public class ImageReader implements AutoCloseable {
    public interface OnImageAvailableListener {
        void onImageAvailable(ImageReader reader);
    }

    private final int mWidth;
    private final int mHeight;
    private final int mFormat;
    private final int mMaxImages;
    private final Surface mSurface = new Surface();

    protected ImageReader(int width, int height, int format, int maxImages) {
        mWidth = width;
        mHeight = height;
        mFormat = format;
        mMaxImages = maxImages;
    }

    public static ImageReader newInstance(int width, int height, int format, int maxImages) {
        return new ImageReader(width, height, format, maxImages);
    }

    public int getWidth() {
        return mWidth;
    }

    public int getHeight() {
        return mHeight;
    }

    public int getImageFormat() {
        return mFormat;
    }

    public int getMaxImages() {
        return mMaxImages;
    }

    public Surface getSurface() {
        return mSurface;
    }

    public void setOnImageAvailableListener(OnImageAvailableListener listener, Handler handler) {
    }

    public Image acquireLatestImage() {
        return null;
    }

    public Image acquireNextImage() {
        return null;
    }

    @Override
    public void close() {
        mSurface.release();
    }
}
// ZOUKAN <--
