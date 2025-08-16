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
import java.util.List;

/**
 * Utility class for creating PDFs.
 * Provides methods for creating PDFs from images and text.
 */
public class PdfCreator {
    private static final String TAG = "PdfCreator";

    // Feintuning für Text-Layer
    private static final float DEFAULT_TEXT_SIZE_RATIO = 0.7f;  // ratio between font size and text height
    private static final float TEXT_POSITION_ADJUSTMENT = 0.0f; // finetuning baseline
    private static final float VERTICAL_ALIGNMENT_FACTOR = 0.5f; // 0=top, 0.5=center, 1=bottom
    private static final float OCR_X_ADJUSTMENT = -2f; // to  left (negativ) / to right (positiv)
    private static final float OCR_Y_ADJUSTMENT = -4f; // to top (negativ) / to bottom (positiv)


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
                // Meta/Version
                try {
                    document.getDocument().setVersion(1.5f);
                } catch (Throwable ignore) {
                }
                document.getDocumentInformation().setCreator("MakeACopy");
                document.getDocumentInformation().setProducer("MakeACopy");

                // Page A4
                PDRectangle pageSize = PDRectangle.A4;
                float pageW = pageSize.getWidth();
                float pageH = pageSize.getHeight();
                PDPage page = new PDPage(pageSize);
                document.addPage(page);

                // Include image in PDF
                float scale = calculateScale(prepared.getWidth(), prepared.getHeight(), pageW, pageH);
                float drawW = prepared.getWidth() * scale;
                float drawH = prepared.getHeight() * scale;
                float offsetX = (pageW - drawW) / 2f;
                float offsetY = (pageH - drawH) / 2f;

                // compression quality (0-100)
                float q = Math.max(0f, Math.min(1f, jpegQuality / 100f));
                PDImageXObject pdImg = (jpegQuality < 100)
                        ? JPEGFactory.createFromImage(document, prepared, q)
                        : LosslessFactory.createFromImage(document, prepared);

                // load font (Unicode, subset)
                PDFont font = chooseFont(document, context);

                try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
                    // draw image
                    cs.drawImage(pdImg, offsetX, offsetY, drawW, drawH);

                    // ocr-text layer
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

    private static PDFont chooseFont(PDDocument document, Context context) {
        try (InputStream is = context.getAssets().open("fonts/NotoSans-Regular.ttf")) {
            return PDType0Font.load(document, is, true); // Subsetting an
        } catch (Exception e) {
            Log.w(TAG, "Unicode font not found, falling back to Helvetica");
            return PDType1Font.HELVETICA;
        }
    }

    private static void addTextLayer(PDPageContentStream cs,
                                     List<RecognizedWord> words,
                                     PDFont font,
                                     float scale,
                                     float offsetX,
                                     float offsetY,
                                     int imageHeight,
                                     float pageHeight) throws Exception {
        for (RecognizedWord w : words) {
            String txt = w.getText();
            if (txt == null || txt.trim().isEmpty()) continue;

            RectF box = w.getBoundingBox();
            float boxH = box.height();
            float fontSize = Math.max(0.1f, boxH * scale * DEFAULT_TEXT_SIZE_RATIO);

            // Baseline
            float baseline;
            try {
                float asc = font.getFontDescriptor().getAscent() / 1000f * fontSize;
                float desc = -font.getFontDescriptor().getDescent() / 1000f * fontSize;
                float fHeight = asc + desc;
                float textTop = box.top + (boxH - fHeight) * VERTICAL_ALIGNMENT_FACTOR;
                float baselineOffset = (textTop - box.bottom) + asc + (boxH * TEXT_POSITION_ADJUSTMENT);
                baseline = box.bottom + baselineOffset;
            } catch (Throwable t) {
                baseline = box.bottom + (boxH * (0.25f + TEXT_POSITION_ADJUSTMENT));
            }

            float x = box.left * scale + offsetX + OCR_X_ADJUSTMENT;
            float y = pageHeight - ((imageHeight - baseline) * scale + offsetY) + OCR_Y_ADJUSTMENT;

            cs.beginText();
            cs.setRenderingMode(RenderingMode.NEITHER); // hidden, but searchable
            cs.setFont(font, fontSize);
            cs.newLineAtOffset(x, y);

            try {
                cs.showText(txt);
            } catch (IllegalArgumentException ex) {
                // fallback: remove non-printable characters
                String simplified = txt.replaceAll("[^\\x00-\\x7F]", " ");
                if (!simplified.trim().isEmpty()) cs.showText(simplified);
            }

            cs.endText();
        }
    }

    private static float calculateScale(int imageWidth, int imageHeight, float pageWidth, float pageHeight) {
        float sx = pageWidth / imageWidth;
        float sy = pageHeight / imageHeight;
        return Math.min(sx, sy);
    }

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

        // conversion to grayscale (0.30 * R + 0.59 * G + 0.11 * B)
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
}
