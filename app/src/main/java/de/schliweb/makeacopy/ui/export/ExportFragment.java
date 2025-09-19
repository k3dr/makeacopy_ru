package de.schliweb.makeacopy.ui.export;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavOptions;
import androidx.navigation.Navigation;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.databinding.FragmentExportBinding;
import de.schliweb.makeacopy.ui.camera.CameraViewModel;
import de.schliweb.makeacopy.ui.crop.CropViewModel;
import de.schliweb.makeacopy.ui.ocr.OCRViewModel;
import de.schliweb.makeacopy.utils.*;
import de.schliweb.makeacopy.utils.jpeg.JpegExportOptions;
import de.schliweb.makeacopy.utils.jpeg.JpegExporter;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * ExportFragment is a UI component that extends Fragment and facilitates exporting
 * scanned or captured documents in various formats such as PDF or TXT.
 * It integrates with ViewModel classes to manage and interact with export logic,
 * OCR text, and camera functionalities.
 * <p>
 * Fields:
 * - TAG: A string tag used for logging.
 * - binding: Represents view binding used for interacting with the fragment's layout.
 * - exportViewModel: Manages the state and logic related to exporting documents.
 * - cropViewModel: Handles the cropping functionality of the document.
 * - ocrViewModel: Manages Optical Character Recognition (OCR) results and data.
 * - cameraViewModel: Manages camera operations and captures.
 * - createDocumentLauncher: ActivityResultLauncher for creating a document.
 * - createTxtDocumentLauncher: ActivityResultLauncher for creating TXT files.
 * - lastExportedDocumentUri: The URI of the last exported document for reference.
 * - lastExportedPdfName: The name of the last exported PDF document.
 * <p>
 * Methods:
 * - onCreateView: Inflates the fragment's layout, initializes ViewModels, sets up event listeners,
 * and manages shared preferences.
 * - checkDocumentReady: Validates if the document is ready for export.
 * - performExport: Executes the export operation based on the specified configurations.
 * - selectFileLocation: Opens the file chooser to select the export file location.
 * - launchTxtFileCreation: Launches the dialog for creating a TXT file.
 * - exportOcrTextToTxt: Exports OCR text data to a specified TXT file.
 * - shareDocument: Facilitates sharing the exported document.
 * - onDestroyView: Handles cleanup tasks when the fragment's view is destroyed.
 * - getOcrTextFromState: Retrieves the OCR text content from the application state.
 * - getOcrWordsFromState: Retrieves a list of recognized OCR words from the application state.
 */
public class ExportFragment extends Fragment {
    private static final String TAG = "ExportFragment";

    private FragmentExportBinding binding;
    private ExportViewModel exportViewModel;
    private CropViewModel cropViewModel;
    private OCRViewModel ocrViewModel;
    private CameraViewModel cameraViewModel;

    private ActivityResultLauncher<String> createDocumentLauncher;
    private ActivityResultLauncher<String> createTxtDocumentLauncher;
    private ActivityResultLauncher<String> createJpegDocumentLauncher;

    // URI of the last exported document for sharing
    private Uri lastExportedDocumentUri;
    private String lastExportedPdfName;

    // Main thread handler for safe UI updates
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    /**
     * Posts a runnable to the main thread only if the Fragment is still added and the view binding exists.
     * If called on the main thread, runs immediately; otherwise posts to main.
     */
    private void postToUiSafe(@NonNull Runnable action) {
        if (!isAdded() || binding == null) return;
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            action.run();
        } else {
            mainHandler.post(() -> {
                if (!isAdded() || binding == null) return;
                action.run();
            });
        }
    }

    /**
     * Creates and initializes the view hierarchy associated with this fragment.
     * This method handles view inflation, view model setup, event listeners, and initializes
     * shared preferences for maintaining user selections.
     *
     * @param inflater           The LayoutInflater object that can be used to inflate any views in the fragment.
     * @param container          If non-null, this is the parent view that the fragment's UI should be attached to.
     *                           The fragment should not add the view itself, but this can be used to generate
     *                           the LayoutParams of the view.
     * @param savedInstanceState If non-null, this fragment is being re-constructed from
     *                           a previous saved state as given here.
     * @return The root view of the fragment's layout that has been created and initialized.
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentExportBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        Context context = requireContext();
        String prefsName = "export_options";
        android.content.SharedPreferences prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE);

        boolean includeOcr = prefs.getBoolean("include_ocr", false);
        boolean convertToGrayscale = prefs.getBoolean("convert_to_grayscale", false);
        boolean exportAsJpeg = prefs.getBoolean("export_as_jpeg", false);
        binding.checkboxIncludeOcr.setChecked(includeOcr);
        binding.checkboxGrayscale.setChecked(convertToGrayscale);
        binding.checkboxExportJpeg.setChecked(exportAsJpeg);

        // Initialize JPEG mode checkboxes from saved preference (default AUTO)
        String savedModeName = prefs.getString("jpeg_mode", JpegExportOptions.Mode.AUTO.name());
        JpegExportOptions.Mode savedMode;
        try {
            savedMode = JpegExportOptions.Mode.valueOf(savedModeName);
        } catch (IllegalArgumentException ex) {
            savedMode = JpegExportOptions.Mode.AUTO;
        }
        if (binding.checkboxJpegNone != null)
            binding.checkboxJpegNone.setChecked(savedMode == JpegExportOptions.Mode.NONE);
        if (binding.checkboxJpegAuto != null)
            binding.checkboxJpegAuto.setChecked(savedMode == JpegExportOptions.Mode.AUTO);
        if (binding.checkboxJpegBwText != null)
            binding.checkboxJpegBwText.setChecked(savedMode == JpegExportOptions.Mode.BW_TEXT);

        // Initial visibility: show JPEG modes only when exporting as JPEG; hide grayscale then.
        binding.jpegModeGroup.setVisibility(exportAsJpeg ? View.VISIBLE : View.GONE);
        binding.checkboxGrayscale.setVisibility(exportAsJpeg ? View.GONE : View.VISIBLE);

        // ViewModel
        exportViewModel = new ViewModelProvider(this).get(ExportViewModel.class);
        exportViewModel.setIncludeOcr(includeOcr);
        exportViewModel.setConvertToGrayscale(convertToGrayscale);
        exportViewModel.setExportFormat(exportAsJpeg ? "JPEG" : "PDF");

        // If user chose to skip OCR earlier (Camera screen), hide the option and force export without OCR
        boolean skipOcr = prefs.getBoolean("skip_ocr", false);
        if (skipOcr) {
            if (binding.checkboxIncludeOcr != null) {
                binding.checkboxIncludeOcr.setVisibility(View.GONE);
                binding.checkboxIncludeOcr.setChecked(false);
            }
            exportViewModel.setIncludeOcr(false);
            // Persist include_ocr=false to avoid accidental TXT export prompts
            prefs.edit().putBoolean("include_ocr", false).apply();
        }

        // Insets
        ViewCompat.setOnApplyWindowInsetsListener(binding.exportOptionsGroup, (v, insets) -> {
            UIUtils.adjustMarginForSystemInsets(binding.exportOptionsGroup, 8); // 8dp extra Abstand
            return insets;
        });

        cropViewModel = new ViewModelProvider(requireActivity()).get(CropViewModel.class);
        ocrViewModel = new ViewModelProvider(requireActivity()).get(OCRViewModel.class);
        cameraViewModel = new ViewModelProvider(requireActivity()).get(CameraViewModel.class);

        createDocumentLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("application/pdf"), uri -> {
            Log.d(TAG, "createDocumentLauncher: Document creation result received");
            if (uri != null) {
                String displayName = FileUtils.getDisplayNameFromUri(requireContext(), uri);
                exportViewModel.setSelectedFileLocation(uri);
                exportViewModel.setSelectedFileLocationName(displayName);
                lastExportedPdfName = displayName;
                performExport();
            } else {
                Log.d(TAG, "createDocumentLauncher: User cancelled document creation");
            }
        });

        createTxtDocumentLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("text/plain"), uri -> {
            if (uri != null) {
                String displayName = FileUtils.getDisplayNameFromUri(requireContext(), uri);
                Log.d(TAG, "createTxtDocumentLauncher: Display name from URI: " + displayName);
                exportOcrTextToTxt(uri);
            } else {
                Log.d(TAG, "createTxtDocumentLauncher: User cancelled TXT document creation");
            }
        });

        createJpegDocumentLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("image/jpeg"), uri -> {
            Log.d(TAG, "createJpegDocumentLauncher: JPEG creation result received");
            if (uri != null) {
                String displayName = FileUtils.getDisplayNameFromUri(requireContext(), uri);
                exportViewModel.setSelectedFileLocation(uri);
                exportViewModel.setSelectedFileLocationName(displayName);
                performJpegExport();
            } else {
                Log.d(TAG, "createJpegDocumentLauncher: User cancelled JPEG document creation");
            }
        });

        // Back-Handling
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                cameraViewModel.setImageUri(null);
                cropViewModel.setImageCropped(false);
                cropViewModel.setImageBitmap(null);
                cropViewModel.setOriginalImageBitmap(null);
                cropViewModel.setImageLoaded(false);
                NavOptions navOptions = new NavOptions.Builder().setPopUpTo(R.id.navigation_camera, true).build();
                Navigation.findNavController(requireView()).navigate(R.id.navigation_camera, null, navOptions);
            }
        });

        exportViewModel.getText().observe(getViewLifecycleOwner(), binding.textExport::setText);

        binding.checkboxIncludeOcr.setOnCheckedChangeListener((button, checked) -> {
            exportViewModel.setIncludeOcr(checked);
            prefs.edit().putBoolean("include_ocr", checked).apply();
        });
        binding.checkboxGrayscale.setOnCheckedChangeListener((button, checked) -> {
            exportViewModel.setConvertToGrayscale(checked);
            prefs.edit().putBoolean("convert_to_grayscale", checked).apply();
        });
        binding.checkboxExportJpeg.setOnCheckedChangeListener((button, checked) -> {
            exportViewModel.setExportFormat(checked ? "JPEG" : "PDF");
            prefs.edit().putBoolean("export_as_jpeg", checked).apply();
            // Toggle UI groups: JPEG modes vs PDF grayscale
            binding.jpegModeGroup.setVisibility(checked ? View.VISIBLE : View.GONE);
            binding.checkboxGrayscale.setVisibility(checked ? View.GONE : View.VISIBLE);
        });

        // Enforce mutual exclusivity for JPEG mode checkboxes and persist selection
        View.OnClickListener jpegModeClick = v -> {
            if (v == binding.checkboxJpegNone) {
                binding.checkboxJpegAuto.setChecked(false);
                binding.checkboxJpegBwText.setChecked(false);
                prefs.edit().putString("jpeg_mode", JpegExportOptions.Mode.NONE.name()).apply();
            } else if (v == binding.checkboxJpegAuto) {
                binding.checkboxJpegNone.setChecked(false);
                binding.checkboxJpegBwText.setChecked(false);
                prefs.edit().putString("jpeg_mode", JpegExportOptions.Mode.AUTO.name()).apply();
            } else if (v == binding.checkboxJpegBwText) {
                binding.checkboxJpegNone.setChecked(false);
                binding.checkboxJpegAuto.setChecked(false);
                prefs.edit().putString("jpeg_mode", JpegExportOptions.Mode.BW_TEXT.name()).apply();
            }
            // Ensure at least one is selected; if user unticked all somehow, default to AUTO
            if (!binding.checkboxJpegNone.isChecked() && !binding.checkboxJpegAuto.isChecked() && !binding.checkboxJpegBwText.isChecked()) {
                binding.checkboxJpegAuto.setChecked(true);
                prefs.edit().putString("jpeg_mode", JpegExportOptions.Mode.AUTO.name()).apply();
            }
        };
        binding.checkboxJpegNone.setOnClickListener(jpegModeClick);
        binding.checkboxJpegAuto.setOnClickListener(jpegModeClick);
        binding.checkboxJpegBwText.setOnClickListener(jpegModeClick);

        binding.buttonExport.setOnClickListener(v -> {
            boolean asJpeg = binding.checkboxExportJpeg.isChecked();
            if (asJpeg) {
                selectJpegFileLocation();
            } else {
                selectFileLocation();
            }
        });
        binding.buttonShare.setOnClickListener(v -> shareDocument());

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            UIUtils.adjustTextViewTopMarginForStatusBar(binding.textExport, 8);
            UIUtils.adjustMarginForSystemInsets(binding.exportOptionsGroup, 80);
            return insets;
        });

        exportViewModel.isDocumentReady().observe(getViewLifecycleOwner(), ready -> {
            binding.buttonExport.setEnabled(ready);
            binding.buttonShare.setEnabled(false); // erst nach Export aktiv
            binding.textExport.setText(ready ? R.string.document_ready_for_export
                    : R.string.no_document_ready_process_ocr_first);
        });

        exportViewModel.getDocumentBitmap().observe(getViewLifecycleOwner(), bitmap -> {
            if (bitmap != null) {
                binding.documentPreview.setImageBitmap(bitmap);
                binding.documentPreview.setVisibility(View.VISIBLE);
            } else {
                binding.documentPreview.setVisibility(View.INVISIBLE);
            }
        });

        checkDocumentReady();

        return root;
    }

    /**
     * Checks and determines whether the document is ready for export.
     * This method evaluates the current state of the cropped bitmap and OCR text, updates the
     * corresponding fields in the exportViewModel, and sets the document ready status accordingly.
     * <p>
     * - Retrieves the cropped bitmap from the cropViewModel. If it exists, it sets the document bitmap
     * in the exportViewModel.
     * - Extracts the OCR text from the current state. If available, it updates the exportViewModel with
     * the extracted OCR text.
     * - Updates the document ready status in the exportViewModel. The document is considered ready
     * if the document bitmap is not null.
     */
    private void checkDocumentReady() {
        // Only consider the document ready if the image has been cropped (perspective-corrected)
        Boolean isCropped = cropViewModel.isImageCropped().getValue();
        Bitmap maybeBitmap = cropViewModel.getImageBitmap().getValue();

        if (Boolean.TRUE.equals(isCropped) && maybeBitmap != null) {
            exportViewModel.setDocumentBitmap(maybeBitmap);
            exportViewModel.setDocumentReady(true);
        } else {
            // Prevent exporting the original, un-cropped image
            exportViewModel.setDocumentBitmap(null);
            exportViewModel.setDocumentReady(false);
        }

        // OCR text (if present) can still be shown/prepared independently
        String ocrText = getOcrTextFromState();
        if (ocrText != null) exportViewModel.setOcrText(ocrText);
    }

    /**
     * Initiates the export process for the current document. This method handles the generation
     * of a PDF with optional OCR content, allows customization options such as grayscale conversion,
     * and manages user-selected file locations.
     * <p>
     * If the required document bitmap is not available, it shows a warning message to the user and
     * cancels the export process. The method also supports the generation of plain text files
     * containing OCR data if enabled.
     * <p>
     * Upon successful export, the resulting file URI and metadata (such as display name) are updated
     * and made available for further actions like sharing. In case of export failure, an error
     * message is displayed, and necessary fields are cleared.
     * <p>
     * The export process runs on a background thread to avoid blocking the UI thread, and state updates
     * such as export status and generated file paths are synchronized with the UI thread.
     * <p>
     * Steps performed in this method include:
     * - Fetching the current document and ensuring it is ready for export.
     * - Checking and applying user preferences for grayscale conversion and OCR inclusion.
     * - Creating a searchable PDF (or notifying the user of failure if the process fails).
     * - Optionally triggering subsequent TXT creation if OCR data is included in the export.
     * - Updating UI elements based on the success or failure of the export.
     * <p>
     * Error handling ensures that unexpected failures are logged, and user feedback is provided
     * through toast messages and UI updates.
     */
    private void performExport() {
        Log.d(TAG, "performExport: Starting export process");

        final Bitmap documentBitmap = exportViewModel.getDocumentBitmap().getValue();
        if (documentBitmap == null) {
            UIUtils.showToast(requireContext(), "No document to export", Toast.LENGTH_SHORT);
            return;
        }

        boolean includeOcr = Boolean.TRUE.equals(exportViewModel.isIncludeOcr().getValue());
        boolean convertToGrayscale = Boolean.TRUE.equals(exportViewModel.isConvertToGrayscale().getValue());
        Uri selectedLocation = exportViewModel.getSelectedFileLocation().getValue();

        List<RecognizedWord> recognizedWords;
        if (includeOcr) {
            List<RecognizedWord> words = getOcrWordsFromState();
            if (words != null && !words.isEmpty()) recognizedWords = words;
            else {
                recognizedWords = null;
            }
        } else {
            recognizedWords = null;
        }

        final Context appContext = requireContext().getApplicationContext();
        exportViewModel.setTxtExportUri(null);
        exportViewModel.setExporting(true);

        new Thread(() -> {
            try {
                int jpegQuality = 75;
                Uri exportUri = PdfCreator.createSearchablePdf(
                        appContext,
                        documentBitmap,
                        recognizedWords,
                        selectedLocation,
                        jpegQuality,
                        convertToGrayscale
                );

                postToUiSafe(() -> {
                    if (exportUri != null) {
                        lastExportedDocumentUri = exportUri;
                        String displayName = FileUtils.getDisplayNameFromUri(requireContext(), lastExportedDocumentUri);
                        lastExportedPdfName = displayName;
                        binding.buttonShare.setEnabled(true);
                        UIUtils.showToast(appContext, "Document " + lastExportedPdfName + " exported", Toast.LENGTH_LONG);

                        if (includeOcr) {
                            launchTxtFileCreation();
                        }
                    } else {
                        lastExportedDocumentUri = null;
                        exportViewModel.setTxtExportUri(null);
                        binding.buttonShare.setEnabled(false);
                        UIUtils.showToast(appContext, "Failed to export document", Toast.LENGTH_SHORT);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error during export", e);
                postToUiSafe(() -> {
                    lastExportedDocumentUri = null;
                    exportViewModel.setTxtExportUri(null);
                    binding.buttonShare.setEnabled(false);
                    UIUtils.showToast(appContext, "Error during export: " + e.getMessage(), Toast.LENGTH_SHORT);
                });
            } finally {
                postToUiSafe(() -> exportViewModel.setExporting(false));
            }
        }).start();
    }

    /**
     * Handles the selection of the file location and initiates the document creation process.
     * <p>
     * This method generates a default file name with the current timestamp in the format
     * "yyyyMMdd_HHmmss" combined with a "DOC_" prefix and a ".pdf" suffix. The generated
     * file name is passed to the document creation launcher, which prompts the user to
     * select a location for saving the file. The selected location can subsequently be used
     * for exporting or saving the generated PDF document.
     */
    private void selectFileLocation() {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String defaultFileName = "DOC_" + timeStamp + ".pdf";
        createDocumentLauncher.launch(defaultFileName);
    }

    /**
     * Launches SAF CreateDocument for JPEG export with default filename.
     */
    private void selectJpegFileLocation() {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String defaultFileName = "DOC_" + timeStamp + ".jpg";
        createJpegDocumentLauncher.launch(defaultFileName);
    }

    /**
     * Performs JPEG export using the already perspective-corrected bitmap and JpegExporter.
     * MVP: uses default options (quality=85, original size, no enhancement).
     */
    private void performJpegExport() {
        // Determine JPEG mode directly from UI checkboxes; fallback to saved preference or AUTO
        Context context = requireContext();
        android.content.SharedPreferences prefs = context.getSharedPreferences("export_options", Context.MODE_PRIVATE);
        JpegExportOptions.Mode mode = null;
        if (binding.checkboxJpegNone.isChecked()) mode = JpegExportOptions.Mode.NONE;
        else if (binding.checkboxJpegAuto.isChecked()) mode = JpegExportOptions.Mode.AUTO;
        else if (binding.checkboxJpegBwText.isChecked()) mode = JpegExportOptions.Mode.BW_TEXT;
        if (mode == null) {
            try {
                mode = JpegExportOptions.Mode.valueOf(prefs.getString("jpeg_mode", JpegExportOptions.Mode.AUTO.name()));
            } catch (Exception ignored) {
                mode = JpegExportOptions.Mode.AUTO;
            }
        }
        performJpegExport(mode);
    }

    /**
     * Performs JPEG export using a chosen enhancement mode.
     */
    private void performJpegExport(JpegExportOptions.Mode chosenMode) {
        Log.d(TAG, "performJpegExport: Starting JPEG export process with mode=" + chosenMode);
        final Bitmap documentBitmap = exportViewModel.getDocumentBitmap().getValue();
        if (documentBitmap == null) {
            UIUtils.showToast(requireContext(), "No document to export", Toast.LENGTH_SHORT);
            return;
        }
        final Uri selectedLocation = exportViewModel.getSelectedFileLocation().getValue();
        if (selectedLocation == null) {
            UIUtils.showToast(requireContext(), "No target selected", Toast.LENGTH_SHORT);
            return;
        }
        final Context appContext = requireContext().getApplicationContext();
        final boolean includeOcr = Boolean.TRUE.equals(exportViewModel.isIncludeOcr().getValue());

        // Ensure OpenCV is initialized before using JpegExporter (which uses OpenCV APIs)
        try {
            if (!OpenCVUtils.isInitialized()) {
                OpenCVUtils.init(appContext);
            }
        } catch (Throwable t) {
            Log.w(TAG, "performJpegExport: OpenCV init failed or not available", t);
        }

        // Reset any previously generated TXT URI to avoid sharing stale OCR text
        exportViewModel.setTxtExportUri(null);
        exportViewModel.setExporting(true);
        new Thread(() -> {
            try {
                JpegExportOptions options = new JpegExportOptions(); // defaults (quality=85, no resize)
                options.mode = (chosenMode != null) ? chosenMode : JpegExportOptions.Mode.NONE;

                Uri exportUri = JpegExporter.export(appContext, documentBitmap, options, selectedLocation);
                postToUiSafe(() -> {
                    if (exportUri != null) {
                        lastExportedDocumentUri = exportUri;
                        String displayName = FileUtils.getDisplayNameFromUri(requireContext(), lastExportedDocumentUri);
                        lastExportedPdfName = displayName;
                        binding.buttonShare.setEnabled(true);
                        UIUtils.showToast(appContext, "Image " + displayName + " exported", Toast.LENGTH_LONG);

                        if (includeOcr) {
                            launchTxtFileCreation();
                        }
                    } else {
                        lastExportedDocumentUri = null;
                        binding.buttonShare.setEnabled(false);
                        UIUtils.showToast(appContext, "Failed to export image", Toast.LENGTH_SHORT);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error during JPEG export", e);
                postToUiSafe(() -> {
                    lastExportedDocumentUri = null;
                    binding.buttonShare.setEnabled(false);
                    UIUtils.showToast(appContext, "Error during JPEG export: " + e.getMessage(), Toast.LENGTH_SHORT);
                });
            } finally {
                postToUiSafe(() -> exportViewModel.setExporting(false));
            }
        }).start();
    }

    /**
     * Launches the process to create a TXT file with a generated name based on the last exported
     * PDF name or a default timestamp.
     * <p>
     * - If the last exported PDF name (`lastExportedPdfName`) is `null`, a default file name is
     * generated using the current timestamp in the format "yyyyMMdd_HHmmss" with a "DOC_" prefix.
     * - If a valid PDF name exists, it is used as the base name, after removing the ".pdf" extension.
     * - In case of an exception during name processing, a fallback file name based on the timestamp
     * is used.
     * - A ".txt" suffix is appended to the final base name, and the resulting file name is
     * provided to the `createTxtDocumentLauncher` to trigger the TXT file creation process.
     */
    private void launchTxtFileCreation() {
        String pdfName = lastExportedPdfName;
        if (pdfName == null) {
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            pdfName = "DOC_" + timeStamp;
        } else {
            String lower = pdfName.toLowerCase();
            boolean endsWithPdf = lower.endsWith(".pdf");
            boolean endsWithJpg = lower.endsWith(".jpg");
            boolean endsWithJpeg = lower.endsWith(".jpeg");
            if (endsWithPdf || endsWithJpg || endsWithJpeg) {
                try {
                    if (endsWithJpeg && pdfName.length() > 5) {
                        pdfName = pdfName.substring(0, pdfName.length() - 5);
                    } else if ((endsWithPdf || endsWithJpg) && pdfName.length() > 4) {
                        pdfName = pdfName.substring(0, pdfName.length() - 4);
                    }
                } catch (Exception e) {
                    String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                    pdfName = "DOC_" + timeStamp;
                }
            }
        }
        String txtFileName = pdfName + ".txt";
        createTxtDocumentLauncher.launch(txtFileName);
    }

    /**
     * Exports the OCR text to a specified TXT file in the given URI.
     * This method retrieves the OCR text from the current state and writes
     * it to the provided TXT file URI. If the OCR text is not available or
     * the output stream cannot be opened, it logs an error or displays a
     * message to the user. On successful export, the URI of the exported
     * TXT file is saved, and a confirmation message is shown to the user.
     *
     * @param txtUri The URI of the TXT file where the OCR text will be exported.
     *               If null, the method will exit without performing any actions.
     */
    private void exportOcrTextToTxt(Uri txtUri) {
        if (txtUri == null) return;

        String ocrText = getOcrTextFromState();
        if (ocrText == null || ocrText.isEmpty()) {
            Log.d(TAG, "exportOcrTextToTxt: No OCR text available to export");
            return;
        }

        try {
            OutputStream os = requireContext().getContentResolver().openOutputStream(txtUri);
            if (os != null) {
                os.write(ocrText.getBytes());
                os.close();
                exportViewModel.setTxtExportUri(txtUri);
                UIUtils.showToast(requireContext(), "OCR text exported as TXT", Toast.LENGTH_SHORT);
            } else {
                Log.e(TAG, "exportOcrTextToTxt: Failed to open output stream for TXT file");
            }
        } catch (Exception e) {
            Log.e(TAG, "exportOcrTextToTxt: Error exporting OCR text to TXT file", e);
            UIUtils.showToast(requireContext(), "Error exporting OCR text: " + e.getMessage(), Toast.LENGTH_SHORT);
        }
    }

    /**
     * Shares the last exported document along with an optional corresponding text (TXT) file if available.
     * <p>
     * This method prepares an Intent to share the exported document, ensuring compatibility with PDF and
     * TXT file formats. If the last exported document URI is null, it displays a message notifying the user to
     * export a document before attempting to share.
     * <p>
     * Key functionality:
     * - Validates the presence of a document to share. Displays a notification if no document is available.
     * - Retrieves the file name and optionally locates a corresponding TXT file for sharing.
     * - Configures the sharing intent based on the presence or absence of a TXT file:
     * - If a TXT file exists, prepares a multiple-file sharing intent including both the PDF and TXT files.
     * - If no TXT file exists, prepares a single-file sharing intent for the PDF file only.
     * - Handles file URIs consistently, using a file provider if necessary to ensure secure content access.
     * - Sets intent details such as the title, subject, shared text, and necessary permissions.
     * - Initiates a system UI to allow users to choose a sharing destination from available apps.
     * <p>
     * Includes robust error handling to catch and log exceptions, displaying appropriate user feedback
     * when sharing fails.
     */
    private void shareDocument() {
        if (lastExportedDocumentUri == null) {
            UIUtils.showToast(requireContext(), "No document available to share. Export a document first.", Toast.LENGTH_SHORT);
            return;
        }

        try {
            String fileName = FileUtils.getDisplayNameFromUri(requireContext(), lastExportedDocumentUri);
            Uri txtUri = exportViewModel.getTxtExportUri().getValue();
            boolean hasTxtFile = txtUri != null;

            // Detect primary document type (PDF or JPEG) from file name as a robust fallback
            String lowerName = (fileName != null) ? fileName.toLowerCase() : "";
            boolean isPdf = lowerName.endsWith(".pdf");
            boolean isJpeg = lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg");
            String primaryMime = isJpeg ? "image/jpeg" : "application/pdf"; // default to PDF if unknown

            Intent shareIntent = hasTxtFile
                    ? new Intent(Intent.ACTION_SEND_MULTIPLE)
                    : new Intent(Intent.ACTION_SEND);

            shareIntent.setType(hasTxtFile ? "*/*" : primaryMime);

            Uri contentUri;
            if ("content".equalsIgnoreCase(lastExportedDocumentUri.getScheme())) {
                contentUri = lastExportedDocumentUri;
            } else {
                String authority = requireContext().getPackageName() + ".fileprovider";
                java.io.File file = new java.io.File(lastExportedDocumentUri.getPath());
                contentUri = FileProvider.getUriForFile(requireContext(), authority, file);
            }

            // Build a clear label to indicate TXT inclusion when applicable
            String label = hasTxtFile ? (fileName + " + OCR TXT") : fileName;
            shareIntent.putExtra(Intent.EXTRA_TITLE, label);
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, label);
            shareIntent.putExtra(Intent.EXTRA_TEXT, label);

            if (hasTxtFile) {
                Uri txtContentUri;
                if ("content".equalsIgnoreCase(txtUri.getScheme())) {
                    txtContentUri = txtUri;
                } else {
                    String authority = requireContext().getPackageName() + ".fileprovider";
                    java.io.File txtFile = new java.io.File(txtUri.getPath());
                    txtContentUri = FileProvider.getUriForFile(requireContext(), authority, txtFile);
                }

                java.util.ArrayList<Uri> uriList = new java.util.ArrayList<>();
                uriList.add(contentUri);
                uriList.add(txtContentUri);

                android.content.ClipData clipData = new android.content.ClipData(
                        label,
                        new String[]{primaryMime, "text/plain"},
                        new android.content.ClipData.Item(contentUri)
                );
                clipData.addItem(new android.content.ClipData.Item(txtContentUri));
                shareIntent.setClipData(clipData);
                shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uriList);
            } else {
                android.content.ClipData clipData = android.content.ClipData.newUri(
                        requireContext().getContentResolver(), label, contentUri);
                shareIntent.setClipData(clipData);
                shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
            }

            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, "Share " + label));
        } catch (Exception e) {
            Log.e(TAG, "Error sharing document", e);
            UIUtils.showToast(requireContext(), "Error sharing document: " + e.getMessage(), Toast.LENGTH_SHORT);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    /**
     * Retrieves the OCR (Optical Character Recognition) text from the current state
     * managed by the `ocrViewModel`.
     * <p>
     * This method accesses the `OcrUiState` from the `ocrViewModel`, and if the state
     * is not null, it extracts and returns the available OCR text. If the state or
     * OCR text is null, it will return null.
     *
     * @return The extracted OCR text from the current state, or null if the state
     * or OCR text is unavailable.
     */
    private String getOcrTextFromState() {
        OCRViewModel.OcrUiState s = ocrViewModel.getState().getValue();
        return (s != null) ? s.ocrText() : null;
    }

    /**
     * Retrieves a list of recognized words from the current OCR (Optical Character Recognition)
     * state managed by the `ocrViewModel`.
     * <p>
     * This method accesses the `OcrUiState` from the `ocrViewModel` and extracts the recognized
     * words if the state is not null. If the state is null, it returns null.
     *
     * @return A list of recognized words from the current OCR state, or null if the state is unavailable.
     */
    private List<RecognizedWord> getOcrWordsFromState() {
        OCRViewModel.OcrUiState s = ocrViewModel.getState().getValue();
        return (s != null) ? s.words() : null;
    }
}
