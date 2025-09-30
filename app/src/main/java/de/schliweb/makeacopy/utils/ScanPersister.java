package de.schliweb.makeacopy.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import de.schliweb.makeacopy.data.CompletedScansRegistry;
import de.schliweb.makeacopy.ui.export.session.CompletedScan;

/**
 * Utility responsible for persisting a scanned page to the app's private storage and registry.
 * It writes the full JPEG (page.jpg), creates a thumbnail (thumb.jpg), and optionally persists
 * OCR outputs (text.txt, words.json). Functionality mirrors the previously inlined logic
 * in ExportFragment to reduce its complexity without changing behavior.
 */
public final class ScanPersister {
    private static final String TAG = "ScanPersister";

    private ScanPersister() {
    }

    /**
     * Persist the given in-memory scan to disk and registry.
     * <p>
     * Behavioural notes (kept identical to previous implementation):
     * - JPEG quality: page 90, thumbnail 75
     * - Thumbnail long edge ~240 px, applies rotation before scaling
     * - Writes text.txt when non-empty text provided
     * - Writes words.json when words provided and prefers it over plain text in registry
     * - Swallows non-critical IO errors, logs registry insert failures
     *
     * @param appContext application context
     * @param inMemory   completed scan that contains id, rotation, createdAt, width/height, and in-memory bitmap
     * @param ocrText    optional OCR text (nullable)
     * @param ocrWords   optional OCR words (nullable)
     * @return the persisted CompletedScan (with file paths, no in-memory bitmap)
     * @throws Exception for unexpected critical failures
     */
    public static CompletedScan persist(Context appContext,
                                        CompletedScan inMemory,
                                        String ocrText,
                                        List<RecognizedWord> ocrWords) throws Exception {
        if (appContext == null || inMemory == null || inMemory.id() == null || inMemory.inMemoryBitmap() == null) {
            throw new IllegalArgumentException("Invalid arguments for persist");
        }
        final Bitmap bmp = inMemory.inMemoryBitmap();
        final String id = inMemory.id();

        File dir = new File(appContext.getFilesDir(), "scans/" + id);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        File page = new File(dir, "page.jpg");
        try (FileOutputStream fos = new FileOutputStream(page)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, fos);
            fos.flush();
        }
        // Create thumbnail with rotation applied
        Bitmap sourceForThumb = bmp;
        try {
            int deg = 0;
            try {
                deg = inMemory.rotationDeg();
            } catch (Throwable ignore) {
            }
            deg = ((deg % 360) + 360) % 360;
            if (deg != 0 && bmp != null && !bmp.isRecycled()) {
                Matrix m = new Matrix();
                m.postRotate(deg);
                Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                if (rotated != null) sourceForThumb = rotated;
            }
        } catch (Throwable ignore) { /* fall back to original */ }
        int w = sourceForThumb.getWidth();
        int h = sourceForThumb.getHeight();
        int longEdge = Math.max(w, h);
        int target = 240;
        float scale = longEdge > target ? (target / (float) longEdge) : 1f;
        int nw = Math.max(1, Math.round(w * scale));
        int nh = Math.max(1, Math.round(h * scale));
        Bitmap thumb = Bitmap.createScaledBitmap(sourceForThumb, nw, nh, true);
        File thumbFile = new File(dir, "thumb.jpg");
        try (FileOutputStream tfos = new FileOutputStream(thumbFile)) {
            thumb.compress(Bitmap.CompressFormat.JPEG, 75, tfos);
            tfos.flush();
        } catch (Throwable ignore) {
        }
        if (sourceForThumb != bmp) {
            try {
                sourceForThumb.recycle();
            } catch (Throwable ignore) {
            }
        }

        // Persist OCR artifacts
        String ocrPath = null;
        String ocrFormat = null;
        try {
            if (ocrText != null && !ocrText.isEmpty()) {
                File txt = new File(dir, "text.txt");
                try (FileOutputStream tf = new FileOutputStream(txt)) {
                    tf.write(ocrText.getBytes(StandardCharsets.UTF_8));
                    tf.flush();
                    ocrPath = txt.getAbsolutePath();
                    ocrFormat = "plain";
                }
            }
            if (ocrWords != null && !ocrWords.isEmpty()) {
                File wordsFile = new File(dir, "words.json");
                try (FileOutputStream wos = new FileOutputStream(wordsFile)) {
                    String json = WordsJson.toWordsJson(ocrWords);
                    wos.write(json.getBytes(StandardCharsets.UTF_8));
                    wos.flush();
                    // Prefer words_json
                    ocrPath = wordsFile.getAbsolutePath();
                    ocrFormat = "words_json";
                }
            }
        } catch (Throwable ignore) { /* leave as last successful */ }

        CompletedScan persisted = new CompletedScan(
                id,
                page.getAbsolutePath(),
                inMemory.rotationDeg(),
                ocrPath,
                ocrFormat,
                thumbFile.getAbsolutePath(),
                inMemory.createdAt(),
                inMemory.widthPx(),
                inMemory.heightPx(),
                null
        );
        try {
            CompletedScansRegistry reg = CompletedScansRegistry.get(appContext);
            reg.insert(persisted);
        } catch (Exception e) {
            Log.w(TAG, "Registry insert failed", e);
        }
        return persisted;
    }
}
