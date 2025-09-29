package de.schliweb.makeacopy.utils;

import java.util.Locale;

/**
 * A utility class providing methods for optimal OCR language selection
 * and handling of supported language configurations. This class is
 * designed to work with Tesseract OCR language codes and assists in
 * determining effective language settings based on user preferences
 * or the system's locale.
 * <p>
 * This class is not intended to be instantiated.
 */
public class OCRUtils {

    private static final String TAG = "OCRUtils";

    private OCRUtils() {
    }

    /**
     * Resolves the effective language based on the provided language option and the system's default locale.
     * If a specific language option is given, it will return that language. Otherwise, it resolves the
     * appropriate language code based on the system's default language and region. The returned language code
     * is compatible with Tesseract OCR.
     *
     * @param languageOpt A string representing the optional language code. If null or empty, the method
     *                    uses the system's default language to determine the effective language.
     * @return A string representing the effective language code. If no valid language can be determined,
     * the default value "eng" (for English) is returned.
     */
    public static String resolveEffectiveLanguage(String languageOpt) {
        if (languageOpt != null && !languageOpt.trim().isEmpty()) {
            return languageOpt;
        }
        try {
            Locale loc = Locale.getDefault();
            String sys = loc.getLanguage();
            if ("zh".equalsIgnoreCase(sys)) {
                String country = loc.getCountry();
                if ("TW".equalsIgnoreCase(country) || "HK".equalsIgnoreCase(country) || "MO".equalsIgnoreCase(country)) {
                    return "chi_tra";
                } else {
                    return "chi_sim";
                }
            } else if ("de".equalsIgnoreCase(sys)) {
                return "deu";
            } else if ("fr".equalsIgnoreCase(sys)) {
                return "fra";
            } else if ("it".equalsIgnoreCase(sys)) {
                return "ita";
            } else if ("es".equalsIgnoreCase(sys)) {
                return "spa";
            } else {
                return "eng";
            }
        } catch (Throwable ignore) {
            return "eng";
        }
    }

    /**
     * Maps a system language code to the corresponding Tesseract OCR language code.
     * If the provided language is not recognized, defaults to "eng" (English).
     * Special handling is applied for Chinese to differentiate between simplified and traditional scripts
     * based on the system region.
     *
     * @param systemLanguage A string representing the system language code (e.g., "en", "de", "zh").
     * @return A string representing the corresponding Tesseract OCR language code.
     * Defaults to "eng" if the input language is not recognized.
     */
    public static String mapSystemLanguageToTesseract(String systemLanguage) {
        return switch (systemLanguage) {
            case "en" -> "eng";
            case "de" -> "deu";
            case "fr" -> "fra";
            case "it" -> "ita";
            case "es" -> "spa";
            case "zh" -> {
                // Map Chinese to Simplified or Traditional based on region, default to Simplified
                try {
                    Locale loc = Locale.getDefault();
                    String country = loc.getCountry();
                    if ("TW".equalsIgnoreCase(country) || "HK".equalsIgnoreCase(country) || "MO".equalsIgnoreCase(country)) {
                        yield "chi_tra";
                    }
                } catch (Throwable ignore) {
                }
                yield "chi_sim";
            }
            default -> "eng";
        };
    }

    /**
     * Retrieves a list of supported language codes.
     *
     * @return An array of strings representing the language codes supported for OCR.
     * The codes include "eng" (English), "deu" (German), "fra" (French),
     * "ita" (Italian), "spa" (Spanish), "chi_sim" (Simplified Chinese),
     * and "chi_tra" (Traditional Chinese).
     */
    public static String[] getLanguages() {
        return new String[]{"eng", "deu", "fra", "ita", "spa", "chi_sim", "chi_tra"};
    }
}
