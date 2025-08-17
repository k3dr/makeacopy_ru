package de.schliweb.makeacopy.ui.ocr;

import android.graphics.Bitmap;
import android.graphics.Matrix;
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
 * OCRFragment handles the Optical Character Recognition (OCR) functionality within the application.
 * This fragment is responsible for managing the lifecycle, user interface, and integration with OCR processing logic.
 * It interacts with OCRViewModel and CropViewModel to manage image processing states and updates.
 * The OCRHelper class is used to perform OCR on given images.
 */
public class OCRFragment extends Fragment {
    private static final String TAG = "OCRFragment";
    private FragmentOcrBinding binding;
    private OCRViewModel ocrViewModel;
    private CropViewModel cropViewModel;
    private OCRHelper ocrHelper;
    private final AtomicBoolean internalImageUpdate = new AtomicBoolean(false);

    /**
     * Inflates and initializes the OCRFragment's view and its components.
     * Sets up the ViewModel observers, initializes the OCR engine, assigns
     * UI event handlers, and applies window insets for proper layout adjustments.
     *
     * @param inflater           The LayoutInflater object that can be used to inflate
     *                           any views in the fragment.
     * @param container          The parent view to which the fragment's UI should be attached,
     *                           or null if it should not be attached to any parent.
     * @param savedInstanceState If non-null, this fragment is being re-created from
     *                           a previous saved state as given here.
     * @return The root View for the fragment's UI, or null if no UI is provided.
     */
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
     * Initializes and configures the language selection spinner for the OCR functionality.
     * <p>
     * The spinner is populated with the list of available OCR languages retrieved from the
     * `getAvailableLanguages()` method and sets the system language as the default selected option if it is
     * available. If the system language is not available, the spinner defaults to the first language in the list.
     * <p>
     * Sets a listener to handle language selection events. When a new language is selected, it validates
     * the language availability using `ocrHelper.isLanguageAvailable`, updates the OCR engine configuration,
     * and triggers appropriate UI updates or OCR processing events based on the selection state.
     * <p>
     * If the selected language is not available, the spinner reverts to the default language, and a toast
     * message is shown to notify the user about the unavailability of the selected language.
     * <p>
     * The selected language is applied to the OCR engine using `ocrHelper.setLanguage`, and default settings
     * for the language are configured using `ocrHelper.applyDefaultsForLanguage`. The OCR whitelist is updated
     * using `OCRWhitelist.getWhitelistForLangSpec`.
     * <p>
     * For the first language selection during the fragment's lifecycle, if an image is already loaded, the OCR
     * operation is triggered immediately by calling `performOCR`. On subsequent selections, the UI is updated
     * to allow the user to manually trigger OCR once the language is changed.
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
     * Retrieves the list of available OCR languages based on the OCR engine's language data.
     * If no languages are available, a default set of languages is returned.
     *
     * @return An array of strings representing the available OCR languages.
     * If unavailable, the default languages are ["eng", "deu", "fra", "ita", "spa"].
     */
    private String[] getAvailableLanguages() {
        String[] langs = ocrHelper.getAvailableLanguages();
        if (langs == null || langs.length == 0) return new String[]{"eng", "deu", "fra", "ita", "spa"};
        return langs;
    }

    /**
     * Executes the Optical Character Recognition (OCR) process on the currently loaded image.
     * <p>
     * This method performs multiple tasks including:
     * 1. Verifies if an image is available for processing and if the OCR engine is properly initialized.
     * 2. Prepares the image by scaling it to A4 dimensions.
     * 3. Updates the user interface with the scaled image and transformation details.
     * 4. Runs the OCR process in a background thread to ensure smooth UI performance.
     * 5. Processes the OCR results, including recognized text and word box data, and updates
     * the ViewModel with the results.
     * 6. Handles exceptions and errors by notifying the ViewModel and displaying appropriate messages.
     * <p>
     * The method checks the initialization state of the OCR engine and the presence of a valid image to
     * process. If any of these conditions are not met, the method returns early with an appropriate user
     * notification.
     * <p>
     * Time taken for the OCR process is captured and included in the result. The recognized text, word boxes,
     * and confidence levels are processed and provided to the ViewModel for further handling.
     * <p>
     * This method uses multi-threading to ensure that computationally intensive OCR processing does not block
     * the main thread, and UI updates are performed on the main/UI thread as required.
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

                Log.d(TAG, "performOCR: Preparing image for OCR - change orientation");
                Bitmap src = imageBitmap;

                int capDeg = 0;
                Integer v = cropViewModel.getCaptureRotationDegrees().getValue();
                if (v != null) capDeg = v;

                int rotateCW = (360 - (capDeg % 360)) % 360;
                if (rotateCW != 0) {
                    src = rotateBitmap(src, rotateCW);
                }

                Log.d(TAG, "performOCR: Pre-scaling image to A4 dimensions before OCR");
                Bitmap scaledBitmap = ImageScaler.scaleToA4(src);

                // calculate transform
                OCRViewModel.OcrTransform tx = new OCRViewModel.OcrTransform(
                        src.getWidth(), src.getHeight(),
                        scaledBitmap.getWidth(), scaledBitmap.getHeight(),
                        scaledBitmap.getWidth() / (float) src.getWidth(),
                        scaledBitmap.getHeight() / (float) src.getHeight(),
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
     * Maps a system language code to the corresponding Tesseract language code.
     *
     * @param systemLanguage The system language code (e.g., "en", "de", "fr").
     * @return The Tesseract language code corresponding to the provided system language.
     * Defaults to "eng" if the system language is not explicitly mapped.
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

    /**
     * Rotates the given Bitmap by the specified degree in a clockwise direction.
     * If the degrees are a multiple of 360, the original Bitmap is returned unchanged.
     *
     * @param src       The Bitmap to be rotated. Must not be null.
     * @param degreesCW The number of degrees to rotate the Bitmap clockwise.
     *                  Values outside the range [0, 360) will be normalized.
     * @return A new rotated Bitmap object, or the original Bitmap if no rotation is applied.
     */
    private static Bitmap rotateBitmap(Bitmap src, int degreesCW) {
        if (degreesCW % 360 == 0) return src;
        Matrix m = new Matrix();
        m.postRotate(degreesCW);
        Bitmap out = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
        return out;
    }
}
