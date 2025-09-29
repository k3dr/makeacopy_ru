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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

/**
 * OCRFragment handles the Optical Character Recognition (OCR) functionality within the application.
 * This fragment manages UI and orchestration; the actual OCR work is done on a dedicated single-thread executor
 * with a fresh TessBaseAPI instance per job to ensure thread-safety.
 * <p>
 * Flow: Crop -> (optional) User Rotation -> OCR -> Export
 */
public class OCRFragment extends Fragment {
    private static final String TAG = "OCRFragment";

    private FragmentOcrBinding binding;
    private OCRViewModel ocrViewModel;
    private CropViewModel cropViewModel;

    // Language helper for listing/availability checks (no long-lived TessBaseAPI instance)
    private OCRHelper langHelper;

    // Concurrency: serialize OCR jobs, 1 job ↔ 1 TessBaseAPI instance
    private final ExecutorService ocrExecutor = Executors.newSingleThreadExecutor();
    private volatile Future<?> runningOcr = null;
    private final AtomicBoolean ocrCancelled = new AtomicBoolean(false);

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        ocrViewModel = new ViewModelProvider(requireActivity()).get(OCRViewModel.class);
        cropViewModel = new ViewModelProvider(requireActivity()).get(CropViewModel.class);
        binding = FragmentOcrBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        // Language helper (no initTesseract() here!)
        langHelper = new OCRHelper(requireContext().getApplicationContext());

        // State observer
        ocrViewModel.getState().observe(getViewLifecycleOwner(), state -> {
            boolean canProceed = state.imageProcessed() && !state.processing();
            binding.buttonProcess.setEnabled(canProceed);
            binding.buttonProcess.setText(R.string.next);

            binding.textOcr.setText(state.processing()
                    ? getString(R.string.processing_image)
                    : (state.imageProcessed()
                    ? getString(R.string.ocr_processing_complete_tap_the_button_to_proceed_to_export)
                    : getString(R.string.no_image_processed_crop_an_image_first)));

            binding.ocrResultText.setText((state.ocrText() == null || state.ocrText().isEmpty())
                    ? getString(R.string.ocr_results_will_appear_here)
                    : state.ocrText());

            // Proceed to Export
            binding.buttonProcess.setOnClickListener(v ->
                    Navigation.findNavController(requireView()).navigate(R.id.navigation_export));
        });

        // Error events
        ocrViewModel.getErrorEvents().observe(getViewLifecycleOwner(), ev -> {
            if (ev == null) return;
            String msg = ev.getContentIfNotHandled();
            if (msg != null) UIUtils.showToast(requireContext(), "OCR failed: " + msg, Toast.LENGTH_LONG);
        });

        // When image changes in Crop VM, reset OCR state (no write-back during OCR)
        cropViewModel.getImageBitmap().observe(getViewLifecycleOwner(), bitmap -> {
            if (bitmap != null) {
                ocrViewModel.resetForNewImage();
            }
        });

        // Insets (status bar)
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            int topInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
            ViewGroup.MarginLayoutParams textParams =
                    (ViewGroup.MarginLayoutParams) binding.textOcr.getLayoutParams();
            textParams.topMargin = (int) (8 * getResources().getDisplayMetrics().density) + topInset;
            binding.textOcr.setLayoutParams(textParams);
            return insets;
        });

        // Bottom inset for button container
        UIUtils.adjustMarginForSystemInsets(binding.buttonContainer, 12);
        ViewCompat.setOnApplyWindowInsetsListener(binding.buttonContainer, (v, insets) -> {
            UIUtils.adjustMarginForSystemInsets(binding.buttonContainer, 12);
            return insets;
        });

        // Back navigates up (to Crop)
        if (binding.buttonBack != null) {
            binding.buttonBack.setOnClickListener(v ->
                    Navigation.findNavController(requireView()).navigateUp());
        }

        // Language selection
        setupLanguageSpinner();

        return root;
    }

    /**
     * Language spinner now only updates ViewModel language and UI.
     * We do NOT touch any long-lived TessBaseAPI here.
     */
    private void setupLanguageSpinner() {
        Spinner spinner = binding.languageSpinner;
        String[] availableLanguages = getAvailableLanguages();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, availableLanguages);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);

        String systemLang = OCRUtils.mapSystemLanguageToTesseract(java.util.Locale.getDefault().getLanguage());
        int defaultPos = IntStream.range(0, availableLanguages.length)
                .filter(i -> availableLanguages[i].equals(systemLang)).findFirst().orElse(0);
        spinner.setSelection(defaultPos);

        final boolean[] firstSelection = {true};
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                String lang = (String) parent.getItemAtPosition(pos);

                if (!isLanguageAvailableSafe(lang)) {
                    UIUtils.showToast(requireContext(), "Language " + lang + " not available.", Toast.LENGTH_LONG);
                    spinner.setSelection(defaultPos, false);
                    return;
                }

                // Only update ViewModel; real OCR init happens inside the OCR job per run
                ocrViewModel.setLanguage(lang);

                if (firstSelection[0]) {
                    firstSelection[0] = false;
                    Bitmap bitmap = cropViewModel.getImageBitmap().getValue();
                    if (bitmap != null) performOCR();
                } else if (ocrViewModel.getState().getValue() != null
                        && ocrViewModel.getState().getValue().imageProcessed()) {
                    // Allow re-run with new language
                    binding.buttonProcess.setText(R.string.btn_process);
                    binding.buttonProcess.setOnClickListener(v -> performOCR());
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) { /* no-op */ }
        });
    }

    private boolean isLanguageAvailableSafe(String lang) {
        try {
            return langHelper != null && langHelper.isLanguageAvailable(lang);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Get available languages without keeping a long-lived TessBaseAPI.
     */
    private String[] getAvailableLanguages() {
        try {
            if (langHelper != null) {
                String[] langs = langHelper.getAvailableLanguages();
                if (langs != null && langs.length > 0) return langs;
            }
        } catch (Throwable ignore) {
        }
        // Fallback includes Chinese (Simplified and Traditional) so users on zh locales can select them when asset listing fails
        return OCRUtils.getLanguages();
    }

    /**
     * Executes OCR in a single-thread executor with a fresh TessBaseAPI per job.
     * Rotation handling: capture-rotation compensation, then user rotation (after crop, before OCR).
     * No write-back to CropViewModel from OCR thread.
     */
    private void performOCR() {
        if (ocrExecutor.isShutdown()) {
            UIUtils.showToast(requireContext(), "Screen is closing, cannot start OCR", Toast.LENGTH_SHORT);
            return;
        }

        Bitmap imageBitmap = cropViewModel.getImageBitmap().getValue();
        if (imageBitmap == null) {
            UIUtils.showToast(requireContext(), "No image to process", Toast.LENGTH_SHORT);
            return;
        }

        // Prevent parallel runs
        if (runningOcr != null && !runningOcr.isDone()) {
            UIUtils.showToast(requireContext(), "OCR already running", Toast.LENGTH_SHORT);
            return;
        }

        ocrViewModel.startProcessing();
        ocrCancelled.set(false);

        try {
            runningOcr = ocrExecutor.submit(() -> {
                long t0 = System.nanoTime();
                OCRHelper localHelper = null;
                try {
                    // Prepare bitmap (orientation corrections)
                    Log.d(TAG, "performOCR: Preparing image for OCR - change orientation");
                    Bitmap src = imageBitmap;

                    int capDeg = 0;
                    Integer v = cropViewModel.getCaptureRotationDegrees().getValue();
                    if (v != null) capDeg = v;

                    int rotateCW = (360 - (capDeg % 360)) % 360;
                    if (rotateCW != 0) {
                        src = rotateBitmap(src, rotateCW);
                    }

                    // Apply user-requested rotation (after crop, before OCR)
                    int userDeg = 0;
                    Integer ur = cropViewModel.getUserRotationDegrees().getValue();
                    if (ur != null) userDeg = ((ur % 360) + 360) % 360;
                    if (userDeg != 0) {
                        src = rotateBitmap(src, userDeg);
                    }

                    Log.d(TAG, "performOCR: Pre-scaling image to A4 dimensions before OCR");
                    Bitmap scaledBitmap = ImageScaler.scaleToA4(src);
                    // Optional robustness: feed immutable ARGB_8888 copy to Tesseract
                    Bitmap inputForOcr = scaledBitmap.copy(Bitmap.Config.ARGB_8888, /*mutable*/ false);

                    // Build transform (used for word boxes mapping); do not write scaled bitmap back to Crop VM
                    OCRViewModel.OcrTransform tx = new OCRViewModel.OcrTransform(
                            src.getWidth(), src.getHeight(),
                            scaledBitmap.getWidth(), scaledBitmap.getHeight(),
                            scaledBitmap.getWidth() / (float) src.getWidth(),
                            scaledBitmap.getHeight() / (float) src.getHeight(),
                            0, 0
                    );

                    // Push transform to VM on UI thread
                    runOnUiThreadSafe(() -> ocrViewModel.setTransform(tx));

                    if (ocrCancelled.get()) {
                        postError("Cancelled");
                        return;
                    }

                    // Fresh Tesseract per job
                    localHelper = new OCRHelper(requireContext().getApplicationContext());

                    String lang = ocrViewModel.getLanguage().getValue();
                    if (lang == null || lang.isEmpty()) lang = "eng";

                    try {
                        // Ensure language is set BEFORE init so Tesseract loads the correct traineddata
                        localHelper.setLanguage(lang);
                    } catch (Throwable t) {
                        Log.e(TAG, "Failed to set language " + lang + ": " + t);
                    }

                    if (!localHelper.initTesseract()) {
                        postError("Engine not initialized");
                        return;
                    }

                    // OCR with words
                    OCRHelper.OcrResultWords r = localHelper.runOcrWithWords(inputForOcr);

                    if (ocrCancelled.get()) {
                        postError("Cancelled");
                        return;
                    }

                    long durMs = (System.nanoTime() - t0) / 1_000_000L;
                    String finalText = (r.text == null || r.text.trim().isEmpty())
                            ? getString(R.string.ocr_results_will_appear_here)
                            : r.text;
                    List<RecognizedWord> words = (r.words != null) ? r.words : new ArrayList<>();

                    runOnUiThreadSafe(() -> {
                        ocrViewModel.setWords(words);
                        ocrViewModel.finishSuccess(finalText, words, durMs, r.meanConfidence, tx);
                    });

                } catch (Throwable e) {
                    postError(e.getMessage() != null ? e.getMessage() : e.toString());
                } finally {
                    // Release Tesseract in the same thread that used it
                    try {
                        if (localHelper != null) localHelper.shutdown();
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            UIUtils.showToast(requireContext(), "OCR service is shutting down", Toast.LENGTH_SHORT);
            ocrViewModel.finishError("Executor shutdown");
        }
    }

    private void postError(String msg) {
        runOnUiThreadSafe(() -> {
            ocrViewModel.finishError(msg);
        });
    }

    private void runOnUiThreadSafe(Runnable r) {
        if (!isAdded()) return;
        try {
            requireActivity().runOnUiThread(() -> {
                if (!isAdded() || binding == null) return;
                r.run();
            });
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Signal cancel; do NOT forcibly interrupt the running job (avoid tearing down Tesseract mid-call)
        ocrCancelled.set(true);

        binding = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Fragment is going away for good: now it's safe to shut down the executor
        ocrExecutor.shutdown();
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
        int deg = ((degreesCW % 360) + 360) % 360;
        if (deg == 0) return src;
        Matrix m = new Matrix();
        m.postRotate(deg);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
    }
}
