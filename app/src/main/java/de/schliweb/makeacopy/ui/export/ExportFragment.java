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
import de.schliweb.makeacopy.utils.FileUtils;
import de.schliweb.makeacopy.utils.PdfCreator;
import de.schliweb.makeacopy.utils.RecognizedWord;
import de.schliweb.makeacopy.utils.UIUtils;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * ExportFragment is a Fragment that handles the export functionality of a document.
 * It manages user interactions for exporting documents to specific formats such as PDF
 * or TXT with optional OCR (Optical Character Recognition) data. This class leverages
 * ViewModels to maintain state and logic separation.
 * <p>
 * The Fragment is responsible for rendering the UI, interacting with ViewModels to determine
 * export readiness, performing export operations, and other related tasks such as
 * file location selection and sharing exported files.
 * <p>
 * This class extends the androidx.fragment.app.Fragment class.
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

    // URI of the last exported document for sharing
    private Uri lastExportedDocumentUri;
    private String lastExportedPdfName;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentExportBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        // SharedPreferences für Export-Optionen
        Context context = requireContext();
        String prefsName = "export_options";
        android.content.SharedPreferences prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE);

        // Auswahl aus SharedPreferences laden
        boolean includeOcr = prefs.getBoolean("include_ocr", false);
        boolean convertToGrayscale = prefs.getBoolean("convert_to_grayscale", false);
        binding.checkboxIncludeOcr.setChecked(includeOcr);
        binding.checkboxGrayscale.setChecked(convertToGrayscale);
        // Werte auch ins ViewModel übernehmen, damit sie wirksam sind
        exportViewModel = new ViewModelProvider(this).get(ExportViewModel.class);
        exportViewModel.setIncludeOcr(includeOcr);
        exportViewModel.setConvertToGrayscale(convertToGrayscale);

        // System-Inset-Margin für Button-Container dynamisch setzen
        ViewCompat.setOnApplyWindowInsetsListener(binding.exportOptionsGroup, (v, insets) -> {
            de.schliweb.makeacopy.utils.UIUtils.adjustMarginForSystemInsets(binding.exportOptionsGroup, 8); // 8dp extra Abstand
            return insets;
        });

        cropViewModel = new ViewModelProvider(requireActivity()).get(CropViewModel.class);
        ocrViewModel = new ViewModelProvider(requireActivity()).get(OCRViewModel.class);
        cameraViewModel = new ViewModelProvider(requireActivity()).get(CameraViewModel.class);

        createDocumentLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("application/pdf"), uri -> {
            Log.d(TAG, "createDocumentLauncher: Document creation result received");
            if (uri != null) {
                Log.d(TAG, "createDocumentLauncher: Selected URI: " + uri);
                Log.d(TAG, "createDocumentLauncher: URI scheme: " + uri.getScheme());
                Log.d(TAG, "createDocumentLauncher: URI path: " + uri.getPath());
                Log.d(TAG, "createDocumentLauncher: URI last path segment: " + uri.getLastPathSegment());

                String displayName = FileUtils.getDisplayNameFromUri(requireContext(), uri);
                Log.d(TAG, "createDocumentLauncher: Display name from URI: " + displayName);

                exportViewModel.setSelectedFileLocation(uri);
                exportViewModel.setSelectedFileLocationName(displayName);
                lastExportedPdfName = displayName;
                Log.d(TAG, "createDocumentLauncher: Starting export process");
                performExport();
            } else {
                Log.d(TAG, "createDocumentLauncher: User cancelled document creation");
            }
        });

        createTxtDocumentLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("text/plain"), uri -> {
            Log.d(TAG, "createTxtDocumentLauncher: TXT document creation result received");
            if (uri != null) {
                Log.d(TAG, "createTxtDocumentLauncher: Selected URI: " + uri);
                Log.d(TAG, "createTxtDocumentLauncher: URI scheme: " + uri.getScheme());
                Log.d(TAG, "createTxtDocumentLauncher: URI path: " + uri.getPath());
                Log.d(TAG, "createTxtDocumentLauncher: URI last path segment: " + uri.getLastPathSegment());

                String displayName = FileUtils.getDisplayNameFromUri(requireContext(), uri);
                Log.d(TAG, "createTxtDocumentLauncher: Display name from URI: " + displayName);

                // Export OCR text to the selected TXT file
                exportOcrTextToTxt(uri);
            } else {
                Log.d(TAG, "createTxtDocumentLauncher: User cancelled TXT document creation");
            }
        });

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

        // UI Binding und LiveData
        exportViewModel.getText().observe(getViewLifecycleOwner(), binding.textExport::setText);

        // binding.pageIndicator.setVisibility(View.GONE);
        // binding.pageNavigation.setVisibility(View.GONE);

        binding.checkboxIncludeOcr.setOnCheckedChangeListener((button, checked) -> {
            exportViewModel.setIncludeOcr(checked);
            prefs.edit().putBoolean("include_ocr", checked).apply();
        });
        binding.checkboxGrayscale.setOnCheckedChangeListener((button, checked) -> {
            exportViewModel.setConvertToGrayscale(checked);
            prefs.edit().putBoolean("convert_to_grayscale", checked).apply();
        });
        exportViewModel.setExportFormat("PDF");

        binding.buttonExport.setOnClickListener(v -> selectFileLocation());
        binding.buttonShare.setOnClickListener(v -> shareDocument());

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            de.schliweb.makeacopy.utils.UIUtils.adjustTextViewTopMarginForStatusBar(binding.textExport, 8);
            de.schliweb.makeacopy.utils.UIUtils.adjustMarginForSystemInsets(binding.exportOptionsGroup, 80);
            return insets;
        });

        exportViewModel.isDocumentReady().observe(getViewLifecycleOwner(), ready -> {
            binding.buttonExport.setEnabled(ready);
            // Initially disable share button until a document is exported
            binding.buttonShare.setEnabled(false);
            binding.textExport.setText(ready ? R.string.document_ready_for_export : R.string.no_document_ready_process_ocr_first);
        });

        exportViewModel.getDocumentBitmap().observe(getViewLifecycleOwner(), bitmap -> {
            if (bitmap != null) {
                binding.documentPreview.setImageBitmap(bitmap);
                binding.documentPreview.setVisibility(View.VISIBLE);
            } else {
                binding.documentPreview.setVisibility(View.INVISIBLE);
            }
        });

        // Daten von vorherigen Schritten übernehmen
        checkDocumentReady();

        return root;
    }

    private void checkDocumentReady() {
        Bitmap cropped = cropViewModel.getImageBitmap().getValue();
        if (cropped != null) exportViewModel.setDocumentBitmap(cropped);
        String ocrText = ocrViewModel.getOcrResult().getValue();
        if (ocrText != null) exportViewModel.setOcrText(ocrText);
        exportViewModel.setDocumentReady(exportViewModel.getDocumentBitmap().getValue() != null);
    }

    private void performExport() {
        Log.d(TAG, "performExport: Starting export process");

        final Bitmap documentBitmap = exportViewModel.getDocumentBitmap().getValue();
        if (documentBitmap == null) {
            Log.d(TAG, "performExport: No document bitmap available");
            UIUtils.showToast(requireContext(), "No document to export", Toast.LENGTH_SHORT);
            return;
        }

        boolean includeOcr = exportViewModel.isIncludeOcr().getValue();
        boolean convertToGrayscale = exportViewModel.isConvertToGrayscale().getValue();
        List<RecognizedWord> recognizedWords = includeOcr ? ocrViewModel.getRecognizedWords().getValue() : null;
        Uri selectedLocation = exportViewModel.getSelectedFileLocation().getValue();

        Log.d(TAG, "performExport: Export parameters - includeOcr: " + includeOcr + ", convertToGrayscale: " + convertToGrayscale);
        Log.d(TAG, "performExport: Selected location URI: " + selectedLocation);
        if (selectedLocation != null) {
            Log.d(TAG, "performExport: Selected location scheme: " + selectedLocation.getScheme());
            Log.d(TAG, "performExport: Selected location path: " + selectedLocation.getPath());
            Log.d(TAG, "performExport: Selected location last path segment: " + selectedLocation.getLastPathSegment());
        }

        // Capture the application context before starting the background thread
        // Application context is tied to the application's lifecycle and is more stable
        final Context appContext = requireContext().getApplicationContext();

        // Reset the TXT export URI when starting a new export
        exportViewModel.setTxtExportUri(null);

        exportViewModel.setExporting(true);
        Log.d(TAG, "performExport: Starting background thread for PDF creation");

        new Thread(() -> {
            try {
                int jpegQuality = 75;
                Log.d(TAG, "performExport: Calling PdfCreator.createSearchablePdf");
                Uri exportUri = PdfCreator.createSearchablePdf(appContext, // Use application context for PDF creation
                        documentBitmap, recognizedWords, selectedLocation, jpegQuality, convertToGrayscale);

                Log.d(TAG, "performExport: PDF creation completed, returned URI: " + exportUri);
                if (exportUri != null) {
                    Log.d(TAG, "performExport: Returned URI scheme: " + exportUri.getScheme());
                    Log.d(TAG, "performExport: Returned URI path: " + exportUri.getPath());
                    Log.d(TAG, "performExport: Returned URI last path segment: " + exportUri.getLastPathSegment());
                }

                // We'll handle TXT export separately after PDF export is complete
                final boolean shouldExportTxt = includeOcr;

                requireActivity().runOnUiThread(() -> {
                    if (exportUri != null) {
                        // Store the exported URI for sharing
                        lastExportedDocumentUri = exportUri;
                        Log.d(TAG, "performExport: Stored lastExportedDocumentUri: " + lastExportedDocumentUri);

                        // Get the display name for logging and future use
                        String displayName = FileUtils.getDisplayNameFromUri(requireContext(), lastExportedDocumentUri);
                        Log.d(TAG, "performExport: Display name from lastExportedDocumentUri: " + displayName);
                        lastExportedPdfName = displayName;

                        // Enable the share button
                        binding.buttonShare.setEnabled(true);
                        Log.d(TAG, "performExport: Share button enabled");

                        // Show success message
                        UIUtils.showToast(appContext, "Document " + lastExportedPdfName + " exported", Toast.LENGTH_LONG);

                        // If checkbox is checked, launch TXT file creation
                        if (shouldExportTxt) {
                            Log.d(TAG, "performExport: Checkbox is checked, launching TXT file creation");
                            launchTxtFileCreation();
                        }
                    } else {
                        Log.d(TAG, "performExport: Export failed, exportUri is null");
                        lastExportedDocumentUri = null;
                        exportViewModel.setTxtExportUri(null); // Reset TXT export URI on failure
                        binding.buttonShare.setEnabled(false);
                        UIUtils.showToast(appContext, "Failed to export document", Toast.LENGTH_SHORT);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error during export", e);
                Log.e(TAG, "performExport: Exception details", e);

                requireActivity().runOnUiThread(() -> {
                    Log.d(TAG, "performExport: Setting lastExportedDocumentUri to null due to error");
                    lastExportedDocumentUri = null;
                    exportViewModel.setTxtExportUri(null); // Reset TXT export URI on exception
                    binding.buttonShare.setEnabled(false);
                    UIUtils.showToast(appContext, "Error during export: " + e.getMessage(), Toast.LENGTH_SHORT);
                });
            } finally {
                requireActivity().runOnUiThread(() -> {
                    exportViewModel.setExporting(false);
                    Log.d(TAG, "performExport: Export process completed");
                });
            }
        }).start();
    }

    private void selectFileLocation() {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String defaultFileName = "DOC_" + timeStamp + ".pdf";
        createDocumentLauncher.launch(defaultFileName);
    }

    /**
     * Launches the TXT file creation dialog
     * This is called after the PDF export is complete when the checkbox is checked
     */
    private void launchTxtFileCreation() {
        Log.d(TAG, "launchTxtFileCreation: Launching TXT file creation dialog");

        // Get the PDF filename without extension
        String pdfName = lastExportedPdfName;
        if (pdfName == null) {
            // If PDF name is not available, generate a new one
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            pdfName = "DOC_" + timeStamp;
        } else if (pdfName.toLowerCase().endsWith(".pdf")) {
            // Remove .pdf extension if present
            try {
                if (pdfName.length() > 4) {
                    pdfName = pdfName.substring(0, pdfName.length() - 4);
                } else {
                    // If filename is too short, just use it as is
                    Log.w(TAG, "launchTxtFileCreation: PDF filename too short to remove extension: " + pdfName);
                }
            } catch (Exception e) {
                Log.e(TAG, "launchTxtFileCreation: Error removing .pdf extension", e);
                // Generate a safe filename if there's an error
                String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                pdfName = "DOC_" + timeStamp;
            }
        }

        // Create TXT filename with same base name
        String txtFileName = pdfName + ".txt";
        Log.d(TAG, "launchTxtFileCreation: TXT filename: " + txtFileName);

        // Launch the TXT file creation dialog
        createTxtDocumentLauncher.launch(txtFileName);
    }

    /**
     * Exports OCR text to a TXT file
     *
     * @param txtUri The URI of the TXT file to write to
     */
    private void exportOcrTextToTxt(Uri txtUri) {
        Log.d(TAG, "exportOcrTextToTxt: Starting TXT export");

        if (txtUri == null) {
            Log.d(TAG, "exportOcrTextToTxt: TXT URI is null, cannot export");
            return;
        }

        String ocrText = ocrViewModel.getOcrResult().getValue();
        if (ocrText == null || ocrText.isEmpty()) {
            Log.d(TAG, "exportOcrTextToTxt: No OCR text available to export");
            return;
        }

        try {
            // Use ContentResolver to open an output stream for the TXT file
            OutputStream os = requireContext().getContentResolver().openOutputStream(txtUri);
            if (os != null) {
                // Write the OCR text to the output stream
                os.write(ocrText.getBytes());
                os.close();

                // Store the TXT URI in the ViewModel for sharing
                exportViewModel.setTxtExportUri(txtUri);
                Log.d(TAG, "exportOcrTextToTxt: TXT file exported successfully to " + txtUri);

                // Show a toast message
                UIUtils.showToast(requireContext(), "OCR text exported as TXT", Toast.LENGTH_SHORT);
            } else {
                Log.e(TAG, "exportOcrTextToTxt: Failed to open output stream for TXT file");
            }
        } catch (Exception e) {
            Log.e(TAG, "exportOcrTextToTxt: Error exporting OCR text to TXT file", e);
            UIUtils.showToast(requireContext(), "Error exporting OCR text: " + e.getMessage(), Toast.LENGTH_SHORT);
        }
    }

    private void shareDocument() {
        Log.d(TAG, "shareDocument: Starting document sharing process");

        if (lastExportedDocumentUri == null) {
            Log.d(TAG, "shareDocument: No document available to share (lastExportedDocumentUri is null)");
            UIUtils.showToast(requireContext(), "No document available to share. Export a document first.", Toast.LENGTH_SHORT);
            return;
        }

        Log.d(TAG, "shareDocument: Last exported document URI: " + lastExportedDocumentUri);
        Log.d(TAG, "shareDocument: URI scheme: " + lastExportedDocumentUri.getScheme());

        try {
            // Get the filename from the URI using the robust method from FileUtils
            Log.d(TAG, "shareDocument: Getting filename from URI");
            String fileName = FileUtils.getDisplayNameFromUri(requireContext(), lastExportedDocumentUri);
            Log.d(TAG, "shareDocument: Extracted filename: " + fileName);

            // Check if we also have a TXT file to share
            Uri txtUri = exportViewModel.getTxtExportUri().getValue();
            boolean hasTxtFile = txtUri != null;
            Log.d(TAG, "shareDocument: Has TXT file to share: " + hasTxtFile);

            Intent shareIntent;

            if (hasTxtFile) {
                // Create a multi-file share intent
                shareIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);
                shareIntent.setType("*/*");
                Log.d(TAG, "shareDocument: Created multi-file share intent");
            } else {
                // Create a single-file share intent for PDF only
                shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("application/pdf");
                Log.d(TAG, "shareDocument: Created single-file share intent with type application/pdf");
            }

            // If the URI is already a content:// URI, use it directly
            // Otherwise, create a content URI using FileProvider
            Uri contentUri;
            if (lastExportedDocumentUri.getScheme() != null && lastExportedDocumentUri.getScheme().equals("content")) {
                // Already a content URI, use it directly
                contentUri = lastExportedDocumentUri;
                Log.d(TAG, "shareDocument: Using existing content URI directly: " + contentUri);
            } else {
                // Convert file:// URI to content:// URI using FileProvider
                String packageName = requireContext().getPackageName();
                String authority = packageName + ".fileprovider";
                Log.d(TAG, "shareDocument: Converting to content URI using authority: " + authority);

                java.io.File file = new java.io.File(lastExportedDocumentUri.getPath());
                Log.d(TAG, "shareDocument: File path: " + file.getAbsolutePath());
                Log.d(TAG, "shareDocument: File exists: " + file.exists());

                contentUri = FileProvider.getUriForFile(requireContext(), authority, file);
                Log.d(TAG, "shareDocument: Created content URI: " + contentUri);
            }

            // Also log the content URI we're actually sharing
            Log.d(TAG, "shareDocument: Content URI being shared (EXTRA_STREAM): " + contentUri);
            String contentUriFileName = FileUtils.getDisplayNameFromUri(requireContext(), contentUri);
            Log.d(TAG, "shareDocument: Filename from content URI: " + contentUriFileName);

            // Add metadata to the share intent
            shareIntent.putExtra(Intent.EXTRA_TITLE, fileName);
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, fileName);
            // Add the filename as EXTRA_TEXT to provide additional context
            shareIntent.putExtra(Intent.EXTRA_TEXT, fileName);
            Log.d(TAG, "shareDocument: Added EXTRA_TITLE, EXTRA_SUBJECT, and EXTRA_TEXT with value: " + fileName);

            // Handle sharing based on whether we have a TXT file
            if (hasTxtFile) {
                // Convert TXT URI to content URI if needed
                Uri txtContentUri;
                if (txtUri.getScheme() != null && txtUri.getScheme().equals("content")) {
                    txtContentUri = txtUri;
                    Log.d(TAG, "shareDocument: Using existing TXT content URI directly: " + txtContentUri);
                } else {
                    // Convert file:// URI to content:// URI using FileProvider
                    String packageName = requireContext().getPackageName();
                    String authority = packageName + ".fileprovider";
                    Log.d(TAG, "shareDocument: Converting TXT to content URI using authority: " + authority);

                    java.io.File txtFile = new java.io.File(txtUri.getPath());
                    Log.d(TAG, "shareDocument: TXT file path: " + txtFile.getAbsolutePath());
                    Log.d(TAG, "shareDocument: TXT file exists: " + txtFile.exists());

                    txtContentUri = FileProvider.getUriForFile(requireContext(), authority, txtFile);
                    Log.d(TAG, "shareDocument: Created TXT content URI: " + txtContentUri);
                }

                // Create a list of URIs to share
                java.util.ArrayList<Uri> uriList = new java.util.ArrayList<>();
                uriList.add(contentUri);
                uriList.add(txtContentUri);

                // Use ClipData to provide better metadata about the shared files
                android.content.ClipData clipData = new android.content.ClipData(fileName,  // Use the PDF filename as the label
                        new String[]{"application/pdf", "text/plain"}, new android.content.ClipData.Item(contentUri));
                clipData.addItem(new android.content.ClipData.Item(txtContentUri));
                shareIntent.setClipData(clipData);
                Log.d(TAG, "shareDocument: Set ClipData with multiple items");

                // Add the list of URIs to the share intent
                shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uriList);
                Log.d(TAG, "shareDocument: Added multiple URIs to EXTRA_STREAM");
            } else {
                // Use ClipData to provide better metadata about the shared file
                android.content.ClipData clipData = android.content.ClipData.newUri(requireContext().getContentResolver(), fileName,  // Use the filename as the label
                        contentUri);
                shareIntent.setClipData(clipData);
                Log.d(TAG, "shareDocument: Set ClipData with label: " + fileName);

                // Add the PDF URI to the share intent
                shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
                Log.d(TAG, "shareDocument: Added PDF URI to EXTRA_STREAM");
            }

            // Grant temporary read permission to the content URIs
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            // Start the sharing activity with a custom title that includes the filename
            Log.d(TAG, "shareDocument: Starting share activity");
            startActivity(Intent.createChooser(shareIntent, "Share " + fileName));
        } catch (Exception e) {
            Log.e(TAG, "Error sharing document", e);
            Log.e(TAG, "shareDocument: Exception details", e);
            UIUtils.showToast(requireContext(), "Error sharing document: " + e.getMessage(), Toast.LENGTH_SHORT);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
