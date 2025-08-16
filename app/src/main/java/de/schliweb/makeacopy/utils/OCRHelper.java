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
 * Helper class for OCR operations.
 * Provides methods for initializing Tesseract, applying OCR options,
 * and running OCR on images.
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

    public OCRHelper(Context context) {
        this.context = context.getApplicationContext();
        this.language = DEFAULT_LANGUAGE;
        this.dataPath = context.getFilesDir().getAbsolutePath();
    }

    /* ==================== Init / Shutdown ==================== */

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

    public boolean isTesseractInitialized() {
        return isInitialized;
    }

    /* ==================== Language / Data ==================== */

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
     * Set default values for Tesseract.
     * Currently only sets the page segmentation mode to PSM_AUTO.
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

    private void ensureLanguageDataPresent(String langSpec) throws IOException {
        for (String part : langSpec.split("\\+")) {
            String lang = part.trim();
            if (!lang.isEmpty()) copyLanguageDataFileSingle(lang);
        }
    }

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

    public void setPageSegMode(int mode) {
        if (!isInitialized) {
            Log.e(TAG, "Tesseract not initialized");
            return;
        }
        tessBaseAPI.setPageSegMode(mode);
    }

    public boolean setVariable(String var, String value) {
        if (!isInitialized) {
            Log.e(TAG, "Tesseract not initialized");
            return false;
        }
        return tessBaseAPI.setVariable(var, value);
    }

    public boolean setWhitelist(String chars) {
        return setVariable("tessedit_char_whitelist", chars);
    }

    /* ==================== OCR – Results ==================== */

    public static class OcrResult {
        public final String text;
        public final Integer meanConfidence;

        public OcrResult(String text, Integer meanConfidence) {
            this.text = text != null ? text : "";
            this.meanConfidence = meanConfidence;
        }
    }

    public static class OcrResultWords extends OcrResult {
        public final List<RecognizedWord> words;

        public OcrResultWords(String text, Integer meanConfidence, List<RecognizedWord> words) {
            super(text, meanConfidence);
            this.words = (words != null) ? words : new ArrayList<>();
        }
    }

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

    // span mit class='ocrx_word' / 'ocr_word' und title='bbox ...; x_wconf n'
    private static final Pattern SPAN_PATTERN = Pattern.compile(
            "<span[^>]*class=[\"'][^\"']*ocrx?_word[^\"']*[\"'][^>]*title=[\"']([^\"']+)[\"'][^>]*>(.*?)</span>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern BBOX_PATTERN = Pattern.compile(
            "bbox\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern XWCONF_PATTERN = Pattern.compile(
            "x_wconf\\s+(\\d+)",
            Pattern.CASE_INSENSITIVE);

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
     * Mittlere Konfidenz (0..100) – falls die API verfügbar ist.
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
