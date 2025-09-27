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

    // Multipage session (v1 increment)
    private de.schliweb.makeacopy.ui.export.session.ExportSessionViewModel exportSessionViewModel;
    private de.schliweb.makeacopy.ui.export.session.ExportPagesAdapter pagesAdapter;

    private ActivityResultLauncher<String> createDocumentLauncher;
    private ActivityResultLauncher<String> createTxtDocumentLauncher;
    private ActivityResultLauncher<String> createJpegDocumentLauncher;
    private ActivityResultLauncher<String> createZipDocumentLauncher;

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

    // Decode a sampled bitmap from file to roughly fit into the given bounds to avoid OOM
    private Bitmap decodeSampled(String path, int reqW, int reqH) {
        try {
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(path, opts);
            int inSampleSize = 1;
            int halfH = Math.max(1, opts.outHeight) / 2;
            int halfW = Math.max(1, opts.outWidth) / 2;
            while ((halfH / inSampleSize) >= Math.max(1, reqH) && (halfW / inSampleSize) >= Math.max(1, reqW)) {
                inSampleSize *= 2;
            }
            android.graphics.BitmapFactory.Options real = new android.graphics.BitmapFactory.Options();
            real.inSampleSize = Math.max(1, inSampleSize);
            real.inPreferredConfig = Bitmap.Config.RGB_565;
            return android.graphics.BitmapFactory.decodeFile(path, real);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Renders the preview image based on user-selected options such as grayscale, black-and-white, or JPEG BW mode.
     * This method fetches user preferences for export options, processes the provided bitmap accordingly,
     * and updates the preview image in the user interface.
     *
     * @param source The source bitmap image to be processed and displayed in the preview.
     */
    private void renderPreview(Bitmap source) {
        if (binding == null || binding.documentPreview == null || source == null) return;
        try {
            Context ctx = requireContext();
            android.content.SharedPreferences prefs = ctx.getSharedPreferences("export_options", Context.MODE_PRIVATE);
            boolean toGray = prefs.getBoolean("convert_to_grayscale", false);
            boolean toBw = prefs.getBoolean("convert_to_blackwhite", false);
            boolean exportAsJpeg = prefs.getBoolean("export_as_jpeg", false);

            if (exportAsJpeg) {
                // Preview should ONLY reflect JPEG options when exporting as JPEG.
                // Ignore PDF preview options (grayscale/bw) in this mode.
                toGray = false;
                toBw = false;
                // If JPEG BW_TEXT mode is selected, prefer showing BW preview
                try {
                    JpegExportOptions.Mode mode = JpegExportOptions.Mode.valueOf(prefs.getString("jpeg_mode", JpegExportOptions.Mode.AUTO.name()));
                    if (mode == JpegExportOptions.Mode.BW_TEXT) {
                        toBw = true;
                    }
                } catch (Exception ignored) {
                }
            }

            Bitmap safe = de.schliweb.makeacopy.utils.BitmapUtils.ensureDisplaySafe(source);
            Bitmap out = safe;

            if (toBw || toGray) {
                // Initialize OpenCV if needed for conversion helpers
                try {
                    if (!de.schliweb.makeacopy.utils.OpenCVUtils.isInitialized()) {
                        de.schliweb.makeacopy.utils.OpenCVUtils.init(ctx.getApplicationContext());
                    }
                } catch (Throwable t) {
                    // If initialization fails, fall back to showing the original
                }
                try {
                    if (toBw) {
                        Bitmap bw = de.schliweb.makeacopy.utils.OpenCVUtils.toBw(safe);
                        if (bw != null) out = bw;
                    } else if (toGray) {
                        Bitmap gr = de.schliweb.makeacopy.utils.OpenCVUtils.toGray(safe);
                        if (gr != null) out = gr;
                    }
                } catch (Throwable ignored) {
                }
            }

            binding.documentPreview.setImageBitmap(out);
            binding.documentPreview.setVisibility(View.VISIBLE);
        } catch (Throwable ignore) {
            // As a last resort, keep previous image state
        }
    }

    private void renderPreviewFromCurrent() {
        if (exportViewModel == null) return;
        Bitmap cur = exportViewModel.getDocumentBitmap().getValue();
        if (cur != null) renderPreview(cur);
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

        // Initialize JPEG mode checkboxes from saved preference (default AUTO)
        String savedModeName = prefs.getString("jpeg_mode", JpegExportOptions.Mode.AUTO.name());
        JpegExportOptions.Mode savedMode;
        try {
            savedMode = JpegExportOptions.Mode.valueOf(savedModeName);
        } catch (IllegalArgumentException ex) {
            savedMode = JpegExportOptions.Mode.AUTO;
        }

        // ViewModel
        exportViewModel = new ViewModelProvider(this).get(ExportViewModel.class);
        exportViewModel.setIncludeOcr(includeOcr);
        exportViewModel.setConvertToGrayscale(convertToGrayscale);
        exportViewModel.setExportFormat(exportAsJpeg ? "JPEG" : "PDF");

        // Include OCR option is now managed solely via ExportOptionsDialogFragment.
        // Keep the inline checkbox hidden and do not alter its visibility here.

        // Insets
        ViewCompat.setOnApplyWindowInsetsListener(binding.exportOptionsGroup, (v, insets) -> {
            UIUtils.adjustMarginForSystemInsets(binding.exportOptionsGroup, 8); // 8dp extra Abstand
            return insets;
        });

        // Observe exporting state and progress to update progress bar
        if (binding.exportProgress != null) {
            exportViewModel.isExporting().observe(getViewLifecycleOwner(), exporting -> {
                if (exporting != null && exporting) {
                    binding.exportProgress.setVisibility(View.VISIBLE);
                    // Disable Share while exporting to meet requirement: only active after export completes
                    binding.buttonShare.setEnabled(false);
                } else {
                    binding.exportProgress.setVisibility(View.GONE);
                    // Do not enable share here; it will be enabled explicitly on successful export
                }
            });
            exportViewModel.getExportProgressMax().observe(getViewLifecycleOwner(), max -> {
                Integer m = (max == null) ? 0 : max;
                binding.exportProgress.setMax((m <= 0) ? 100 : m);
                binding.exportProgress.setIndeterminate(m == null || m <= 0);
            });
            exportViewModel.getExportProgress().observe(getViewLifecycleOwner(), value -> {
                if (value != null) binding.exportProgress.setProgress(value);
            });
        }

        // Back button: navigate to OCR (if not skipping OCR) or Crop (if skipping OCR)
        View backBtn = root.findViewById(R.id.button_back);
        if (backBtn != null) {
            backBtn.setOnClickListener(v -> {
                // Delegate to the same back handling as system Back to ensure identical behavior
                requireActivity().getOnBackPressedDispatcher().onBackPressed();
            });
        }

        cropViewModel = new ViewModelProvider(requireActivity()).get(CropViewModel.class);
        ocrViewModel = new ViewModelProvider(requireActivity()).get(OCRViewModel.class);
        cameraViewModel = new ViewModelProvider(requireActivity()).get(CameraViewModel.class);

        // Multipage session setup (v1 increment) - use Activity scope so it survives navigation
        exportSessionViewModel = new ViewModelProvider(requireActivity()).get(de.schliweb.makeacopy.ui.export.session.ExportSessionViewModel.class);
        pagesAdapter = new de.schliweb.makeacopy.ui.export.session.ExportPagesAdapter(new de.schliweb.makeacopy.ui.export.session.ExportPagesAdapter.Callbacks() {
            @Override
            public void onRemoveClicked(int position) {
                exportSessionViewModel.removeAt(position);
            }

            @Override
            public void onPageClicked(int position) {
                List<de.schliweb.makeacopy.ui.export.session.CompletedScan> cur = exportSessionViewModel.getPages().getValue();
                if (cur == null || position < 0 || position >= cur.size()) return;
                de.schliweb.makeacopy.ui.export.session.CompletedScan sel = cur.get(position);
                if (sel == null) return;
                Bitmap bmp = sel.inMemoryBitmap();
                try {
                    if (bmp == null) {
                        String path = sel.filePath();
                        if (path != null) {
                            // Decode a sampled bitmap to avoid OOM; target a reasonable bound
                            int reqW = (binding != null && binding.documentPreview != null && binding.documentPreview.getWidth() > 0)
                                    ? binding.documentPreview.getWidth() : 2048;
                            int reqH = (binding != null && binding.documentPreview != null && binding.documentPreview.getHeight() > 0)
                                    ? binding.documentPreview.getHeight() : 2048;
                            bmp = decodeSampled(path, reqW, reqH);
                        }
                    }
                    // Apply rotation for preview if needed
                    if (bmp != null) {
                        int deg = 0;
                        try {
                            deg = sel.rotationDeg();
                        } catch (Throwable ignore) {
                        }
                        if (deg % 360 != 0) {
                            try {
                                android.graphics.Matrix m = new android.graphics.Matrix();
                                m.postRotate(deg);
                                Bitmap rotated = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                                if (rotated != null) bmp = rotated;
                            } catch (Throwable ignore) {
                            }
                        }
                    }
                } catch (Throwable t) {
                    // ignore decode errors
                }
                if (bmp != null) {
                    exportViewModel.setDocumentBitmap(bmp);
                    exportViewModel.setDocumentReady(true);
                }
            }

            @Override
            public void onReorder(int fromPosition, int toPosition) {
                exportSessionViewModel.move(fromPosition, toPosition);
            }

            @Override
            public void onOcrRequested(int position) {
                runInlineOcrForPage(position);
            }
        });
        if (binding.pagesRecycler != null) {
            androidx.recyclerview.widget.LinearLayoutManager lm = new androidx.recyclerview.widget.LinearLayoutManager(requireContext(), androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false);
            binding.pagesRecycler.setLayoutManager(lm);
            binding.pagesRecycler.setAdapter(pagesAdapter);

            // Enable drag & drop reordering via ItemTouchHelper (horizontal)
            androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback cb = new androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(androidx.recyclerview.widget.ItemTouchHelper.LEFT | androidx.recyclerview.widget.ItemTouchHelper.RIGHT, 0) {
                @Override
                public boolean onMove(@NonNull androidx.recyclerview.widget.RecyclerView recyclerView,
                                      @NonNull androidx.recyclerview.widget.RecyclerView.ViewHolder viewHolder,
                                      @NonNull androidx.recyclerview.widget.RecyclerView.ViewHolder target) {
                    int from = viewHolder.getBindingAdapterPosition();
                    int to = target.getBindingAdapterPosition();
                    return pagesAdapter.onItemMove(from, to);
                }

                @Override
                public void onSwiped(@NonNull androidx.recyclerview.widget.RecyclerView.ViewHolder viewHolder, int direction) {
                    // no-op (we don't support swipe to dismiss here)
                }

                @Override
                public boolean isLongPressDragEnabled() {
                    // Long-press on the item starts drag
                    return true;
                }
            };
            new androidx.recyclerview.widget.ItemTouchHelper(cb).attachToRecyclerView(binding.pagesRecycler);
        }
        // Observe pages to update UI
        exportSessionViewModel.getPages().observe(getViewLifecycleOwner(), pages -> {
            pagesAdapter.submitList(pages);
            int n = (pages == null) ? 0 : pages.size();
            // Show filmstrip only when there are actually more than one page
            if (binding.pagesContainer != null) {
                binding.pagesContainer.setVisibility(n > 1 ? View.VISIBLE : View.GONE);
            }
            // Show "Clear all" only when more than one page exists
            if (binding.buttonClearPages != null) {
                // Keep the button slot occupied to maintain fixed placement (Settings left, Plus center, Trash right)
                binding.buttonClearPages.setVisibility(n > 1 ? View.VISIBLE : View.INVISIBLE);
            }
            // If current preview points to a removed page, auto-select a remaining one
            Bitmap curPreview = exportViewModel.getDocumentBitmap().getValue();
            boolean found = false;
            if (curPreview != null && pages != null) {
                for (de.schliweb.makeacopy.ui.export.session.CompletedScan s : pages) {
                    if (s != null && s.inMemoryBitmap() == curPreview) {
                        found = true;
                        break;
                    }
                }
            }
            if (!found && pages != null && !pages.isEmpty()) {
                de.schliweb.makeacopy.ui.export.session.CompletedScan first = pages.get(0);
                if (first != null && first.inMemoryBitmap() != null) {
                    Bitmap bmp = first.inMemoryBitmap();
                    try {
                        int deg = 0;
                        try {
                            deg = first.rotationDeg();
                        } catch (Throwable ignore) {
                        }
                        deg = ((deg % 360) + 360) % 360;
                        if (deg != 0 && bmp != null) {
                            android.graphics.Matrix m = new android.graphics.Matrix();
                            m.postRotate(deg);
                            Bitmap rotated = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                            if (rotated != null) bmp = rotated;
                        }
                    } catch (Throwable ignore) {
                    }
                    exportViewModel.setDocumentBitmap(bmp);
                    exportViewModel.setDocumentReady(true);
                }
            }
            // Do not toggle Include OCR checkbox visibility here; it remains hidden and controlled by the dialog.
        });
        // Initialize or update pages based on current state and pending add-page flag
        Bitmap initBmp = cropViewModel.getImageBitmap().getValue();
        List<de.schliweb.makeacopy.ui.export.session.CompletedScan> currentPages = exportSessionViewModel.getPages().getValue();
        int curSize = (currentPages == null) ? 0 : currentPages.size();

        boolean pendingAdd = prefs.getBoolean("pending_add_page", false);
        if (curSize == 0) {
            // First time opening Export in this session: seed with current cropped bitmap if available
            if (initBmp != null) {
                int userDeg = 0;
                try {
                    Integer v = cropViewModel.getUserRotationDegrees().getValue();
                    if (v != null) userDeg = ((v % 360) + 360) % 360;
                } catch (Throwable ignore) {
                }
                de.schliweb.makeacopy.ui.export.session.CompletedScan initial = new de.schliweb.makeacopy.ui.export.session.CompletedScan(
                        java.util.UUID.randomUUID().toString(),
                        null,
                        userDeg,
                        null,
                        null,
                        null,
                        System.currentTimeMillis(),
                        initBmp.getWidth(),
                        initBmp.getHeight(),
                        initBmp
                );
                exportSessionViewModel.setInitial(initial);
                // Persist initial page so it appears in the registry as well
                try {
                    persistCompletedScanAsync(initial);
                } catch (Throwable t) {
                    Log.w(TAG, "Failed to persist initial scan to registry", t);
                }
            } else {
                exportSessionViewModel.setInitial(null);
            }
        } else if (pendingAdd) {
            // User initiated adding another page and returned here after new capture/crop
            if (initBmp != null) {
                // Avoid adding duplicates if the same bitmap reference is already present
                boolean alreadyPresent = false;
                for (de.schliweb.makeacopy.ui.export.session.CompletedScan s : currentPages) {
                    if (s != null && s.inMemoryBitmap() == initBmp) {
                        alreadyPresent = true;
                        break;
                    }
                }
                if (!alreadyPresent) {
                    int userDeg = 0;
                    try {
                        Integer v = cropViewModel.getUserRotationDegrees().getValue();
                        if (v != null) userDeg = ((v % 360) + 360) % 360;
                    } catch (Throwable ignore) {
                    }
                    de.schliweb.makeacopy.ui.export.session.CompletedScan added = new de.schliweb.makeacopy.ui.export.session.CompletedScan(
                            java.util.UUID.randomUUID().toString(),
                            null,
                            userDeg,
                            null,
                            null,
                            null,
                            System.currentTimeMillis(),
                            initBmp.getWidth(),
                            initBmp.getHeight(),
                            initBmp
                    );
                    exportSessionViewModel.add(added);
                    // Persist this newly added page into the CompletedScans registry (Insert-Hook)
                    try {
                        persistCompletedScanAsync(added);
                    } catch (Throwable t) {
                        Log.w(TAG, "Failed to persist completed scan to registry", t);
                    }
                }
            }
            // Clear the flag regardless to prevent re-adding on future opens
            prefs.edit().putBoolean("pending_add_page", false).apply();
        }
        if (binding.buttonAddPage != null) {
            binding.buttonAddPage.setOnClickListener(v -> {
                // Show simple choice dialog instead of directly jumping to camera
                String[] options = new String[]{
                        getString(R.string.add_page_option_choose_completed),
                        getString(R.string.add_page_option_capture_new)
                };
                androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.add_another_page))
                        .setItems(options, (dialogInterface, which) -> {
                            if (which == 0) {
                                // Open picker; pass already selected IDs to disable them
                                try {
                                    java.util.ArrayList<String> already = new java.util.ArrayList<>();
                                    java.util.List<de.schliweb.makeacopy.ui.export.session.CompletedScan> cur = exportSessionViewModel.getPages().getValue();
                                    if (cur != null) {
                                        for (de.schliweb.makeacopy.ui.export.session.CompletedScan s : cur) {
                                            if (s != null && s.id() != null) already.add(s.id());
                                        }
                                    }
                                    android.os.Bundle args = new android.os.Bundle();
                                    args.putStringArrayList(de.schliweb.makeacopy.ui.export.picker.CompletedScansPickerFragment.ARG_ALREADY_SELECTED_IDS, already);
                                    Navigation.findNavController(requireView()).navigate(R.id.navigation_completed_scans_picker, args);
                                } catch (Throwable t) {
                                    // ignore
                                }
                            } else if (which == 1) {
                                // Capture new page (previous behavior)
                                prefs.edit().putBoolean("pending_add_page", true).apply();
                                cameraViewModel.setImageUri(null);
                                cropViewModel.setImageCropped(false);
                                cropViewModel.setImageBitmap(null);
                                cropViewModel.setOriginalImageBitmap(null);
                                cropViewModel.setImageLoaded(false);
                                Navigation.findNavController(requireView()).navigate(R.id.navigation_camera);
                            }
                        })
                        .setNegativeButton(R.string.cancel, (d, wch) -> d.dismiss())
                        .create();
                // Improve dark mode contrast for dialog buttons similar to low-light dialog
                dialog.setOnShowListener(dlg -> {
                    int nightModeFlags = requireContext().getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
                    if (nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
                        try {
                            int white = androidx.core.content.ContextCompat.getColor(requireContext(), android.R.color.white);
                            if (dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE) != null) {
                                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setTextColor(white);
                            }
                            if (dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE) != null) {
                                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).setTextColor(white);
                            }
                            if (dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL) != null) {
                                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setTextColor(white);
                            }
                        } catch (Exception ignored) {
                        }
                    }
                });
                dialog.show();
            });
            // Long-press: open Completed Scans Picker (v1 minimal wiring)
            binding.buttonAddPage.setOnLongClickListener(v -> {
                try {
                    // Pass already selected IDs to disable them in picker
                    java.util.ArrayList<String> already = new java.util.ArrayList<>();
                    java.util.List<de.schliweb.makeacopy.ui.export.session.CompletedScan> cur = exportSessionViewModel.getPages().getValue();
                    if (cur != null) {
                        for (de.schliweb.makeacopy.ui.export.session.CompletedScan s : cur) {
                            if (s != null && s.id() != null) already.add(s.id());
                        }
                    }
                    android.os.Bundle args = new android.os.Bundle();
                    args.putStringArrayList(de.schliweb.makeacopy.ui.export.picker.CompletedScansPickerFragment.ARG_ALREADY_SELECTED_IDS, already);
                    Navigation.findNavController(requireView()).navigate(R.id.navigation_completed_scans_picker, args);
                    UIUtils.showToast(requireContext(), getString(R.string.picker_title_completed_scans), Toast.LENGTH_SHORT);
                    return true;
                } catch (Throwable t) {
                    return false;
                }
            });
        }
        if (binding.buttonClearPages != null) {
            binding.buttonClearPages.setOnClickListener(v -> {
                androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setTitle(getString(R.string.confirm_clear_pages_title))
                        .setMessage(getString(R.string.confirm_clear_pages_message))
                        .setPositiveButton(R.string.confirm, (dialogInterface, which) -> {
                            // Reset to initial single page
                            Bitmap bmp = exportViewModel.getDocumentBitmap().getValue();
                            de.schliweb.makeacopy.ui.export.session.CompletedScan one = null;
                            if (bmp != null) {
                                one = new de.schliweb.makeacopy.ui.export.session.CompletedScan(
                                        java.util.UUID.randomUUID().toString(), null, 0, null, null, null, System.currentTimeMillis(), bmp.getWidth(), bmp.getHeight(), bmp);
                            }
                            exportSessionViewModel.setInitial(one);
                        })
                        .setNegativeButton(R.string.cancel, (dialogInterface, which) -> dialogInterface.dismiss())
                        .create();
                // Improve dark mode contrast for dialog buttons similar to low-light dialog
                dialog.setOnShowListener(dlg -> {
                    int nightModeFlags = requireContext().getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
                    if (nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
                        try {
                            int white = androidx.core.content.ContextCompat.getColor(requireContext(), android.R.color.white);
                            if (dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE) != null) {
                                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setTextColor(white);
                            }
                            if (dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE) != null) {
                                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).setTextColor(white);
                            }
                            if (dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL) != null) {
                                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setTextColor(white);
                            }
                        } catch (Exception ignored) {
                        }
                    }
                });
                dialog.show();
            });
        }

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
        createZipDocumentLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("application/zip"), uri -> {
            Log.d(TAG, "createZipDocumentLauncher: ZIP creation result received");
            if (uri != null) {
                String displayName = FileUtils.getDisplayNameFromUri(requireContext(), uri);
                exportViewModel.setSelectedFileLocation(uri);
                exportViewModel.setSelectedFileLocationName(displayName);
                performJpegZipExport();
            } else {
                Log.d(TAG, "createZipDocumentLauncher: User cancelled ZIP document creation");
            }
        });

        // Listen for results from CompletedScansPickerFragment
        getParentFragmentManager().setFragmentResultListener(
                de.schliweb.makeacopy.ui.export.picker.CompletedScansPickerFragment.RESULT_KEY,
                getViewLifecycleOwner(),
                (requestKey, bundle) -> {
                    try {
                        java.util.ArrayList<String> ids = bundle.getStringArrayList(
                                de.schliweb.makeacopy.ui.export.picker.CompletedScansPickerFragment.RESULT_IDS);
                        if (ids == null || ids.isEmpty()) return;
                        // Resolve from registry
                        java.util.List<de.schliweb.makeacopy.ui.export.session.CompletedScan> all =
                                de.schliweb.makeacopy.data.CompletedScansRegistry.get(requireContext()).listAllOrderedByDateDesc();
                        java.util.Map<String, de.schliweb.makeacopy.ui.export.session.CompletedScan> byId = new java.util.HashMap<>();
                        for (de.schliweb.makeacopy.ui.export.session.CompletedScan s : all) {
                            if (s != null && s.id() != null) byId.put(s.id(), s);
                        }
                        java.util.ArrayList<de.schliweb.makeacopy.ui.export.session.CompletedScan> picked = new java.util.ArrayList<>();
                        for (String id : ids) {
                            de.schliweb.makeacopy.ui.export.session.CompletedScan s = byId.get(id);
                            if (s != null) picked.add(s);
                        }
                        if (!picked.isEmpty()) {
                            exportSessionViewModel.addAll(picked);
                            UIUtils.showToast(requireContext(), getString(R.string.added_pages_from_registry, picked.size()), Toast.LENGTH_SHORT);
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "Failed to handle picker result", t);
                    }
                }
        );

        // Back-Handling
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                // If multipage session is active (>1 pages), ask for confirmation to delete all pages
                List<de.schliweb.makeacopy.ui.export.session.CompletedScan> pages = exportSessionViewModel != null ? exportSessionViewModel.getPages().getValue() : null;
                int n = (pages == null) ? 0 : pages.size();
                if (n > 1) {
                    androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                            .setTitle(getString(R.string.confirm_clear_multipage_title))
                            .setMessage(getString(R.string.confirm_clear_multipage_message))
                            .setPositiveButton(R.string.confirm, (dialogInterface, which) -> {
                                // Clear all pages in the session before leaving
                                if (exportSessionViewModel != null) exportSessionViewModel.setInitial(null);
                                // Reset camera/crop state and navigate back to camera
                                cameraViewModel.setImageUri(null);
                                cropViewModel.setImageCropped(false);
                                cropViewModel.setImageBitmap(null);
                                cropViewModel.setOriginalImageBitmap(null);
                                cropViewModel.setImageLoaded(false);
                                // Also clear pending add flag to avoid unintended re-adding on next open
                                try {
                                    android.content.SharedPreferences prefs = requireContext().getSharedPreferences("export_options", Context.MODE_PRIVATE);
                                    prefs.edit().putBoolean("pending_add_page", false).apply();
                                } catch (Throwable ignore) {
                                }
                                NavOptions navOptions = new NavOptions.Builder().setPopUpTo(R.id.navigation_camera, true).build();
                                Navigation.findNavController(requireView()).navigate(R.id.navigation_camera, null, navOptions);
                            })
                            .setNegativeButton(R.string.cancel, (dialogInterface, which) -> {
                                dialogInterface.dismiss(); // stay on Export
                            })
                            .create();
                    // Improve dark mode contrast for dialog buttons similar to low-light dialog
                    dialog.setOnShowListener(dlg -> {
                        int nightModeFlags = requireContext().getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
                        if (nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
                            try {
                                int white = androidx.core.content.ContextCompat.getColor(requireContext(), android.R.color.white);
                                if (dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE) != null) {
                                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setTextColor(white);
                                }
                                if (dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE) != null) {
                                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).setTextColor(white);
                                }
                                if (dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL) != null) {
                                    dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setTextColor(white);
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    });
                    dialog.show();
                    return;
                }
                // Default behavior (single/zero page): clear session, reset and navigate back
                if (exportSessionViewModel != null) exportSessionViewModel.setInitial(null);
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

        // No inline option listeners: options are managed exclusively via ExportOptionsDialogFragment.

        binding.buttonExport.setOnClickListener(v -> {
            // Use last saved options directly to save a click
            Context ctx = requireContext();
            android.content.SharedPreferences p = ctx.getSharedPreferences("export_options", Context.MODE_PRIVATE);
            boolean includeOcrSel = p.getBoolean("include_ocr", false);
            boolean exportAsJpegSel = p.getBoolean("export_as_jpeg", false);
            boolean graySel = p.getBoolean("convert_to_grayscale", false);

            // Update ViewModel to reflect the options used for this export
            exportViewModel.setIncludeOcr(includeOcrSel);
            exportViewModel.setConvertToGrayscale(graySel);
            exportViewModel.setExportFormat(exportAsJpegSel ? "JPEG" : "PDF");

            // Proceed to file location selection based on format
            if (exportAsJpegSel) {
                selectJpegFileLocation();
            } else {
                selectFileLocation();
            }
        });

        // Options button opens the export options dialog without starting export
        if (binding.buttonOptions != null) {
            binding.buttonOptions.setOnClickListener(v -> {
                getParentFragmentManager().setFragmentResultListener(ExportOptionsDialogFragment.REQUEST_KEY, getViewLifecycleOwner(), (requestKey, bundle) -> {
                    // Update ViewModel with new choices for immediate feedback and re-render preview
                    boolean includeOcrSel = bundle.getBoolean(ExportOptionsDialogFragment.BUNDLE_INCLUDE_OCR, false);
                    boolean exportAsJpegSel = bundle.getBoolean(ExportOptionsDialogFragment.BUNDLE_EXPORT_AS_JPEG, false);
                    boolean graySel = bundle.getBoolean(ExportOptionsDialogFragment.BUNDLE_CONVERT_TO_GRAYSCALE, false);
                    exportViewModel.setIncludeOcr(includeOcrSel);
                    exportViewModel.setConvertToGrayscale(graySel);
                    exportViewModel.setExportFormat(exportAsJpegSel ? "JPEG" : "PDF");
                    // Re-render preview to reflect grayscale/BW selections immediately
                    renderPreviewFromCurrent();
                    // No export kickoff here
                    getParentFragmentManager().clearFragmentResultListener(ExportOptionsDialogFragment.REQUEST_KEY);
                });
                ExportOptionsDialogFragment.show(getParentFragmentManager());
            });
        }
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
                renderPreview(bitmap);
            } else {
                binding.documentPreview.setVisibility(View.INVISIBLE);
            }
        });

        // No inline PDF preset UI setup: presets are chosen in the dialog and stored in SharedPreferences.

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
            // Apply user rotation (from CropViewModel) to the preview so it matches what the user sees elsewhere
            Bitmap bmp = maybeBitmap;
            try {
                int userDeg = 0;
                try {
                    Integer v = cropViewModel.getUserRotationDegrees().getValue();
                    if (v != null) userDeg = v;
                } catch (Throwable ignore) {
                }
                userDeg = ((userDeg % 360) + 360) % 360;
                if (userDeg != 0) {
                    android.graphics.Matrix m = new android.graphics.Matrix();
                    m.postRotate(userDeg);
                    Bitmap rotated = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                    if (rotated != null) bmp = rotated;
                }
            } catch (Throwable ignore) {
            }
            exportViewModel.setDocumentBitmap(bmp);
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

        // Multipage handling: if >1 pages, compose PDF
        java.util.List<de.schliweb.makeacopy.ui.export.session.CompletedScan> pages = exportSessionViewModel != null ? exportSessionViewModel.getPages().getValue() : null;
        final boolean isMulti = pages != null && pages.size() > 1;

        final Bitmap documentBitmap = exportViewModel.getDocumentBitmap().getValue();
        if (!isMulti && documentBitmap == null) {
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
        // Disable Share at the start of export; it will be re-enabled only on success
        binding.buttonShare.setEnabled(false);
        lastExportedDocumentUri = null;
        lastExportedPdfName = null;
        exportViewModel.setExporting(true);

        new Thread(() -> {
            try {
                // Determine PDF quality preset from SharedPreferences (set by dialog)
                de.schliweb.makeacopy.utils.PdfQualityPreset preset;
                boolean convertBwEffective = false;
                try {
                    android.content.SharedPreferences p = requireContext().getSharedPreferences("export_options", Context.MODE_PRIVATE);
                    String presetSaved = p.getString("pdf_preset", null);
                    convertBwEffective = p.getBoolean("convert_to_blackwhite", false);
                    java.util.List<de.schliweb.makeacopy.ui.export.session.CompletedScan> pgs = exportSessionViewModel != null ? exportSessionViewModel.getPages().getValue() : null;
                    int pageCount = (pgs == null) ? 0 : pgs.size();
                    de.schliweb.makeacopy.utils.PdfQualityPreset def = (pageCount > 1) ? de.schliweb.makeacopy.utils.PdfQualityPreset.STANDARD : de.schliweb.makeacopy.utils.PdfQualityPreset.HIGH;
                    preset = de.schliweb.makeacopy.utils.PdfQualityPreset.fromName(presetSaved, def);
                } catch (Throwable t) {
                    preset = de.schliweb.makeacopy.utils.PdfQualityPreset.STANDARD;
                }
                int jpegQuality = preset.jpegQuality;
                // If preset forces grayscale, override checkbox
                boolean convertGrayEffective = preset.forceGrayscale || convertToGrayscale;
                Uri exportUri;
                if (isMulti) {
                    // Build lists
                    java.util.ArrayList<Bitmap> bitmaps = new java.util.ArrayList<>();
                    java.util.ArrayList<java.util.List<RecognizedWord>> perPage = new java.util.ArrayList<>();
                    Bitmap current = documentBitmap;
                    java.util.HashSet<Bitmap> toRecycle = new java.util.HashSet<>();
                    for (de.schliweb.makeacopy.ui.export.session.CompletedScan s : pages) {
                        if (s == null) {
                            perPage.add(null);
                            continue;
                        }
                        Bitmap pageBmp = s.inMemoryBitmap();
                        boolean loadedFromFile = false;
                        if (pageBmp == null) {
                            String p = s.filePath();
                            if (p != null) {
                                try {
                                    pageBmp = android.graphics.BitmapFactory.decodeFile(p);
                                    loadedFromFile = (pageBmp != null);
                                    if (loadedFromFile) toRecycle.add(pageBmp);
                                } catch (Throwable ignore) {
                                }
                            }
                        }
                        if (pageBmp == null) { // skip page if nothing available
                            bitmaps.add(null);
                            perPage.add(null);
                            continue;
                        }
                        // Apply rotation if specified in the scan metadata
                        int deg = 0;
                        try {
                            deg = s.rotationDeg();
                        } catch (Throwable ignore) {
                        }
                        if (deg % 360 != 0) {
                            try {
                                android.graphics.Matrix m = new android.graphics.Matrix();
                                m.postRotate(deg);
                                Bitmap rotated = android.graphics.Bitmap.createBitmap(pageBmp, 0, 0, pageBmp.getWidth(), pageBmp.getHeight(), m, true);
                                if (rotated != pageBmp) {
                                    if (loadedFromFile) { // recycle only if we own the original
                                        try {
                                            pageBmp.recycle();
                                        } catch (Throwable ignore) {
                                        }
                                        toRecycle.remove(pageBmp);
                                    }
                                    pageBmp = rotated;
                                    toRecycle.add(pageBmp);
                                }
                            } catch (Throwable t) {
                                // keep original pageBmp
                            }
                        }
                        bitmaps.add(pageBmp);
                        // Prefer registry-backed per-page words if available (ocrFormat=="words_json");
                        // otherwise, fallback to current page's in-memory words (legacy behavior).
                        List<RecognizedWord> pageWords = null;
                        if (includeOcr) {
                            try {
                                String fmt = s.ocrFormat();
                                String path = s.ocrTextPath();
                                if ("words_json".equalsIgnoreCase(fmt) && path != null) {
                                    java.io.File f = new java.io.File(path);
                                    if (f.exists() && f.isFile()) {
                                        pageWords = de.schliweb.makeacopy.utils.WordsJson.parseFile(f);
                                        if (pageWords != null && pageWords.isEmpty()) pageWords = null;
                                    }
                                }
                            } catch (Throwable ignore) {
                            }
                            if (pageWords == null && s.inMemoryBitmap() == current && recognizedWords != null && !recognizedWords.isEmpty()) {
                                pageWords = recognizedWords;
                            }
                        }
                        perPage.add(pageWords);
                    }
                    // Setup progress for multi-page export
                    final int totalPages = (bitmaps == null) ? 0 : bitmaps.size();
                    postToUiSafe(() -> {
                        exportViewModel.setExportProgressMax(totalPages);
                        exportViewModel.setExportProgress(0);
                    });
                    exportUri = PdfCreator.createSearchablePdf(
                            appContext,
                            bitmaps,
                            perPage,
                            selectedLocation,
                            jpegQuality,
                            convertGrayEffective,
                            convertBwEffective,
                            preset.targetDpi,
                            (pageIndex, total) -> postToUiSafe(() -> exportViewModel.setExportProgress(pageIndex))
                    );
                    // Recycle any temporary bitmaps we created (those not part of the session's in-memory references)
                    try {
                        java.util.HashSet<Bitmap> sessionBitmaps = new java.util.HashSet<>();
                        for (de.schliweb.makeacopy.ui.export.session.CompletedScan s2 : pages) {
                            if (s2 != null && s2.inMemoryBitmap() != null) sessionBitmaps.add(s2.inMemoryBitmap());
                        }
                        for (Bitmap b : bitmaps) {
                            if (b != null && !sessionBitmaps.contains(b)) {
                                try {
                                    b.recycle();
                                } catch (Throwable ignore) {
                                }
                            }
                        }
                    } catch (Throwable ignore) {
                    }
                } else {
                    // Single-page: documentBitmap is already oriented for preview; avoid double-rotating here
                    Bitmap toExport = documentBitmap;
                    exportUri = PdfCreator.createSearchablePdf(
                            appContext,
                            toExport,
                            recognizedWords,
                            selectedLocation,
                            jpegQuality,
                            convertGrayEffective,
                            convertBwEffective,
                            preset.targetDpi
                    );
                }

                final Uri finalUri = exportUri;
                postToUiSafe(() -> {
                    if (finalUri != null) {
                        lastExportedDocumentUri = finalUri;
                        String displayName = FileUtils.getDisplayNameFromUri(requireContext(), lastExportedDocumentUri);
                        lastExportedPdfName = displayName;
                        binding.buttonShare.setEnabled(true);
                        UIUtils.showToast(appContext, (isMulti ? "Document (multi-page) " : "Document ") + lastExportedPdfName + " exported", Toast.LENGTH_LONG);

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
                postToUiSafe(() -> {
                    exportViewModel.setExporting(false);
                    exportViewModel.setExportProgress(0);
                    exportViewModel.setExportProgressMax(0);
                });
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
        String defaultFileName = buildDefaultBaseName() + ".pdf";
        createDocumentLauncher.launch(defaultFileName);
    }

    // Centralized default base-name derivation used for PDF/JPEG/ZIP/TXT
    private String buildDefaultBaseName() {
        try {
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            return "DOC_" + timeStamp;
        } catch (Throwable ignore) {
            return "DOC_" + System.currentTimeMillis();
        }
    }

    // Strip a single trailing extension (case-insensitive), e.g. file.pdf -> file; file.name.zip -> file.name
    private String stripOneExtension(String name) {
        if (name == null) return null;
        int idx = name.lastIndexOf('.');
        if (idx > 0 && idx < name.length() - 1) {
            return name.substring(0, idx);
        }
        return name;
    }

    /**
     * Launches SAF CreateDocument for JPEG export with default filename.
     */
    private void selectJpegFileLocation() {
        java.util.List<de.schliweb.makeacopy.ui.export.session.CompletedScan> pages = exportSessionViewModel != null ? exportSessionViewModel.getPages().getValue() : null;
        int n = (pages == null) ? 0 : pages.size();
        String base = buildDefaultBaseName();
        if (n > 1) {
            String defaultZipName = base + ".zip";
            createZipDocumentLauncher.launch(defaultZipName);
        } else {
            String defaultFileName = base + ".jpg";
            createJpegDocumentLauncher.launch(defaultFileName);
        }
    }

    /**
     * Performs JPEG export using the already perspective-corrected bitmap and JpegExporter.
     * MVP: uses default options (quality=85, original size, no enhancement).
     */
    private void performJpegExport() {
        // Determine JPEG mode from SharedPreferences (set by dialog)
        Context context = requireContext();
        android.content.SharedPreferences prefs = context.getSharedPreferences("export_options", Context.MODE_PRIVATE);
        JpegExportOptions.Mode mode;
        try {
            mode = JpegExportOptions.Mode.valueOf(prefs.getString("jpeg_mode", JpegExportOptions.Mode.AUTO.name()));
        } catch (Exception ignored) {
            mode = JpegExportOptions.Mode.AUTO;
        }
        performJpegExport(mode);
    }

    /**
     * Performs JPEG export using a chosen enhancement mode.
     */
    private void performJpegExport(JpegExportOptions.Mode chosenMode) {
        // If multiple pages, this call path shouldn't be used; ZIP path handles it
        java.util.List<de.schliweb.makeacopy.ui.export.session.CompletedScan> pagesCheck = exportSessionViewModel != null ? exportSessionViewModel.getPages().getValue() : null;
        if (pagesCheck != null && pagesCheck.size() > 1) {
            // Should have gone through ZIP flow
            UIUtils.showToast(requireContext(), getString(R.string.multipage_not_implemented), Toast.LENGTH_SHORT);
            return;
        }
        Log.d(TAG, "performJpegExport: Starting JPEG export process with mode=" + chosenMode);
        // v1 increment: if multiple pages are present, multi-image ZIP export is not implemented
        java.util.List<de.schliweb.makeacopy.ui.export.session.CompletedScan> pages = exportSessionViewModel != null ? exportSessionViewModel.getPages().getValue() : null;
        if (pages != null && pages.size() > 1) {
            UIUtils.showToast(requireContext(), getString(R.string.multipage_not_implemented), Toast.LENGTH_SHORT);
            return;
        }
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
        // Disable Share at the start of export; it will be re-enabled only on success
        binding.buttonShare.setEnabled(false);
        lastExportedDocumentUri = null;
        lastExportedPdfName = null;
        exportViewModel.setExporting(true);
        new Thread(() -> {
            try {
                JpegExportOptions options = new JpegExportOptions(); // defaults (quality=85, no resize)
                options.mode = (chosenMode != null) ? chosenMode : JpegExportOptions.Mode.NONE;

                // For single-image JPEG, the preview bitmap is already oriented (rotated) for display.
                // Align behavior with PDF export: do not apply rotation again to avoid double-rotation.
                Uri exportUri = JpegExporter.export(appContext, documentBitmap, options, selectedLocation);
                final Uri exportUriFinal = exportUri;
                postToUiSafe(() -> {
                    if (exportUriFinal != null) {
                        lastExportedDocumentUri = exportUriFinal;
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
                postToUiSafe(() -> {
                    exportViewModel.setExporting(false);
                    exportViewModel.setExportProgress(0);
                    exportViewModel.setExportProgressMax(0);
                });
            }
        }).start();
    }

    /**
     * Performs a multi-image ZIP export for JPEG when there are multiple pages.
     */
    private void performJpegZipExport() {
        // Determine mode from SharedPreferences (set by dialog)
        Context context = requireContext();
        JpegExportOptions.Mode mode;
        try {
            android.content.SharedPreferences prefs = context.getSharedPreferences("export_options", Context.MODE_PRIVATE);
            mode = JpegExportOptions.Mode.valueOf(prefs.getString("jpeg_mode", JpegExportOptions.Mode.AUTO.name()));
        } catch (Exception e) {
            mode = JpegExportOptions.Mode.AUTO;
        }

        final Uri selectedLocation = exportViewModel.getSelectedFileLocation().getValue();
        if (selectedLocation == null) {
            UIUtils.showToast(requireContext(), "No target selected", Toast.LENGTH_SHORT);
            return;
        }
        final List<de.schliweb.makeacopy.ui.export.session.CompletedScan> pages = exportSessionViewModel != null ? exportSessionViewModel.getPages().getValue() : null;
        if (pages == null || pages.size() <= 1) {
            UIUtils.showToast(requireContext(), getString(R.string.multipage_not_implemented), Toast.LENGTH_SHORT);
            return;
        }
        final Context appContext = requireContext().getApplicationContext();
        exportViewModel.setExporting(true);
        exportViewModel.setTxtExportUri(null);
        // Disable Share at the start of export; it will be re-enabled only on success
        binding.buttonShare.setEnabled(false);
        lastExportedDocumentUri = null;
        lastExportedPdfName = null;

        final JpegExportOptions.Mode finalMode = mode;
        new Thread(() -> {
            java.util.zip.ZipOutputStream zos = null;
            // Initialize progress for ZIP multi-image export
            final int totalPages = (pages == null) ? 0 : pages.size();
            postToUiSafe(() -> {
                exportViewModel.setExportProgressMax(totalPages);
                exportViewModel.setExportProgress(0);
            });
            try {
                // Ensure OpenCV is initialized
                try {
                    if (!OpenCVUtils.isInitialized()) OpenCVUtils.init(appContext);
                } catch (Throwable ignored) {
                }

                JpegExportOptions options = new JpegExportOptions();
                options.mode = finalMode;

                OutputStream os = requireContext().getContentResolver().openOutputStream(selectedLocation, "w");
                if (os == null) throw new RuntimeException("Failed to open ZIP output stream");
                zos = new java.util.zip.ZipOutputStream(os);

                int idx = 1;
                for (de.schliweb.makeacopy.ui.export.session.CompletedScan s : pages) {
                    if (s == null) continue;
                    String name = String.format(Locale.getDefault(), "page_%03d.jpg", idx);
                    java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry(name);
                    zos.putNextEntry(entry);
                    Bitmap pageBmp = s.inMemoryBitmap();
                    if (pageBmp == null) {
                        String p = s.filePath();
                        if (p != null) {
                            try {
                                pageBmp = android.graphics.BitmapFactory.decodeFile(p);
                            } catch (Throwable ignore) {
                            }
                        }
                    }
                    if (pageBmp == null) {
                        // Nothing to write for this page
                        zos.closeEntry();
                        idx++;
                        continue;
                    }
                    int deg = 0;
                    try {
                        deg = s.rotationDeg();
                    } catch (Throwable ignore) {
                    }
                    if (deg % 360 != 0) {
                        try {
                            android.graphics.Matrix m = new android.graphics.Matrix();
                            m.postRotate(deg);
                            Bitmap rotated = android.graphics.Bitmap.createBitmap(pageBmp, 0, 0, pageBmp.getWidth(), pageBmp.getHeight(), m, true);
                            if (rotated != pageBmp) pageBmp = rotated;
                        } catch (Throwable t) {
                            // keep original pageBmp
                        }
                    }
                    boolean ok = JpegExporter.exportToStream(appContext, pageBmp, options, zos);
                    zos.closeEntry();
                    if (!ok) throw new RuntimeException("Failed to encode " + name);
                    // Recycle if this bitmap was not the session's in-memory reference
                    if (s.inMemoryBitmap() != pageBmp) {
                        try {
                            pageBmp.recycle();
                        } catch (Throwable ignore) {
                        }
                    }
                    // Update progress after each page
                    final int done = idx;
                    postToUiSafe(() -> exportViewModel.setExportProgress(done));
                    idx++;
                }
                zos.finish();
                zos.flush();

                Uri exportUri = selectedLocation;
                postToUiSafe(() -> {
                    if (exportUri != null) {
                        lastExportedDocumentUri = exportUri;
                        String displayName = FileUtils.getDisplayNameFromUri(requireContext(), exportUri);
                        lastExportedPdfName = displayName;
                        binding.buttonShare.setEnabled(true);
                        UIUtils.showToast(appContext, "ZIP " + displayName + " exported", Toast.LENGTH_LONG);
                        if (Boolean.TRUE.equals(exportViewModel.isIncludeOcr().getValue())) {
                            launchTxtFileCreation();
                        }
                    } else {
                        lastExportedDocumentUri = null;
                        binding.buttonShare.setEnabled(false);
                        UIUtils.showToast(appContext, "Failed to export ZIP", Toast.LENGTH_SHORT);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error during ZIP export", e);
                postToUiSafe(() -> {
                    lastExportedDocumentUri = null;
                    binding.buttonShare.setEnabled(false);
                    UIUtils.showToast(appContext, "Error during ZIP export: " + e.getMessage(), Toast.LENGTH_SHORT);
                });
            } finally {
                if (zos != null) {
                    try {
                        zos.close();
                    } catch (Exception ignore) {
                    }
                }
                postToUiSafe(() -> {
                    exportViewModel.setExporting(false);
                    exportViewModel.setExportProgress(0);
                    exportViewModel.setExportProgressMax(0);
                });
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
            pdfName = buildDefaultBaseName();
        } else {
            try {
                // Strip one extension (handles .pdf, .jpg, .jpeg, .zip, etc.)
                pdfName = stripOneExtension(pdfName);
            } catch (Throwable t) {
                pdfName = buildDefaultBaseName();
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

        // Build concatenated OCR text in filmstrip order using per-page OCR from the registry when available.
        // Fallbacks:
        //  - If a page has no persisted OCR (ocrTextPath == null or missing file) and it is the currently
        //    previewed page, use the in-memory OCR text from state.
        //  - Otherwise, append empty for that page.
        java.util.List<de.schliweb.makeacopy.ui.export.session.CompletedScan> pages = exportSessionViewModel != null ? exportSessionViewModel.getPages().getValue() : null;
        boolean isMulti = pages != null && pages.size() > 1;

        String currentText = getOcrTextFromState();
        // Single-page: Just use current in-memory OCR text if present
        if (!isMulti) {
            if (currentText == null || currentText.isEmpty()) {
                Log.d(TAG, "exportOcrTextToTxt: No OCR text available to export (single page)");
                return;
            }
            writeTxtToUri(txtUri, currentText);
            return;
        }

        // Multi-page: concatenate per-page OCR from registry
        StringBuilder sb = new StringBuilder();
        android.content.Context ctx = requireContext().getApplicationContext();
        // Current preview bitmap to detect which page matches in-memory OCR state
        Bitmap curPreview = exportViewModel.getDocumentBitmap().getValue();
        for (int i = 0; i < pages.size(); i++) {
            de.schliweb.makeacopy.ui.export.session.CompletedScan s = pages.get(i);
            String pageText = null;
            try {
                String p = (s != null) ? s.ocrTextPath() : null;
                String fmt = (s != null) ? s.ocrFormat() : null;
                boolean isPlain = (fmt == null) || "plain".equalsIgnoreCase(fmt);
                if (p != null && isPlain) {
                    java.io.File f = new java.io.File(p);
                    if (f.exists() && f.isFile()) {
                        pageText = readAllUtf8(f);
                    }
                } else if (p != null && !isPlain) {
                    // Fallback: if not plain, try sibling text.txt next to words_json/hocr/alto
                    try {
                        java.io.File f = new java.io.File(p);
                        java.io.File dir = f.getParentFile();
                        if (dir != null) {
                            java.io.File txtFile = new java.io.File(dir, "text.txt");
                            if (txtFile.exists() && txtFile.isFile()) {
                                pageText = readAllUtf8(txtFile);
                            }
                        }
                    } catch (Throwable ignore) {
                    }
                }
            } catch (Throwable t) {
                // ignore per-page read errors; fallback below
            }
            if ((pageText == null || pageText.isEmpty()) && s != null && s.inMemoryBitmap() != null && curPreview == s.inMemoryBitmap()) {
                // Fallback: if this page is the currently previewed one, use in-memory OCR text
                pageText = currentText;
            }
            if (pageText != null) sb.append(pageText);
            if (i < pages.size() - 1) sb.append("\n\n");
        }

        writeTxtToUri(txtUri, sb.toString());
    }

    private static String readAllUtf8(java.io.File file) throws java.io.IOException {
        byte[] buf = java.nio.file.Files.readAllBytes(file.toPath());
        return new String(buf, java.nio.charset.StandardCharsets.UTF_8);
    }

    private void writeTxtToUri(Uri txtUri, String content) {
        try {
            OutputStream os = requireContext().getContentResolver().openOutputStream(txtUri);
            if (os != null) {
                os.write((content != null ? content : "").getBytes(java.nio.charset.StandardCharsets.UTF_8));
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

    // Insert-Hook implementation: persist a newly added CompletedScan to app storage and registry
    private void persistCompletedScanAsync(de.schliweb.makeacopy.ui.export.session.CompletedScan s) {
        if (s == null || s.id() == null || s.inMemoryBitmap() == null) return;
        final android.content.Context appContext = requireContext().getApplicationContext();
        final android.graphics.Bitmap bmp = s.inMemoryBitmap();
        final String id = s.id();
        // Respect user preference: Skip OCR (export only)
        boolean skipOcrPref = false;
        try {
            android.content.SharedPreferences prefs = requireContext().getSharedPreferences("export_options", android.content.Context.MODE_PRIVATE);
            skipOcrPref = prefs.getBoolean("skip_ocr", false);
        } catch (Throwable ignore) {
        }
        // Capture current in-memory OCR text/words at call time unless Skip OCR is enabled
        final String ocrTextAtCall = skipOcrPref ? null : getOcrTextFromState();
        final java.util.List<de.schliweb.makeacopy.utils.RecognizedWord> ocrWordsAtCall = skipOcrPref ? null : getOcrWordsFromState();
        new Thread(() -> {
            try {
                java.io.File dir = new java.io.File(appContext.getFilesDir(), "scans/" + id);
                if (!dir.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    dir.mkdirs();
                }
                java.io.File page = new java.io.File(dir, "page.jpg");
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(page)) {
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, fos);
                    fos.flush();
                }
                // Create a small thumbnail (long edge ~240px). Ensure rotation is applied so registry thumbnail matches preview.
                android.graphics.Bitmap sourceForThumb = bmp;
                try {
                    int deg = 0;
                    try {
                        deg = s.rotationDeg();
                    } catch (Throwable ignore) {
                    }
                    deg = ((deg % 360) + 360) % 360;
                    if (deg != 0 && bmp != null && !bmp.isRecycled()) {
                        android.graphics.Matrix m = new android.graphics.Matrix();
                        m.postRotate(deg);
                        android.graphics.Bitmap rotated = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                        if (rotated != null) sourceForThumb = rotated;
                    }
                } catch (Throwable ignore) { /* fall back to original */ }
                int w = sourceForThumb.getWidth();
                int h = sourceForThumb.getHeight();
                int longEdge = Math.max(w, h);
                int target = 240;
                float scale = longEdge > target ? (target / (float) longEdge) : 1f;
                int nw = Math.max(1, Math.round(w * scale));
                int nh = Math.max(1, Math.round(h * scale));
                android.graphics.Bitmap thumb = android.graphics.Bitmap.createScaledBitmap(sourceForThumb, nw, nh, true);
                java.io.File thumbFile = new java.io.File(dir, "thumb.jpg");
                try (java.io.FileOutputStream tfos = new java.io.FileOutputStream(thumbFile)) {
                    thumb.compress(android.graphics.Bitmap.CompressFormat.JPEG, 75, tfos);
                    tfos.flush();
                } catch (Throwable ignore) {
                }
                // Recycle intermediate rotated bitmap if it was created just for thumbnail
                if (sourceForThumb != bmp) {
                    try {
                        sourceForThumb.recycle();
                    } catch (Throwable ignore) {
                    }
                }

                // If OCR text/words are available, persist text.txt and words.json; prefer words_json if present
                String ocrPath = null;
                String ocrFormat = null;
                try {
                    // Always try to write plain text first if available (TXT fallback)
                    if (ocrTextAtCall != null && !ocrTextAtCall.isEmpty()) {
                        java.io.File txt = new java.io.File(dir, "text.txt");
                        try (java.io.FileOutputStream tf = new java.io.FileOutputStream(txt)) {
                            tf.write(ocrTextAtCall.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            tf.flush();
                            ocrPath = txt.getAbsolutePath();
                            ocrFormat = "plain";
                        }
                    }
                    // If word boxes are available in memory, also persist words.json and prefer it for registry/export
                    if (ocrWordsAtCall != null && !ocrWordsAtCall.isEmpty()) {
                        java.io.File wordsFile = new java.io.File(dir, "words.json");
                        try (java.io.FileOutputStream wos = new java.io.FileOutputStream(wordsFile)) {
                            String json = toWordsJson(ocrWordsAtCall);
                            wos.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            wos.flush();
                            // Prefer words_json
                            ocrPath = wordsFile.getAbsolutePath();
                            ocrFormat = "words_json";
                        }
                    }
                } catch (Throwable ignore) { /* leave as last successful */ }

                // Build persisted model (no in-memory bitmap in registry), but keep thumb/file paths
                de.schliweb.makeacopy.ui.export.session.CompletedScan persisted =
                        new de.schliweb.makeacopy.ui.export.session.CompletedScan(
                                id,
                                page.getAbsolutePath(),
                                s.rotationDeg(),
                                ocrPath,
                                ocrFormat,
                                thumbFile.getAbsolutePath(),
                                s.createdAt(),
                                s.widthPx(),
                                s.heightPx(),
                                null
                        );
                try {
                    de.schliweb.makeacopy.data.CompletedScansRegistry reg = de.schliweb.makeacopy.data.CompletedScansRegistry.get(appContext);
                    reg.insert(persisted);
                } catch (Exception e) {
                    Log.w(TAG, "Registry insert failed", e);
                }

                // Update current session item so the filmstrip badge reflects OCR immediately
                final String finalOcrPath = ocrPath;
                final String finalOcrFormat = ocrFormat;
                postToUiSafe(() -> {
                    List<de.schliweb.makeacopy.ui.export.session.CompletedScan> cur = exportSessionViewModel.getPages().getValue();
                    if (cur == null) return;
                    for (int i = 0; i < cur.size(); i++) {
                        de.schliweb.makeacopy.ui.export.session.CompletedScan it = cur.get(i);
                        if (it != null && id.equals(it.id())) {
                            de.schliweb.makeacopy.ui.export.session.CompletedScan updated = new de.schliweb.makeacopy.ui.export.session.CompletedScan(
                                    it.id(),
                                    page.getAbsolutePath(),
                                    it.rotationDeg(),
                                    finalOcrPath,
                                    finalOcrFormat,
                                    it.thumbPath() != null ? it.thumbPath() : thumbFile.getAbsolutePath(),
                                    it.createdAt(),
                                    it.widthPx(),
                                    it.heightPx(),
                                    it.inMemoryBitmap()
                            );
                            exportSessionViewModel.updateAt(i, updated);
                            break;
                        }
                    }
                });
            } catch (Throwable t) {
                Log.w(TAG, "Persist scan failed", t);
            }
        }).start();
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
    private void runInlineOcrForPage(int position) {
        List<de.schliweb.makeacopy.ui.export.session.CompletedScan> cur = exportSessionViewModel.getPages().getValue();
        if (cur == null || position < 0 || position >= cur.size()) return;
        de.schliweb.makeacopy.ui.export.session.CompletedScan s = cur.get(position);
        if (s == null) return;
        // Enqueue background OCR job for this page id. UI will be updated when the job broadcasts completion.
        UIUtils.showToast(requireContext(), getString(R.string.ocr_processing_started), Toast.LENGTH_SHORT);
        String lang = null;
        try {
            de.schliweb.makeacopy.ui.ocr.OCRViewModel.OcrUiState st = ocrViewModel.getState().getValue();
            lang = (st != null && st.language() != null) ? st.language() : null;
        } catch (Throwable ignore) {
        }
        de.schliweb.makeacopy.jobs.OcrBackgroundJobs.enqueueReprocess(requireContext().getApplicationContext(), s.id(), lang);
    }

    // Serialize OCR words to a compact JSON array used for words_json format
    private static String toWordsJson(java.util.List<de.schliweb.makeacopy.utils.RecognizedWord> words) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        if (words != null) {
            boolean first = true;
            for (de.schliweb.makeacopy.utils.RecognizedWord w : words) {
                if (w == null) continue;
                android.graphics.RectF r = w.getBoundingBox();
                if (!first) sb.append(',');
                first = false;
                // confidence is 0..1
                float conf = 0f;
                try {
                    conf = w.getConfidence();
                } catch (Throwable ignore) {
                }
                sb.append('{')
                        .append("\"text\":").append(escapeJsonString(w.getText())).append(',')
                        .append("\"left\":").append(formatFloat(r.left)).append(',')
                        .append("\"top\":").append(formatFloat(r.top)).append(',')
                        .append("\"right\":").append(formatFloat(r.right)).append(',')
                        .append("\"bottom\":").append(formatFloat(r.bottom)).append(',')
                        .append("\"confidence\":").append(formatFloat(conf))
                        .append('}');
            }
        }
        sb.append(']');
        return sb.toString();
    }

    private static String escapeJsonString(String s) {
        if (s == null) return "\"\"";
        StringBuilder out = new StringBuilder();
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format(java.util.Locale.US, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
        return out.toString();
    }

    private static String formatFloat(float f) {
        // Use US locale to ensure dot decimal separator
        return String.format(java.util.Locale.US, "%.6f", f);
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

            // Detect primary document type (PDF or JPEG) from file name as a robust fallback
            String lowerName = (fileName != null) ? fileName.toLowerCase() : "";
            boolean isPdf = lowerName.endsWith(".pdf");
            boolean isJpeg = lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg");
            boolean isZip = lowerName.endsWith(".zip");
            String primaryMime = isZip ? "application/zip" : (isJpeg ? "image/jpeg" : "application/pdf"); // default to PDF if unknown

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

    private boolean ocrReceiverRegistered = false;
    private final android.content.BroadcastReceiver ocrUpdateReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, android.content.Intent intent) {
            if (intent == null) return;
            String id = intent.getStringExtra(de.schliweb.makeacopy.jobs.OcrBackgroundJobs.EXTRA_PAGE_ID);
            boolean success = intent.getBooleanExtra(de.schliweb.makeacopy.jobs.OcrBackgroundJobs.EXTRA_SUCCESS, false);
            if (id == null) return;
            if (success) {
                // Reload registry entry and update session item if present
                try {
                    de.schliweb.makeacopy.data.CompletedScansRegistry reg = de.schliweb.makeacopy.data.CompletedScansRegistry.get(requireContext().getApplicationContext());
                    java.util.List<de.schliweb.makeacopy.ui.export.session.CompletedScan> cur = exportSessionViewModel.getPages().getValue();
                    if (cur == null) return;
                    de.schliweb.makeacopy.ui.export.session.CompletedScan persisted = null;
                    for (de.schliweb.makeacopy.ui.export.session.CompletedScan e : reg.listAllOrderedByDateDesc()) {
                        if (e != null && id.equals(e.id())) {
                            persisted = e;
                            break;
                        }
                    }
                    if (persisted == null) return;
                    for (int i = 0; i < cur.size(); i++) {
                        de.schliweb.makeacopy.ui.export.session.CompletedScan it = cur.get(i);
                        if (it != null && id.equals(it.id())) {
                            de.schliweb.makeacopy.ui.export.session.CompletedScan updated = new de.schliweb.makeacopy.ui.export.session.CompletedScan(
                                    it.id(), persisted.filePath(), it.rotationDeg(), persisted.ocrTextPath(), persisted.ocrFormat(),
                                    (it.thumbPath() != null ? it.thumbPath() : persisted.thumbPath()), it.createdAt(), it.widthPx(), it.heightPx(), it.inMemoryBitmap()
                            );
                            exportSessionViewModel.updateAt(i, updated);
                            break;
                        }
                    }
                    UIUtils.showToast(requireContext(), getString(R.string.ocr_processing_finished), Toast.LENGTH_SHORT);
                } catch (Throwable t) {
                    Log.w(TAG, "Failed to update session after OCR job", t);
                }
            } else {
                UIUtils.showToast(requireContext(), getString(R.string.ocr_processing_failed), Toast.LENGTH_SHORT);
            }
        }
    };

    @Override
    public void onStart() {
        super.onStart();
        try {
            if (!ocrReceiverRegistered) {
                android.content.Context app = requireContext().getApplicationContext();
                android.content.IntentFilter filter = new android.content.IntentFilter(de.schliweb.makeacopy.jobs.OcrBackgroundJobs.ACTION_OCR_UPDATED);
                int flags = androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED;
                try {
                    androidx.core.content.ContextCompat.registerReceiver(app, ocrUpdateReceiver, filter, flags);
                    ocrReceiverRegistered = true;
                } catch (Throwable ignore) {
                }
            }
        } catch (Throwable ignore) {
        }
    }

    @Override
    public void onStop() {
        try {
            if (ocrReceiverRegistered) {
                android.content.Context app = requireContext().getApplicationContext();
                try {
                    app.unregisterReceiver(ocrUpdateReceiver);
                } catch (Throwable ignore) {
                }
                ocrReceiverRegistered = false;
            }
        } catch (Throwable ignore) {
        }
        super.onStop();
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
