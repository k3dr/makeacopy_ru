package de.schliweb.makeacopy.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;
import com.googlecode.tesseract.android.TessBaseAPI;

import java.io.*;

/**
 * OCRHelper is a utility class that provides functionality for performing OCR (Optical Character Recognition)
 * using the Tesseract OCR engine. It supports initializing Tesseract, setting OCR languages, performing text
 * recognition on images or image regions, and retrieving available languages for recognition.
 */
public class OCRHelper {
    private static final String TAG = "OCRHelper";
    private static final String TESSDATA_DIR = "tessdata";
    private static final String DEFAULT_LANGUAGE = "eng";

    private final Context context;
    private final String dataPath;
    private TessBaseAPI tessBaseAPI;
    private String language;
    private boolean isInitialized = false;

    /**
     * Constructor
     *
     * @param context Application context
     */
    public OCRHelper(Context context) {
        this.context = context;
        this.language = DEFAULT_LANGUAGE;
        this.dataPath = context.getFilesDir().getAbsolutePath();
    }

    /**
     * Initializes Tesseract OCR engine
     *
     * @return true if initialization was successful, false otherwise
     */
    public boolean initTesseract() {
        if (isInitialized) {
            return true;
        }

        try {
            // Create the tessdata directory if it doesn't exist
            File tessdataDir = new File(dataPath + "/" + TESSDATA_DIR);
            if (!tessdataDir.exists()) {
                tessdataDir.mkdirs();
            }

            // Copy the language data file from assets to the tessdata directory
            copyLanguageDataFile(language);

            // Initialize Tesseract
            tessBaseAPI = new TessBaseAPI();
            boolean result = tessBaseAPI.init(dataPath, language);

            if (result) {
                Log.i(TAG, "Tesseract initialized successfully");
                isInitialized = true;
                return true;
            } else {
                Log.e(TAG, "Tesseract initialization failed");
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error initializing Tesseract", e);
            return false;
        }
    }

    /**
     * Copies the language data file from assets to the tessdata directory
     *
     * @param language Language code (e.g., "eng" for English)
     * @throws IOException If an I/O error occurs
     */
    private void copyLanguageDataFile(String language) throws IOException {
        String filename = language + ".traineddata";
        File file = new File(dataPath + "/" + TESSDATA_DIR + "/" + filename);

        // If the file already exists, skip copying
        if (file.exists()) {
            return;
        }

        // Copy the file from assets to the tessdata directory
        InputStream in = context.getAssets().open(TESSDATA_DIR + "/" + filename);
        OutputStream out = new FileOutputStream(file);

        byte[] buffer = new byte[1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }

        in.close();
        out.flush();
        out.close();
    }

    /**
     * Sets the language for OCR
     *
     * @param language Language code (e.g., "eng" for English, "deu" for German)
     * @throws IOException If an I/O error occurs
     */
    public void setLanguage(String language) throws IOException {
        if (this.language.equals(language)) {
            return;
        }

        this.language = language;

        // Copy the language data file if it doesn't exist
        copyLanguageDataFile(language);

        // Reinitialize Tesseract with the new language
        if (isInitialized) {
            // Recycle the old instance
            tessBaseAPI.recycle();

            // Create a new instance and initialize it
            tessBaseAPI = new TessBaseAPI();
            boolean result = tessBaseAPI.init(dataPath, language);

            if (!result) {
                Log.e(TAG, "Failed to reinitialize Tesseract with language: " + language);
                isInitialized = false;
            }
        }
    }

    /**
     * Checks if Tesseract is initialized
     *
     * @return true if Tesseract is initialized, false otherwise
     */
    public boolean isTesseractInitialized() {
        return isInitialized;
    }

    /**
     * Performs OCR on an image
     *
     * @param bitmap Input bitmap
     * @return Recognized text, or empty string if recognition failed
     */
    public String recognizeText(Bitmap bitmap) {
        if (!isInitialized) {
            Log.e(TAG, "Tesseract not initialized");
            return "";
        }

        try {
            // Set the image to process
            tessBaseAPI.setImage(bitmap);

            // Get the recognized text
            String text = tessBaseAPI.getUTF8Text();

            // Clear the last recognized results
            tessBaseAPI.clear();

            return text;
        } catch (Exception e) {
            Log.e(TAG, "Error performing OCR", e);
            return "";
        }
    }

    /**
     * Gets the list of available languages
     *
     * @return Array of available language codes
     */
    public String[] getAvailableLanguages() {
        if (!isInitialized) {
            Log.e(TAG, "Tesseract not initialized");
            return new String[0];
        }

        try {
            // Get languages from assets directory
            String[] assetLanguages = context.getAssets().list(TESSDATA_DIR);
            if (assetLanguages == null) return new String[0];

            // Filter for .traineddata files and extract language codes
            java.util.List<String> languageList = new java.util.ArrayList<>();
            for (String filename : assetLanguages) {
                if (filename.endsWith(".traineddata")) {
                    String language = filename.replace(".traineddata", "");
                    languageList.add(language);

                    // Copy language file to internal storage if it doesn't exist
                    try {
                        copyLanguageDataFile(language);
                    } catch (IOException e) {
                        Log.e(TAG, "Error copying language file for " + language, e);
                    }
                }
            }

            // Convert list to array
            String[] languages = new String[languageList.size()];
            languageList.toArray(languages);
            return languages;
        } catch (IOException e) {
            Log.e(TAG, "Error getting available languages from assets", e);

            // Fallback to checking internal storage
            File tessdata = new File(dataPath + "/" + TESSDATA_DIR);
            File[] files = tessdata.listFiles((dir, name) -> name.endsWith(".traineddata"));
            if (files == null) return new String[0];
            String[] languages = new String[files.length];
            for (int i = 0; i < files.length; i++) {
                languages[i] = files[i].getName().replace(".traineddata", "");
            }
            return languages;
        }
    }

    /**
     * Checks if a language is available
     *
     * @param language Language code to check
     * @return true if the language is available, false otherwise
     */
    public boolean isLanguageAvailable(String language) {
        // First check if the language file exists in the assets directory
        try {
            String filename = language + ".traineddata";
            String[] assetFiles = context.getAssets().list(TESSDATA_DIR);
            for (String assetFile : assetFiles) {
                if (assetFile.equals(filename)) {
                    // If the language file exists in assets but not in internal storage, copy it
                    File file = new File(dataPath + "/" + TESSDATA_DIR + "/" + filename);
                    if (!file.exists()) {
                        try {
                            copyLanguageDataFile(language);
                        } catch (IOException e) {
                            Log.e(TAG, "Error copying language file for " + language, e);
                        }
                    }
                    return true;
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Error checking if language is available in assets", e);
        }

        // Fallback to checking internal storage
        String[] availableLanguages = getAvailableLanguages();
        for (String availableLanguage : availableLanguages) {
            if (availableLanguage.equals(language)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sets the page segmentation mode
     *
     * @param mode Page segmentation mode
     */
    public void setPageSegMode(int mode) {
        if (!isInitialized) {
            Log.e(TAG, "Tesseract not initialized");
            return;
        }

        tessBaseAPI.setPageSegMode(mode);
    }

    /**
     * Sets a variable
     *
     * @param var   Variable name
     * @param value Variable value
     * @return true if the variable was set, false otherwise
     */
    public boolean setVariable(String var, String value) {
        if (!isInitialized) {
            Log.e(TAG, "Tesseract not initialized");
            return false;
        }

        return tessBaseAPI.setVariable(var, value);
    }

}