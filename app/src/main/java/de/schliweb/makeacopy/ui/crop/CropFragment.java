package de.schliweb.makeacopy.ui.crop;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;
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
     * Inflates the layout for this fragment and initializes all necessary components including
     * ViewModels, UI bindings, and View listeners. It also observes various LiveData objects
     * to dynamically update the UI based on changes in app state.
     *
     * @param inflater           The LayoutInflater object that can be used to inflate any views in the fragment.
     * @param container          If non-null, this is the parent view that the fragment's UI should be attached to.
     *                           The fragment should not add the view itself, but this can be used to generate
     *                           the LayoutParams of the view.
     * @param savedInstanceState If non-null, this fragment is being re-constructed from a previous
     *                           saved state as given here.
     * @return The root View for the fragment's UI, or null if the fragment does not provide a UI.
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        OpenCVUtils.init(requireContext());
        cropViewModel = new ViewModelProvider(requireActivity()).get(CropViewModel.class);
        cameraViewModel = new ViewModelProvider(requireActivity()).get(CameraViewModel.class);
        binding = FragmentCropBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        // Wire the Magnifier source view: compute and pass image->overlay matrix once layout/bitmap ready
        if (binding.trapezoidSelection != null && binding.imageToCrop != null) {
            // Try immediately; if sizes are 0 we'll retry after bitmap/layout
            tryUpdateMagnifierMapping();
        }

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
        // Rotation buttons (post-crop)
        if (binding.buttonRotateLeft != null) {
            binding.buttonRotateLeft.setOnClickListener(v -> cropViewModel.rotateLeft());
        }
        if (binding.buttonRotateRight != null) {
            binding.buttonRotateRight.setOnClickListener(v -> cropViewModel.rotateRight());
        }
        binding.buttonConfirmCrop.setOnClickListener(v -> {
            android.content.SharedPreferences prefs = requireContext().getSharedPreferences("export_options", android.content.Context.MODE_PRIVATE);
            boolean skipOcr = prefs.getBoolean("skip_ocr", false);
            int dest = skipOcr ? R.id.navigation_export : R.id.navigation_ocr;
            Navigation.findNavController(requireView()).navigate(dest);
        });

        // Bitmap-Change
        cropViewModel.getImageBitmap().observe(getViewLifecycleOwner(), bitmap -> {
            if (bitmap != null) {
                if (Boolean.TRUE.equals(cropViewModel.isImageCropped().getValue())) {
                    showReviewMode(bitmap);
                } else {
                    showCropMode();
                    Bitmap safe = de.schliweb.makeacopy.utils.BitmapUtils.ensureDisplaySafe(bitmap);
                    binding.imageToCrop.setImageBitmap(safe);
                    if (binding.trapezoidSelection != null)
                        binding.trapezoidSelection.setImageBitmap(safe);
                    // With a new bitmap, recompute and wire the magnifier mapping
                    tryUpdateMagnifierMapping();
                }
            }
        });

        cropViewModel.isImageCropped().observe(getViewLifecycleOwner(), isCropped -> {
            if (Boolean.TRUE.equals(isCropped) && cropViewModel.getImageBitmap().getValue() != null) {
                showReviewMode(cropViewModel.getImageBitmap().getValue());
            }
        });
        // React to rotation changes by updating the review preview if applicable
        cropViewModel.getUserRotationDegrees().observe(getViewLifecycleOwner(), degObj -> {
            if (Boolean.TRUE.equals(cropViewModel.isImageCropped().getValue())) {
                Bitmap bmp = cropViewModel.getImageBitmap().getValue();
                if (bmp != null) showReviewMode(bmp);
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

    private void tryUpdateMagnifierMapping() {
        if (binding == null) return;
        final Bitmap bmp = cropViewModel != null ? cropViewModel.getImageBitmap().getValue() : null;
        final ImageView imageView = binding.imageToCrop;
        final View overlay = binding.trapezoidSelection;
        if (bmp == null || imageView == null || overlay == null) return;
        overlay.post(() -> ensureMagnifierMapping(bmp, imageView, overlay, 0));
    }

    private void ensureMagnifierMapping(Bitmap bitmap, ImageView imageView, View overlay, int attempt) {
        if (!isAdded()) return;
        int ow = overlay.getWidth();
        int oh = overlay.getHeight();
        int vw = imageView.getWidth();
        int vh = imageView.getHeight();
        if (ow <= 0 || oh <= 0 || vw <= 0 || vh <= 0) {
            if (attempt < 6) {
                overlay.postDelayed(() -> ensureMagnifierMapping(bitmap, imageView, overlay, attempt + 1), 50);
            }
            return;
        }
        // Use the ImageView as magnifier source view and rely on robust screen-space mapping
        binding.trapezoidSelection.setMagnifierSourceView(imageView, null);
    }

    /**
     * Loads an image from the provided URI, sets the image URI and bitmaps in the crop view model,
     * and handles any errors if the image cannot be loaded.
     *
     * @param uri The URI of the image to be loaded.
     */
    private void loadImageFromUri(Uri uri) {
        String path = cameraViewModel != null && cameraViewModel.getImagePath() != null
                ? cameraViewModel.getImagePath().getValue() : null;
        Bitmap bitmap = de.schliweb.makeacopy.utils.ImageLoader.decode(requireContext(), path, uri);
        if (bitmap != null) {
            cropViewModel.setImageUri(uri);
            cropViewModel.setImageBitmap(bitmap);
            cropViewModel.setOriginalImageBitmap(bitmap);
        } else {
            // Error Handling: show friendly message
            de.schliweb.makeacopy.utils.UIUtils.showToast(requireContext(), getString(R.string.error_displaying_image, "decode failed"), android.widget.Toast.LENGTH_SHORT);
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
     * Configures the UI for cropping mode by adjusting the visibility of relevant views
     * and updating instructional text for the user.
     * <p>
     * The method prepares the fragment for the initial cropping workflow by:
     * - Hiding the cropped image preview.
     * - Displaying the image to be cropped.
     * - Showing the trapezoid selection area, if available.
     * - Making the cropping button container visible.
     * - Hiding the general button container.
     * - Setting an instructional message prompting the user to adjust the trapezoid corners
     * and proceed with cropping.
     */
    private void showCropMode() {
        binding.croppedImage.setVisibility(View.GONE);
        binding.imageToCrop.setVisibility(View.VISIBLE);
        if (binding.trapezoidSelection != null)
            binding.trapezoidSelection.setVisibility(View.VISIBLE);
        binding.cropButtonContainer.setVisibility(View.VISIBLE);
        binding.buttonContainer.setVisibility(View.GONE);
        if (binding.rotationButtonBar != null) binding.rotationButtonBar.setVisibility(View.GONE);
        // Ensure in crop mode the cropped_image (when later shown) would anchor to button_container to avoid overlap
        relinkCroppedImageBottomTo(binding.buttonContainer);
        binding.textCrop.setText(R.string.adjust_the_trapezoid_corners_to_select_the_area_to_crop_then_tap_the_crop_button);
    }

    /**
     * Configures the UI for review mode after a cropping operation.
     * <p>
     * This method adjusts the visibility of relevant UI components for reviewing the cropped image.
     * It hides the image to be cropped and the trapezoid selection UI elements, displays the cropped image,
     * applies any user-defined rotation to the cropped image, and updates the button container for review actions.
     *
     * @param croppedBitmap The bitmap image resulting from the cropping operation, to be displayed in review mode.
     */
    private void showReviewMode(Bitmap croppedBitmap) {
        binding.imageToCrop.setVisibility(View.GONE);
        if (binding.trapezoidSelection != null)
            binding.trapezoidSelection.setVisibility(View.GONE);
        binding.croppedImage.setVisibility(View.VISIBLE);
        // Apply current user rotation for review display (display-safe copy)
        Bitmap safe = de.schliweb.makeacopy.utils.BitmapUtils.ensureDisplaySafe(croppedBitmap);
        Integer rot = (cropViewModel != null && cropViewModel.getUserRotationDegrees() != null)
                ? cropViewModel.getUserRotationDegrees().getValue() : 0;
        int deg = rot == null ? 0 : ((rot % 360) + 360) % 360;
        if (deg != 0 && safe != null && !safe.isRecycled()) {
            try {
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.postRotate(deg);
                Bitmap rotated = android.graphics.Bitmap.createBitmap(safe, 0, 0, safe.getWidth(), safe.getHeight(), m, true);
                if (rotated != null) safe = rotated;
            } catch (Throwable ignore) {
            }
        }
        binding.croppedImage.setImageBitmap(safe);
        binding.cropButtonContainer.setVisibility(View.GONE);
        binding.buttonContainer.setVisibility(View.VISIBLE);
        if (binding.rotationButtonBar != null) binding.rotationButtonBar.setVisibility(View.VISIBLE);
        // In review mode, anchor the preview above the rotation button bar to avoid overlap
        relinkCroppedImageBottomTo(binding.rotationButtonBar != null ? binding.rotationButtonBar : binding.buttonContainer);
        binding.textCrop.setText(R.string.review_your_cropped_image_tap_confirm_to_proceed_or_recrop_to_try_again);
    }

    /**
     * Re-links the bottom constraint of the cropped preview image to the provided target view's top.
     * This prevents the rotation icon bar from overlaying the preview by reserving space above it.
     */
    private void relinkCroppedImageBottomTo(View targetTop) {
        if (binding == null || targetTop == null) return;
        View root = binding.getRoot();
        if (!(root instanceof ConstraintLayout cl)) return;
        ConstraintSet set = new ConstraintSet();
        set.clone(cl);
        try {
            set.clear(R.id.cropped_image, ConstraintSet.BOTTOM);
        } catch (Throwable ignore) {
        }
        set.connect(R.id.cropped_image, ConstraintSet.BOTTOM, targetTop.getId(), ConstraintSet.TOP);
        set.applyTo(cl);
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
            if (bitmap != null) {
                Bitmap safe = de.schliweb.makeacopy.utils.BitmapUtils.ensureDisplaySafe(bitmap);
                binding.trapezoidSelection.setImageBitmap(safe);
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
