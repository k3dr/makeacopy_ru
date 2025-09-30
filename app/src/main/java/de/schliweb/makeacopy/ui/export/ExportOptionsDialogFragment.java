package de.schliweb.makeacopy.ui.export;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.utils.PdfQualityPreset;
import de.schliweb.makeacopy.utils.jpeg.JpegExportOptions;

/**
 * A dialog fragment that displays export options for the user to configure.
 * Options include selecting whether to include OCR data, exporting as JPEG or PDF,
 * enabling grayscale conversion, and choosing specific PDF or JPEG settings.
 * <p>
 * This dialog allows users to modify their preferences for exporting content
 * and persists these settings for future use. Once the user confirms their choices,
 * the selected options are sent back via a result bundle.
 * <p>
 * Constants:
 * - REQUEST_KEY: The key for retrieving the fragment result.
 * - BUNDLE_INCLUDE_OCR: Key for including or excluding OCR data in export.
 * - BUNDLE_EXPORT_AS_JPEG: Key for exporting the output as JPEG format.
 * - BUNDLE_CONVERT_TO_GRAYSCALE: Key for converting the output to grayscale.
 * - BUNDLE_JPEG_MODE: Key for specifying the JPEG export mode, represented as an enum name.
 * - BUNDLE_PDF_PRESET: Key for defining the PDF export quality preset, also represented as an enum name.
 * <p>
 * Overrides:
 * - onCreateDialog(Bundle): Creates and initializes the dialog with its UI and logic.
 * <p>
 * Methods:
 * - show(FragmentManager): Static method to show the dialog using the provided FragmentManager.
 * - updateGroups(boolean, View, View): Private helper method to toggle visibility
 * between PDF and JPEG option groups within the dialog.
 */
public class ExportOptionsDialogFragment extends DialogFragment {

    public static final String REQUEST_KEY = "export_options";
    public static final String BUNDLE_INCLUDE_OCR = "include_ocr";
    public static final String BUNDLE_EXPORT_AS_JPEG = "export_as_jpeg";
    public static final String BUNDLE_CONVERT_TO_GRAYSCALE = "convert_to_grayscale";
    public static final String BUNDLE_JPEG_MODE = "jpeg_mode"; // enum name
    public static final String BUNDLE_PDF_PRESET = "pdf_preset"; // enum name
    public static final String BUNDLE_CONVERT_TO_BLACKWHITE = "convert_to_blackwhite";

    public static void show(@NonNull FragmentManager fm) {
        new ExportOptionsDialogFragment().show(fm, "ExportOptionsDialogFragment");
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Context ctx = requireContext();
        LayoutInflater inflater = LayoutInflater.from(ctx);
        View view = inflater.inflate(R.layout.dialog_export_options, null);

        CheckBox cbIncludeOcr = view.findViewById(R.id.dialog_checkbox_include_ocr);
        RadioButton rbExportPdf = view.findViewById(R.id.dialog_checkbox_export_pdf);
        RadioButton rbExportJpeg = view.findViewById(R.id.dialog_checkbox_export_jpeg);
        View pdfGroup = view.findViewById(R.id.dialog_pdf_group);
        CheckBox cbGray = view.findViewById(R.id.dialog_checkbox_grayscale);
        CheckBox cbBw = view.findViewById(R.id.dialog_checkbox_blackwhite);
        RadioGroup pdfPresetGroup = view.findViewById(R.id.dialog_pdf_preset_group);
        RadioButton rbHigh = view.findViewById(R.id.dialog_radio_pdf_high);
        RadioButton rbStandard = view.findViewById(R.id.dialog_radio_pdf_standard);
        RadioButton rbSmall = view.findViewById(R.id.dialog_radio_pdf_small);
        RadioButton rbVerySmall = view.findViewById(R.id.dialog_radio_pdf_very_small);

        View jpegGroup = view.findViewById(R.id.dialog_jpeg_group);
        RadioGroup jpegModeGroup = view.findViewById(R.id.dialog_jpeg_mode_group);
        RadioButton rbJpegNone = view.findViewById(R.id.dialog_radio_jpeg_none);
        RadioButton rbJpegAuto = view.findViewById(R.id.dialog_radio_jpeg_auto);
        RadioButton rbJpegBw = view.findViewById(R.id.dialog_radio_jpeg_bw_text);

        SharedPreferences prefs = ctx.getSharedPreferences("export_options", Context.MODE_PRIVATE);
        boolean includeOcr = prefs.getBoolean("include_ocr", false);
        boolean exportAsJpeg = prefs.getBoolean("export_as_jpeg", false);
        boolean toGray = prefs.getBoolean("convert_to_grayscale", false);
        boolean toBw = prefs.getBoolean("convert_to_blackwhite", false);
        String jpegModeSaved = prefs.getString("jpeg_mode", JpegExportOptions.Mode.AUTO.name());
        JpegExportOptions.Mode jpegMode;
        try {
            jpegMode = JpegExportOptions.Mode.valueOf(jpegModeSaved);
        } catch (Exception e) {
            jpegMode = JpegExportOptions.Mode.AUTO;
        }
        String presetSaved = prefs.getString("pdf_preset", null);

        cbIncludeOcr.setChecked(includeOcr);
        // Initialize format selection from preference (PDF default)
        if (exportAsJpeg) {
            rbExportJpeg.setChecked(true);
        } else {
            rbExportPdf.setChecked(true);
        }
        cbGray.setChecked(toGray);
        if (cbBw != null) cbBw.setChecked(toBw);

        // pick default preset if none saved: High for single page, Standard for multi (ExportFragment will compute page count; here fallback Standard)
        PdfQualityPreset preset = presetSaved != null ? PdfQualityPreset.fromName(presetSaved, PdfQualityPreset.STANDARD) : PdfQualityPreset.STANDARD;
        if (preset == PdfQualityPreset.HIGH) rbHigh.setChecked(true);
        else if (preset == PdfQualityPreset.STANDARD) rbStandard.setChecked(true);
        else if (preset == PdfQualityPreset.SMALL) rbSmall.setChecked(true);
        else if (preset == PdfQualityPreset.VERY_SMALL) rbVerySmall.setChecked(true);

        if (jpegMode == JpegExportOptions.Mode.NONE) rbJpegNone.setChecked(true);
        else if (jpegMode == JpegExportOptions.Mode.AUTO) rbJpegAuto.setChecked(true);
        else if (jpegMode == JpegExportOptions.Mode.BW_TEXT) rbJpegBw.setChecked(true);

        // Visibility toggle between PDF and JPEG groups based on selected format
        updateGroups(exportAsJpeg, pdfGroup, jpegGroup);
        RadioGroup formatGroup = view.findViewById(R.id.dialog_format_group);
        if (formatGroup != null) {
            formatGroup.setOnCheckedChangeListener((group, checkedId) -> {
                boolean jpegSelected = checkedId == rbExportJpeg.getId();
                updateGroups(jpegSelected, pdfGroup, jpegGroup);
            });
        }

        // JPEG modes use RadioGroup; mutual exclusivity is handled by the group.

        AlertDialog dialog = new AlertDialog.Builder(ctx)
                .setTitle(R.string.export_options_title)
                .setView(view)
                .setNegativeButton(R.string.cancel, (d, w) -> d.dismiss())
                .setPositiveButton(R.string.confirm, (d, w) -> {
                    boolean incOcr = cbIncludeOcr.isChecked();
                    boolean asJpeg = rbExportJpeg.isChecked();
                    boolean gray = cbGray.isChecked();
                    boolean bw = cbBw != null && cbBw.isChecked();

                    // determine jpeg mode from RadioGroup
                    JpegExportOptions.Mode mode = JpegExportOptions.Mode.AUTO;
                    int jpegCheckedId = jpegModeGroup.getCheckedRadioButtonId();
                    if (jpegCheckedId == rbJpegNone.getId()) mode = JpegExportOptions.Mode.NONE;
                    else if (jpegCheckedId == rbJpegAuto.getId()) mode = JpegExportOptions.Mode.AUTO;
                    else if (jpegCheckedId == rbJpegBw.getId()) mode = JpegExportOptions.Mode.BW_TEXT;

                    // determine pdf preset
                    PdfQualityPreset sel = PdfQualityPreset.STANDARD;
                    int checkedId = pdfPresetGroup.getCheckedRadioButtonId();
                    if (checkedId == rbHigh.getId()) sel = PdfQualityPreset.HIGH;
                    else if (checkedId == rbStandard.getId()) sel = PdfQualityPreset.STANDARD;
                    else if (checkedId == rbSmall.getId()) sel = PdfQualityPreset.SMALL;
                    else if (checkedId == rbVerySmall.getId()) sel = PdfQualityPreset.VERY_SMALL;

                    // persist
                    prefs.edit()
                            .putBoolean("include_ocr", incOcr)
                            .putBoolean("export_as_jpeg", asJpeg)
                            .putBoolean("convert_to_grayscale", gray)
                            .putBoolean("convert_to_blackwhite", bw)
                            .putString("jpeg_mode", mode.name())
                            .putString("pdf_preset", sel.name())
                            .apply();

                    Bundle result = new Bundle();
                    result.putBoolean(BUNDLE_INCLUDE_OCR, incOcr);
                    result.putBoolean(BUNDLE_EXPORT_AS_JPEG, asJpeg);
                    result.putBoolean(BUNDLE_CONVERT_TO_GRAYSCALE, gray);
                    result.putBoolean(BUNDLE_CONVERT_TO_BLACKWHITE, bw);
                    result.putString(BUNDLE_JPEG_MODE, mode.name());
                    result.putString(BUNDLE_PDF_PRESET, sel.name());
                    getParentFragmentManager().setFragmentResult(REQUEST_KEY, result);
                })
                .create();

        // Improve dark mode contrast for dialog buttons similar to other dialogs
        dialog.setOnShowListener(dlg -> de.schliweb.makeacopy.utils.DialogUtils.improveAlertDialogButtonContrastForNight(dialog, ctx));

        return dialog;
    }

    private void updateGroups(boolean exportJpeg, View pdfGroup, View jpegGroup) {
        pdfGroup.setVisibility(exportJpeg ? View.GONE : View.VISIBLE);
        jpegGroup.setVisibility(exportJpeg ? View.VISIBLE : View.GONE);
    }
}
