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
 * - AUTO: CLAHE on L channel (Lab) + mild unsharp mask.
 * - BW_TEXT: grayscale + adaptive threshold, then back to 3-channel for JPEG.
 * <p>
 * Notes: This operates purely in the export path and does not depend on OpenCVUtils.
 */
public final class JpegExporter {

    private static final String TAG = "JpegExporter";
    private static final int MAX_GUARD_LONG_EDGE = 4096; // safety to avoid OOM on huge images

    private JpegExporter() {
    }

    /**
     * Exports the given (already perspective-corrected) bitmap to the provided targetUri as JPEG.
     *
     * @param context   Application or Activity context
     * @param bitmap    Perspective-corrected bitmap to save (ARGB_8888 recommended)
     * @param options   Export options
     * @param targetUri Target Uri (e.g., from ACTION_CREATE_DOCUMENT with MIME image/jpeg)
     * @return targetUri if success, otherwise null
     */
    public static Uri export(Context context, Bitmap bitmap, JpegExportOptions options, Uri targetUri) {
        if (context == null || bitmap == null || targetUri == null) {
            Log.e(TAG, "export: invalid arguments (context/bitmap/uri)");
            return null;
        }
        if (options == null) options = new JpegExportOptions();

        // Shortcut: if no processing requested (NONE, longEdge=0), write original bitmap.
        boolean noResize = options.longEdgePx <= 0;
        boolean noEnhancement = options.mode == JpegExportOptions.Mode.NONE;
        if (noResize && noEnhancement) {
            return compressToUri(context, bitmap, options.quality, targetUri);
        }

        Bitmap outBitmap = null;
        Mat srcRgba = new Mat();
        Mat work = new Mat();
        Mat tmp = new Mat();
        try {
            // Input to RGBA Mat
            Utils.bitmapToMat(bitmap, srcRgba); // RGBA

            // Apply downscale guard and requested longEdgePx
            int width = srcRgba.cols();
            int height = srcRgba.rows();
            int curLong = Math.max(width, height);
            int targetLong = curLong;
            if (options.longEdgePx > 0) targetLong = Math.min(options.longEdgePx, MAX_GUARD_LONG_EDGE);
            else targetLong = Math.min(curLong, MAX_GUARD_LONG_EDGE);

            double scale = (curLong > 0 && targetLong > 0 && targetLong < curLong) ? (targetLong / (double) curLong) : 1.0;
            Mat current = srcRgba;
            if (scale < 1.0) {
                int newW = (int) Math.round(width * scale);
                int newH = (int) Math.round(height * scale);
                Imgproc.resize(srcRgba, tmp, new Size(newW, newH), 0, 0, Imgproc.INTER_AREA);
                current = tmp;
            }

            if (options.mode == JpegExportOptions.Mode.NONE) {
                // No enhancement, just convert to Bitmap for compression.
                outBitmap = Bitmap.createBitmap(current.cols(), current.rows(), Bitmap.Config.ARGB_8888);
                Utils.matToBitmap(current, outBitmap);
                return compressToUri(context, outBitmap, options.quality, targetUri);
            }

            // Convert RGBA -> BGR for color processing
            Imgproc.cvtColor(current, work, Imgproc.COLOR_RGBA2BGR);

            switch (options.mode) {
                case AUTO:
                    applyAutoEnhancement(work);
                    break;
                case BW_TEXT:
                    applyBwText(work);
                    break;
                default:
                    // Should not happen due to NONE handled above.
                    break;
            }

            // Convert back to RGBA for Bitmap
            Imgproc.cvtColor(work, work, Imgproc.COLOR_BGR2RGBA);
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
            if (outBitmap != null && outBitmap != bitmap && !outBitmap.isRecycled()) {
                // The caller still owns the input bitmap; we recycle only the temporary one created here.
                // Do not recycle here to avoid issues if the caller wants to reuse the result elsewhere.
                // Keeping GC to handle it; if we wanted, we could offer a flag to recycle.
            }
        }
    }

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

            // Histogram equalization on L channel (CLAHE fallback if photo module not available)
            Imgproc.equalizeHist(l, l);

            // Merge back
            chans.set(0, l);
            chans.set(1, a);
            chans.set(2, b);
            Core.merge(chans, lab);
            Imgproc.cvtColor(lab, bgr, Imgproc.COLOR_Lab2BGR);

            // Unsharp mask: blur then addWeighted
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

    private static void applyBwText(Mat bgr) {
        // Safer pure-Java binarization to avoid OpenCV's adaptiveThreshold crash on some devices.
        // Strategy: Convert to RGBA -> Bitmap, compute Otsu global threshold in Java, binarize, back to Mat (RGBA) -> BGR.
        Mat rgba = new Mat();
        Bitmap bmp = null;
        try {
            // Convert BGR (3ch) -> RGBA (4ch) for Bitmap interop
            Imgproc.cvtColor(bgr, rgba, Imgproc.COLOR_BGR2RGBA);

            int width = rgba.cols();
            int height = rgba.rows();
            bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(rgba, bmp);

            int size = width * height;
            int[] pixels = new int[size];
            bmp.getPixels(pixels, 0, width, 0, 0, width, height);

            // Build histogram of grayscale values
            int[] hist = new int[256];
            int idx = 0;
            for (int y = 0; y < height; y++) {
                int base = y * width;
                for (int x = 0; x < width; x++) {
                    int c = pixels[base + x];
                    int r = (c >> 16) & 0xFF;
                    int g = (c >> 8) & 0xFF;
                    int b = c & 0xFF;
                    // Luma approximation with integer math
                    int gray = (299 * r + 587 * g + 114 * b + 500) / 1000;
                    hist[gray]++;
                }
            }

            // Compute Otsu threshold
            long total = size;
            long sum = 0;
            for (int t = 0; t < 256; t++) sum += (long) t * hist[t];
            long sumB = 0;
            long wB = 0;
            long wF;
            double maxVar = -1.0;
            int threshold = 127;
            for (int t = 0; t < 256; t++) {
                wB += hist[t];
                if (wB == 0) continue;
                wF = total - wB;
                if (wF == 0) break;
                sumB += (long) t * hist[t];
                double mB = sumB / (double) wB;
                double mF = (sum - sumB) / (double) wF;
                double varBetween = (double) wB * (double) wF * (mB - mF) * (mB - mF);
                if (varBetween > maxVar) {
                    maxVar = varBetween;
                    threshold = t;
                }
            }

            // Apply threshold to produce black/white
            int white = 0xFFFFFFFF;
            int black = 0xFF000000;
            for (int y = 0; y < height; y++) {
                int base = y * width;
                for (int x = 0; x < width; x++) {
                    int c = pixels[base + x];
                    int r = (c >> 16) & 0xFF;
                    int g = (c >> 8) & 0xFF;
                    int b = c & 0xFF;
                    int gray = (299 * r + 587 * g + 114 * b + 500) / 1000;
                    pixels[base + x] = (gray > threshold) ? white : black;
                }
            }

            bmp.setPixels(pixels, 0, width, 0, 0, width, height);

            // Back to Mat (RGBA) and then to BGR for downstream consistency
            Utils.bitmapToMat(bmp, rgba);
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR);
        } finally {
            try {
                rgba.release();
            } catch (Throwable ignore) {
            }
            // Allow GC to free bmp; don't recycle explicitly to avoid risks if reused upstream.
        }
    }

    private static Uri compressToUri(Context context, Bitmap bmp, int quality, Uri targetUri) {
        ContentResolver resolver = context.getContentResolver();
        try (OutputStream os = resolver.openOutputStream(targetUri, "w")) {
            if (os == null) {
                Log.e(TAG, "compressToUri: failed to open OutputStream for Uri: " + targetUri);
                return null;
            }
            boolean ok = bmp.compress(Bitmap.CompressFormat.JPEG, Math.max(0, Math.min(100, quality)), os);
            if (!ok) {
                Log.e(TAG, "compressToUri: Bitmap.compress returned false");
                return null;
            }
            os.flush();
            return targetUri;
        } catch (IOException e) {
            Log.e(TAG, "compressToUri: IO error while writing JPEG", e);
            return null;
        } catch (SecurityException se) {
            Log.e(TAG, "compressToUri: security error while writing JPEG", se);
            return null;
        }
    }
}
