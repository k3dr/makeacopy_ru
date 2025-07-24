package de.schliweb.makeacopy.ui.ocr;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.databinding.FragmentOcrBinding;
import de.schliweb.makeacopy.ui.crop.CropViewModel;
import de.schliweb.makeacopy.utils.ImageScaler;
import de.schliweb.makeacopy.utils.OCRHelper;
import de.schliweb.makeacopy.utils.UIUtils;

import java.io.IOException;
import java.util.stream.IntStream;

/**
 * OCRFragment is a UI fragment designed to facilitate Optical Character Recognition (OCR)
 * processing. It integrates a Tesseract-based OCR engine and provides an interface for users
 * to process image-based text recognition, select languages, and view or further export OCR results.
 * <p>
 * The functionality includes initialization of OCR engine, handling input images,
 * processing text recognition, updating the UI based on processing results, and managing
 * available language options for OCR processing.
 */
public class OCRFragment extends Fragment {
    private static final String TAG = "OCRFragment";
    private FragmentOcrBinding binding;
    private OCRViewModel ocrViewModel;
    private CropViewModel cropViewModel;
    private OCRHelper ocrHelper;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        ocrViewModel = new ViewModelProvider(requireActivity()).get(OCRViewModel.class);
        cropViewModel = new ViewModelProvider(requireActivity()).get(CropViewModel.class);
        binding = FragmentOcrBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        // OCR-Engine initialisieren
        ocrHelper = new OCRHelper(requireContext());
        if (!ocrHelper.initTesseract()) {
            UIUtils.showToast(requireContext(), "Failed to initialize Tesseract", Toast.LENGTH_SHORT);
        } else {
            ocrHelper.setPageSegMode(3);
            ocrHelper.setVariable("tessedit_char_whitelist", de.schliweb.makeacopy.utils.OCRWhitelist.DEFAULT);
        }

        // Text-Status-Observer
        ocrViewModel.getText().observe(getViewLifecycleOwner(), binding.textOcr::setText);

        // OCR Ergebnis-TextView
        ocrViewModel.getOcrResult().observe(getViewLifecycleOwner(), result -> {
            binding.ocrResultText.setText(result == null || result.isEmpty() ? getString(R.string.ocr_results_will_appear_here) : result);
        });

        // Verarbeitung-Button
        binding.buttonProcess.setOnClickListener(v -> performOCR());

        // Window-Inset-Margins für Button-Container
        ViewCompat.setOnApplyWindowInsetsListener(binding.buttonContainer, (v, insets) -> {
            de.schliweb.makeacopy.utils.UIUtils.adjustMarginForSystemInsets(binding.buttonContainer, 8);
            return insets;
        });

        // Prozess-Status anzeigen
        ocrViewModel.isProcessing().observe(getViewLifecycleOwner(), processing -> {
            binding.buttonProcess.setEnabled(!processing);
            if (processing) binding.textOcr.setText(R.string.processing_image);
        });

        // Status: Kein Bild vorhanden
        ocrViewModel.isImageProcessed().observe(getViewLifecycleOwner(), processed -> {
            binding.textOcr.setText(processed ? R.string.ocr_processing_complete_tap_the_button_to_proceed_to_export : R.string.no_image_processed_crop_an_image_first);
        });

        // Crop-Resultat laden
        cropViewModel.getImageBitmap().observe(getViewLifecycleOwner(), bitmap -> {
            if (bitmap != null) {
                ocrViewModel.setImageProcessed(false);
                ocrViewModel.setOcrResult("");
            }
        });

        // Insets (Status bar)
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            int topInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
            ViewGroup.MarginLayoutParams textParams = (ViewGroup.MarginLayoutParams) binding.textOcr.getLayoutParams();
            textParams.topMargin = (int) (8 * getResources().getDisplayMetrics().density) + topInset;
            binding.textOcr.setLayoutParams(textParams);
            return insets;
        });

        // Language Spinner vorbereiten
        setupLanguageSpinner();

        return root;
    }

    /**
     * Language-Auswahl vorbereiten und reagieren
     */
    private void setupLanguageSpinner() {
        Spinner spinner = binding.languageSpinner;
        String[] availableLanguages = getAvailableLanguages();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, availableLanguages);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);

        // Default: Systemsprache oder Englisch
        String systemLang = mapSystemLanguageToTesseract(java.util.Locale.getDefault().getLanguage());
        int defaultPos = IntStream.range(0, availableLanguages.length).filter(i -> availableLanguages[i].equals(systemLang)).findFirst().orElse(0);
        spinner.setSelection(defaultPos);

        final boolean[] firstSelection = {true};
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                String lang = (String) parent.getItemAtPosition(pos);
                if (!ocrHelper.isLanguageAvailable(lang)) {
                    UIUtils.showToast(requireContext(), "Language " + lang + " not available.", Toast.LENGTH_LONG);
                    spinner.setSelection(defaultPos, false);
                    return;
                }
                try {
                    ocrHelper.setLanguage(lang);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                ocrHelper.setVariable("tessedit_char_whitelist", de.schliweb.makeacopy.utils.OCRWhitelist.getWhitelistForLanguage(lang));
                if (firstSelection[0]) {
                    firstSelection[0] = false;
                    Bitmap bitmap = cropViewModel.getImageBitmap().getValue();
                    if (bitmap != null) performOCR();
                } else if (ocrViewModel.isImageProcessed().getValue() != null && ocrViewModel.isImageProcessed().getValue()) {
                    binding.buttonProcess.setText(R.string.btn_process);
                    binding.buttonProcess.setOnClickListener(v -> performOCR());
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    /**
     * Liefert Liste aller verfügbaren Sprachen, oder Default-Liste
     */
    private String[] getAvailableLanguages() {
        String[] langs = ocrHelper.getAvailableLanguages();
        if (langs == null || langs.length == 0) return new String[]{"eng", "deu", "fra", "ita", "spa"};
        return langs;
    }

    /**
     * OCR-Funktion
     */
    private void performOCR() {
        Bitmap imageBitmap = cropViewModel.getImageBitmap().getValue();
        if (imageBitmap == null) {
            UIUtils.showToast(requireContext(), "No image to process", Toast.LENGTH_SHORT);
            return;
        }
        if (!ocrHelper.isTesseractInitialized()) {
            UIUtils.showToast(requireContext(), "Tesseract not initialized. Please restart the app.", Toast.LENGTH_LONG);
            ocrViewModel.setProcessing(false);
            return;
        }

        ocrViewModel.setProcessing(true);
        new Thread(() -> {
            try {
                // Pre-scale the image to A4 dimensions before OCR
                // This ensures that OCR coordinates will match PDF coordinates
                Log.d(TAG, "performOCR: Pre-scaling image to A4 dimensions before OCR");
                Bitmap scaledBitmap = ImageScaler.scaleToA4(imageBitmap);

                // Store the scaled bitmap back in the CropViewModel for PDF export
                requireActivity().runOnUiThread(() -> {
                    cropViewModel.setImageBitmap(scaledBitmap);
                    Log.d(TAG, "performOCR: Updated CropViewModel with pre-scaled bitmap");
                });

                // Perform OCR on the pre-scaled image
                String recognizedText = ocrHelper.recognizeText(scaledBitmap);

                requireActivity().runOnUiThread(() -> {
                    if (recognizedText == null || recognizedText.trim().isEmpty()) {
                        ocrViewModel.setOcrResult(getString(R.string.ocr_results_will_appear_here));
                    } else {
                        ocrViewModel.setOcrResult(recognizedText);
                    }
                    ocrViewModel.setImageProcessed(true);
                    ocrViewModel.setProcessing(false);
                    binding.buttonProcess.setText(R.string.btn_export);
                    binding.buttonProcess.setOnClickListener(v -> Navigation.findNavController(requireView()).navigate(R.id.navigation_export));
                });
            } catch (Exception e) {
                requireActivity().runOnUiThread(() -> {
                    ocrViewModel.setProcessing(false);
                    ocrViewModel.setOcrResult("OCR failed: " + (e.getMessage() != null ? e.getMessage() : e));
                    UIUtils.showToast(requireContext(), "OCR failed: " + e.getMessage(), Toast.LENGTH_LONG);
                });
            }
        }).start();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    /**
     * Mapping ISO-Lang-Codes auf Tesseract
     */
    private String mapSystemLanguageToTesseract(String systemLanguage) {
        switch (systemLanguage) {
            case "en":
                return "eng";
            case "de":
                return "deu";
            case "fr":
                return "fra";
            case "it":
                return "ita";
            case "es":
                return "spa";
            default:
                return "eng";
        }
    }
}
