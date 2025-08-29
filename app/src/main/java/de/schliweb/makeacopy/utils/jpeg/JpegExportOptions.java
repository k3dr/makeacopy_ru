package de.schliweb.makeacopy.utils.jpeg;

import androidx.annotation.IntRange;

/**
 * Options for JPEG export. This class intentionally contains only simple fields
 * to keep it parcel-agnostic and easy to extend later.
 */
public class JpegExportOptions {

    public enum Mode {
        /**
         * No enhancement, just compress the given bitmap.
         */
        NONE,
        /**
         * Auto document enhancement (CLAHE + Unsharp + optional mild gamma).
         */
        AUTO,
        /**
         * Black/White mode optimized for text via adaptive thresholding.
         */
        BW_TEXT
    }

    /**
     * JPEG quality (0..100). Default 85.
     */
    @IntRange(from = 0, to = 100)
    public int quality = 85;

    /**
     * Long edge in pixels. 0 = keep original size. Default 0.
     */
    public int longEdgePx = 0;

    /**
     * Enhancement mode. Default NONE.
     */
    public Mode mode = Mode.NONE;

    public JpegExportOptions() {
    }

    public JpegExportOptions(@IntRange(from = 0, to = 100) int quality, int longEdgePx, Mode mode) {
        this.quality = Math.max(0, Math.min(100, quality));
        this.longEdgePx = Math.max(0, longEdgePx);
        this.mode = (mode == null) ? Mode.NONE : mode;
    }
}
