package de.schliweb.makeacopy.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.net.Uri;
import androidx.core.content.FileProvider;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Instrumented test for verifying the functionality of the PdfCreator utility class.
 * The test ensures that all test PDF files can be rendered into searchable PDFs
 * with a text layer, and verifies the integrity of the extracted text.
 * <p>
 * This test runs on an Android device or emulator.
 */
@RunWith(AndroidJUnit4.class)
public class PdfCreatorInstrumentedTest {

    private static Context context;

    @BeforeClass
    public static void setupOnce() {
        context = ApplicationProvider.getApplicationContext();
        PDFBoxResourceLoader.init(context);
    }

    /**
     * Tests that all provided test PDFs produce a searchable text layer with correct text output.
     * This test ensures the end-to-end functionality of creating searchable PDFs using dummy OCR data.
     * The steps include rendering the first page of each input PDF to a bitmap, generating OCR words
     * with bounding boxes, creating a new PDF with a searchable text layer, and validating the
     * extracted text.
     * <p>
     * Test PDFs:
     * - simple_line.pdf
     * - vertical_scan.pdf
     * - multi_line.pdf
     * - multi_column.pdf
     * - special_chars.pdf
     * - stress_test.pdf
     * - bilingual.pdf
     * - narrow_spacing.pdf
     * - two_column_rotated.pdf
     * <p>
     * Steps involved:
     * 1. Render the first page of the PDF to a bitmap.
     * 2. Generate dummy OCR words with bounding boxes relative to the bitmap.
     * 3. Define the output file and its URI.
     * 4. Create a PDF with a searchable text layer using the bitmap and dummy OCR words.
     * 5. Extract the text from the created PDF and verify it contains the expected dummy tokens ("Alpha" and "Beta").
     * <p>
     * Assertions:
     * - Input PDFs render successfully to bitmap.
     * - Dummy words are correctly embedded into the output PDF.
     * - Output PDFs are successfully generated and contain the expected text layer.
     *
     * @throws Exception If rendering, PDF creation, or validation fails.
     */
    @Test
    public void test_All_TestPdfs_Produce_Searchable_TextLayer() throws Exception {
        String base = "test_pdfs/";
        String[] testFiles = {
                "simple_line.pdf",
                "vertical_scan.pdf",
                "multi_line.pdf",
                "multi_column.pdf",
                "special_chars.pdf",
                "stress_test.pdf",
                "bilingual.pdf",
                "narrow_spacing.pdf",
                "two_column_rotated.pdf"
        };

        for (String name : testFiles) {
            String assetPath = base + name;

            Bitmap pageBmp = PdfTestUtils.renderPdfAssetToBitmap(context, assetPath, 0);
            assertNotNull("Render failed for: " + name, pageBmp);

            List<RecognizedWord> words = makeDummyWords(pageBmp.getWidth(), pageBmp.getHeight());

            File outDir = new File(context.getExternalFilesDir(null), "test_out");
            //noinspection ResultOfMethodCallIgnored
            outDir.mkdirs();
            File outFile = new File(outDir, "out_" + name.replace(".pdf", "") + ".pdf");
            Uri outUri = FileProvider.getUriForFile(
                    context, context.getPackageName() + ".fileprovider", outFile
            );

            Uri result = PdfCreator.createSearchablePdf(
                    context, pageBmp, words, outUri, 90 /*jpegQuality*/, true /*toGray*/
            );
            assertNotNull("PdfCreator returned null for: " + name, result);
            assertTrue("Output file not created for: " + name, outFile.exists() && outFile.length() > 0);

            try (InputStream in = context.getContentResolver().openInputStream(result);
                 PDDocument doc = PDDocument.load(in)) {
                String txt = new PDFTextStripper().getText(doc);
                assertNotNull("Extracted text null for: " + name, txt);
                // Enthält beide Dummy-Wörter?
                assertTrue("Missing token 'Alpha' in: " + name, txt.contains("Alpha"));
                assertTrue("Missing token 'Beta' in: " + name, txt.contains("Beta"));
            }
        }
    }

    /**
     * Creates a list of dummy recognized words with predefined text labels, bounding boxes,
     * and confidence levels. The generated words will be positioned within a rectangular
     * area calculated based on the given width and height parameters.
     *
     * @param w the width of the area in which the dummy words will be placed.
     * @param h the height of the area in which the dummy words will be placed.
     * @return a list of dummy RecognizedWord objects, each containing predefined text,
     * bounding box dimensions, and confidence levels.
     */
    private static List<RecognizedWord> makeDummyWords(int w, int h) {
        float x1 = w * 0.12f, x2 = w * 0.22f;
        float yTop = h * 0.20f;
        float yBottom = yTop + Math.max(24f, h * 0.02f);

        // Dummy Confidence = 0.99f (fast 100%)
        RecognizedWord a = new RecognizedWord("Alpha",
                new RectF(x1, yTop, x1 + w * 0.08f, yBottom),
                0.99f);

        RecognizedWord b = new RecognizedWord("Beta",
                new RectF(x2, yTop, x2 + w * 0.06f, yBottom),
                0.99f);

        List<RecognizedWord> list = new ArrayList<>();
        list.add(a);
        list.add(b);
        return list;
    }

}
