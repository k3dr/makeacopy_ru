package de.schliweb.makeacopy.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.Log;
import org.opencv.core.Point;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RealtimeDocumentDetector is a class designed to facilitate real-time document detection
 * within a stream of frames (e.g., video or image sequences). It uses OpenCV for detecting
 * document corners and provides tools for smoothing, cropping, and other optimizations
 * to improve detection stability and reduce unnecessary computational overhead.
 * <p>
 * The detection results are communicated via a listener interface which provides the
 * detected corners, latency, and confidence values for the frame being processed.
 * <p>
 * This class operates asynchronously, using a dedicated single-threaded executor
 * for processing frames in the background.
 */
public class RealtimeDocumentDetector {

    public interface OnResultListener {
        void onResult(Point[] corners, long latencyMs, float confidence);
    }

    private static final String TAG = "RealtimeDetector";

    private final Context appContext;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private final OnResultListener listener;

    private volatile boolean enableRoi = true;
    private volatile boolean enableFrameSkip = true;
    private volatile int frameSkip = 1;
    private volatile int frameCounter = 0;

    private volatile double emaAlpha = 0.65;
    private volatile float roiMarginFraction = 0.12f;
    private volatile Point[] lastPts = null;

    public RealtimeDocumentDetector(Context context, OnResultListener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
        OpenCVUtils.init(appContext);
    }

    public RealtimeDocumentDetector setEnableRoi(boolean enable) {
        this.enableRoi = enable;
        return this;
    }

    public RealtimeDocumentDetector setEnableFrameSkip(boolean enable, int skipN) {
        this.enableFrameSkip = enable;
        this.frameSkip = Math.max(0, skipN);
        return this;
    }

    public RealtimeDocumentDetector setEmaAlpha(double alpha) {
        this.emaAlpha = Math.max(0.0, Math.min(1.0, alpha));
        return this;
    }

    public RealtimeDocumentDetector setRoiMarginFraction(float frac) {
        this.roiMarginFraction = Math.max(0f, Math.min(0.4f, frac));
        return this;
    }

    public void shutdown() {
        exec.shutdownNow();
    }

    public void submitFrame(Bitmap frame) {
        if (frame == null || frame.isRecycled()) return;

        if (enableFrameSkip) {
            int c = (frameCounter++ % (frameSkip + 1));
            if (c != 0) return;
        }

        if (busy.getAndSet(true)) return;

        final Bitmap frameRef = frame.copy(Bitmap.Config.ARGB_8888, false);
        exec.execute(() -> {
            long t0 = SystemClock.elapsedRealtimeNanos();
            org.opencv.core.Point[] pts = null;
            float conf = 0f;

            try {
                Bitmap roiBmp = frameRef;
                RectF roiRect = null;

                if (enableRoi && lastPts != null) {
                    roiRect = computeTightRoi(lastPts, frameRef.getWidth(), frameRef.getHeight(), roiMarginFraction);
                    Bitmap cropped = safeCrop(frameRef, roiRect);
                    if (cropped != null) {
                        roiBmp = cropped;
                    } else {
                        roiRect = null;
                    }
                }

                pts = OpenCVUtils.detectDocumentCorners(appContext, roiBmp);

                if (pts != null && roiRect != null) {
                    for (int i = 0; i < 4; i++) {
                        pts[i].x += roiRect.left;
                        pts[i].y += roiRect.top;
                    }
                }

                if (pts != null) {
                    pts = smoothCorners(lastPts, pts, emaAlpha);
                    lastPts = pts;
                    conf = clamp01((float) (quadArea(pts) / (frameRef.getWidth() * (double) frameRef.getHeight())));
                    conf = Math.min(1f, Math.max(0f, (conf - 0.02f) / 0.5f));
                }
            } catch (Throwable t) {
                Log.w(TAG, "submitFrame inference failed: " + t.getMessage());
            } finally {
                long dtMs = (long) ((SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000.0);
                try {
                    listener.onResult(pts, dtMs, conf);
                } catch (Throwable ignore) {
                }
                busy.set(false);
                if (frameRef != frame && !frameRef.isRecycled()) frameRef.recycle();
            }
        });
    }

    private static org.opencv.core.Point[] smoothCorners(org.opencv.core.Point[] prev, org.opencv.core.Point[] curr, double alpha) {
        if (curr == null || curr.length != 4) return curr;
        if (prev == null || prev.length != 4) return curr;
        org.opencv.core.Point[] out = new org.opencv.core.Point[4];
        for (int i = 0; i < 4; i++) {
            out[i] = new org.opencv.core.Point(
                    alpha * curr[i].x + (1 - alpha) * prev[i].x,
                    alpha * curr[i].y + (1 - alpha) * prev[i].y
            );
        }
        return out;
    }

    private static RectF computeTightRoi(org.opencv.core.Point[] pts, int w, int h, float marginFrac) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, maxX = -1, maxY = -1;
        for (int i = 0; i < 4; i++) {
            minX = Math.min(minX, pts[i].x);
            minY = Math.min(minY, pts[i].y);
            maxX = Math.max(maxX, pts[i].x);
            maxY = Math.max(maxY, pts[i].y);
        }
        float mw = w * marginFrac;
        float mh = h * marginFrac;
        float left = clamp((float) (minX - mw), 0, w - 1);
        float top = clamp((float) (minY - mh), 0, h - 1);
        float right = clamp((float) (maxX + mw), 1, w);
        float bottom = clamp((float) (maxY + mh), 1, h);

        float minSide = Math.max(64, Math.min(w, h) * 0.25f);
        if ((right - left) < minSide) {
            float cx = (left + right) * 0.5f;
            left = clamp(cx - minSide / 2f, 0, w - 1);
            right = clamp(cx + minSide / 2f, 1, w);
        }
        if ((bottom - top) < minSide) {
            float cy = (top + bottom) * 0.5f;
            top = clamp(cy - minSide / 2f, 0, h - 1);
            bottom = clamp(cy + minSide / 2f, 1, h);
        }
        return new RectF(left, top, right, bottom);
    }

    private static Bitmap safeCrop(Bitmap src, RectF r) {
        try {
            int x = Math.max(0, Math.round(r.left));
            int y = Math.max(0, Math.round(r.top));
            int w = Math.min(src.getWidth() - x, Math.round(r.width()));
            int h = Math.min(src.getHeight() - y, Math.round(r.height()));
            if (w <= 1 || h <= 1) return null;
            return Bitmap.createBitmap(src, x, y, w, h);
        } catch (Throwable t) {
            return null;
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static float clamp01(float v) {
        return clamp(v, 0f, 1f);
    }

    private static double quadArea(org.opencv.core.Point[] q) {
        if (q == null || q.length != 4) return 0.0;
        double area = 0;
        for (int i = 0; i < 4; i++) {
            org.opencv.core.Point a = q[i], b = q[(i + 1) % 4];
            area += (a.x * b.y - b.x * a.y);
        }
        return Math.abs(area) * 0.5;
    }
}
