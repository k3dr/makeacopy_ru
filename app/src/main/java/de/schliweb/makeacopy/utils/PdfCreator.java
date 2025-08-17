package de.schliweb.makeacopy.utils;

import android.content.Context;
import android.graphics.*;
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

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Utility class for generating searchable PDF files from bitmap images
 * and recognized text information.
 * <p>
 * This class integrates image processing and Optical Character Recognition (OCR) outputs
 * to generate a PDF file that includes an image representation of the source as well
 * as selectable text rendered on top of the image for searchability.
 * <p>
 * The PDF generation makes use of the Apache PDFBox library for managing PDF file creation,
 * image embedding, and text layer rendering.
 */
public class PdfCreator {
    private static final String TAG = "PdfCreator";

    // Fine-tuning constants
    private static final float DEFAULT_TEXT_SIZE_RATIO = 0.7f;
    private static final float TEXT_POSITION_ADJUSTMENT = 0.0f;
    private static final float VERTICAL_ALIGNMENT_FACTOR = 0.5f;
    private static final float OCR_X_ADJUSTMENT = -2f;
    private static final float OCR_Y_ADJUSTMENT = -4f;

    /**
     * Creates a searchable PDF file from the given image and recognized words. The PDF
     * will include the image as its background and an optional text layer based on the
     * recognized words.
     *
     * @param context            the Context used to access resources such as fonts and output streams
     * @param bitmap             the bitmap image to include in the PDF as the background
     * @param words              the list of recognized words to include in the text layer of the PDF; can be null or empty
     * @param outputUri          the URI where the resulting PDF should be saved
     * @param jpegQuality        the quality of the image to be saved in the PDF (1-100); used if the image is compressed to JPEG
     * @param convertToGrayscale a flag that indicates whether the image should be converted to grayscale before adding to the PDF
     * @return the URI of the created PDF if successful, or null otherwise
     */
    public static Uri createSearchablePdf(Context context,
                                          Bitmap bitmap,
                                          List<RecognizedWord> words,
                                          Uri outputUri,
                                          int jpegQuality,
                                          boolean convertToGrayscale) {
        Log.d(TAG, "createSearchablePdf: uri=" + outputUri + ", words=" + (words == null ? 0 : words.size()));
        if (bitmap == null || outputUri == null) return null;

        try {
            PDFBoxResourceLoader.init(context);
        } catch (Throwable t) {
            Log.e(TAG, "PDFBox init failed", t);
            return null;
        }

        Bitmap prepared = null;
        try {
            prepared = processImageForPdf(bitmap, convertToGrayscale);

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

                PDFont font = chooseFont(document, context);

                try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                    cs.drawImage(pdImg, offsetX, offsetY, drawW, drawH);

                    if (words != null && !words.isEmpty()) {
                        addTextLayer(cs, words, font, scale, offsetX, offsetY, prepared.getHeight(), pageH);
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

    /**
     * Chooses and loads a font to be used in a PDF document. Attempts to load a Unicode font
     * from the application's assets. If the font is not found or fails to load, it falls back to
     * a default Helvetica font.
     *
     * @param document the {@link PDDocument} representing the PDF document where the font will be used
     * @param context  the {@link Context} used to access application resources, such as the font file
     * @return the {@link PDFont} object representing the loaded font; either the specified Unicode font or Helvetica as a fallback
     */
    private static PDFont chooseFont(PDDocument document, Context context) {
        try (InputStream is = context.getAssets().open("fonts/NotoSans-Regular.ttf")) {
            return PDType0Font.load(document, is, true);
        } catch (Exception e) {
            Log.w(TAG, "Unicode font not found, falling back to Helvetica");
            return PDType1Font.HELVETICA;
        }
    }

    /**
     * Adds a selectable and searchable text layer over an existing PDF page content based
     * on recognized text data. The method processes recognized words, groups them into lines,
     * and positions the text layer accurately over the image content while taking into account
     * scaling and offsets.
     *
     * @param cs          the {@link PDPageContentStream} in which the text layer will be drawn
     * @param words       the list of {@link RecognizedWord} objects representing the recognized text and bounding boxes
     * @param font        the {@link PDFont} to be used for the text layer
     * @param scale       the scaling factor for positioning the text
     * @param offsetX     the horizontal offset to adjust text placement
     * @param offsetY     the vertical offset to adjust text placement
     * @param imageHeight the height of the image related to the recognized words
     * @param pageHeight  the height of the PDF page
     * @throws Exception if an error occurs during text layer creation or rendering
     */
    private static void addTextLayer(PDPageContentStream cs,
                                     List<RecognizedWord> words,
                                     PDFont font,
                                     float scale,
                                     float offsetX,
                                     float offsetY,
                                     int imageHeight,
                                     float pageHeight) throws Exception {
        if (words == null || words.isEmpty()) return;

        try {
            // 1) Sort words top-to-bottom, then left-to-right
            words.sort((a, b) -> {
                int cmp = Float.compare(midY(a), midY(b));
                if (Math.abs(midY(a) - midY(b)) < 6f) {
                    return Float.compare(a.getBoundingBox().left, b.getBoundingBox().left);
                }
                return cmp;
            });

            // 2) Cluster into lines
            List<List<RecognizedWord>> lines = new ArrayList<>();
            for (RecognizedWord w : words) {
                if (lines.isEmpty()) {
                    List<RecognizedWord> first = new ArrayList<>();
                    first.add(w);
                    lines.add(first);
                } else {
                    List<RecognizedWord> lastLine = lines.get(lines.size() - 1);
                    float refY = midY(lastLine.get(0));
                    if (Math.abs(midY(w) - refY) < 6f) {
                        lastLine.add(w);
                    } else {
                        List<RecognizedWord> newline = new ArrayList<>();
                        newline.add(w);
                        lines.add(newline);
                    }
                }
            }

            // 3) Render lines
            for (List<RecognizedWord> line : lines) {
                if (line.isEmpty()) continue;
                line.sort(Comparator.comparingDouble(rw -> rw.getBoundingBox().left));

                float lineHeightPx = medianHeight(line);
                float fontSize = Math.max(0.1f, lineHeightPx * scale * DEFAULT_TEXT_SIZE_RATIO);

                RectF firstBox = line.get(0).getBoundingBox();
                float baseline;
                try {
                    float asc = font.getFontDescriptor().getAscent() / 1000f * fontSize;
                    float desc = -font.getFontDescriptor().getDescent() / 1000f * fontSize;
                    float fHeight = asc + desc;
                    float textTop = firstBox.top + (lineHeightPx - fHeight) * VERTICAL_ALIGNMENT_FACTOR;
                    float baselineOffset = (textTop - firstBox.bottom) + asc + (lineHeightPx * TEXT_POSITION_ADJUSTMENT);
                    baseline = firstBox.bottom + baselineOffset;
                } catch (Throwable t) {
                    baseline = firstBox.bottom + (lineHeightPx * (0.25f + TEXT_POSITION_ADJUSTMENT));
                }

                float startX = firstBox.left * scale + offsetX + OCR_X_ADJUSTMENT;
                float startY = pageHeight - ((imageHeight - baseline) * scale + offsetY) + OCR_Y_ADJUSTMENT;

                List<Object> tj = new ArrayList<>();
                float cursorX = startX;

                for (int i = 0; i < line.size(); i++) {
                    RecognizedWord w = line.get(i);
                    String token = (i == 0) ? safeText(w.getText()) : " " + safeText(w.getText());
                    if (token.trim().isEmpty()) continue;
                    tj.add(token);

                    if (i < line.size() - 1) {
                        RecognizedWord next = line.get(i + 1);
                        float tokenWidthPt = font.getStringWidth(token) / 1000f * fontSize;
                        float expectedNextX = cursorX + tokenWidthPt;
                        float targetNextX = next.getBoundingBox().left * scale + offsetX + OCR_X_ADJUSTMENT;

                        float gapPt = targetNextX - expectedNextX;
                        float adj = -(gapPt * 1000f) / fontSize;
                        if (adj > 5000f) adj = 5000f;
                        if (adj < -5000f) adj = -5000f;

                        tj.add(adj);
                        cursorX = expectedNextX + gapPt;
                    }
                }

                cs.beginText();
                cs.setRenderingMode(RenderingMode.NEITHER);
                cs.setFont(font, fontSize);
                cs.newLineAtOffset(startX, startY);
                cs.showTextWithPositioning(tj.toArray());
                cs.endText();
            }
        } catch (Exception e) {
            Log.w(TAG, "Line grouping failed, fallback to word-by-word: " + e.getMessage());
            for (RecognizedWord w : words) {
                String txt = safeText(w.getText());
                if (txt.trim().isEmpty()) continue;

                RectF box = w.getBoundingBox();
                float boxH = box.height();
                float fontSize = Math.max(0.1f, boxH * scale * DEFAULT_TEXT_SIZE_RATIO);

                float baseline = box.bottom + (boxH * (0.25f + TEXT_POSITION_ADJUSTMENT));
                float x = box.left * scale + offsetX + OCR_X_ADJUSTMENT;
                float y = pageHeight - ((imageHeight - baseline) * scale + offsetY) + OCR_Y_ADJUSTMENT;

                cs.beginText();
                cs.setRenderingMode(RenderingMode.NEITHER);
                cs.setFont(font, fontSize);
                cs.newLineAtOffset(x, y);
                cs.showText(txt);
                cs.endText();
            }
        }
    }

    /**
     * Calculates the scale factor to fit an image within the dimensions of a page
     * while maintaining the aspect ratio of the image.
     *
     * @param imageWidth  the width of the image in pixels
     * @param imageHeight the height of the image in pixels
     * @param pageWidth   the width of the page in the desired unit (e.g., points or pixels)
     * @param pageHeight  the height of the page in the desired unit (e.g., points or pixels)
     * @return the scale factor as a float, which is the smaller of the horizontal
     * and vertical scaling factors
     */
    private static float calculateScale(int imageWidth, int imageHeight, float pageWidth, float pageHeight) {
        float sx = pageWidth / imageWidth;
        float sy = pageHeight / imageHeight;
        return Math.min(sx, sy);
    }

    /**
     * Processes a given bitmap image for inclusion in a PDF by resizing it to A4 dimensions at 300 DPI
     * and optionally converting it to grayscale. The method ensures the image fits within the required
     * dimensions without distortion and applies grayscale conversion if specified.
     *
     * @param original the original bitmap image to be processed; must not be null
     * @param toGray   a flag indicating whether the image should be converted to grayscale
     * @return a processed bitmap image scaled to fit A4 dimensions at 300 DPI or null if the input is null
     */
    private static Bitmap processImageForPdf(Bitmap original, boolean toGray) {
        if (original == null) return null;

        boolean preScaled =
                Math.abs(original.getWidth() - ImageScaler.A4_WIDTH_300DPI) <= 1 &&
                        Math.abs(original.getHeight() - ImageScaler.A4_HEIGHT_300DPI) <= 1;

        Bitmap base = original;

        if (!preScaled) {
            int maxW = ImageScaler.A4_WIDTH_300DPI;
            int maxH = ImageScaler.A4_HEIGHT_300DPI;

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

        if (!toGray) return base;

        Bitmap gray = Bitmap.createBitmap(base.getWidth(), base.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(gray);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ColorMatrix cm = new ColorMatrix();
        cm.setSaturation(0);
        paint.setColorFilter(new ColorMatrixColorFilter(cm));
        canvas.drawBitmap(base, 0, 0, paint);

        if (base != original) {
            try {
                base.recycle();
            } catch (Throwable ignore) {
            }
        }
        return gray;
    }

    /**
     * Calculates the vertical midpoint (Y-coordinate) of the bounding box of a given RecognizedWord.
     *
     * @param rw the RecognizedWord object containing the bounding box for which the vertical midpoint is to be calculated
     * @return the vertical midpoint (Y-coordinate) of the bounding box as a float value
     */
    // --- Helper methods ---
    private static float midY(RecognizedWord rw) {
        RectF b = rw.getBoundingBox();
        return (b.top + b.bottom) * 0.5f;
    }

    /**
     * Calculates the median height of the bounding boxes of the given list of RecognizedWord objects.
     * The bounding boxes are extracted and sorted by height, and the median is computed.
     * If the list is empty, the method returns 0.
     *
     * @param line the list of RecognizedWord objects, where each word contains a bounding box with height information
     * @return the median height of the bounding boxes as a float value
     */
    private static float medianHeight(List<RecognizedWord> line) {
        List<Float> heights = new ArrayList<>();
        for (RecognizedWord w : line) heights.add(w.getBoundingBox().height());
        Collections.sort(heights);
        int n = heights.size();
        if (n == 0) return 0;
        if (n % 2 == 1) return heights.get(n / 2);
        return (heights.get(n / 2 - 1) + heights.get(n / 2)) / 2f;
    }

    /**
     * Replaces control characters in the input string with spaces, excluding
     * tab, newline, and carriage return characters. If the input string is null,
     * an empty string is returned.
     *
     * @param t the input string to process; can be null
     * @return a sanitized string where control characters are replaced with spaces,
     * or an empty string if the input is null
     */
    private static String safeText(String t) {
        if (t == null) return "";
        return t.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", " ");
    }
}
