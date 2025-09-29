package de.schliweb.makeacopy.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.net.Uri;
import android.util.Log;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.font.PDFont;
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory;
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject;
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode;
import com.tom_roush.pdfbox.util.Matrix;

import java.io.InputStream;
import java.io.OutputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Generates a searchable PDF (image + invisible OCR text layer).
 * The OCR text is drawn in the SAME transform as the image to avoid viewer-specific drift.
 */
public class PdfCreator {
    private static final String TAG = "PdfCreator";
    // Text sizing (relative to OCR box height in image space)
    private static final float TEXT_SIZE_RATIO = 0.70f;
    private static final float MIN_FONT_PT = 2f; // lower bound for tiny boxes

    /**
     * Creates a searchable PDF from bitmap + OCR words.
     */
    public static Uri createSearchablePdf(Context context,
                                          Bitmap bitmap,
                                          List<RecognizedWord> words,
                                          Uri outputUri,
                                          int jpegQuality,
                                          boolean convertToGrayscale) {
        // Backward-compatible overload: no black-and-white flag -> false
        return createSearchablePdf(context, bitmap, words, outputUri, jpegQuality, convertToGrayscale, false, 300);
    }

    /**
     * Creates a searchable PDF from bitmap + OCR words with explicit black-and-white option.
     */
    public static Uri createSearchablePdf(Context context,
                                          Bitmap bitmap,
                                          List<RecognizedWord> words,
                                          Uri outputUri,
                                          int jpegQuality,
                                          boolean convertToGrayscale,
                                          boolean convertToBlackWhite) {
        return createSearchablePdf(context, bitmap, words, outputUri, jpegQuality, convertToGrayscale, convertToBlackWhite, 300);
    }

    // Phase 1: Overload with target DPI
    // Backward-compatible: target DPI overload without BW flag delegates to BW=false
    public static Uri createSearchablePdf(Context context,
                                          Bitmap bitmap,
                                          List<RecognizedWord> words,
                                          Uri outputUri,
                                          int jpegQuality,
                                          boolean convertToGrayscale,
                                          int targetDpi) {
        return createSearchablePdf(context, bitmap, words, outputUri, jpegQuality, convertToGrayscale, false, targetDpi);
    }

    // Phase 1: Overload with target DPI and black-white flag
    public static Uri createSearchablePdf(Context context,
                                          Bitmap bitmap,
                                          List<RecognizedWord> words,
                                          Uri outputUri,
                                          int jpegQuality,
                                          boolean convertToGrayscale,
                                          boolean convertToBlackWhite,
                                          int targetDpi) {
        Log.d(TAG, "createSearchablePdf: uri=" + outputUri + ", words=" + (words == null ? 0 : words.size()));
        if (bitmap == null || outputUri == null) return null;

        try {
            PDFBoxResourceLoader.init(context);
            try {
                OpenCVUtils.init(context);
            } catch (Throwable ignore) {
            }
        } catch (Throwable t) {
            Log.e(TAG, "PDFBox init failed", t);
            return null;
        }

        Bitmap prepared = null;
        try {
            prepared = processImageForPdf(bitmap, convertToGrayscale, convertToBlackWhite, targetDpi);
            if (prepared == null) {
                Log.e(TAG, "Image preparation via OpenCV failed");
                return null;
            }

            try (PDDocument document = new PDDocument()) {
                try {
                    document.getDocument().setVersion(1.5f);
                } catch (Throwable ignore) {
                }
                document.getDocumentInformation().setCreator("MakeACopy");
                document.getDocumentInformation().setProducer("MakeACopy");

                PDRectangle pageSize = PDRectangle.A4;
                float pageW = pageSize.getWidth();
                float pageH = pageSize.getHeight();

                PDPage page = new PDPage(pageSize);
                // Harmonize page boxes to avoid viewer-specific cropping/offset interpretations
                try {
                    page.setMediaBox(pageSize);
                    page.setCropBox(pageSize);
                    page.setBleedBox(pageSize);
                    page.setTrimBox(pageSize);
                    page.setArtBox(pageSize);
                } catch (Throwable ignore) {
                }
                document.addPage(page);

                // Fit image into page while preserving aspect ratio (letterboxing if needed)
                float scale = calculateScale(prepared.getWidth(), prepared.getHeight(), pageW, pageH);
                float drawW = prepared.getWidth() * scale;
                float drawH = prepared.getHeight() * scale;
                float offsetX = (pageW - drawW) / 2f;
                float offsetY = (pageH - drawH) / 2f;

                float q = Math.max(0f, Math.min(1f, jpegQuality / 100f));
                PDImageXObject pdImg = (jpegQuality < 100)
                        ? JPEGFactory.createFromImage(document, prepared, q)
                        : LosslessFactory.createFromImage(document, prepared);

                // Load embedded fonts with fallbacks
                List<PDFont> fonts = loadFontsWithFallbacks(document, context);

                try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                    // 1) Draw image in page coordinates
                    cs.drawImage(pdImg, offsetX, offsetY, drawW, drawH);

                    // 2) Draw OCR text in the EXACT SAME transform as the image
                    if (words != null && !words.isEmpty()) {
                        cs.saveGraphicsState();
                        cs.transform(new Matrix(scale, 0, 0, scale, offsetX, offsetY)); // identical CTM
                        // Normalize OCR boxes from source bitmap space to prepared bitmap space if needed
                        List<RecognizedWord> normWords;
                        if (bitmap.getWidth() != prepared.getWidth() || bitmap.getHeight() != prepared.getHeight()) {
                            float sxImg = (float) prepared.getWidth() / (float) bitmap.getWidth();
                            float syImg = (float) prepared.getHeight() / (float) bitmap.getHeight();
                            normWords = new ArrayList<>(words.size());
                            for (RecognizedWord w : words) {
                                normWords.add(w.transform(sxImg, syImg, 0f, 0f).clipTo(prepared.getWidth(), prepared.getHeight()));
                            }
                        } else {
                            normWords = words;
                        }
                        Log.d(TAG, "createSearchablePdf: " + normWords.size() + " OCR words");
                        // now output text in IMAGE coordinates (0..imgW / 0..imgH)
                        addTextLayerImageSpace(cs, normWords, fonts, prepared.getWidth(), prepared.getHeight());
                        cs.restoreGraphicsState();
                    }
                }

                try (OutputStream os = context.getContentResolver().openOutputStream(outputUri)) {
                    if (os == null) {
                        Log.e(TAG, "createSearchablePdf: openOutputStream returned null");
                        return null;
                    }
                    document.save(os);
                }
                return outputUri;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error creating PDF", e);
            return null;
        } finally {
            if (prepared != null && prepared != bitmap) {
                try {
                    prepared.recycle();
                } catch (Throwable ignore) {
                }
            }
        }
    }

    private static List<PDFont> loadFontsWithFallbacks(PDDocument document, Context context) {
        List<PDFont> fonts = new ArrayList<>();
        // Put the widest-coverage fonts first. Only those present in assets/ will be loaded.
        String[] candidates = new String[]{
                "fonts/NotoSans-Regular.ttf",             // Latin
                "fonts/NotoSansSymbols2-Regular.ttf",     // Symbols (optional)
                "fonts/NotoSansCJKsc-Regular.ttf",        // CJK Simplified (optional, large)
                "fonts/NotoSansCJKtc-Regular.ttf",        // CJK Traditional (optional, large)
                "fonts/NotoNaskhArabic-Regular.ttf",      // Arabic (optional)
                "fonts/NotoSansDevanagari-Regular.ttf"    // Indic (optional)
        };
        for (String path : candidates) {
            try (InputStream is = context.getAssets().open(path)) {
                Log.d(TAG, "Loading font: " + path);
                fonts.add(PDType0Font.load(document, is, true));
            } catch (Exception exception) {
                Log.w(TAG, "Font not found: " + path);
                Log.w(TAG, "Exception: " + exception.getMessage());
            }
        }
        if (fonts.isEmpty()) {
            // Last resort: try the base NotoSans; if missing, fall back to Helvetica (not embedded)
            try (InputStream is = context.getAssets().open("fonts/NotoSans-Regular.ttf")) {
                fonts.add(PDType0Font.load(document, is, true));
            } catch (Exception e) {
                Log.w(TAG, "No embedded font found, falling back to Helvetica (not embedded)");
                fonts.add(PDType1Font.HELVETICA);
            }
        }
        return fonts;
    }

    // ===== Fonts (embedded) with fallbacks =====

    private static void showTextWithFallbacks(PDPageContentStream cs,
                                              String token,
                                              float fontSize,
                                              List<PDFont> fonts) throws Exception {
        Exception last = null;
        for (PDFont f : fonts) {
            try {
                cs.setFont(f, fontSize);
                cs.showText(token);
                return; // success
            } catch (Exception e) {
                last = e; // try next
            }
        }
        // As a safety net, replace control chars and draw with first font
        cs.setFont(fonts.get(0), fontSize);
        cs.showText(token.replaceAll("\\p{C}", "?"));
        if (last != null) {
            Log.w(TAG, "showText fallback used: " + last.getMessage());
        }
    }

    private static void addTextLayerImageSpace(PDPageContentStream cs,
                                               List<RecognizedWord> words,
                                               List<PDFont> fonts,
                                               int imageWidth,
                                               int imageHeight) throws Exception {
        if (words == null || words.isEmpty()) return;

        // Sort top->bottom, then left->right
        words.sort((a, b) -> {
            float ya = (a.getBoundingBox().top + a.getBoundingBox().bottom) * 0.5f;
            float yb = (b.getBoundingBox().top + b.getBoundingBox().bottom) * 0.5f;
            if (Math.abs(ya - yb) < 6f) {
                return Float.compare(a.getBoundingBox().left, b.getBoundingBox().left);
            }
            return Float.compare(ya, yb);
        });

        // Cluster to lines
        List<List<RecognizedWord>> lines = new ArrayList<>();
        for (RecognizedWord w : words) {
            if (lines.isEmpty()) {
                List<RecognizedWord> l = new ArrayList<>();
                l.add(w);
                lines.add(l);
            } else {
                List<RecognizedWord> last = lines.get(lines.size() - 1);
                float refY = (last.get(0).getBoundingBox().top + last.get(0).getBoundingBox().bottom) * 0.5f;
                float curY = (w.getBoundingBox().top + w.getBoundingBox().bottom) * 0.5f;
                if (Math.abs(curY - refY) < 6f) last.add(w);
                else {
                    List<RecognizedWord> l = new ArrayList<>();
                    l.add(w);
                    lines.add(l);
                }
            }
        }

        // Render lines; absolute positioning per token (no TJ-kerning)
        for (List<RecognizedWord> line : lines) {
            if (line.isEmpty()) continue;
            line.sort(Comparator.comparingDouble(rw -> rw.getBoundingBox().left));

            float medianH = medianHeight(line);                       // px in image
            float fontSize = Math.max(MIN_FONT_PT, medianH * TEXT_SIZE_RATIO);

            for (int i = 0; i < line.size(); i++) {
                RecognizedWord w = line.get(i);
                String tokenRaw = safeText(w.getText());
                if (tokenRaw.trim().isEmpty()) continue;

                // Normalize to NFC; trailing space improves selection continuity
                String token = Normalizer.normalize(tokenRaw + " ", Normalizer.Form.NFC);

                RectF b = w.getBoundingBox();
                float boxH = b.height();

                // Baseline ~ lower quarter of the box (image space: Y grows downward)
                float baselineImgY = b.bottom + boxH * 0.25f;

                // Convert to PDF Y-up (image space): invert Y once
                float x_img = clamp(b.left, 0f, imageWidth);
                float y_img = clamp((imageHeight - baselineImgY), 0f, imageHeight);

                cs.beginText();
                cs.setRenderingMode(RenderingMode.NEITHER); // invisible but selectable
                cs.setTextMatrix(Matrix.getTranslateInstance(x_img, y_img));
                showTextWithFallbacks(cs, token, fontSize, fonts);
                cs.endText();
            }
        }
    }

    // ===== OCR text rendering in IMAGE SPACE =====

    private static float calculateScale(int imageWidth, int imageHeight, float pageWidth, float pageHeight) {
        float sx = pageWidth / imageWidth;
        float sy = pageHeight / imageHeight;
        return Math.min(sx, sy);
    }

    // ===== Image prep & helpers =====

    private static Bitmap processImageForPdf(Bitmap original, boolean toGray) {
        // Backward-compatible: default to 300 dpi A4 target
        return processImageForPdf(original, toGray, false, 300);
    }

    // Backward-compatible: target DPI control without BW flag
    private static Bitmap processImageForPdf(Bitmap original, boolean toGray, int targetDpi) {
        return processImageForPdf(original, toGray, false, targetDpi);
    }

    // New: allow BW conversion with precedence over grayscale
    private static Bitmap processImageForPdf(Bitmap original, boolean toGray, boolean toBw, int targetDpi) {
        if (original == null) return null;

        int[] a4px = a4PixelsForDpi(targetDpi <= 0 ? 300 : targetDpi);
        int maxW = a4px[0];
        int maxH = a4px[1];

        boolean preScaled =
                Math.abs(original.getWidth() - maxW) <= 1 &&
                        Math.abs(original.getHeight() - maxH) <= 1;

        Bitmap base = original;

        if (!preScaled) {
            float scale = 1f;
            if (original.getWidth() > maxW || original.getHeight() > maxH) {
                float sw = (float) maxW / original.getWidth();
                float sh = (float) maxH / original.getHeight();
                scale = Math.min(sw, sh);
            }

            if (scale < 1f) {
                int w = Math.max(1, Math.round(original.getWidth() * scale));
                int h = Math.max(1, Math.round(original.getHeight() * scale));
                base = Bitmap.createScaledBitmap(original, w, h, true);
            }
        }

        if (toBw) {
            Bitmap viaCv = OpenCVUtils.toBw(base);
            if (base != original) {
                try {
                    base.recycle();
                } catch (Throwable ignore) {
                }
            }
            return viaCv; // may be null → caller will handle
        }

        if (!toGray) return base;

        Bitmap viaCvGray = OpenCVUtils.toGray(base);
        if (base != original) {
            try {
                base.recycle();
            } catch (Throwable ignore) {
            }
        }
        return viaCvGray; // may be null → caller will handle
    }


    private static int[] a4PixelsForDpi(int dpi) {
        // A4 size in inches: 8.27 x 11.69
        int w = Math.max(1, Math.round(8.27f * dpi));
        int h = Math.max(1, Math.round(11.69f * dpi));
        return new int[]{w, h};
    }

    private static float medianHeight(List<RecognizedWord> line) {
        List<Float> heights = new ArrayList<>();
        for (RecognizedWord w : line) heights.add(w.getBoundingBox().height());
        Collections.sort(heights);
        int n = heights.size();
        if (n == 0) return 0;
        if (n % 2 == 1) return heights.get(n / 2);
        return (heights.get(n / 2 - 1) + heights.get(n / 2)) / 2f;
    }

    private static String safeText(String t) {
        if (t == null) return "";
        return t.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", " ");
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    /**
     * Creates a searchable multi-page PDF from a list of bitmaps and optional per-page OCR words.
     * Each bitmap is placed on its own A4 page with the same scaling and centering logic as the
     * single-page variant. If perPageWords[i] is non-null/non-empty, an OCR text layer is drawn
     * for that page using identical image-space transform.
     */
    public static Uri createSearchablePdf(Context context,
                                          List<Bitmap> bitmaps,
                                          List<List<RecognizedWord>> perPageWords,
                                          Uri outputUri,
                                          int jpegQuality,
                                          boolean convertToGrayscale) {
        // Backward-compatible: no BW flag
        return createSearchablePdf(context, bitmaps, perPageWords, outputUri, jpegQuality, convertToGrayscale, false);
    }

    public static Uri createSearchablePdf(Context context,
                                          List<Bitmap> bitmaps,
                                          List<List<RecognizedWord>> perPageWords,
                                          Uri outputUri,
                                          int jpegQuality,
                                          boolean convertToGrayscale,
                                          boolean convertToBlackWhite) {
        // Default 300 dpi behavior
        return createSearchablePdf(context, bitmaps, perPageWords, outputUri, jpegQuality, convertToGrayscale, convertToBlackWhite, 300, null);
    }

    public static Uri createSearchablePdf(Context context,
                                          List<Bitmap> bitmaps,
                                          List<List<RecognizedWord>> perPageWords,
                                          Uri outputUri,
                                          int jpegQuality,
                                          boolean convertToGrayscale,
                                          ProgressListener listener) {
        // Default 300 dpi behavior without BW flag
        return createSearchablePdf(context, bitmaps, perPageWords, outputUri, jpegQuality, convertToGrayscale, false, 300, listener);
    }

    // Phase 1: Overload with target DPI + progress listener
    public static Uri createSearchablePdf(Context context,
                                          List<Bitmap> bitmaps,
                                          List<List<RecognizedWord>> perPageWords,
                                          Uri outputUri,
                                          int jpegQuality,
                                          boolean convertToGrayscale,
                                          int targetDpi,
                                          ProgressListener listener) {
        return createSearchablePdf(context, bitmaps, perPageWords, outputUri, jpegQuality, convertToGrayscale, false, targetDpi, listener);
    }

    public static Uri createSearchablePdf(Context context,
                                          List<Bitmap> bitmaps,
                                          List<List<RecognizedWord>> perPageWords,
                                          Uri outputUri,
                                          int jpegQuality,
                                          boolean convertToGrayscale,
                                          boolean convertToBlackWhite,
                                          int targetDpi,
                                          ProgressListener listener) {
        if (bitmaps == null || bitmaps.isEmpty() || outputUri == null) return null;
        try {
            PDFBoxResourceLoader.init(context);
            try {
                OpenCVUtils.init(context);
            } catch (Throwable ignore) {
            }
        } catch (Throwable t) {
            Log.e(TAG, "PDFBox init failed", t);
            return null;
        }
        try (PDDocument document = new PDDocument()) {
            try {
                document.getDocument().setVersion(1.5f);
            } catch (Throwable ignore) {
            }
            document.getDocumentInformation().setCreator("MakeACopy");
            document.getDocumentInformation().setProducer("MakeACopy");

            PDRectangle pageSize = PDRectangle.A4;
            float pageW = pageSize.getWidth();
            float pageH = pageSize.getHeight();

            // Load fonts once
            List<PDFont> fonts = loadFontsWithFallbacks(document, context);

            int total = bitmaps.size();
            for (int i = 0; i < bitmaps.size(); i++) {
                Bitmap src = bitmaps.get(i);
                if (src == null) {
                    if (listener != null) try {
                        listener.onPageProcessed(i + 1, total);
                    } catch (Throwable ignore) {
                    }
                    continue; // skip nulls defensively
                }
                Bitmap prepared = null;
                try {
                    prepared = processImageForPdf(src, convertToGrayscale, convertToBlackWhite, targetDpi);
                    if (prepared == null) {
                        Log.e(TAG, "Image preparation via OpenCV failed for page " + (i + 1));
                        return null;
                    }

                    PDPage page = new PDPage(pageSize);
                    // Harmonize page boxes to avoid viewer-specific cropping/offset interpretations
                    try {
                        page.setMediaBox(pageSize);
                        page.setCropBox(pageSize);
                        page.setBleedBox(pageSize);
                        page.setTrimBox(pageSize);
                        page.setArtBox(pageSize);
                    } catch (Throwable ignore) {
                    }
                    document.addPage(page);

                    float scale = calculateScale(prepared.getWidth(), prepared.getHeight(), pageW, pageH);
                    float drawW = prepared.getWidth() * scale;
                    float drawH = prepared.getHeight() * scale;
                    float offsetX = (pageW - drawW) / 2f;
                    float offsetY = (pageH - drawH) / 2f;

                    float q = Math.max(0f, Math.min(1f, jpegQuality / 100f));
                    PDImageXObject pdImg = (jpegQuality < 100)
                            ? JPEGFactory.createFromImage(document, prepared, q)
                            : LosslessFactory.createFromImage(document, prepared);

                    try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                        cs.drawImage(pdImg, offsetX, offsetY, drawW, drawH);
                        List<RecognizedWord> words = (perPageWords != null && i < perPageWords.size()) ? perPageWords.get(i) : null;
                        if (words != null && !words.isEmpty()) {
                            cs.saveGraphicsState();
                            cs.transform(new Matrix(scale, 0, 0, scale, offsetX, offsetY));
                            // Normalize OCR boxes from source bitmap space to prepared bitmap space if needed
                            List<RecognizedWord> normWords;
                            if (src.getWidth() != prepared.getWidth() || src.getHeight() != prepared.getHeight()) {
                                float sxImg = (float) prepared.getWidth() / (float) src.getWidth();
                                float syImg = (float) prepared.getHeight() / (float) src.getHeight();
                                normWords = new ArrayList<>(words.size());
                                for (RecognizedWord w : words) {
                                    normWords.add(w.transform(sxImg, syImg, 0f, 0f).clipTo(prepared.getWidth(), prepared.getHeight()));
                                }
                            } else {
                                normWords = words;
                            }
                            addTextLayerImageSpace(cs, normWords, fonts, prepared.getWidth(), prepared.getHeight());
                            cs.restoreGraphicsState();
                        }
                    }
                    if (listener != null) {
                        try {
                            listener.onPageProcessed(i + 1, total);
                        } catch (Throwable ignore) {
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error rendering page " + (i + 1), e);
                    return null;
                } finally {
                    if (prepared != null && prepared != src) {
                        try {
                            prepared.recycle();
                        } catch (Throwable ignore) {
                        }
                    }
                }
            }

            try (OutputStream os = context.getContentResolver().openOutputStream(outputUri)) {
                if (os == null) {
                    Log.e(TAG, "createSearchablePdf(multi): openOutputStream returned null");
                    return null;
                }
                document.save(os);
            }
            return outputUri;
        } catch (Exception e) {
            Log.e(TAG, "Error creating multi-page PDF", e);
            return null;
        }
    }

    public interface ProgressListener {
        void onPageProcessed(int pageIndex, int totalPages);
    }
}
