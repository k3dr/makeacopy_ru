package de.schliweb.makeacopy.utils;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.util.DisplayMetrics;
import android.util.Size;

/**
 * Utility class providing methods for handling and optimizing {@code Bitmap} instances,
 * specifically in ensuring they adhere to size and memory constraints for display.
 * This class is not intended to be instantiated.
 */
public final class BitmapUtils {
    private BitmapUtils() {}

    // Conservative caps to avoid Canvas: trying to draw too large(...) bitmap
    // - Max edge in pixels
    // - Max total bytes for ARGB_8888 draw (~4 bytes per pixel)
    private static final int DEFAULT_MAX_EDGE = 4096; // widely safe on many devices
    private static final long DEFAULT_MAX_DRAW_BYTES = 100L * 1024L * 1024L; // ~100 MB

    /**
     * Ensures that the provided bitmap is safe to display on a Canvas by scaling it down
     * if it exceeds predefined edge or memory limits. If the bitmap is within the limits,
     * the same instance is returned. Otherwise, a downscaled copy is created and returned.
     *
     * @param src The input bitmap to process. If null, the method returns null.
     * @return A bitmap that is safe to display. Returns the original bitmap if it is within
     *         the limits, or a scaled-down copy otherwise. If the provided bitmap is null,
     *         the method returns null.
     */
    public static Bitmap ensureDisplaySafe(Bitmap src) {
        return ensureDisplaySafe(src, DEFAULT_MAX_EDGE, DEFAULT_MAX_DRAW_BYTES);
    }

    /**
     * Ensures that the provided bitmap is safe to display on a Canvas by scaling it down
     * if it exceeds predefined edge or memory limits. If the bitmap is within the limits,
     * the same instance is returned. Otherwise, a downscaled copy is created and returned.
     *
     * @param src      The input bitmap to process. If null, the method returns null.
     * @param maxEdge  The maximum allowed size (in pixels) for the width or height of the bitmap.
     * @param maxBytes The maximum allowed memory size (in bytes) for the bitmap.
     * @return A bitmap that is safe to display. Returns the original bitmap if it is within
     *         the limits, or a scaled-down copy otherwise. If the provided bitmap is null,
     *         the method returns null.
     */
    public static Bitmap ensureDisplaySafe(Bitmap src, int maxEdge, long maxBytes) {
        if (src == null) return null;
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0) return src;
        long bytes = bytesForDraw(src);
        boolean overEdge = (w > maxEdge) || (h > maxEdge);
        boolean overBytes = bytes > maxBytes;
        if (!overEdge && !overBytes) return src;

        // Compute scale factor to satisfy both edge and bytes constraints
        float scaleEdge = 1f;
        if (overEdge) {
            scaleEdge = Math.min(maxEdge / (float) w, maxEdge / (float) h);
        }
        float scaleBytes = 1f;
        if (overBytes) {
            // bytes scales with area => scale factor by sqrt(maxBytes/currentBytes)
            scaleBytes = (float) Math.sqrt((double) maxBytes / Math.max(1d, (double) bytes));
        }
        float scale = Math.min(scaleEdge, scaleBytes);
        if (scale >= 1f) return src; // numeric safety

        int newW = Math.max(1, Math.round(w * scale));
        int newH = Math.max(1, Math.round(h * scale));
        if (newW == w && newH == h) return src;

        // Create a scaled bitmap. Use createScaledBitmap for quality/simplicity.
        Bitmap scaled = Bitmap.createScaledBitmap(src, newW, newH, true);
        return scaled != null ? scaled : src;
    }

    /**
     * Calculates the estimated memory usage in bytes for a bitmap when rendered with the
     * ARGB_8888 configuration, which requires 4 bytes per pixel.
     *
     * @param b The input Bitmap for which the byte size is calculated. Must not be null.
     * @return The estimated memory usage in bytes for drawing the bitmap.
     */
    private static long bytesForDraw(Bitmap b) {
        // Most draw paths end up using ARGB_8888; use 4 bytes per pixel estimate.
        // Use getAllocationByteCount if available, but keep a cap for safety.
        long pixels = (long) b.getWidth() * (long) b.getHeight();
        return pixels * 4L;
    }

    /**
     * Scales the dimensions of a source rectangle to fit within a target rectangle,
     * preserving the aspect ratio.
     *
     * @param srcW The width of the source rectangle. Must be a positive integer.
     * @param srcH The height of the source rectangle. Must be a positive integer.
     * @param maxW The maximum width of the target rectangle. Must be a positive integer.
     * @param maxH The maximum height of the target rectangle. Must be a positive integer.
     * @return A {@code Size} object representing the scaled width and height that
     *         fit within the target dimensions while preserving the aspect ratio.
     *         Returns a {@code Size} of (0, 0) if either {@code srcW} or {@code srcH}
     *         is less than or equal to zero.
     */
    public static Size fitInto(int srcW, int srcH, int maxW, int maxH) {
        if (srcW <= 0 || srcH <= 0) return new Size(0, 0);
        float scale = Math.min(maxW / (float) srcW, maxH / (float) srcH);
        int w = Math.max(1, Math.round(srcW * scale));
        int h = Math.max(1, Math.round(srcH * scale));
        return new Size(w, h);
    }
}
