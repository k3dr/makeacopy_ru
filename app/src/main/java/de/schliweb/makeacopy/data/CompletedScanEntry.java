package de.schliweb.makeacopy.data;

import androidx.annotation.Nullable;

/**
 * Represents an entry for a completed scan, containing metadata about the scan and associated file paths.
 * This class is typically used to store and transfer information about a single scanned document.
 */
public class CompletedScanEntry {
    public String id;
    @Nullable
    public String filePath;
    public int rotationDeg;
    @Nullable
    public String ocrTextPath;
    @Nullable
    public String ocrFormat; // "plain" | "hocr" | "alto" | "words_json" (optional; null implies "plain")
    @Nullable
    public String thumbPath;
    public long createdAt;
    public int widthPx;
    public int heightPx;

    public CompletedScanEntry() {
    }

    public CompletedScanEntry(String id,
                              @Nullable String filePath,
                              int rotationDeg,
                              @Nullable String ocrTextPath,
                              @Nullable String ocrFormat,
                              @Nullable String thumbPath,
                              long createdAt,
                              int widthPx,
                              int heightPx) {
        this.id = id;
        this.filePath = filePath;
        this.rotationDeg = rotationDeg;
        this.ocrTextPath = ocrTextPath;
        this.ocrFormat = ocrFormat;
        this.thumbPath = thumbPath;
        this.createdAt = createdAt;
        this.widthPx = widthPx;
        this.heightPx = heightPx;
    }
}
