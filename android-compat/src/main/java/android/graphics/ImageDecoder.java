package android.graphics;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ContentResolver;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.Size;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Iterator;
import java.util.concurrent.Callable;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

// ZOUKAN -->
// Shadows android.jar's stub ImageDecoder. Decodes with ImageIO (and its WebP/TwelveMonkeys
// plugins) into an ARGB_8888 Bitmap, honouring the header listener's target size, crop and post
// processor. Sources from Resources, a ContentResolver or an AssetFileDescriptor and
// decodeDrawable stay stubs.
public final class ImageDecoder implements AutoCloseable {
    public static final int ALLOCATOR_DEFAULT = 0;
    public static final int ALLOCATOR_SOFTWARE = 1;
    public static final int ALLOCATOR_SHARED_MEMORY = 2;
    public static final int ALLOCATOR_HARDWARE = 3;
    public static final int MEMORY_POLICY_LOW_RAM = 0;
    public static final int MEMORY_POLICY_DEFAULT = 1;

    public static abstract class Source {
        Source() {
        }

        abstract byte[] readAll() throws IOException;
    }

    private static final class ByteArraySource extends Source {
        private final byte[] data;
        private final int offset;
        private final int length;

        ByteArraySource(byte[] data, int offset, int length) {
            this.data = data;
            this.offset = offset;
            this.length = length;
        }

        @Override
        byte[] readAll() {
            byte[] copy = new byte[length];
            System.arraycopy(data, offset, copy, 0, length);
            return copy;
        }
    }

    private static final class ByteBufferSource extends Source {
        private final ByteBuffer buffer;

        ByteBufferSource(ByteBuffer buffer) {
            this.buffer = buffer;
        }

        @Override
        byte[] readAll() {
            ByteBuffer b = buffer.duplicate();
            byte[] bytes = new byte[b.remaining()];
            b.get(bytes);
            return bytes;
        }
    }

    private static final class FileSource extends Source {
        private final File file;

        FileSource(File file) {
            this.file = file;
        }

        @Override
        byte[] readAll() throws IOException {
            return Files.readAllBytes(file.toPath());
        }
    }

    private static final class AssetSource extends Source {
        private final AssetManager assets;
        private final String fileName;

        AssetSource(AssetManager assets, String fileName) {
            this.assets = assets;
            this.fileName = fileName;
        }

        @Override
        byte[] readAll() throws IOException {
            try (InputStream in = assets.open(fileName)) {
                return in.readAllBytes();
            }
        }
    }

    public static class ImageInfo {
        private final int width;
        private final int height;
        private final String mimeType;

        ImageInfo(int width, int height, String mimeType) {
            this.width = width;
            this.height = height;
            this.mimeType = mimeType;
        }

        /** android.util.Size is shadowed in android-compat. */
        @NonNull
        public Size getSize() {
            return new Size(width, height);
        }

        @NonNull
        public String getMimeType() {
            return mimeType;
        }

        public boolean isAnimated() {
            return false;
        }

        @Nullable
        public ColorSpace getColorSpace() {
            return ColorSpace.get(ColorSpace.Named.SRGB);
        }
    }

    public static final class DecodeException extends IOException {
        public static final int SOURCE_EXCEPTION = 1;
        public static final int SOURCE_INCOMPLETE = 2;
        public static final int SOURCE_MALFORMED_DATA = 3;

        private final int error;
        private final transient Source source;

        DecodeException(int error, String message, Throwable cause, Source source) {
            super(message, cause);
            this.error = error;
            this.source = source;
        }

        public int getError() {
            return error;
        }

        @NonNull
        public Source getSource() {
            return source;
        }
    }

    public interface OnHeaderDecodedListener {
        void onHeaderDecoded(@NonNull ImageDecoder decoder, @NonNull ImageInfo info, @NonNull Source source);
    }

    public interface OnPartialImageListener {
        boolean onPartialImage(@NonNull DecodeException exception);
    }

    private final int width;
    private final int height;
    private int targetWidth;
    private int targetHeight;
    private int allocator = ALLOCATOR_DEFAULT;
    private boolean unpremultipliedRequired;
    private boolean mutableRequired;
    private boolean decodeAsAlphaMask;
    private int memoryPolicy = MEMORY_POLICY_DEFAULT;
    private Rect crop;
    private PostProcessor postProcessor;
    private OnPartialImageListener onPartialImageListener;

    private ImageDecoder(int width, int height) {
        this.width = width;
        this.height = height;
        this.targetWidth = width;
        this.targetHeight = height;
    }

    public static boolean isMimeTypeSupported(@NonNull String mimeType) {
        return ImageIO.getImageReadersByMIMEType(mimeType).hasNext();
    }

    @NonNull
    public static Source createSource(@NonNull Resources res, int resId) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    public static Source createSource(@NonNull ContentResolver cr, @NonNull Uri uri) {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    public static Source createSource(@NonNull AssetManager assets, @NonNull String fileName) {
        return new AssetSource(assets, fileName);
    }

    @NonNull
    public static Source createSource(@NonNull byte[] data, int offset, int length) throws ArrayIndexOutOfBoundsException {
        if (data == null) {
            throw new NullPointerException("null byte[] in createSource!");
        }
        if (offset < 0 || length < 0 || offset >= data.length || offset + length > data.length) {
            throw new ArrayIndexOutOfBoundsException("invalid offset/length!");
        }
        return new ByteArraySource(data, offset, length);
    }

    @NonNull
    public static Source createSource(@NonNull byte[] data) {
        return createSource(data, 0, data.length);
    }

    @NonNull
    public static Source createSource(@NonNull ByteBuffer buffer) {
        return new ByteBufferSource(buffer);
    }

    @NonNull
    public static Source createSource(@NonNull File file) {
        return new FileSource(file);
    }

    @NonNull
    public static Source createSource(@NonNull Callable<AssetFileDescriptor> callable) {
        throw new RuntimeException("Stub!");
    }

    public void setTargetSize(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Dimensions must be positive! provided (" + width + ", " + height + ")");
        }
        targetWidth = width;
        targetHeight = height;
    }

    public void setTargetSampleSize(int sampleSize) {
        if (sampleSize <= 0) {
            throw new IllegalArgumentException("sampleSize must be positive! provided " + sampleSize);
        }
        setTargetSize(Math.max(1, (width + sampleSize - 1) / sampleSize), Math.max(1, (height + sampleSize - 1) / sampleSize));
    }

    /** Every allocator decodes into an ordinary software bitmap here. */
    public void setAllocator(int allocator) {
        if (allocator < ALLOCATOR_DEFAULT || allocator > ALLOCATOR_HARDWARE) {
            throw new IllegalArgumentException("invalid allocator " + allocator);
        }
        this.allocator = allocator;
    }

    public int getAllocator() {
        return allocator;
    }

    public void setUnpremultipliedRequired(boolean unpremultipliedRequired) {
        this.unpremultipliedRequired = unpremultipliedRequired;
    }

    public boolean isUnpremultipliedRequired() {
        return unpremultipliedRequired;
    }

    public void setPostProcessor(@Nullable PostProcessor postProcessor) {
        this.postProcessor = postProcessor;
    }

    @Nullable
    public PostProcessor getPostProcessor() {
        return postProcessor;
    }

    public void setOnPartialImageListener(@Nullable OnPartialImageListener listener) {
        this.onPartialImageListener = listener;
    }

    @Nullable
    public OnPartialImageListener getOnPartialImageListener() {
        return onPartialImageListener;
    }

    public void setCrop(@Nullable Rect subset) {
        this.crop = subset;
    }

    @Nullable
    public Rect getCrop() {
        return crop;
    }

    public void setMutableRequired(boolean mutable) {
        this.mutableRequired = mutable;
    }

    public boolean isMutableRequired() {
        return mutableRequired;
    }

    public void setMemorySizePolicy(int policy) {
        this.memoryPolicy = policy;
    }

    public int getMemorySizePolicy() {
        return memoryPolicy;
    }

    public void setDecodeAsAlphaMaskEnabled(boolean enabled) {
        this.decodeAsAlphaMask = enabled;
    }

    public boolean isDecodeAsAlphaMaskEnabled() {
        return decodeAsAlphaMask;
    }

    /** Ignored: bitmaps are always sRGB here. */
    public void setTargetColorSpace(ColorSpace colorSpace) {
    }

    @Override
    public void close() {
    }

    @NonNull
    public static Drawable decodeDrawable(@NonNull Source src, @NonNull OnHeaderDecodedListener listener) throws IOException {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    public static Drawable decodeDrawable(@NonNull Source src) throws IOException {
        throw new RuntimeException("Stub!");
    }

    @NonNull
    public static Bitmap decodeBitmap(@NonNull Source src, @NonNull OnHeaderDecodedListener listener) throws IOException {
        if (listener == null) {
            throw new IllegalArgumentException("listener cannot be null! Use decodeBitmap(Source) to not have a listener");
        }
        return decode(src, listener);
    }

    @NonNull
    public static Bitmap decodeBitmap(@NonNull Source src) throws IOException {
        return decode(src, null);
    }

    private static Bitmap decode(Source src, OnHeaderDecodedListener listener) throws IOException {
        byte[] bytes;
        try {
            bytes = src.readAll();
        } catch (IOException e) {
            throw new DecodeException(DecodeException.SOURCE_EXCEPTION, "Failed to read the image source", e, src);
        }
        BufferedImage image;
        String mimeType;
        // Over the bytes themselves, no copy: ImageIO.createImageInputStream may spill the page to a temp file
        try (ImageInputStream in = xyz.nulldev.androidcompat.graphics.ByteArrayImageInput.byteArrayImageInputStream(bytes)) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) {
                throw new DecodeException(DecodeException.SOURCE_MALFORMED_DATA, "Failed to create image decoder with message 'unimplemented'Input contained an error.", null, src);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in);
                String[] mimeTypes = reader.getOriginatingProvider() == null ? null : reader.getOriginatingProvider().getMIMETypes();
                mimeType = mimeTypes != null && mimeTypes.length > 0 ? mimeTypes[0] : "";
                ImageDecoder decoder = new ImageDecoder(reader.getWidth(0), reader.getHeight(0));
                if (listener != null) {
                    listener.onHeaderDecoded(decoder, new ImageInfo(decoder.width, decoder.height, mimeType), src);
                }
                try {
                    javax.imageio.ImageReadParam param = reader.getDefaultReadParam();
                    // Decode at a reduced size when a smaller target is set, and refuse a size too large to hold
                    int sx = Math.max(1, Math.min(decoder.width / Math.max(1, decoder.targetWidth),
                            decoder.height / Math.max(1, decoder.targetHeight)));
                    if (decoder.width > xyz.nulldev.androidcompat.graphics.ImageIoDecoding.MAX_SIDE
                            || decoder.height > xyz.nulldev.androidcompat.graphics.ImageIoDecoding.MAX_SIDE) {
                        sx = Math.max(sx, Math.max(decoder.width, decoder.height) / xyz.nulldev.androidcompat.graphics.ImageIoDecoding.MAX_SIDE + 1);
                    }
                    if (sx > 1) param.setSourceSubsampling(sx, sx, 0, 0);
                    xyz.nulldev.androidcompat.graphics.ImageIoDecoding.checkSize((decoder.width + sx - 1) / sx, (decoder.height + sx - 1) / sx);
                    xyz.nulldev.androidcompat.graphics.ImageIoDecoding.checkSize(decoder.targetWidth, decoder.targetHeight);
                    image = reader.read(0, param);
                } catch (IOException e) {
                    throw new DecodeException(DecodeException.SOURCE_MALFORMED_DATA, "Failed to decode the image: " + e, e, src);
                } catch (RuntimeException e) {
                    throw new DecodeException(DecodeException.SOURCE_MALFORMED_DATA, "Failed to decode the image: " + e, e, src);
                }
                return decoder.finish(image);
            } finally {
                reader.dispose();
            }
        } catch (DecodeException e) {
            throw e;
        } catch (IOException e) {
            throw new DecodeException(DecodeException.SOURCE_MALFORMED_DATA, "Failed to decode the image: " + e, e, src);
        }
    }

    /** Converts to ARGB_8888 at the target size, then crops and post-processes, as Android orders it. */
    private Bitmap finish(BufferedImage decoded) {
        BufferedImage argb = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = argb.createGraphics();
        try {
            if (targetWidth != decoded.getWidth() || targetHeight != decoded.getHeight()) {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            }
            g.drawImage(decoded, 0, 0, targetWidth, targetHeight, null);
        } finally {
            g.dispose();
        }
        if (crop != null) {
            if (crop.left < 0 || crop.top < 0 || crop.right > targetWidth || crop.bottom > targetHeight
                    || crop.left >= crop.right || crop.top >= crop.bottom) {
                throw new IllegalStateException("Subset " + crop + " not contained by scaled image bounds: (" + targetWidth + " x " + targetHeight + ")");
            }
            BufferedImage cropped = new BufferedImage(crop.right - crop.left, crop.bottom - crop.top, BufferedImage.TYPE_INT_ARGB);
            Graphics2D cg = cropped.createGraphics();
            cg.drawImage(argb, -crop.left, -crop.top, null);
            cg.dispose();
            argb = cropped;
        }
        Bitmap bitmap = new Bitmap(argb);
        if (postProcessor != null) {
            postProcessor.onPostProcess(new Canvas(bitmap));
        }
        return bitmap;
    }
}
// ZOUKAN <--
