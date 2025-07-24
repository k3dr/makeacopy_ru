package de.schliweb.makeacopy.utils;

import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import android.util.Log;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.font.PDFont;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory;
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject;
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode;
import com.tom_roush.pdfbox.util.Matrix;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * PdfCreator is a utility class for creating searchable PDFs using Apache PDFBox.
 * It provides methods for creating PDFs with a text layer based on the recognized words from OCR.
 * <p>
 * The class handles:
 * 1. Image processing (compression, scaling, grayscale conversion)
 * 2. PDF document creation with proper A4 page sizing
 * 3. Precise positioning of OCR text layer for searchability
 * <p>
 * Text Positioning:
 * - Uses PDFBox's font metrics for precise text placement
 * - Dynamically sizes text based on the height of each word's bounding box
 * - Aligns text vertically within its bounding box using font metrics (ascent, descent)
 * - Provides configuration constants for fine-tuning text positioning:
 * - DEFAULT_TEXT_SIZE_RATIO: Controls text size as percentage of bounding box height
 * - VERTICAL_ALIGNMENT_FACTOR: Controls vertical positioning (0.5 = centered)
 * - TEXT_POSITION_ADJUSTMENT: Allows additional fine-tuning of baseline position
 * - OCR_X_ADJUSTMENT: Shifts the entire text layer horizontally (negative = left)
 * - OCR_Y_ADJUSTMENT: Shifts the entire text layer vertically (negative = up)
 * <p>
 * Font Metrics-Based Positioning:
 * - Uses PDFont.getFontDescriptor() to access font metrics (ascent, descent)
 * - Calculates exact baseline position based on these metrics
 * - Provides fallback to approximation method if font metrics are unavailable
 * - Logs detailed positioning information for debugging
 * <p>
 * Coordinate System Transformation:
 * - Image coordinates: Origin (0,0) at top-left, y increases downward
 * - PDF coordinates: Origin (0,0) at bottom-left, y increases upward
 * - The transformation accounts for this difference to ensure accurate text positioning
 * <p>
 * Troubleshooting Text Positioning:
 * - If text appears too high: Decrease VERTICAL_ALIGNMENT_FACTOR or use negative TEXT_POSITION_ADJUSTMENT
 * - If text appears too low: Increase VERTICAL_ALIGNMENT_FACTOR or use positive TEXT_POSITION_ADJUSTMENT
 * - If text appears too large: Decrease DEFAULT_TEXT_SIZE_RATIO
 * - If text appears too small: Increase DEFAULT_TEXT_SIZE_RATIO
 * - For top alignment: Set VERTICAL_ALIGNMENT_FACTOR to 0.0
 * - For bottom alignment: Set VERTICAL_ALIGNMENT_FACTOR to 1.0
 * - For center alignment: Set VERTICAL_ALIGNMENT_FACTOR to 0.5 (default)
 */
public class PdfCreator {
    private static final String TAG = "PdfCreator";

    // Configuration constants for text positioning
    private static final float DEFAULT_TEXT_SIZE_RATIO = 0.7f; // Text size as percentage of bounding box height
    private static final float TEXT_POSITION_ADJUSTMENT = 0.0f; // Adjustment factor for fine-tuning baseline position
    private static final float VERTICAL_ALIGNMENT_FACTOR = 0.5f; // 0.5 = center text vertically in bounding box

    // Fine-tuning parameters for OCR text layer positioning
    private static final float OCR_X_ADJUSTMENT = -2f; // Shifts the text layer left/right (negative = left)
    private static final float OCR_Y_ADJUSTMENT = -4f; // Shifts the text layer up/down (negative = up)

    /**
     * Creates a searchable PDF from an image and recognized words using Apache PDFBox
     *
     * @param context            Application context
     * @param bitmap             Image to include in the PDF
     * @param words              List of recognized words with their positions
     * @param outputUri          URI to save the PDF to
     * @param jpegQuality        JPEG quality (0-100) for image compression
     * @param convertToGrayscale Whether to convert the image to grayscale
     * @return URI of the created PDF, or null if creation failed
     */
    public static Uri createSearchablePdf(Context context, Bitmap bitmap, List<RecognizedWord> words, Uri outputUri, int jpegQuality, boolean convertToGrayscale) {
        Log.d(TAG, "createSearchablePdf: Starting PDF creation with PDFBox");
        Log.d(TAG, "createSearchablePdf: Output URI: " + outputUri);

        if (outputUri != null) {
            Log.d(TAG, "createSearchablePdf: URI scheme: " + outputUri.getScheme());
            Log.d(TAG, "createSearchablePdf: URI path: " + outputUri.getPath());
            Log.d(TAG, "createSearchablePdf: URI last path segment: " + outputUri.getLastPathSegment());
        }

        try {
            // Initialize PDFBox
            com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context);

            // Process the image (compress and optionally convert to grayscale)
            Log.d(TAG, "createSearchablePdf: Processing image");
            Bitmap processedBitmap = processImageForPdf(bitmap, jpegQuality, convertToGrayscale);

            // Create a new PDF document
            PDDocument document = new PDDocument();

            // Set PDF version to 1.5 to enable object compression
            document.getDocument().setVersion(1.5f);

            // Set minimal metadata to reduce file size
            document.getDocumentInformation().setCreator("MakeACopy");
            document.getDocumentInformation().setProducer("MakeACopy");

            Log.d(TAG, "createSearchablePdf: PDFBox document created with compression enabled");

            // A4 dimensions in points (1/72 inch)
            // 595 x 842 points (8.27 x 11.69 inches)
            PDRectangle pageSize = PDRectangle.A4;
            float pageWidth = pageSize.getWidth();
            float pageHeight = pageSize.getHeight();

            // Create a page with A4 dimensions
            PDPage page = new PDPage(pageSize);
            document.addPage(page);
            Log.d(TAG, "createSearchablePdf: PDF page created");

            // Calculate scaling to fit image on page while maintaining aspect ratio
            float scale = calculateScale(processedBitmap.getWidth(), processedBitmap.getHeight(), pageWidth, pageHeight);

            // Calculate margins to center the image on the page
            float marginX = (pageWidth - (processedBitmap.getWidth() * scale)) / 2;
            float marginY = (pageHeight - (processedBitmap.getHeight() * scale)) / 2;

            // Create PDImageXObject from the bitmap
            PDImageXObject pdImage;

            // For grayscale images, we can use more aggressive compression
            float pdfBoxJpegQuality = jpegQuality / 100f;
            if (convertToGrayscale) {
                // For grayscale documents, we can use lower quality in PDFBox too
                pdfBoxJpegQuality = Math.max((jpegQuality - 10) / 100f, 0.5f);
                Log.d(TAG, "createSearchablePdf: Using optimized PDFBox JPEG quality for grayscale: " + pdfBoxJpegQuality);
            }

            if (jpegQuality < 100) {
                // Use JPEG compression for the image
                pdImage = JPEGFactory.createFromImage(document, processedBitmap, pdfBoxJpegQuality);
                Log.d(TAG, "createSearchablePdf: Created JPEG image with quality " + (pdfBoxJpegQuality * 100));
            } else {
                // Use lossless compression
                pdImage = LosslessFactory.createFromImage(document, processedBitmap);
                Log.d(TAG, "createSearchablePdf: Created lossless image");
            }

            // Create a content stream to draw on the page
            PDPageContentStream contentStream = new PDPageContentStream(document, page);

            // In PDFBox, the origin (0,0) is at the bottom-left corner of the page
            // We need to adjust the y-coordinate to position the image correctly

            // Save the graphics state
            contentStream.saveGraphicsState();

            // Create a transformation matrix for scaling and translation
            Matrix matrix = new Matrix();
            matrix.scale(scale, scale);
            matrix.translate(marginX / scale, marginY / scale);

            // Apply the transformation
            contentStream.transform(matrix);

            // Draw the image at the origin (0,0) - the matrix will handle positioning
            contentStream.drawImage(pdImage, 0, 0);

            // Restore the graphics state
            contentStream.restoreGraphicsState();

            Log.d(TAG, "createSearchablePdf: Image drawn on PDF page");

            // Add text layer for searchability
            if (words != null && !words.isEmpty()) {
                Log.d(TAG, "createSearchablePdf: Adding text layer with " + words.size() + " words");
                addTextLayerPDFBox(contentStream, words, scale, marginX, marginY, processedBitmap.getHeight(), pageHeight);
            } else {
                Log.d(TAG, "createSearchablePdf: No words provided, skipping text layer");
            }

            // Close the content stream
            contentStream.close();
            Log.d(TAG, "createSearchablePdf: Content stream closed");

            // Save the PDF
            Log.d(TAG, "createSearchablePdf: Opening output stream for URI: " + outputUri);
            OutputStream os = context.getContentResolver().openOutputStream(outputUri);
            document.save(os);
            document.close();
            os.close();
            Log.d(TAG, "createSearchablePdf: PDF written to output stream and closed");

            // Clean up
            if (processedBitmap != bitmap) {
                processedBitmap.recycle();
            }

            Log.d(TAG, "createSearchablePdf: PDF creation completed successfully");
            Log.d(TAG, "createSearchablePdf: Final output URI: " + outputUri);
            if (outputUri != null) {
                Log.d(TAG, "createSearchablePdf: Final URI scheme: " + outputUri.getScheme());
                Log.d(TAG, "createSearchablePdf: Final URI path: " + outputUri.getPath());
                Log.d(TAG, "createSearchablePdf: Final URI last path segment: " + outputUri.getLastPathSegment());
            }

            return outputUri;
        } catch (Exception e) {
            Log.e(TAG, "Error creating PDF with PDFBox", e);
            Log.e(TAG, "createSearchablePdf: Exception details", e);
            return null;
        }
    }

    /**
     * Overloaded method with default JPEG quality (75) and grayscale conversion (false)
     * This ensures that PDFs are created in color by default unless explicitly set to grayscale.
     */
    public static Uri createSearchablePdf(Context context, Bitmap bitmap, List<RecognizedWord> words, Uri outputUri) {
        return createSearchablePdf(context, bitmap, words, outputUri, 75, false);
    }

    /**
     * Adds a text layer to the PDF for searchability by positioning each recognized word
     * at its exact location in the document using PDFBox.
     * <p>
     * The method performs several key operations:
     * 1. Creates invisible text that's searchable but doesn't obscure the image
     * 2. Transforms coordinates from image space to PDF space, accounting for different origins
     * 3. Positions each word precisely to align with its appearance in the image
     *
     * @param contentStream PDPageContentStream to draw on
     * @param words         List of recognized words with their positions
     * @param scale         Scale factor applied to the image (for fitting on page)
     * @param offsetX       X offset for the image (for centering on page)
     * @param offsetY       Y offset for the image (for centering on page)
     * @param imageHeight   Height of the original image in pixels (for coordinate transformation)
     * @param pageHeight    Height of the PDF page in points
     */
    private static void addTextLayerPDFBox(PDPageContentStream contentStream, List<RecognizedWord> words, float scale, float offsetX, float offsetY, int imageHeight, float pageHeight) throws IOException {
        if (words == null || words.isEmpty()) {
            return;
        }

        // Use Helvetica as the default font
        PDFont font = PDType1Font.HELVETICA;

        // Set text rendering mode to NEITHER (invisible text) for searchable but invisible text
        // RenderingMode.NEITHER = neither fill nor stroke = invisible
        contentStream.setRenderingMode(RenderingMode.NEITHER);

        // For debugging, uncomment the line below to make text visible
        // contentStream.setRenderingMode(RenderingMode.FILL);

        // Set non-stroking color to black
        contentStream.setNonStrokingColor(0, 0, 0);

        // For each word, position it exactly where it appears in the image
        for (RecognizedWord word : words) {
            // Skip empty words
            if (word.getText() == null || word.getText().trim().isEmpty()) {
                continue;
            }

            // Get the bounding box
            RectF box = word.getBoundingBox();

            // Calculate appropriate text size based on the height of the bounding box
            float boxHeight = box.height();
            float fontSize = boxHeight * scale * DEFAULT_TEXT_SIZE_RATIO; // Scale the font size

            // In PDFBox, we need to save and restore the graphics state when changing text size
            contentStream.saveGraphicsState();
            contentStream.setFont(font, fontSize);

            // Transform coordinates from image space to PDF space
            // In image space, origin (0,0) is at top-left
            // In PDF space, origin (0,0) is at bottom-left, so we need to flip the y-coordinate
            // Apply OCR_X_ADJUSTMENT to shift the text layer horizontally
            float x = box.left * scale + offsetX + OCR_X_ADJUSTMENT;

            // Calculate the baseline position
            // In PDFBox, text is positioned at the baseline, not at the top-left corner
            // We need to position the text so it's properly aligned vertically in the bounding box

            float baseline;

            try {
                // Get font metrics from the font descriptor
                float fontAscent = font.getFontDescriptor().getAscent() / 1000f * fontSize;
                float fontDescent = -font.getFontDescriptor().getDescent() / 1000f * fontSize;
                float fontHeight = fontAscent + fontDescent;

                // Calculate the vertical position based on the alignment factor
                // VERTICAL_ALIGNMENT_FACTOR = 0.5 centers the text vertically in the box
                float textTop = box.top + (boxHeight - fontHeight) * VERTICAL_ALIGNMENT_FACTOR;

                // The baseline is at textTop + fontAscent
                float baselineOffset = (textTop - box.bottom) + fontAscent;

                // Apply any additional adjustment to fine-tune the baseline position
                baselineOffset += boxHeight * TEXT_POSITION_ADJUSTMENT;

                baseline = box.bottom + baselineOffset;

                Log.d(TAG, "Using font metrics for text positioning: " + "ascent=" + fontAscent + ", descent=" + fontDescent + ", height=" + fontHeight + ", baseline=" + baseline);
            } catch (Exception e) {
                // Fallback to the previous approximation method if font metrics are unavailable
                Log.w(TAG, "Could not access font metrics, using approximation", e);
                float baselineOffset = boxHeight * 0.25f;
                baselineOffset += boxHeight * TEXT_POSITION_ADJUSTMENT;
                baseline = box.bottom + baselineOffset;
            }

            // Flip the y-coordinate for PDF space and apply scaling and offset
            // In PDF, y=0 is at the bottom, but in the image, y=0 is at the top
            // Apply OCR_Y_ADJUSTMENT to shift the text layer vertically
            float y = pageHeight - ((imageHeight - baseline) * scale + offsetY) + OCR_Y_ADJUSTMENT;

            // Begin text at the calculated position
            contentStream.beginText();
            contentStream.newLineAtOffset(x, y);

            // Show the text
            // PDFBox requires us to handle special characters
            String text = word.getText();
            try {
                contentStream.showText(text);
            } catch (IllegalArgumentException e) {
                // If the text contains characters not supported by the font,
                // replace them with spaces or skip the word
                Log.w(TAG, "Unsupported character in word: " + text, e);
                // Try with a simplified version of the text (only ASCII characters)
                String simplifiedText = text.replaceAll("[^\\x00-\\x7F]", " ");
                if (!simplifiedText.trim().isEmpty()) {
                    contentStream.showText(simplifiedText);
                }
            }

            // End text
            contentStream.endText();

            // Restore the graphics state
            contentStream.restoreGraphicsState();
        }
    }

    /**
     * Calculates scale factor to fit image on page while maintaining aspect ratio
     *
     * @param imageWidth  Image width
     * @param imageHeight Image height
     * @param pageWidth   Page width
     * @param pageHeight  Page height
     * @return Scale factor
     */
    private static float calculateScale(int imageWidth, int imageHeight, float pageWidth, float pageHeight) {
        float scaleX = pageWidth / imageWidth;
        float scaleY = pageHeight / imageHeight;
        return Math.min(scaleX, scaleY) * 0.9f; // 90% of the maximum scale to add some margin
    }

    /**
     * Processes an image for PDF embedding (compression and optional grayscale conversion)
     * <p>
     * This method applies several optimization techniques to reduce the final PDF size:
     * 1. Resolution reduction - scales down images to an optimal DPI for documents
     * 2. Grayscale conversion - removes color information for text documents
     * 3. JPEG compression - applies lossy compression with configurable quality
     * 4. Image format optimization - ensures optimal encoding for the content type
     * <p>
     * Note: If the image has already been pre-scaled to A4 dimensions (by ImageScaler),
     * the scaling step will be skipped to preserve the exact dimensions used for OCR.
     *
     * @param originalBitmap     Original bitmap
     * @param jpegQuality        JPEG quality (0-100) for compression
     * @param convertToGrayscale Whether to convert to grayscale
     * @return Processed bitmap
     */
    private static Bitmap processImageForPdf(Bitmap originalBitmap, int jpegQuality, boolean convertToGrayscale) {
        if (originalBitmap == null) {
            return null;
        }

        // Step 1: Check if the image is already pre-scaled to A4 dimensions
        // If it's already at the target dimensions or very close to them, skip scaling
        boolean isPreScaled = false;

        // Allow a small tolerance (1 pixel) for rounding errors in scaling calculations
        if (Math.abs(originalBitmap.getWidth() - ImageScaler.A4_WIDTH_300DPI) <= 1 && Math.abs(originalBitmap.getHeight() - ImageScaler.A4_HEIGHT_300DPI) <= 1) {
            isPreScaled = true;
            Log.d(TAG, "Image appears to be pre-scaled to A4 dimensions, skipping scaling");
        } else if (originalBitmap.getWidth() <= ImageScaler.A4_WIDTH_300DPI && originalBitmap.getHeight() <= ImageScaler.A4_HEIGHT_300DPI) {
            // The image is smaller than A4 dimensions but has the correct aspect ratio
            float widthRatio = (float) originalBitmap.getWidth() / ImageScaler.A4_WIDTH_300DPI;
            float heightRatio = (float) originalBitmap.getHeight() / ImageScaler.A4_HEIGHT_300DPI;

            // If the ratios are very close (within 1%), consider it pre-scaled
            if (Math.abs(widthRatio - heightRatio) < 0.01) {
                isPreScaled = true;
                Log.d(TAG, "Image is smaller than A4 but has correct aspect ratio, skipping scaling");
            }
        }

        Bitmap scaledBitmap;

        if (isPreScaled) {
            // Skip scaling if the image is already pre-scaled
            scaledBitmap = originalBitmap;
            Log.d(TAG, "Using pre-scaled image: " + scaledBitmap.getWidth() + "x" + scaledBitmap.getHeight());
        } else {
            // Step 2: Scale the image to A4 dimensions if needed
            // Use the same constants as ImageScaler for consistency
            int maxWidth = ImageScaler.A4_WIDTH_300DPI;
            int maxHeight = ImageScaler.A4_HEIGHT_300DPI;

            // Calculate scaling factor if the image is larger than our target resolution
            float scale = 1.0f;
            if (originalBitmap.getWidth() > maxWidth || originalBitmap.getHeight() > maxHeight) {
                float scaleWidth = (float) maxWidth / originalBitmap.getWidth();
                float scaleHeight = (float) maxHeight / originalBitmap.getHeight();
                scale = Math.min(scaleWidth, scaleHeight);
            }

            // Skip scaling if the image is already smaller than our target
            if (scale >= 1.0f) {
                Log.d(TAG, "Bitmap already at optimal resolution, skipping resolution reduction");
                scale = 1.0f;
            }

            // Create a scaled-down bitmap
            int scaledWidth = Math.round(originalBitmap.getWidth() * scale);
            int scaledHeight = Math.round(originalBitmap.getHeight() * scale);

            scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, scaledWidth, scaledHeight, true // Use filtering for better quality
            );

            if (scale < 1.0f) {
                Log.d(TAG, "Reduced bitmap resolution from " + originalBitmap.getWidth() + "x" + originalBitmap.getHeight() + " to " + scaledWidth + "x" + scaledHeight);
            }
        }

        // Step 3: Convert to grayscale if needed
        Bitmap processedBitmap = scaledBitmap;

        if (convertToGrayscale) {
            Bitmap grayscaleBitmap = Bitmap.createBitmap(scaledBitmap.getWidth(), scaledBitmap.getHeight(), Bitmap.Config.ARGB_8888);

            Canvas canvas = new Canvas(grayscaleBitmap);
            Paint paint = new Paint();

            // ColorMatrix for grayscale conversion
            ColorMatrix colorMatrix = new ColorMatrix();
            colorMatrix.setSaturation(0); // 0 = grayscale

            ColorMatrixColorFilter filter = new ColorMatrixColorFilter(colorMatrix);
            paint.setColorFilter(filter);

            canvas.drawBitmap(scaledBitmap, 0, 0, paint);

            // Recycle the scaled bitmap as we no longer need it
            if (scaledBitmap != originalBitmap) {
                scaledBitmap.recycle();
            }

            Log.d(TAG, "Converted bitmap to grayscale");
            processedBitmap = grayscaleBitmap;
        }

        // Step 4: Apply JPEG compression with quality reduction
        // This is done by compressing to JPEG in memory and then decompressing
        if (jpegQuality > 0 && jpegQuality < 100) {
            // Optimize JPEG quality based on content type
            // For grayscale images (likely text documents), we can use more aggressive compression
            int optimizedQuality = jpegQuality;
            if (convertToGrayscale) {
                // For grayscale documents, we can use lower quality without noticeable degradation
                // Reduce quality by 10-15% for grayscale images
                optimizedQuality = Math.max(jpegQuality - 15, 50);
                Log.d(TAG, "Optimized JPEG quality for grayscale document: " + optimizedQuality);
            }

            // Compress the bitmap to a ByteArrayOutputStream using JPEG format
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            processedBitmap.compress(Bitmap.CompressFormat.JPEG, optimizedQuality, outputStream);

            // Get the compressed data
            byte[] compressedData = outputStream.toByteArray();

            // Log the compression ratio
            float compressionRatio = (float) compressedData.length / (processedBitmap.getWidth() * processedBitmap.getHeight() * 4); // 4 bytes per pixel in ARGB_8888

            Log.d(TAG, "JPEG compression applied with quality " + optimizedQuality + ", compression ratio: " + compressionRatio + ", compressed size: " + (compressedData.length / 1024) + " KB");

            // Decode the compressed data back to a bitmap
            // Create options to ensure color information is preserved
            android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888; // Use ARGB_8888 to preserve full color information

            Log.d(TAG, "Using explicit ARGB_8888 config to preserve color information");

            Bitmap compressedBitmap = android.graphics.BitmapFactory.decodeByteArray(compressedData, 0, compressedData.length, options);

            // Log the bitmap configuration to verify color preservation
            Log.d(TAG, "Compressed bitmap config: " + compressedBitmap.getConfig() + ", hasAlpha: " + compressedBitmap.hasAlpha() + ", isGrayscale: " + (convertToGrayscale ? "true (by user choice)" : "false"));

            // Recycle the previous bitmap if it's not the original
            if (processedBitmap != originalBitmap) {
                processedBitmap.recycle();
            }

            // Return the compressed bitmap
            return compressedBitmap;
        }

        // Return the processed bitmap (either scaled, grayscale, or original)
        return processedBitmap;
    }

}