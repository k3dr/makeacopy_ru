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
import de.schliweb.makeacopy.utils.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

/**
 * Fragment for OCR functionality.
 * This fragment handles the OCR functionality of a document.
 * It manages user interactions for selecting a language, starting OCR,
 * and displaying the results.
 */
public class OCRFragment extends Fragment {
    private static final String TAG = "OCRFragment";
    private FragmentOcrBinding binding;
    private OCRViewModel ocrViewModel;
    private CropViewModel cropViewModel;
    private OCRHelper ocrHelper;
    private final AtomicBoolean internalImageUpdate = new AtomicBoolean(false);

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        ocrViewModel = new ViewModelProvider(requireActivity()).get(OCRViewModel.class);
        cropViewModel = new ViewModelProvider(requireActivity()).get(CropViewModel.class);
        binding = FragmentOcrBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        // init OCR-Engine
        ocrHelper = new OCRHelper(requireContext());
        if (!ocrHelper.initTesseract()) {
            UIUtils.showToast(requireContext(), "Failed to initialize Tesseract", Toast.LENGTH_SHORT);
        } else {
            ocrHelper.applyDefaultsForLanguage("eng");
            ocrHelper.setWhitelist(OCRWhitelist.DEFAULT);
        }

        // State-Observer
        ocrViewModel.getState().observe(getViewLifecycleOwner(), state -> {
            binding.buttonProcess.setEnabled(!state.processing());
            binding.textOcr.setText(state.processing()
                    ? getString(R.string.processing_image)
                    : (state.imageProcessed() ? getString(R.string.ocr_processing_complete_tap_the_button_to_proceed_to_export)
                    : getString(R.string.no_image_processed_crop_an_image_first)));

            binding.ocrResultText.setText((state.ocrText() == null || state.ocrText().isEmpty())
                    ? getString(R.string.ocr_results_will_appear_here)
                    : state.ocrText());

            if (state.imageProcessed()) {
                binding.buttonProcess.setText(R.string.btn_export);
                binding.buttonProcess.setOnClickListener(v ->
                        Navigation.findNavController(requireView()).navigate(R.id.navigation_export));
            } else {
                binding.buttonProcess.setText(R.string.btn_process);
                binding.buttonProcess.setOnClickListener(v -> performOCR());
            }
        });

        // Error-Events
        ocrViewModel.getErrorEvents().observe(getViewLifecycleOwner(), ev -> {
            if (ev == null) return;
            String msg = ev.getContentIfNotHandled();
            if (msg != null) UIUtils.showToast(requireContext(), "OCR failed: " + msg, Toast.LENGTH_LONG);
        });

        // On image change (only trigger reset)
        cropViewModel.getImageBitmap().observe(getViewLifecycleOwner(), bitmap -> {
            if (bitmap != null) {
                if (internalImageUpdate.getAndSet(false)) return; // internes Update ignorieren
                ocrViewModel.resetForNewImage();
            }
        });

        // Insets (Statusbar)
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            int topInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
            ViewGroup.MarginLayoutParams textParams = (ViewGroup.MarginLayoutParams) binding.textOcr.getLayoutParams();
            textParams.topMargin = (int) (8 * getResources().getDisplayMetrics().density) + topInset;
            binding.textOcr.setLayoutParams(textParams);
            return insets;
        });

        // select language
        setupLanguageSpinner();

        return root;
    }

    /**
     * Setup the language spinner
     */
    private void setupLanguageSpinner() {
        Spinner spinner = binding.languageSpinner;
        String[] availableLanguages = getAvailableLanguages();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, availableLanguages);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);

        String systemLang = mapSystemLanguageToTesseract(java.util.Locale.getDefault().getLanguage());
        int defaultPos = IntStream.range(0, availableLanguages.length)
                .filter(i -> availableLanguages[i].equals(systemLang)).findFirst().orElse(0);
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
                    ocrHelper.applyDefaultsForLanguage(lang);
                    ocrHelper.setWhitelist(OCRWhitelist.getWhitelistForLangSpec(lang));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                ocrViewModel.setLanguage(lang);

                if (firstSelection[0]) {
                    firstSelection[0] = false;
                    Bitmap bitmap = cropViewModel.getImageBitmap().getValue();
                    if (bitmap != null) performOCR();
                } else if (ocrViewModel.getState().getValue() != null
                        && ocrViewModel.getState().getValue().imageProcessed()) {
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
     * Returns the list of available languages
     */
    private String[] getAvailableLanguages() {
        String[] langs = ocrHelper.getAvailableLanguages();
        if (langs == null || langs.length == 0) return new String[]{"eng", "deu", "fra", "ita", "spa"};
        return langs;
    }

    /**
     * Performs OCR on the current image
     */
    private void performOCR() {
        Bitmap imageBitmap = cropViewModel.getImageBitmap().getValue();
        if (imageBitmap == null) {
            UIUtils.showToast(requireContext(), "No image to process", Toast.LENGTH_SHORT);
            return;
        }
        if (!ocrHelper.isTesseractInitialized()) {
            UIUtils.showToast(requireContext(), "Tesseract not initialized. Please restart the app.", Toast.LENGTH_LONG);
            ocrViewModel.finishError("Engine not initialized");
            return;
        }

        ocrViewModel.startProcessing();

        new Thread(() -> {
            long t0 = System.nanoTime();
            try {
                Log.d(TAG, "performOCR: Pre-scaling image to A4 dimensions before OCR");
                Bitmap scaledBitmap = ImageScaler.scaleToA4(imageBitmap);

                // calculate transform
                OCRViewModel.OcrTransform tx = new OCRViewModel.OcrTransform(
                        imageBitmap.getWidth(), imageBitmap.getHeight(),
                        scaledBitmap.getWidth(), scaledBitmap.getHeight(),
                        scaledBitmap.getWidth() / (float) imageBitmap.getWidth(),
                        scaledBitmap.getHeight() / (float) imageBitmap.getHeight(),
                        0, 0
                );

                // UI-Thread: set bitmap and transform
                internalImageUpdate.set(true);
                requireActivity().runOnUiThread(() -> {
                    if (!isAdded() || binding == null) return;
                    cropViewModel.setImageBitmap(scaledBitmap);
                    ocrViewModel.setTransform(tx); // UI-Thread
                });

                // OCR with word boxes
                OCRHelper.OcrResultWords r = ocrHelper.runOcrWithWords(scaledBitmap);
                long durMs = (System.nanoTime() - t0) / 1_000_000L;

                String finalText = (r.text == null || r.text.trim().isEmpty())
                        ? getString(R.string.ocr_results_will_appear_here)
                        : r.text;

                List<RecognizedWord> words = (r.words != null) ? r.words : new ArrayList<>();

                // UI-Thread: Result
                requireActivity().runOnUiThread(() -> {
                    if (!isAdded() || binding == null) return;
                    ocrViewModel.setWords(words);
                    ocrViewModel.finishSuccess(finalText, words, durMs, r.meanConfidence, tx);
                });

            } catch (Exception e) {
                requireActivity().runOnUiThread(() -> {
                    if (!isAdded() || binding == null) return;
                    ocrViewModel.finishError(e.getMessage() != null ? e.getMessage() : e.toString());
                });
            }
        }).start();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (ocrHelper != null) ocrHelper.shutdown();
        binding = null;
    }

    /**
     * Maps the system language to the language used by Tesseract
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
