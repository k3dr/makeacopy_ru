package de.schliweb.makeacopy.jobs;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.schliweb.makeacopy.data.CompletedScansRegistry;
import de.schliweb.makeacopy.ui.export.session.CompletedScan;
import de.schliweb.makeacopy.ui.ocr.OCRViewModel; // only for type reference if needed
import de.schliweb.makeacopy.ui.export.ExportFragment; // for toWordsJson? no, not accessible here
import de.schliweb.makeacopy.utils.OCRHelper;
import de.schliweb.makeacopy.utils.RecognizedWord;

/**
 * Minimal background OCR job runner without external dependencies.
 * Ensures only one OCR job per page id runs at a time.
 * After success/failure, broadcasts ACTION_OCR_UPDATED with extras.
 */
public final class OcrBackgroundJobs {
    private static final String TAG = "OcrBackgroundJobs";

    public static final String ACTION_OCR_UPDATED = "de.schliweb.makeacopy.ACTION_OCR_UPDATED";
    public static final String EXTRA_PAGE_ID = "page_id";
    public static final String EXTRA_SUCCESS = "success";

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final Set<String> running = Collections.synchronizedSet(new HashSet<>());

    private OcrBackgroundJobs() {}

    /**
     * Enqueues a background reprocessing task for Optical Character Recognition (OCR)
     * on a scanned page. The method will attempt to generate and store OCR results
     * including text and recognized words for the specified page.
     *
     * @param ctx     The application context used for accessing system resources.
     * @param pageId  The unique identifier of the scanned page to be reprocessed.
     * @param languageOpt Optional language code for OCR processing (e.g., "eng" for English).
     *                    If null or empty, a default language will be used.
     */
    public static void enqueueReprocess(Context ctx, String pageId, String languageOpt) {
        if (ctx == null || pageId == null) return;
        final Context app = ctx.getApplicationContext();
        synchronized (running) {
            if (running.contains(pageId)) {
                Log.d(TAG, "Job already running for pageId=" + pageId);
                return;
            }
            running.add(pageId);
        }
        EXEC.execute(() -> {
            boolean success = false;
            try {
                CompletedScansRegistry reg = CompletedScansRegistry.get(app);
                CompletedScan s = null;
                for (CompletedScan it : reg.listAllOrderedByDateDesc()) {
                    if (it != null && pageId.equals(it.id())) { s = it; break; }
                }
                if (s == null) throw new RuntimeException("Entry not found in registry: " + pageId);
                Bitmap bmp = null;
                if (s.filePath() != null) bmp = BitmapFactory.decodeFile(s.filePath());
                if (bmp == null && s.thumbPath() != null) bmp = BitmapFactory.decodeFile(s.thumbPath());
                if (bmp == null) throw new RuntimeException("No bitmap available for OCR");

                OCRHelper helper = new OCRHelper(app);
                if (!helper.initTesseract()) throw new RuntimeException("Tesseract init failed");
                if (languageOpt != null && !languageOpt.trim().isEmpty()) {
                    try { helper.setLanguage(languageOpt); } catch (Throwable ignore) {}
                }
                OCRHelper.OcrResultWords res = helper.runOcrWithWords(bmp);
                String text = (res != null && res.text != null) ? res.text : "";

                File dir = new File(app.getFilesDir(), "scans/" + s.id());
                if (!dir.exists()) //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();

                // Write plain text as fallback
                File txt = new File(dir, "text.txt");
                try (FileOutputStream fos = new FileOutputStream(txt)) {
                    fos.write(text.getBytes(StandardCharsets.UTF_8));
                    fos.flush();
                }
                // Write words.json
                File wordsFile = new File(dir, "words.json");
                try (FileOutputStream wos = new FileOutputStream(wordsFile)) {
                    String json = toWordsJson(res != null ? res.words : null);
                    wos.write(json.getBytes(StandardCharsets.UTF_8));
                    wos.flush();
                }

                // Update registry to prefer words_json
                CompletedScan updated = new CompletedScan(
                        s.id(), s.filePath(), s.rotationDeg(), wordsFile.getAbsolutePath(), "words_json",
                        s.thumbPath(), s.createdAt(), s.widthPx(), s.heightPx(), s.inMemoryBitmap());
                try {
                    reg.remove(s.id());
                } catch (Throwable ignore) {}
                try {
                    reg.insert(updated);
                } catch (Throwable e) {
                    Log.w(TAG, "Failed to insert updated OCR entry", e);
                }
                success = true;
            } catch (Throwable t) {
                Log.e(TAG, "Background OCR failed", t);
            } finally {
                running.remove(pageId);
                // Notify UI (if alive)
                Intent intent = new Intent(ACTION_OCR_UPDATED);
                intent.putExtra(EXTRA_PAGE_ID, pageId);
                intent.putExtra(EXTRA_SUCCESS, success);
                try {
                    androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(app).sendBroadcast(intent);
                } catch (Throwable ignore) {}
            }
        });
    }

    // Simple serializer duplicated to avoid coupling to ExportFragment
    private static String toWordsJson(java.util.List<RecognizedWord> words) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        if (words != null) {
            boolean first = true;
            for (RecognizedWord w : words) {
                if (w == null) continue;
                android.graphics.RectF r = w.getBoundingBox();
                if (!first) sb.append(',');
                first = false;
                float conf = 0f;
                try { conf = (float) w.getConfidence(); } catch (Throwable ignore) {}
                sb.append('{')
                        .append("\"text\":").append(escapeJsonString(w.getText())).append(',')
                        .append("\"left\":").append(formatFloat(r.left)).append(',')
                        .append("\"top\":").append(formatFloat(r.top)).append(',')
                        .append("\"right\":").append(formatFloat(r.right)).append(',')
                        .append("\"bottom\":").append(formatFloat(r.bottom)).append(',')
                        .append("\"confidence\":").append(formatFloat(conf))
                        .append('}');
            }
        }
        sb.append(']');
        return sb.toString();
    }

    private static String escapeJsonString(String s) {
        if (s == null) return "\"\"";
        StringBuilder out = new StringBuilder();
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format(java.util.Locale.US, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
        return out.toString();
    }

    private static String formatFloat(float f) {
        return String.format(java.util.Locale.US, "%.6f", f);
    }
}
