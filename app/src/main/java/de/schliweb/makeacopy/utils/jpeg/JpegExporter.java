package de.schliweb.makeacopy.utils.jpeg;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.util.Log;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.io.IOException;
import java.io.OutputStream;

/**
 * JPEG exporter with optional downscale and document enhancement modes.
 * <p>
 * Modes:
 * - NONE: optional downscale only.
 * - AUTO: L-channel equalization + mild unsharp mask.
 * - BW_TEXT: grayscale binarization (Otsu) optimized for text; exported as grayscale-like JPEG.
 * <p>
 * Notes:
 * - Uses an optional long-edge guard (from options) to avoid OOM on huge images.
 * - Can round resize targets to multiples of 8 (helps JPEG block alignment).
 * - Fast path: for "NONE + resize" without grayscale forcing, uses Bitmap.createScaledBitmap (no OpenCV).
 */
public final class JpegExporter {

    private static final String TAG = "JpegExporter";

    private JpegExporter() {
    }

    /**
     * Exports the given (already perspective-corrected) bitmap to the provided targetUri as JPEG.
     *
     * @param context   Application or Activity context
     * @param bitmap    Perspective-corrected bitmap to save (ARGB_8888 recommended)
     * @param options   Export options (if null a default instance is used)
     * @param targetUri Target Uri (e.g., from ACTION_CREATE_DOCUMENT with MIME image/jpeg)
     * @return targetUri if success, otherwise null
     */
    public static Uri export(Context context, Bitmap bitmap, JpegExportOptions options, Uri targetUri) {
        if (context == null || bitmap == null || targetUri == null) {
            Log.e(TAG, "export: invalid arguments (context/bitmap/uri)");
            return null;
        }
        if (options == null) options = new JpegExportOptions();

        // Quick decisions without OpenCV allocation
        final boolean enhancementNone = options.mode == JpegExportOptions.Mode.NONE;
        final int srcW = bitmap.getWidth();
        final int srcH = bitmap.getHeight();
        final int curLong = Math.max(srcW, srcH);

        final int targetLong = computeTargetLongEdge(curLong, options);
        final boolean needsResize = targetLong > 0 && targetLong < curLong;

        // Shortcut 1: no resize + no enhancement → compress original bitmap
        if (!needsResize && enhancementNone) {
            return compressToUri(context, bitmap, options.quality, targetUri);
        }

        // Shortcut 2: ONLY resize (mode NONE) → fast path w/o OpenCV
        // (only if we don't force grayscale JPEG, which needs OpenCV here)
        if (needsResize && enhancementNone && !options.forceGrayscaleJpeg) {
            final double scale = targetLong / (double) curLong;
            int newW = (int) Math.round(srcW * scale);
            int newH = (int) Math.round(srcH * scale);
            newW = roundToMultiple(newW, 8, options.roundResizeToMultipleOf8);
            newH = roundToMultiple(newH, 8, options.roundResizeToMultipleOf8);
            newW = Math.max(8, newW);
            newH = Math.max(8, newH);
            Bitmap scaled = null;
            try {
                scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true);
                return compressToUri(context, scaled, options.quality, targetUri);
            } catch (OutOfMemoryError oom) {
                Log.e(TAG, "export: OOM during Bitmap scaling", oom);
                return null;
            } catch (Exception e) {
                Log.e(TAG, "export: error during Bitmap scaling", e);
                return null;
            }
        }

        // From here on, process with OpenCV
        Bitmap outBitmap = null;
        Mat srcRgba = new Mat();
        Mat work = new Mat();
        Mat tmp = new Mat();
        try {
            // Input to RGBA Mat
            Utils.bitmapToMat(bitmap, srcRgba); // RGBA

            // Downscale if needed (with guard & optional multiples-of-8 rounding)
            Mat current = srcRgba;
            if (needsResize) {
                final double scale = targetLong / (double) curLong;
                int newW = (int) Math.round(srcW * scale);
                int newH = (int) Math.round(srcH * scale);
                newW = roundToMultiple(newW, 8, options.roundResizeToMultipleOf8);
                newH = roundToMultiple(newH, 8, options.roundResizeToMultipleOf8);
                newW = Math.max(8, newW);
                newH = Math.max(8, newH);
                Imgproc.resize(srcRgba, tmp, new Size(newW, newH), 0, 0, Imgproc.INTER_AREA);
                current = tmp;
            }

            // Convert RGBA -> BGR for processing
            Imgproc.cvtColor(current, work, Imgproc.COLOR_RGBA2BGR);

            switch (options.mode) {
                case AUTO:
                    applyAutoEnhancement(work);
                    // back to RGBA (color); grayscale may still be forced below
                    Imgproc.cvtColor(work, work, Imgproc.COLOR_BGR2RGBA);
                    break;

                case BW_TEXT:
                    // Fast, native Otsu binarization for documents
                    applyBwText(work); // B/W content in BGR
                    // Export as grayscale-like JPEG: Gray -> RGBA
                    Imgproc.cvtColor(work, work, Imgproc.COLOR_BGR2GRAY);
                    Imgproc.cvtColor(work, work, Imgproc.COLOR_GRAY2RGBA);
                    break;

                case NONE:
                default:
                    // shouldn't reach here because NONE handled in shortcuts
                    Imgproc.cvtColor(work, work, Imgproc.COLOR_BGR2RGBA);
                    break;
            }

            // If grayscale JPEG is explicitly forced for non-BW_TEXT modes, convert now
            if (options.forceGrayscaleJpeg && options.mode != JpegExportOptions.Mode.BW_TEXT) {
                Imgproc.cvtColor(work, work, Imgproc.COLOR_RGBA2GRAY);
                Imgproc.cvtColor(work, work, Imgproc.COLOR_GRAY2RGBA);
            }

            // Convert back to Bitmap and compress
            outBitmap = Bitmap.createBitmap(work.cols(), work.rows(), Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(work, outBitmap);
            return compressToUri(context, outBitmap, options.quality, targetUri);

        } catch (OutOfMemoryError oom) {
            Log.e(TAG, "export: OutOfMemoryError during processing", oom);
            return null;
        } catch (Exception e) {
            Log.e(TAG, "export: error during processing", e);
            return null;
        } finally {
            try {
                srcRgba.release();
            } catch (Throwable ignore) {
            }
            try {
                work.release();
            } catch (Throwable ignore) {
            }
            try {
                tmp.release();
            } catch (Throwable ignore) {
            }
            // outBitmap dem GC überlassen
        }
    }

    // === Image ops ===

    private static void applyAutoEnhancement(Mat bgr) {
        // bgr: 3-channel 8-bit image
        Mat lab = new Mat();
        Mat l = new Mat();
        Mat a = new Mat();
        Mat b = new Mat();
        try {
            Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab);

            java.util.List<Mat> chans = new java.util.ArrayList<>(3);
            Core.split(lab, chans);
            l = chans.get(0);
            a = chans.get(1);
            b = chans.get(2);

            // L-channel equalization (simple equalizeHist; CLAHE optional später)
            Imgproc.equalizeHist(l, l);

            // Merge back → Lab → BGR
            chans.set(0, l);
            chans.set(1, a);
            chans.set(2, b);
            Core.merge(chans, lab);
            Imgproc.cvtColor(lab, bgr, Imgproc.COLOR_Lab2BGR);

            // Mild unsharp mask
            Mat blurred = new Mat();
            try {
                Imgproc.GaussianBlur(bgr, blurred, new Size(0, 0), 1.0);
                Core.addWeighted(bgr, 1.5, blurred, -0.5, 0, bgr);
            } finally {
                blurred.release();
            }
        } finally {
            try {
                lab.release();
            } catch (Throwable ignore) {
            }
            try {
                l.release();
            } catch (Throwable ignore) {
            }
            try {
                a.release();
            } catch (Throwable ignore) {
            }
            try {
                b.release();
            } catch (Throwable ignore) {
            }
        }
    }

    /**
     * B/W binarization optimized for documents:
     * - Convert to gray
     * - Light Gaussian blur to stabilize noise
     * - Otsu threshold → binary (0/255)
     * Writes result back into the provided BGR Mat (content becomes black/white).
     */
    private static void applyBwText(Mat bgr) {
        Mat gray = new Mat();
        try {
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY);
            Imgproc.GaussianBlur(gray, gray, new Size(0, 0), 0.8);
            Imgproc.threshold(gray, gray, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);
            // Re-expand to BGR for consistent downstream handling
            Imgproc.cvtColor(gray, bgr, Imgproc.COLOR_GRAY2BGR);
        } finally {
            try {
                gray.release();
            } catch (Throwable ignore) {
            }
        }
    }

    // === IO ===

    private static Uri compressToUri(Context context, Bitmap bmp, int quality, Uri targetUri) {
        ContentResolver resolver = context.getContentResolver();
        try (OutputStream os = resolver.openOutputStream(targetUri, "w")) {
            if (os == null) {
                Log.e(TAG, "compressToUri: failed to open OutputStream for Uri: " + targetUri);
                return null;
            }
            boolean ok = bmp.compress(Bitmap.CompressFormat.JPEG, clampQuality(quality), os);
            if (!ok) {
                Log.e(TAG, "compressToUri: Bitmap.compress returned false");
                return null;
            }
            os.flush(); // close() flushes too; explicit for clarity
            return targetUri;
        } catch (IOException e) {
            Log.e(TAG, "compressToUri: IO error while writing JPEG", e);
            return null;
        } catch (SecurityException se) {
            Log.e(TAG, "compressToUri: security error while writing JPEG", se);
            return null;
        }
    }

    // === utils ===

    private static int roundToMultiple(int value, int multiple, boolean enabled) {
        if (!enabled || multiple <= 1) return value;
        return (value / multiple) * multiple;
    }

    private static int clampQuality(int q) {
        return Math.max(0, Math.min(100, q));
    }

    private static int computeTargetLongEdge(int curLong, JpegExportOptions opts) {
        // Base target: desired long edge or current
        int target = (opts.longEdgePx > 0) ? opts.longEdgePx : curLong;
        // Apply guard if set (>0)
        if (opts.maxLongEdgeGuardPx > 0) {
            target = Math.min(target, opts.maxLongEdgeGuardPx);
        }
        return target;
    }
}
