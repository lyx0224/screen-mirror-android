package dev.airbridge;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.WindowManager;

import dev.airbridge.server.FrameHub;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * Owns one user-authorized MediaProjection session. Call start only after the
 * mediaProjection foreground service has started. All capture work is serialized
 * on a worker thread; listener callbacks are delivered on the main thread.
 */
public final class ScreenCapture implements AutoCloseable {
    public interface Listener {
        void onStopped();
        void onError(String message);
    }

    private final Context context;
    private final MediaProjection projection;
    private final FrameHub frames;
    private final int maxEdge;
    private final long frameIntervalMs;
    private final int jpegQuality;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final HandlerThread captureThread = new HandlerThread("AirBridge-Capture");
    private final ByteArrayOutputStream encoded = new ByteArrayOutputStream(256 * 1024);

    private volatile boolean closed;
    private boolean started;
    private Handler captureHandler;
    // The following fields are used only on captureThread.
    private boolean released;
    private boolean callbackRegistered;
    private boolean displayListenerRegistered;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private Bitmap bitmap;
    private ByteBuffer packedPixels;
    private int[] fallbackPixels;
    private int captureWidth;
    private int captureHeight;
    private long lastFrameAt;
    private boolean frameScheduled;
    private ImageReader scheduledReader;
    private final Runnable consumeScheduledFrame = () -> {
        ImageReader reader = scheduledReader;
        scheduledReader = null;
        frameScheduled = false;
        if (!closed && reader != null && reader == imageReader) consumeLatestImage(reader);
    };

    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override public void onStop() {
            if (closed) {
                releaseResources();
                return;
            }
            closed = true;
            releaseResources();
            mainHandler.post(listener::onStopped);
        }

        @Override public void onCapturedContentResize(int width, int height) {
            if (!closed && width > 0 && height > 0) {
                try {
                    resize(width, height);
                } catch (RuntimeException error) {
                    fail("无法调整投屏尺寸，请重新开始投屏。", error);
                }
            }
        }
    };

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int displayId) { }
        @Override public void onDisplayRemoved(int displayId) { }
        @Override public void onDisplayChanged(int displayId) {
            if (closed || displayId != Display.DEFAULT_DISPLAY) return;
            try {
                Point size = displaySize();
                resize(size.x, size.y);
            } catch (RuntimeException error) {
                fail("屏幕方向变化后无法继续投屏，请重新开始。", error);
            }
        }
    };

    public ScreenCapture(Context context, MediaProjection projection, FrameHub frames,
                         int maxEdge, int targetFps, int jpegQuality, Listener listener) {
        if (context == null || projection == null || frames == null || listener == null) {
            throw new IllegalArgumentException("Capture dependencies must not be null");
        }
        this.context = context.getApplicationContext();
        this.projection = projection;
        this.frames = frames;
        this.maxEdge = Math.max(320, Math.min(1920, maxEdge));
        this.frameIntervalMs = Math.max(1, 1000L / Math.max(1, Math.min(30, targetFps)));
        this.jpegQuality = Math.max(35, Math.min(95, jpegQuality));
        this.listener = listener;
    }

    /** Starts asynchronously. Repeated calls have no effect. */
    public synchronized void start() {
        if (started || closed) return;
        started = true;
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        captureHandler.post(this::initialize);
    }

    private void initialize() {
        if (closed) return;
        try {
            // Required before createVirtualDisplay for apps targeting Android 14+.
            projection.registerCallback(projectionCallback, captureHandler);
            callbackRegistered = true;
            Point size = displaySize();
            int[] output = outputSize(size.x, size.y);
            captureWidth = output[0];
            captureHeight = output[1];
            imageReader = newReader(captureWidth, captureHeight);
            virtualDisplay = projection.createVirtualDisplay(
                    "AirBridge", captureWidth, captureHeight, densityDpi(),
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(), null, captureHandler);
            if (virtualDisplay == null) throw new IllegalStateException("No virtual display");
            // Android 14+ gives the actual captured region through the projection
            // callback, including single-app sharing. Older releases need rotation.
            if (Build.VERSION.SDK_INT < 34) {
                DisplayManager manager = context.getSystemService(DisplayManager.class);
                if (manager != null) {
                    manager.registerDisplayListener(displayListener, captureHandler);
                    displayListenerRegistered = true;
                }
            }
        } catch (RuntimeException error) {
            fail("投屏启动失败，请重新授权屏幕共享。", error);
        }
    }

    private ImageReader newReader(int width, int height) {
        ImageReader reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(this::scheduleFrame, captureHandler);
        return reader;
    }

    private void scheduleFrame(ImageReader reader) {
        if (closed || reader != imageReader || frameScheduled) return;
        // Keep the latest image queued until it is due. Acquiring then discarding
        // an early image can lose the final screen change when the display goes
        // idle and no further onImageAvailable callback follows it.
        long elapsed = SystemClock.uptimeMillis() - lastFrameAt;
        long delay = lastFrameAt == 0 ? 0 : Math.max(0, frameIntervalMs - elapsed);
        frameScheduled = true;
        scheduledReader = reader;
        captureHandler.postDelayed(consumeScheduledFrame, delay);
    }

    private void cancelScheduledFrame() {
        if (captureHandler != null) captureHandler.removeCallbacks(consumeScheduledFrame);
        scheduledReader = null;
        frameScheduled = false;
    }

    private void resize(int sourceWidth, int sourceHeight) {
        if (virtualDisplay == null || closed) return;
        int[] output = outputSize(sourceWidth, sourceHeight);
        if (captureWidth == output[0] && captureHeight == output[1]) return;

        cancelScheduledFrame();
        ImageReader replacement = newReader(output[0], output[1]);
        ImageReader previous = imageReader;
        try {
            // Never create another VirtualDisplay with the same projection token.
            virtualDisplay.setSurface(null);
            virtualDisplay.resize(output[0], output[1], densityDpi());
            virtualDisplay.setSurface(replacement.getSurface());
            imageReader = replacement;
            captureWidth = output[0];
            captureHeight = output[1];
            lastFrameAt = 0;
            recyclePixelStorage();
            frames.clear();
        } catch (RuntimeException error) {
            replacement.close();
            throw error;
        } finally {
            if (previous != null) {
                previous.setOnImageAvailableListener(null, null);
                previous.close();
            }
        }
    }

    private void consumeLatestImage(ImageReader reader) {
        if (closed || reader != imageReader) return;
        try (Image image = reader.acquireLatestImage()) {
            if (image == null) return;
            long now = SystemClock.uptimeMillis();
            lastFrameAt = now;
            int width = image.getWidth();
            int height = image.getHeight();
            copyRgbaPixels(image, width, height);
            encoded.reset();
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, encoded)) {
                throw new IllegalStateException("JPEG encoding failed");
            }
            if (!closed) frames.publish(encoded.toByteArray(), width, height);
        } catch (RuntimeException error) {
            if (!closed) fail("画面采集失败，请停止后重新开始投屏。", error);
        }
    }

    private void copyRgbaPixels(Image image, int width, int height) {
        if (bitmap == null || bitmap.getWidth() != width || bitmap.getHeight() != height) {
            recyclePixelStorage();
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        }
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer source = plane.getBuffer().duplicate();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        int sourceOffset = source.position();
        int rowBytes = width * 4;

        if (pixelStride == 4) {
            if (rowStride == rowBytes && source.remaining() >= rowBytes * height) {
                source.limit(sourceOffset + rowBytes * height);
                bitmap.copyPixelsFromBuffer(source);
                return;
            }
            // The last row is not guaranteed to contain stride padding. Copy
            // only visible bytes, instead of reading a padded-width bitmap.
            if (packedPixels == null || packedPixels.capacity() < rowBytes * height) {
                packedPixels = ByteBuffer.allocateDirect(rowBytes * height);
            }
            packedPixels.clear();
            for (int y = 0; y < height; y++) {
                int start = sourceOffset + y * rowStride;
                source.limit(plane.getBuffer().limit());
                source.position(start);
                source.limit(start + rowBytes);
                packedPixels.put(source);
            }
            packedPixels.flip();
            bitmap.copyPixelsFromBuffer(packedPixels);
        } else {
            if (pixelStride < 4) throw new IllegalStateException("Unsupported pixel stride");
            if (fallbackPixels == null || fallbackPixels.length < width * height) {
                fallbackPixels = new int[width * height];
            }
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int offset = sourceOffset + y * rowStride + x * pixelStride;
                    int red = source.get(offset) & 0xff;
                    int green = source.get(offset + 1) & 0xff;
                    int blue = source.get(offset + 2) & 0xff;
                    int alpha = source.get(offset + 3) & 0xff;
                    fallbackPixels[y * width + x] = (alpha << 24) | (red << 16) | (green << 8) | blue;
                }
            }
            bitmap.setPixels(fallbackPixels, 0, width, 0, 0, width, height);
        }
    }

    private int[] outputSize(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid capture size");
        double scale = Math.min(1.0, (double) maxEdge / Math.max(width, height));
        return new int[]{Math.max(1, (int) Math.round(width * scale)),
                Math.max(1, (int) Math.round(height * scale))};
    }

    @SuppressWarnings("deprecation")
    private Point displaySize() {
        WindowManager manager = context.getSystemService(WindowManager.class);
        if (manager == null) throw new IllegalStateException("Window service unavailable");
        if (Build.VERSION.SDK_INT >= 30) {
            Rect bounds = manager.getMaximumWindowMetrics().getBounds();
            return new Point(bounds.width(), bounds.height());
        }
        Point size = new Point();
        manager.getDefaultDisplay().getRealSize(size);
        return size;
    }

    private int densityDpi() {
        return Math.max(1, context.getResources().getConfiguration().densityDpi);
    }

    private void fail(String message, RuntimeException cause) {
        if (closed) return;
        closed = true;
        android.util.Log.e("AirBridgeCapture", message, cause);
        releaseResources();
        mainHandler.post(() -> listener.onError(message));
    }

    /** Stops asynchronously; it is safe to call from a service's onDestroy. */
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (captureHandler != null) {
            captureHandler.post(this::releaseResources);
        } else {
            frames.clear();
            try { projection.stop(); } catch (RuntimeException ignored) { }
        }
    }

    private void releaseResources() {
        if (released) return;
        released = true;
        cancelScheduledFrame();
        if (displayListenerRegistered) {
            DisplayManager manager = context.getSystemService(DisplayManager.class);
            if (manager != null) manager.unregisterDisplayListener(displayListener);
            displayListenerRegistered = false;
        }
        if (callbackRegistered) {
            projection.unregisterCallback(projectionCallback);
            callbackRegistered = false;
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (imageReader != null) {
            imageReader.setOnImageAvailableListener(null, null);
            imageReader.close();
            imageReader = null;
        }
        recyclePixelStorage();
        encoded.reset();
        frames.clear();
        try { projection.stop(); } catch (RuntimeException ignored) { }
        captureThread.quitSafely();
    }

    private void recyclePixelStorage() {
        if (bitmap != null) bitmap.recycle();
        bitmap = null;
        packedPixels = null;
        fallbackPixels = null;
    }
}
