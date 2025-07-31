package de.schliweb.makeacopy.ui.crop;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.databinding.FragmentCropBinding;
import de.schliweb.makeacopy.ui.camera.CameraViewModel;
import de.schliweb.makeacopy.utils.OpenCVUtils;

/**
 * The CropFragment class is a user interface component responsible for handling image cropping operations.
 * It manages the cropping process through interaction with the CropViewModel and CameraViewModel.
 * The fragment provides a cropping UI, handles user input for cropping or resetting the image,
 * and dynamically adjusts UI elements for system insets.
 */
public class CropFragment extends Fragment {
    private static final String TAG = "CropFragment";

    private FragmentCropBinding binding;
    private CropViewModel cropViewModel;
    private CameraViewModel cameraViewModel;

    /**
     * Called to have the fragment create its view object hierarchy and initialize the UI.
     * This method sets up data binding, observes LiveData objects for UI updates, configures window inset handling,
     * and defines behavior for UI interactions with cropping functionalities.
     *
     * @param inflater           The LayoutInflater object that can be used to inflate any views in the fragment.
     * @param container          The parent view that this fragment's UI should be attached to, or null if it's not attached to a parent.
     * @param savedInstanceState If non-null, this fragment is being re-constructed from a previous saved state as given here.
     * @return The root View for the fragment's layout.
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        OpenCVUtils.init(requireContext());
        cropViewModel = new ViewModelProvider(requireActivity()).get(CropViewModel.class);
        cameraViewModel = new ViewModelProvider(requireActivity()).get(CameraViewModel.class);
        binding = FragmentCropBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        cropViewModel.getText().observe(getViewLifecycleOwner(), binding.textCrop::setText);

        // System-Inset-Margin
        ViewCompat.setOnApplyWindowInsetsListener(binding.buttonContainer, (v, insets) -> {
            de.schliweb.makeacopy.utils.UIUtils.adjustMarginForSystemInsets(binding.buttonContainer, 8); // 8dp extra Abstand
            return insets;
        });
        ViewCompat.setOnApplyWindowInsetsListener(binding.cropButtonContainer, (v, insets) -> {
            de.schliweb.makeacopy.utils.UIUtils.adjustMarginForSystemInsets(binding.cropButtonContainer, 8);
            return insets;
        });

        // Initial UI-Mode
        showCropMode();

        // Crop-Button
        binding.buttonCrop.setOnClickListener(v -> performCrop());

        // Recrop/Confirm-Buttons
        binding.buttonRecrop.setOnClickListener(v -> resetCrop());
        binding.buttonConfirmCrop.setOnClickListener(v -> Navigation.findNavController(requireView()).navigate(R.id.navigation_ocr));

        // Bitmap-Change
        cropViewModel.getImageBitmap().observe(getViewLifecycleOwner(), bitmap -> {
            if (bitmap != null) {
                if (Boolean.TRUE.equals(cropViewModel.isImageCropped().getValue())) {
                    showReviewMode(bitmap);
                } else {
                    showCropMode();
                    binding.imageToCrop.setImageBitmap(bitmap);
                    if (binding.trapezoidSelection != null)
                        binding.trapezoidSelection.setImageBitmap(bitmap);
                }
            }
        });

        cropViewModel.isImageCropped().observe(getViewLifecycleOwner(), isCropped -> {
            if (Boolean.TRUE.equals(isCropped) && cropViewModel.getImageBitmap().getValue() != null) {
                showReviewMode(cropViewModel.getImageBitmap().getValue());
            }
        });

        cameraViewModel.getImageUri().observe(getViewLifecycleOwner(), uri -> {
            if (uri != null) loadImageFromUri(uri);
        });

        // Window Insets
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            de.schliweb.makeacopy.utils.UIUtils.adjustTextViewTopMarginForStatusBar(binding.textCrop, 8);
            de.schliweb.makeacopy.utils.UIUtils.adjustMarginForSystemInsets(binding.cropButtonContainer, 80);
            de.schliweb.makeacopy.utils.UIUtils.adjustMarginForSystemInsets(binding.buttonContainer, 80);
            return insets;
        });

        return root;
    }

    /**
     * Loads an image from the provided URI, sets the image URI and bitmaps in the crop view model,
     * and handles any errors if the image cannot be loaded.
     *
     * @param uri The URI of the image to be loaded.
     */
    private void loadImageFromUri(Uri uri) {
        Bitmap bitmap = de.schliweb.makeacopy.utils.ImageUtils.loadImageFromUri(requireContext(), uri);
        if (bitmap != null) {
            cropViewModel.setImageUri(uri);
            cropViewModel.setImageBitmap(bitmap);
            cropViewModel.setOriginalImageBitmap(bitmap);
        } else {
            // Error Handling
        }
    }

    /**
     * Performs the cropping operation on the current image bitmap.
     * <p>
     * This method checks if a valid image bitmap exists in the cropViewModel. If no bitmap
     * is present, the method exits early. It ensures OpenCV is initialized, transforming the
     * corners of a trapezoid selection from view coordinates to image coordinates if available.
     * Using OpenCV utilities, the method applies a perspective correction based on the transformed
     * trapezoid corners. If the perspective correction operation produces a valid cropped bitmap,
     * it updates the cropViewModel with the new cropped bitmap and marks the image as cropped.
     * <p>
     * Key operations:
     * - Verifies a bitmap is available in the cropViewModel.
     * - Initializes OpenCV if it has not yet been initialized.
     * - Retrieves and transforms trapezoid selection corners, if present.
     * - Performs perspective correction using OpenCV to generate a cropped bitmap.
     * - Updates the cropViewModel with the new cropped image and sets the cropped flag to true.
     */
    private void performCrop() {
        Bitmap originalBitmap = cropViewModel.getImageBitmap().getValue();
        if (originalBitmap == null) return;
        if (!OpenCVUtils.isInitialized()) OpenCVUtils.init(requireContext());

        org.opencv.core.Point[] corners = null;
        if (binding.trapezoidSelection != null) {
            corners = binding.trapezoidSelection.getCorners();
            corners = de.schliweb.makeacopy.utils.CoordinateTransformUtils.transformViewToImageCoordinates(
                    corners, originalBitmap, binding.imageToCrop);
        }
        Bitmap croppedBitmap = OpenCVUtils.applyPerspectiveCorrection(originalBitmap, corners);
        if (croppedBitmap != null) {
            cropViewModel.setImageBitmap(croppedBitmap);
            cropViewModel.setImageCropped(true);
        }
    }

    /**
     * UI-State: Cropping-Modus anzeigen
     */
    private void showCropMode() {
        binding.croppedImage.setVisibility(View.GONE);
        binding.imageToCrop.setVisibility(View.VISIBLE);
        if (binding.trapezoidSelection != null)
            binding.trapezoidSelection.setVisibility(View.VISIBLE);
        binding.cropButtonContainer.setVisibility(View.VISIBLE);
        binding.buttonContainer.setVisibility(View.GONE);
        binding.textCrop.setText(R.string.adjust_the_trapezoid_corners_to_select_the_area_to_crop_then_tap_the_crop_button);
    }

    /**
     * UI-State: Review/Bestätigungsmodus nach Cropping
     */
    private void showReviewMode(Bitmap croppedBitmap) {
        binding.imageToCrop.setVisibility(View.GONE);
        if (binding.trapezoidSelection != null)
            binding.trapezoidSelection.setVisibility(View.GONE);
        binding.croppedImage.setVisibility(View.VISIBLE);
        binding.croppedImage.setImageBitmap(croppedBitmap);
        binding.cropButtonContainer.setVisibility(View.GONE);
        binding.buttonContainer.setVisibility(View.VISIBLE);
        binding.textCrop.setText(R.string.review_your_cropped_image_tap_confirm_to_proceed_or_recrop_to_try_again);
    }

    /**
     * Resets the cropping state and UI to its initial condition.
     * <p>
     * Clears any changes made during the cropping process by restoring the original bitmap,
     * resetting the cropped status in the cropViewModel, and updating the UI to display
     * the cropping mode.
     * <p>
     * Specifically:
     * - Sets the cropped state in the cropViewModel to false.
     * - Retrieves the original bitmap from the cropViewModel and restores it as the active image.
     * - Displays the cropping mode UI.
     * - Ensures the trapezoid selection element is visible and properly initialized, if available.
     */
    private void resetCrop() {
        cropViewModel.setImageCropped(false);
        Bitmap originalBitmap = cropViewModel.getOriginalImageBitmap().getValue();
        if (originalBitmap != null) cropViewModel.setImageBitmap(originalBitmap);
        showCropMode();
        if (binding.trapezoidSelection != null)
            binding.trapezoidSelection.forceVisibleAndInitialized();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (binding.trapezoidSelection != null
                && Boolean.TRUE.equals(cropViewModel.isImageLoaded().getValue())
                && Boolean.FALSE.equals(cropViewModel.isImageCropped().getValue())) {
            showCropMode();
            Bitmap bitmap = cropViewModel.getImageBitmap().getValue();
            if (bitmap != null) binding.trapezoidSelection.setImageBitmap(bitmap);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
