package com.bluemind.app;

import android.accessibilityservice.AccessibilityService;
import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.SystemClock;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Samples only an already-revealed player when its accessibility playback state is absent. */
final class PlaybackProbe {
    private static final long SAMPLE_INTERVAL_MS = 850;
    private static final long FAILURE_INTERVAL_MS = 6_000;
    private static final int GRID = 16;
    private final AccessibilityService service;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            task -> new Thread(task, "Bluemind-playback-sample"));
    private Object token;
    private int[] previous;
    private int consecutiveChanges;
    private long nextSampleAt;
    private boolean pending;
    private boolean closed;

    PlaybackProbe(AccessibilityService service) {
        this.service = service;
    }

    void observe(ViewingSession.Probe candidate, int windowId, Rect viewport, Runnable onMotion) {
        if (Build.VERSION.SDK_INT < 34 || closed) {
            return;
        }
        Object requested = candidate == null ? null : candidate.token;
        if (requested != token) {
            token = requested;
            previous = null;
            consecutiveChanges = 0;
        }
        long now = SystemClock.elapsedRealtime();
        if (candidate == null || pending || now < nextSampleAt) {
            return;
        }
        Rect samplingArea = ScreenSnapshot.bounds(candidate.bounds);
        if (!samplingArea.intersect(viewport) || samplingArea.width() < GRID || samplingArea.height() < GRID) {
            return;
        }

        pending = true;
        nextSampleAt = now + SAMPLE_INTERVAL_MS;
        Rect captureWindow = new Rect(viewport);
        try {
            service.takeScreenshotOfWindow(windowId, service.getMainExecutor(),
                    new AccessibilityService.TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(AccessibilityService.ScreenshotResult result) {
                            HardwareBuffer buffer = result.getHardwareBuffer();
                            if (closed || token != requested) {
                                buffer.close();
                                pending = false;
                                return;
                            }
                            worker.execute(() -> {
                                int[] pixels = null;
                                try (HardwareBuffer captured = buffer) {
                                    pixels = sample(result, captured, captureWindow, samplingArea);
                                } catch (RuntimeException unavailable) {
                                    // Protected/inaccessible video surfaces cannot always be read.
                                }
                                int[] sampled = pixels;
                                service.getMainExecutor().execute(
                                        () -> handleSample(requested, sampled, onMotion, candidate));
                            });
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            handleSample(requested, null, onMotion, candidate);
                        }
                    });
        } catch (IllegalStateException | SecurityException unavailable) {
            handleSample(requested, null, onMotion, candidate);
        }
    }

    void reset() {
        token = null;
        previous = null;
        consecutiveChanges = 0;
    }

    void close() {
        closed = true;
        reset();
        worker.shutdown();
    }

    private void handleSample(Object requested, int[] pixels, Runnable onMotion, ViewingSession.Probe candidate) {
        pending = false;
        if (closed || requested != token) {
            return;
        }
        if (pixels == null) {
            nextSampleAt = SystemClock.elapsedRealtime() + FAILURE_INTERVAL_MS;
            previous = null;
            consecutiveChanges = 0;
            return;
        }
        consecutiveChanges = previous != null && visiblyChanged(previous, pixels)
                ? consecutiveChanges + 1 : 0;
        previous = pixels;
        if (consecutiveChanges >= 2) {
            candidate.motionObserved(SystemClock.elapsedRealtime());
            onMotion.run();
        }
    }

    @TargetApi(34)
    private static int[] sample(AccessibilityService.ScreenshotResult result, HardwareBuffer buffer,
                                Rect window, Rect target) {
        Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
        if (hardware == null) {
            return null;
        }
        Bitmap scaled = null;
        Bitmap readable = null;
        try {
            // Avoid the action rail, player chrome and edge transitions.
            int insetX = target.width() / 5;
            int insetY = target.height() / 5;
            float scaleX = (float) hardware.getWidth() / window.width();
            float scaleY = (float) hardware.getHeight() / window.height();
            int left = (int) ((target.left + insetX - window.left) * scaleX);
            int top = (int) ((target.top + insetY - window.top) * scaleY);
            int width = Math.min(hardware.getWidth() - left, Math.max(1,
                    (int) ((target.width() - 2 * insetX) * scaleX)));
            int height = Math.min(hardware.getHeight() - top, Math.max(1,
                    (int) ((target.height() - 2 * insetY) * scaleY)));
            if (left < 0 || top < 0 || width <= 0 || height <= 0) {
                return null;
            }
            Matrix resize = new Matrix();
            resize.setScale((float) GRID / width, (float) GRID / height);
            scaled = Bitmap.createBitmap(hardware, left, top, width, height, resize, true);
            readable = scaled.getConfig() == Bitmap.Config.HARDWARE
                    ? scaled.copy(Bitmap.Config.ARGB_8888, false) : scaled;
            if (readable == null || readable.getWidth() != GRID || readable.getHeight() != GRID) {
                return null;
            }
            int[] pixels = new int[GRID * GRID];
            readable.getPixels(pixels, 0, GRID, 0, 0, GRID, GRID);
            return pixels;
        } finally {
            if (readable != null && readable != scaled) {
                readable.recycle();
            }
            if (scaled != null && scaled != hardware) {
                scaled.recycle();
            }
            hardware.recycle();
        }
    }

    private static boolean visiblyChanged(int[] before, int[] after) {
        int changed = 0;
        for (int i = 0; i < after.length; i++) {
            int one = before[i];
            int two = after[i];
            int difference = Math.abs((one >> 16 & 255) - (two >> 16 & 255))
                    + Math.abs((one >> 8 & 255) - (two >> 8 & 255))
                    + Math.abs((one & 255) - (two & 255));
            if (difference >= 75) {
                changed++;
            }
        }
        return changed * 5 >= before.length;
    }
}
