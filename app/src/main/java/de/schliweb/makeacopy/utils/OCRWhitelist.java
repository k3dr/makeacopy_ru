package de.schliweb.makeacopy.utils;

/**
 * Whitelist for OCR.
 * This class provides a static method to get the whitelist for a given language.
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
