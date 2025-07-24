package de.schliweb.makeacopy.utils;

/**
 * OCRWhitelist provides language-specific character whitelists for OCR recognition.
 * These whitelists define which characters should be recognized by the OCR engine
 * for each supported language.
 */
public class OCRWhitelist {

    // Deutsch
    public static final String DE = "ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÜabcdefghijklmnopqrstuvwxyzäöüß0123456789.,:;-?!()[]/\"' ";

    // Englisch
    public static final String EN = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789.,:;-?!()[]/\"' ";

    // Spanisch
    public static final String ES = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyzáéíóúüñÁÉÍÓÚÜÑ0123456789.,:;-?!()[]/\"' ";

    // Französisch
    public static final String FR = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyzàâäçéèêëîïôöùûüÿœæÀÂÄÇÉÈÊËÎÏÔÖÙÛÜŸŒÆ0123456789.,:;-?!()[]/\"' ";

    // Default whitelist (includes all characters from all supported languages)
    public static final String DEFAULT = "ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÜabcdefghijklmnopqrstuvwxyzäöüßáéíóúüñÁÉÍÓÚÜÑàâäçéèêëîïôöùûüÿœæÀÂÄÇÉÈÊËÎÏÔÖÙÛÜŸŒÆ0123456789.,:;-?!()[]/\"' ";

    /**
     * Gets the appropriate whitelist for the given language code.
     *
     * @param languageCode The Tesseract language code (e.g., "eng", "deu", "fra", "spa")
     * @return The character whitelist for the specified language, or the default whitelist if the language is not supported
     */
    public static String getWhitelistForLanguage(String languageCode) {
        if (languageCode == null) {
            return DEFAULT;
        }

        switch (languageCode) {
            case "deu":
                return DE;
            case "eng":
                return EN;
            case "spa":
                return ES;
            case "fra":
                return FR;
            default:
                return DEFAULT;
        }
    }
}