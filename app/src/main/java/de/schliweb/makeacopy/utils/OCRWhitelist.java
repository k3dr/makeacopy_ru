package de.schliweb.makeacopy.utils;

/**
 * A utility class that provides predefined whitelists for characters based on specific languages,
 * commonly used in Optical Character Recognition (OCR) processes. This class helps restrict
 * the character set to improve OCR accuracy and reduce errors.
 * <p>
 * This class is not intended to be instantiated.
 */
public class OCRWhitelist {

    // German
    public static final String DE = "ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÜabcdefghijklmnopqrstuvwxyzäöüß0123456789.,:;-?!()[]/\"' ";

    // English
    public static final String EN = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789.,:;-?!()[]/\"' ";

    // Spanish
    public static final String ES = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyzáéíóúüñÁÉÍÓÚÜÑ0123456789.,:;-?!()[]/\"' ";

    // French
    public static final String FR = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyzàâäçéèêëîïôöùûüÿœæÀÂÄÇÉÈÊËÎÏÔÖÙÛÜŸŒÆ0123456789.,:;-?!()[]/\"' ";

    // Italian
    public static final String IT = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyzàèéìíîòóùúÀÈÉÌÍÎÒÓÙÚ0123456789.,:;-?!()[]/\"' ";

    // Default: Superset
    public static final String DEFAULT = (DE + EN + ES + FR + IT);

    /**
     * Returns a predefined whitelist of allowed characters for a given language code.
     * The whitelist is used to improve processing accuracy by restricting the character set.
     *
     * @param languageCode The ISO language code (e.g., "deu" for German, "eng" for English).
     *                     When null or an unsupported code is provided, a default whitelist is returned.
     * @return A string containing the whitelist of allowed characters for the specified language,
     * or the default whitelist if the language code is null or unsupported.
     */
    public static String getWhitelistForLanguage(String languageCode) {
        if (languageCode == null) return DEFAULT;
        switch (languageCode) {
            case "deu":
                return DE;
            case "eng":
                return EN;
            case "spa":
                return ES;
            case "fra":
                return FR;
            case "ita":
                return IT;
            default:
                return DEFAULT;
        }
    }

    /**
     * Generates a composite whitelist of allowed characters for a given language specification.
     * The language specification can consist of multiple language codes separated by a "+".
     * Each language code will correspond to a predefined whitelist, and their characters
     * are combined into a single whitelist, ensuring no duplicate characters are present.
     *
     * @param langSpec A string containing one or more ISO language codes separated by "+"
     *                 (e.g., "eng+deu+fra"). If null or empty, a default whitelist is returned.
     * @return A string containing the combined whitelist of allowed characters for the specified
     * language specification, or the default whitelist if the input is null or empty.
     */
    public static String getWhitelistForLangSpec(String langSpec) {
        if (langSpec == null || langSpec.trim().isEmpty()) return DEFAULT;
        StringBuilder sb = new StringBuilder();
        for (String part : langSpec.split("\\+")) {
            String lang = part.trim();
            if (!lang.isEmpty()) sb.append(getWhitelistForLanguage(lang));
        }
        // cleanup duplicates
        return sb.chars().distinct()
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }
}
