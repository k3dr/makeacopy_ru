package de.schliweb.makeacopy.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.Log;
import com.googlecode.tesseract.android.TessBaseAPI;

import java.io.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OCRHelper is a utility class used for Optical Character Recognition (OCR)
 * by leveraging Tesseract OCR technologies. It provides methods to initialize,
 * configure, and perform OCR operations on images.
 */
public class OCRHelper {
    private static final String TAG = "OCRHelper";
    private static final String TESSDATA_DIR = "tessdata";
    private static final String DEFAULT_LANGUAGE = "eng";
    private static final String TRAINEDDATA_EXT = ".traineddata";
    private static final String DEFAULT_DPI = "300";

    private final Context context;
    private final String dataPath;
    private TessBaseAPI tessBaseAPI;
    private String language;
    private boolean isInitialized = false;

    /**
     * Constructs an instance of the OCRHelper class.
     *
     * @param context The application context used to initialize the OCR framework.
     *                It is recommended to pass a valid context to ensure the proper
     *                functioning of the OCRHelper.
     */
    public OCRHelper(Context context) {
        this.context = context.getApplicationContext();
        this.language = DEFAULT_LANGUAGE;
        this.dataPath = context.getFilesDir().getAbsolutePath();
    }

    /* ==================== Init / Shutdown ==================== */

    /**
     * Initializes the Tesseract OCR engine for the specified language and prepares it for processing.
     * This method ensures that the required language data is available, sets default configurations,
     * and initializes the underlying Tesseract API.
     *
     * @return true if the Tesseract engine is successfully initialized; false otherwise
     */
    public boolean initTesseract() {
        if (isInitialized) return true;
        try {
            ensureLanguageDataPresent(language);
            tessBaseAPI = new TessBaseAPI();
            boolean ok = tessBaseAPI.init(dataPath, language);
            if (!ok) {
                Log.e(TAG, "Tesseract initialization failed");
                return false;
            }
            applyDefaultsForLanguage(language);
            isInitialized = true;
            Log.i(TAG, "Tesseract initialized");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error initializing Tesseract", e);
            return false;
        }
    }

    /**
     * Releases resources associated with the Tesseract OCR engine and resets the state of the OCRHelper.
     * <p>
     * This method ensures that the Tesseract OCR engine is properly shut down and memory is released.
     * If the engine has been initialized, its resources are recycled and the associated instance is set to null.
     * The state of OCRHelper is also updated to indicate that it is no longer initialized.
     */
    public void shutdown() {
        if (tessBaseAPI != null) {
            try {
                tessBaseAPI.recycle();
            } catch (Throwable ignore) {
            }
            tessBaseAPI = null;
        }
        isInitialized = false;
    }

    /**
     * Checks whether the Tesseract OCR engine has been successfully initialized.
     *
     * @return true if the Tesseract engine is initialized and ready for processing; false otherwise
     */
    public boolean isTesseractInitialized() {
        return isInitialized;
    }

    /* ==================== Language / Data ==================== */

    /**
     * Sets the language for the OCR engine. If the specified language is null or empty,
     * a default language value is used. If the given language differs from the currently
     * set language and the OCR engine has been initialized, it reinitializes the engine
     * with the new language.
     *
     * @param language The language code to be set for the OCR engine. This should correspond
     *                 to the available language files. If null or empty, a predefined
     *                 default language will be used.
     * @throws IOException If there is an error ensuring the required language data files
     *                     are present.
     */
    public void setLanguage(String language) throws IOException {
        if (language == null || language.isEmpty()) language = DEFAULT_LANGUAGE;
        if (language.equals(this.language) && isInitialized) return;

        this.language = language;
        ensureLanguageDataPresent(language);

        if (isInitialized) {
            tessBaseAPI.recycle();
            tessBaseAPI = new TessBaseAPI();
            boolean ok = tessBaseAPI.init(dataPath, language);
            if (!ok) {
                isInitialized = false;
                Log.e(TAG, "Failed to reinit Tesseract with language: " + language);
                return;
            }
            applyDefaultsForLanguage(language);
        }
    }

    /**
     * Applies default configuration settings for the specified language or language specification
     * within the OCR engine. This method ensures that the OCR engine is configured with optimal
     * settings such as page segmentation mode, DPI, interword space preservation, and character
     * whitelists based on the given language specification.
     *
     * @param langSpec The language specification provided as a string (e.g., "eng", "deu+eng").
     *                 This is used to configure the character whitelist and other language-specific
     *                 settings for the OCR engine. If null or empty, default settings are applied.
     */
    public void applyDefaultsForLanguage(String langSpec) {
        if (!isInitialized) return;
        try {
            tessBaseAPI.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO);
        } catch (Throwable ignored) {
        }
        try {
            tessBaseAPI.setVariable("user_defined_dpi", DEFAULT_DPI);
        } catch (Throwable ignored) {
        }
        try {
            tessBaseAPI.setVariable("preserve_interword_spaces", "1");
        } catch (Throwable ignored) {
        }
        try {
            setWhitelist(OCRWhitelist.getWhitelistForLangSpec(langSpec));
        } catch (Throwable ignored) {
        }
    }

    /**
     * Ensures that the required language data files are available for the specified language specification.
     * This method processes the language specification, which may consist of one or more language codes
     * separated by a "+" symbol, and ensures that data files for each language are present.
     *
     * @param langSpec The language specification string (e.g., "eng", "deu+eng").
     *                 Each language part should correspond to a valid language code for which
     *                 the required data files will be checked or copied.
     * @throws IOException If an I/O error occurs while ensuring the language data files are present.
     */
    private void ensureLanguageDataPresent(String langSpec) throws IOException {
        for (String part : langSpec.split("\\+")) {
            String lang = part.trim();
            if (!lang.isEmpty()) copyLanguageDataFileSingle(lang);
        }
    }

    /**
     * Copies a single language data file for the Tesseract OCR engine to the appropriate directory.
     * If the file already exists and is non-empty, the method does nothing. Otherwise, it attempts to
     * extract the file from the application's assets and saves it to the target location.
     *
     * @param lang The language code specifying the language data file to copy (e.g., "eng" for English).
     * @throws IOException If an error occurs while creating the directory or copying the file.
     */
    private void copyLanguageDataFileSingle(String lang) throws IOException {
        File dir = new File(dataPath + "/" + TESSDATA_DIR);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Failed to create tessdata dir: " + dir);

        String filename = lang + TRAINEDDATA_EXT;
        File target = new File(dir, filename);
        if (target.exists() && target.length() > 0) return;

        try (InputStream in = context.getAssets().open(TESSDATA_DIR + "/" + filename)) {
            File tmp = File.createTempFile(lang + ".", ".tmp", dir);
            try (OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                out.flush();
            }
            if (!tmp.renameTo(target)) {
                try (InputStream rin = new FileInputStream(tmp);
                     OutputStream rout = new FileOutputStream(target)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = rin.read(buf)) != -1) rout.write(buf, 0, n);
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }

    /**
     * Retrieves the list of available languages for OCR processing based on the
     * language data files present in the application's assets or local directory.
     *
     * @return An array of strings, where each string corresponds to the code of an available language.
     * Returns an empty array if no languages are found or if an error occurs while accessing the data.
     */
    public String[] getAvailableLanguages() {
        try {
            LinkedHashSet<String> langs = new LinkedHashSet<>();

            String[] assetFiles = context.getAssets().list(TESSDATA_DIR);
            if (assetFiles != null) {
                for (String f : assetFiles)
                    if (f.endsWith(TRAINEDDATA_EXT))
                        langs.add(f.substring(0, f.length() - TRAINEDDATA_EXT.length()));
            }

            File localDir = new File(dataPath + "/" + TESSDATA_DIR);
            File[] local = localDir.listFiles((d, name) -> name.endsWith(TRAINEDDATA_EXT));
            if (local != null) {
                for (File f : local) {
                    String n = f.getName();
                    langs.add(n.substring(0, n.length() - TRAINEDDATA_EXT.length()));
                }
            }
            return langs.toArray(new String[0]);
        } catch (IOException e) {
            Log.e(TAG, "Error listing languages", e);
            return new String[0];
        }
    }

    /**
     * Checks whether the specified language is available for the OCR engine.
     * The method verifies the presence of the corresponding trained data file used by the OCR engine.
     *
     * @param lang The language code to check (e.g., "eng" for English). This should be a valid language
     *             code corresponding to the expected trained data file.
     * @return true if the trained data file for the specified language is available in the assets
     * or local directory; false otherwise.
     */
    public boolean isLanguageAvailable(String lang) {
        String filename = lang + TRAINEDDATA_EXT;
        try {
            String[] assetFiles = context.getAssets().list(TESSDATA_DIR);
            if (assetFiles != null) {
                for (String a : assetFiles) if (a.equals(filename)) return true;
            }
        } catch (IOException e) {
            Log.e(TAG, "Error checking assets", e);
        }
        File f = new File(dataPath + "/" + TESSDATA_DIR + "/" + filename);
        return f.exists() && f.length() > 0;
    }

    /* ==================== OCR-Options ==================== */

    /**
     * Sets the page segmentation mode for the Tesseract OCR engine.
     * The page segmentation mode determines how Tesseract interprets the structure of the input image,
     * such as whether the input is a single block of text, a single word, or a single character.
     * This setting can influence the accuracy and performance of OCR processing.
     *
     * @param mode The page segmentation mode to be set. Valid values are defined by the Tesseract API
     *             and include modes such as single block of text, single word, single character, etc.
     *             Refer to the Tesseract documentation for details on available modes.
     */
    public void setPageSegMode(int mode) {
        if (!isInitialized) {
            Log.e(TAG, "Tesseract not initialized");
            return;
        }
        tessBaseAPI.setPageSegMode(mode);
    }

    /**
     * Sets a configuration variable for the Tesseract OCR engine.
     * This method allows dynamically updating the behavior of the Tesseract engine
     * by setting specific variables to the provided values. The method returns false
     * if the Tesseract engine is not initialized.
     *
     * @param var   The name of the variable to be set. This corresponds to a
     *              configuration parameter recognized by the Tesseract engine.
     * @param value The value to assign to the configuration variable. This value
     *              modifies the behavior of the specified variable in the OCR engine.
     * @return true if the variable is successfully set; false if the Tesseract engine
     * is not initialized or the variable could not be set.
     */
    public boolean setVariable(String var, String value) {
        if (!isInitialized) {
            Log.e(TAG, "Tesseract not initialized");
            return false;
        }
        return tessBaseAPI.setVariable(var, value);
    }

    /**
     * Sets a whitelist of characters to be recognized by the OCR engine.
     * This method specifies a string of allowed characters that the OCR engine
     * should consider during text recognition, which can improve accuracy by
     * limiting the character set.
     *
     * @param chars The string containing characters to whitelist for recognition.
     *              Only these characters will be considered valid during OCR processing.
     * @return true if the whitelist is successfully set; false if the OCR engine
     * is not initialized or the whitelist could not be applied.
     */
    public boolean setWhitelist(String chars) {
        return setVariable("tessedit_char_whitelist", chars);
    }

    /* ==================== OCR – Results ==================== */

    /**
     * Represents the result of an OCR (Optical Character Recognition) process.
     * This class encapsulates the extracted text along with the mean confidence
     * score of the OCR engine.
     */
    public static class OcrResult {
        public final String text;
        public final Integer meanConfidence;

        public OcrResult(String text, Integer meanConfidence) {
            this.text = text != null ? text : "";
            this.meanConfidence = meanConfidence;
        }
    }

    /**
     * Represents the result of an OCR (Optical Character Recognition) process with detailed
     * recognition information at the word level. This class extends {@code OcrResult} by
     * including a list of recognized words, each with additional details.
     * <p>
     * The {@code words} property contains a collection of {@code RecognizedWord} objects,
     * allowing for more granular inspection of the OCR output, such as bounding boxes,
     * confidence scores, and text content for each recognized word.
     */
    public static class OcrResultWords extends OcrResult {
        public final List<RecognizedWord> words;

        public OcrResultWords(String text, Integer meanConfidence, List<RecognizedWord> words) {
            super(text, meanConfidence);
            this.words = (words != null) ? words : new ArrayList<>();
        }
    }

    /**
     * Performs Optical Character Recognition (OCR) on the given bitmap and retrieves the recognized text
     * along with additional details such as recognition confidence and word-level information.
     * This method uses Tesseract OCR and processes the input image to extract text.
     *
     * @param bitmap The input image as a {@link Bitmap} object. The image should be clear and
     *               appropriately oriented for optimal OCR results.
     * @return An {@link OcrResultWords} object containing the recognized text, confidence value,
     * and a list of recognized words with their respective details. If OCR fails or Tesseract
     * is not initialized, the result will contain empty text and default values.
     */
    public OcrResultWords runOcrWithWords(Bitmap bitmap) {
        if (!isTesseractInitialized()) {
            Log.e(TAG, "Tesseract not initialized");
            return new OcrResultWords("", null, new ArrayList<>());
        }
        try {
            Bitmap src = bitmap.getConfig() == Bitmap.Config.ARGB_8888 ? bitmap : bitmap.copy(Bitmap.Config.ARGB_8888, false);

            tessBaseAPI.setImage(src);
            String text = tessBaseAPI.getUTF8Text();
            String hocr = null;
            try {
                hocr = tessBaseAPI.getHOCRText(0); // Seite 0
            } catch (Throwable t) {
                Log.e(TAG, "getHOCRText not available", t);
            }
            Integer conf = getMeanConfidenceSafe();
            tessBaseAPI.clear();

            List<RecognizedWord> words = parseHocrWords(hocr, conf);
            return new OcrResultWords(text, conf, words);
        } catch (Exception e) {
            Log.e(TAG, "Error performing OCR with HOCR", e);
            return new OcrResultWords("", null, new ArrayList<>());
        }
    }

    /* ==================== HOCR-Parsing ==================== */

    /**
     * A regular expression pattern used to match and extract specific HTML span elements
     * related to OCR results. These span elements represent words extracted by OCR engines
     * such as Tesseract and are associated with word-level metadata like bounding box
     * coordinates and recognition confidence.
     * <p>
     * The pattern matches `<span>` elements with the following characteristics:
     * - A class attribute that contains `ocrx_word` or `ocr_word`.
     * - A title attribute that includes bounding box (`bbox`) information and confidence
     * (`x_wconf`) values.
     * <p>
     * Capturing groups are used to extract:
     * 1. Metadata information from the title attribute (e.g., bounding box data).
     * 2. The text content enclosed within the span element.
     * <p>
     * This pattern is case-insensitive and supports matching across multiple lines.
     */
    private static final Pattern SPAN_PATTERN = Pattern.compile(
            "<span[^>]*class=[\"'][^\"']*ocrx?_word[^\"']*[\"'][^>]*title=[\"']([^\"']+)[\"'][^>]*>(.*?)</span>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * A regular expression pattern used to match bounding box data in text. This pattern
     * identifies strings in the format "bbox x1 y1 x2 y2", where `x1`, `y1`, `x2`, and `y2`
     * are integers representing the coordinates of a bounding box.
     * <p>
     * The pattern is case-insensitive and designed to capture four integer groups
     * corresponding to the bounding box's corners.
     */
    private static final Pattern BBOX_PATTERN = Pattern.compile(
            "bbox\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)",
            Pattern.CASE_INSENSITIVE);

    /**
     * A precompiled {@link Pattern} used to match and extract confidence (x_wconf) values
     * from text, typically in the context of processing OCR-related data or structured
     * output formats such as HOCR.
     * <p>
     * The pattern is constructed with the following properties:
     * - It matches strings containing "x_wconf" followed by one or more digits.
     * - The matching is case insensitive due to the use of {@link Pattern#CASE_INSENSITIVE}.
     * <p>
     * Capturing groups:
     * - Captures the numeric component immediately following "x_wconf".
     * <p>
     * This pattern is likely used to parse and extract confidence levels associated with
     * OCR-recognized words or regions.
     */
    private static final Pattern XWCONF_PATTERN = Pattern.compile(
            "x_wconf\\s+(\\d+)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Parses the given hOCR (HTML for OCR) content to extract recognized words along
     * with their bounding box coordinates and confidence levels. Each recognized word
     * is represented as an instance of the RecognizedWord class.
     *
     * @param hocr        the hOCR content to be parsed, represented as a String. It contains
     *                    structured OCR data with bounding box coordinates and confidence values.
     * @param defaultConf the default confidence value to use if a specific confidence
     *                    value is not available in the hOCR data.
     * @return a list of recognized words extracted from the hOCR content. Each recognized
     * word includes the text content, bounding box, and confidence level.
     */
    private List<RecognizedWord> parseHocrWords(String hocr, Integer defaultConf) {
        List<RecognizedWord> out = new ArrayList<>();
        if (hocr == null || hocr.isEmpty()) return out;

        Matcher m = SPAN_PATTERN.matcher(hocr);
        while (m.find()) {
            String title = m.group(1);
            String htmlText = m.group(2);

            if (title == null) continue;
            Matcher bboxM = BBOX_PATTERN.matcher(title);
            if (!bboxM.find()) continue;

            try {
                float left = Float.parseFloat(bboxM.group(1));
                float top = Float.parseFloat(bboxM.group(2));
                float right = Float.parseFloat(bboxM.group(3));
                float bottom = Float.parseFloat(bboxM.group(4));

                RectF box = new RectF(left, top, right, bottom);

                float conf = (defaultConf != null) ? defaultConf : 0f;
                Matcher confM = XWCONF_PATTERN.matcher(title);
                if (confM.find()) {
                    try {
                        conf = Float.parseFloat(confM.group(1));
                    } catch (Throwable ignore) {
                    }
                }

                String text = cleanHtmlText(htmlText);
                if (text.isEmpty()) continue;

                out.add(new RecognizedWord(text, box, conf));
            } catch (Throwable ignore) {
                // schluckt fehlerhafte Einträge
            }
        }
        return out;
    }

    /**
     * Cleans an HTML text string by removing HTML tags, resolving basic HTML entities,
     * trimming whitespace, and normalizing multiple consecutive spaces into a single space.
     *
     * @param html the HTML text string to be cleaned; can be null
     * @return the cleaned plain text string; returns an empty string if the input is null
     */
    private static String cleanHtmlText(String html) {
        if (html == null) return "";
        // Tags entfernen
        String t = html.replaceAll("<[^>]+>", "");
        // Grundlegende Entities auflösen
        t = t.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'");
        // trim & normalisieren
        t = t.trim();
        // Mehrfach-Leerzeichen → eins
        t = t.replaceAll("\\s{2,}", " ");
        return t;
    }

    /* ==================== Metriken ==================== */

    /**
     * Retrieves the mean confidence value of the OCR engine if it has been successfully initialized.
     * The mean confidence represents the average confidence level of the recognized text.
     * If the OCR engine is not initialized or an error occurs during retrieval, this method returns null.
     *
     * @return The mean confidence value as an Integer if available, or null if the OCR engine is not initialized
     * or an error occurs during the operation.
     */
    public Integer getMeanConfidenceSafe() {
        if (!isInitialized) return null;
        try {
            return tessBaseAPI.meanConfidence(); // in tess-two oft so benannt
        } catch (Throwable t) {
            return null;
        }
    }
}
