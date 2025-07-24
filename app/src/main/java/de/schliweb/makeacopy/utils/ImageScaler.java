package de.schliweb.makeacopy.utils;

import android.graphics.Bitmap;
import android.util.Log;

/**
 * A utility class for scaling images to predefined dimensions while preserving their aspect ratio.
 * Primarily designed for preparing images to fit A4 dimensions at 300 DPI.
 * <p>
 * This class is useful in scenarios where images need to be prepared for PDF generation
 * or any other process requiring uniform image dimensions.
 */
public class ImageScaler {
    // A4 dimensions at 300 DPI (in pixels)
    public static final int A4_WIDTH_300DPI = 2480;
    public static final int A4_HEIGHT_300DPI = 3508;
    private static final String TAG = "ImageScaler";

    /**
     * Scales an image to A4 dimensions at 300 DPI while maintaining aspect ratio.
     * The image will be scaled to fit within A4 dimensions without stretching.
     * <p>
     * This pre-scaling ensures that OCR coordinates will match PDF coordinates
     * without complex transformations, resulting in perfect alignment of the
     * OCR text layer in the final PDF.
     * <p>
     * Using 300 DPI (instead of 150 DPI) provides better OCR results and
     * more accurate text positioning in the final PDF.
     *
     * @param originalBitmap The original bitmap to scale
     * @return A new bitmap scaled to A4 dimensions, or the original bitmap if it's already smaller
     */
    public static Bitmap scaleToA4(Bitmap originalBitmap) {
        if (originalBitmap == null) {
            Log.e(TAG, "scaleToA4: originalBitmap is null");
            return null;
        }

        // Calculate scaling factor to fit the image within A4 dimensions
        float scale = 1.0f;
        if (originalBitmap.getWidth() > A4_WIDTH_300DPI || originalBitmap.getHeight() > A4_HEIGHT_300DPI) {
            float scaleWidth = (float) A4_WIDTH_300DPI / originalBitmap.getWidth();
            float scaleHeight = (float) A4_HEIGHT_300DPI / originalBitmap.getHeight();
            scale = Math.min(scaleWidth, scaleHeight);

            Log.d(TAG, "scaleToA4: Scaling image from " + originalBitmap.getWidth() + "x" + originalBitmap.getHeight() + " to fit within A4 dimensions (" + A4_WIDTH_300DPI + "x" + A4_HEIGHT_300DPI + ") with scale factor " + scale);
        } else {
            Log.d(TAG, "scaleToA4: Image already fits within A4 dimensions, no scaling needed");
            return originalBitmap;
        }

        // Create a scaled bitmap
        int scaledWidth = Math.round(originalBitmap.getWidth() * scale);
        int scaledHeight = Math.round(originalBitmap.getHeight() * scale);

        Bitmap scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, scaledWidth, scaledHeight, true // Use filtering for better quality
        );

        Log.d(TAG, "scaleToA4: Scaled image to " + scaledWidth + "x" + scaledHeight);

        return scaledBitmap;
    }
}