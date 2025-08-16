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

        // ViewModel
        exportViewModel = new ViewModelProvider(this).get(ExportViewModel.class);
        exportViewModel.setIncludeOcr(includeOcr);
        exportViewModel.setConvertToGrayscale(convertToGrayscale);

        // Insets
        ViewCompat.setOnApplyWindowInsetsListener(binding.exportOptionsGroup, (v, insets) -> {
            UIUtils.adjustMarginForSystemInsets(binding.exportOptionsGroup, 8); // 8dp extra Abstand
            return insets;
        });

        cropViewModel = new ViewModelProvider(requireActivity()).get(CropViewModel.class);
        ocrViewModel = new ViewModelProvider(requireActivity()).get(OCRViewModel.class);
        cameraViewModel = new ViewModelProvider(requireActivity()).get(CameraViewModel.class);

        // PDF-Auswahl
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

        // TXT-Auswahl
        createTxtDocumentLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("text/plain"), uri -> {
            if (uri != null) {
                String displayName = FileUtils.getDisplayNameFromUri(requireContext(), uri);
                Log.d(TAG, "createTxtDocumentLauncher: Display name from URI: " + displayName);
                exportOcrTextToTxt(uri);
            } else {
                Log.d(TAG, "createTxtDocumentLauncher: User cancelled TXT document creation");
            }
        });

        // Back-Handling
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
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

        // Daten von vorherigen Schritten übernehmen
        checkDocumentReady();

        return root;
    }

    private void checkDocumentReady() {
        Bitmap cropped = cropViewModel.getImageBitmap().getValue();
        if (cropped != null) exportViewModel.setDocumentBitmap(cropped);

        String ocrText = getOcrTextFromState();
        if (ocrText != null) exportViewModel.setOcrText(ocrText);

        exportViewModel.setDocumentReady(exportViewModel.getDocumentBitmap().getValue() != null);
    }

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

        // Wortliste nur übergeben, wenn vorhanden/nicht leer (aktuell ohne HOCR -> meist leer)
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

                requireActivity().runOnUiThread(() -> {
                    if (exportUri != null) {
                        lastExportedDocumentUri = exportUri;
                        String displayName = FileUtils.getDisplayNameFromUri(requireContext(), lastExportedDocumentUri);
                        lastExportedPdfName = displayName;
                        binding.buttonShare.setEnabled(true);
                        UIUtils.showToast(appContext, "Document " + lastExportedPdfName + " exported", Toast.LENGTH_LONG);

                        // Optional: TXT-Export direkt im Anschluss, wenn Checkbox gesetzt
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
                requireActivity().runOnUiThread(() -> {
                    lastExportedDocumentUri = null;
                    exportViewModel.setTxtExportUri(null);
                    binding.buttonShare.setEnabled(false);
                    UIUtils.showToast(appContext, "Error during export: " + e.getMessage(), Toast.LENGTH_SHORT);
                });
            } finally {
                requireActivity().runOnUiThread(() -> exportViewModel.setExporting(false));
            }
        }).start();
    }

    private void selectFileLocation() {
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String defaultFileName = "DOC_" + timeStamp + ".pdf";
        createDocumentLauncher.launch(defaultFileName);
    }

    /** Launches the TXT file creation dialog */
    private void launchTxtFileCreation() {
        String pdfName = lastExportedPdfName;
        if (pdfName == null) {
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            pdfName = "DOC_" + timeStamp;
        } else if (pdfName.toLowerCase().endsWith(".pdf")) {
            try {
                if (pdfName.length() > 4) {
                    pdfName = pdfName.substring(0, pdfName.length() - 4);
                }
            } catch (Exception e) {
                String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                pdfName = "DOC_" + timeStamp;
            }
        }
        String txtFileName = pdfName + ".txt";
        createTxtDocumentLauncher.launch(txtFileName);
    }

    /** Exports OCR text to a TXT file */
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

    private void shareDocument() {
        if (lastExportedDocumentUri == null) {
            UIUtils.showToast(requireContext(), "No document available to share. Export a document first.", Toast.LENGTH_SHORT);
            return;
        }

        try {
            String fileName = FileUtils.getDisplayNameFromUri(requireContext(), lastExportedDocumentUri);
            Uri txtUri = exportViewModel.getTxtExportUri().getValue();
            boolean hasTxtFile = txtUri != null;

            Intent shareIntent = hasTxtFile
                    ? new Intent(Intent.ACTION_SEND_MULTIPLE)
                    : new Intent(Intent.ACTION_SEND);

            shareIntent.setType(hasTxtFile ? "*/*" : "application/pdf");

            Uri contentUri;
            if ("content".equalsIgnoreCase(lastExportedDocumentUri.getScheme())) {
                contentUri = lastExportedDocumentUri;
            } else {
                String authority = requireContext().getPackageName() + ".fileprovider";
                java.io.File file = new java.io.File(lastExportedDocumentUri.getPath());
                contentUri = FileProvider.getUriForFile(requireContext(), authority, file);
            }

            shareIntent.putExtra(Intent.EXTRA_TITLE, fileName);
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, fileName);
            shareIntent.putExtra(Intent.EXTRA_TEXT, fileName);

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
                        fileName,
                        new String[]{"application/pdf", "text/plain"},
                        new android.content.ClipData.Item(contentUri)
                );
                clipData.addItem(new android.content.ClipData.Item(txtContentUri));
                shareIntent.setClipData(clipData);
                shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uriList);
            } else {
                android.content.ClipData clipData = android.content.ClipData.newUri(
                        requireContext().getContentResolver(), fileName, contentUri);
                shareIntent.setClipData(clipData);
                shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
            }

            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(shareIntent, "Share " + fileName));
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

    // ==== Helpers zum Zugriff auf den neuen OCR-State ====

    private String getOcrTextFromState() {
        OCRViewModel.OcrUiState s = ocrViewModel.getState().getValue();
        return (s != null) ? s.ocrText() : null;
    }

    private List<RecognizedWord> getOcrWordsFromState() {
        OCRViewModel.OcrUiState s = ocrViewModel.getState().getValue();
        return (s != null) ? s.words() : null;
    }
}
