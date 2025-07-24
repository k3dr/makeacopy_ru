package de.schliweb.makeacopy.ui.camera;

import android.Manifest;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.Navigation;
import com.google.common.util.concurrent.ListenableFuture;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.databinding.FragmentCameraBinding;
import de.schliweb.makeacopy.utils.UIUtils;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

/**
 * CameraFragment is a fragment that manages camera operations, including
 * capturing images, handling camera permissions, managing a light sensor for
 * low-light scenarios, and providing UI transitions between camera and review modes.
 * <p>
 * This fragment integrates camera controls and user interface components optimized
 * for capturing and reviewing images with additional support for error handling
 * and dynamic light condition adjustments.
 */
public class CameraFragment extends Fragment implements SensorEventListener {

    private static final String TAG = "CameraFragment";
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 100;

    // Light sensor constants
    private static final float LOW_LIGHT_THRESHOLD = 10.0f; // Lux value below which light is considered low
    private static final long MIN_TIME_BETWEEN_PROMPTS = 60000; // 1 minute in milliseconds

    private FragmentCameraBinding binding;
    private CameraViewModel cameraViewModel;

    private ImageCapture imageCapture;
    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private boolean isFlashlightOn = false;
    private boolean hasFlash = false;

    // Light sensor variables
    private SensorManager sensorManager;
    private Sensor lightSensor;
    private boolean hasLightSensor = false;
    private boolean lowLightPromptShown = false;
    private long lastPromptTime = 0;

    private ActivityResultLauncher<String> requestPermissionLauncher;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        cameraViewModel = new ViewModelProvider(requireActivity()).get(CameraViewModel.class);

        requestPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
            if (isGranted) {
                cameraViewModel.setCameraPermissionGranted(true);
            } else {
                UIUtils.showToast(requireContext(), R.string.msg_camera_permission_required, Toast.LENGTH_LONG);
            }
        });

        binding = FragmentCameraBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        // System-Inset-Margin für Button-Container dynamisch setzen
        ViewCompat.setOnApplyWindowInsetsListener(binding.buttonContainer, (v, insets) -> {
            de.schliweb.makeacopy.utils.UIUtils.adjustMarginForSystemInsets(binding.buttonContainer, 8); // 8dp extra Abstand
            return insets;
        });
        ViewCompat.setOnApplyWindowInsetsListener(binding.scanButtonContainer, (v, insets) -> {
            de.schliweb.makeacopy.utils.UIUtils.adjustMarginForSystemInsets(binding.scanButtonContainer, 8);
            return insets;
        });

        // Init UI visibility
        showCameraMode();

        final TextView textView = binding.textCamera;
        cameraViewModel.getText().observe(getViewLifecycleOwner(), textView::setText);

        // Observe the image URI and switch modes accordingly
        cameraViewModel.getImageUri().observe(getViewLifecycleOwner(), uri -> {
            if (uri != null) {
                displayCapturedImage(uri);
            }
            // Don't call resetCamera() when URI is null to avoid potential recursive loop
            // The UI will be reset explicitly when needed
        });

        // Set up scan button
        binding.buttonScan.setOnClickListener(v -> {
            if (cameraViewModel != null && cameraViewModel.isCameraPermissionGranted().getValue() == Boolean.TRUE) {
                captureImage();
            } else {
                checkCameraPermission();
            }
        });

        // Set up flashlight button
        binding.buttonFlash.setOnClickListener(v -> toggleFlashlight());

        // Set up retake and confirm button listeners
        binding.buttonRetake.setOnClickListener(v -> {
            try {
                if (isAdded() && binding != null) { // Check if fragment is still attached and binding is not null
                    // Disable the button to prevent multiple clicks
                    v.setEnabled(false);

                    // Show a toast to indicate the camera is being reset
                    UIUtils.showToast(requireContext(), "Resetting camera...", Toast.LENGTH_SHORT);

                    // Reset the camera with a slight delay to allow the UI to update
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        resetCamera();

                        // Re-enable the button after a delay
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                            if (isAdded() && binding != null && binding.buttonRetake != null) {
                                binding.buttonRetake.setEnabled(true);
                            }
                        }, 1000); // 1 second delay before re-enabling
                    }, 100); // 100ms delay before resetting
                }
            } catch (Exception e) {
                Log.e(TAG, "Error handling retake button click: " + e.getMessage());
                if (isAdded()) {
                    UIUtils.showToast(requireContext(), "Error resetting camera: " + e.getMessage(), Toast.LENGTH_SHORT);

                    // Re-enable the button in case of error
                    v.setEnabled(true);
                }
            }
        });

        binding.buttonConfirm.setOnClickListener(v -> {
            if (isAdded() && getView() != null) { // Check if fragment is still attached and view is not null
                Navigation.findNavController(requireView()).navigate(R.id.navigation_crop);
            }
        });

        // Insets (Status bar)
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            int topInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
            ViewGroup.MarginLayoutParams textParams = (ViewGroup.MarginLayoutParams) binding.textCamera.getLayoutParams();
            textParams.topMargin = (int) (8 * getResources().getDisplayMetrics().density) + topInset;
            binding.textCamera.setLayoutParams(textParams);
            return insets;
        });

        // Camera permission
        cameraViewModel.isCameraPermissionGranted().observe(getViewLifecycleOwner(), granted -> {
            if (granted) {
                initializeCamera();
            }
        });
        checkCameraPermission();

        // Initialize light sensor
        initLightSensor();

        // Handle back: in review mode -> reset, sonst default
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (binding != null && binding.capturedImage.getVisibility() == View.VISIBLE) {
                    // Just call resetCamera() which already handles setting the image URI to null
                    resetCamera();
                } else {
                    this.setEnabled(false);
                    requireActivity().getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        return root;
    }

    private void checkCameraPermission() {
        if (!isAdded()) return; // Prevent issues if fragment is not attached

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (requestPermissionLauncher != null) {
                requestPermissionLauncher.launch(Manifest.permission.CAMERA);
            }
        } else {
            if (cameraViewModel != null) {
                cameraViewModel.setCameraPermissionGranted(true);
            }
        }
    }

    private int getDisplayRotation() {
        if (!isAdded()) return 0; // Default to 0 if not attached

        try {
            WindowManager windowManager = (WindowManager) requireContext().getSystemService(Context.WINDOW_SERVICE);
            if (windowManager == null) return 0;

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                android.view.Display display = requireContext().getDisplay();
                return display != null ? display.getRotation() : 0;
            } else {
                @SuppressWarnings("deprecation") int rotation = windowManager.getDefaultDisplay().getRotation();
                return rotation;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting display rotation: " + e.getMessage());
            return 0; // Default to 0 in case of error
        }
    }

    private void initializeCamera() {
        if (binding == null || !isAdded()) {
            Log.d(TAG, "initializeCamera: Skipping camera initialization - binding is null or fragment not attached");
            return;
        }

        // Show loading indicator
        binding.textCamera.setText("Initializing camera...");

        PreviewView viewFinder = binding.viewFinder;
        viewFinder.setImplementationMode(PreviewView.ImplementationMode.PERFORMANCE);
        viewFinder.setScaleType(PreviewView.ScaleType.FIT_CENTER);

        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext());

        cameraProviderFuture.addListener(() -> {
            try {
                if (binding == null || !isAdded() || getView() == null) {
                    Log.d(TAG, "Camera initialization callback: Skipping - binding is null, fragment not attached, or view is null");
                    return;
                }

                Log.d(TAG, "Getting camera provider from future");
                cameraProvider = cameraProviderFuture.get();
                if (cameraProvider == null) {
                    Log.e(TAG, "Failed to get camera provider - provider is null");
                    if (isAdded()) {
                        UIUtils.showToast(requireContext(), "Failed to initialize camera - provider is null", Toast.LENGTH_SHORT);
                        binding.textCamera.setText(R.string.camera_ready_tap_the_button_to_scan_a_document);
                    }
                    return;
                }

                Log.d(TAG, "Creating preview and image capture use cases");
                // Get the display rotation for proper orientation
                int rotation = getDisplayRotation();

                // Create preview use case
                Preview preview = new Preview.Builder().setTargetRotation(rotation).build();

                // Create image capture use case
                imageCapture = new ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).setTargetRotation(rotation).build();

                // Select back camera
                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                Log.d(TAG, "Unbinding any existing use cases");
                // Unbind any existing use cases before binding new ones
                cameraProvider.unbindAll();

                Log.d(TAG, "Binding use cases to lifecycle");
                // Bind use cases to lifecycle
                camera = cameraProvider.bindToLifecycle(getViewLifecycleOwner(), cameraSelector, preview, imageCapture);

                // Check if device has flashlight
                hasFlash = requireContext().getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH);

                // Update flashlight button visibility based on flash availability
                if (binding.buttonFlash != null) {
                    binding.buttonFlash.setVisibility(hasFlash ? View.VISIBLE : View.GONE);
                    // Reset flashlight state
                    isFlashlightOn = false;
                    binding.buttonFlash.setImageResource(R.drawable.ic_flash_off);
                }

                // Set surface provider for preview
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

                Log.d(TAG, "Camera initialized successfully");
                // Update UI to indicate camera is ready
                binding.textCamera.setText(R.string.camera_ready_tap_the_button_to_scan_a_document);
            } catch (ExecutionException e) {
                Log.e(TAG, "Error initializing camera (ExecutionException): " + e.getMessage(), e);
                handleCameraInitializationError(e);
            } catch (InterruptedException e) {
                Log.e(TAG, "Error initializing camera (InterruptedException): " + e.getMessage(), e);
                Thread.currentThread().interrupt(); // Restore interrupted status
                handleCameraInitializationError(e);
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error initializing camera: " + e.getMessage(), e);
                handleCameraInitializationError(e);
            }
        }, ContextCompat.getMainExecutor(requireContext()));
    }

    /**
     * Handles errors that occur during camera initialization
     *
     * @param e The exception that occurred
     */
    private void handleCameraInitializationError(Exception e) {
        if (!isAdded() || binding == null) return;

        // Log the error
        Log.e(TAG, "Camera initialization error: " + e.getMessage());

        // Show a toast message to the user
        UIUtils.showToast(requireContext(), "Error initializing camera: " + e.getMessage(), Toast.LENGTH_SHORT);

        // Update the UI to indicate camera is not ready
        binding.textCamera.setText(R.string.camera_ready_tap_the_button_to_scan_a_document);

        // Try to recover by scheduling a retry after a delay
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (isAdded() && binding != null && cameraViewModel != null && cameraViewModel.isCameraPermissionGranted().getValue() == Boolean.TRUE) {
                Log.d(TAG, "Retrying camera initialization after error");
                initializeCamera();
            }
        }, 3000); // 3 second delay before retrying
    }

    /**
     * Captures an image using the camera
     * Includes error handling and UI feedback
     */
    private void captureImage() {
        if (!isAdded() || binding == null) {
            Log.d(TAG, "captureImage: Skipping - fragment not attached or binding is null");
            return;
        }

        if (imageCapture == null) {
            Log.e(TAG, "captureImage: Camera not initialized");
            UIUtils.showToast(requireContext(), "Camera not initialized. Please try again.", Toast.LENGTH_SHORT);
            initializeCamera();
            return;
        }

        try {
            binding.textCamera.setText(R.string.processing_image);

            // Speicherort im app-eigenen Ordner
            File outputDir = new File(requireContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES), "MakeACopy");
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }

            // Datei mit Zeitstempel erstellen
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis());
            File photoFile = new File(outputDir, "MakeACopy_" + timestamp + ".jpg");

            // OutputFileOptions für ImageCapture
            ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(photoFile).build();

            Log.d(TAG, "Taking picture...");
            imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(requireContext()), new ImageCapture.OnImageSavedCallback() {
                @Override
                public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                    Log.d(TAG, "Image saved to: " + photoFile.getAbsolutePath());

                    Uri imageUri = FileProvider.getUriForFile(requireContext(), requireContext().getPackageName() + ".fileprovider", photoFile);

                    if (cameraViewModel != null && isAdded()) {
                        cameraViewModel.setImageUri(imageUri);
                        displayCapturedImage(imageUri);
                    }
                }

                @Override
                public void onError(@NonNull ImageCaptureException exception) {
                    Log.e(TAG, "Image capture failed: " + exception.getMessage(), exception);
                    handleCaptureError(exception);
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Unexpected error in captureImage: " + e.getMessage(), e);
            handleCaptureError(e);
        }
    }

    /**
     * Handles errors that occur during image capture
     *
     * @param exception The exception that occurred
     */
    private void handleCaptureError(Exception exception) {
        if (!isAdded() || binding == null) return;

        // Show error message
        UIUtils.showToast(requireContext(), "Image capture failed: " + exception.getMessage(), Toast.LENGTH_SHORT);

        // Reset UI
        binding.textCamera.setText(R.string.camera_ready_tap_the_button_to_scan_a_document);
    }

    private void displayCapturedImage(Uri imageUri) {
        if (binding == null || !isAdded()) return; // Prevent NPE if binding is null or fragment is not attached

        // Show loading indicator
        binding.textCamera.setText(R.string.processing_image);

        // Load image asynchronously
        de.schliweb.makeacopy.utils.ImageUtils.loadImageFromUriAsync(requireContext(), imageUri, new de.schliweb.makeacopy.utils.ImageUtils.ImageLoadCallback() {
            @Override
            public void onImageLoaded(Bitmap bitmap) {
                if (binding == null || !isAdded()) return; // Check again in case things changed

                binding.capturedImage.setImageBitmap(bitmap);

                // Wechsel in den Review-Modus
                showReviewMode();
            }

            @Override
            public void onImageLoadFailed(String error) {
                if (!isAdded()) return; // Check if fragment is still attached

                UIUtils.showToast(requireContext(), "Error displaying image: " + error, Toast.LENGTH_SHORT);
                if (getView() != null) {
                    Navigation.findNavController(requireView()).navigate(R.id.navigation_crop);
                }
            }
        });
    }

    /**
     * Updates the user interface to display the camera mode.
     * <p>
     * This method adjusts the visibility and content of various UI elements to
     * transition to the "camera mode" of the application. It ensures that the
     * camera viewfinder and scan-related buttons are shown while hiding elements
     * related to the captured image or review mode. Additionally, it updates
     * instructional text to guide the user in this mode.
     * <p>
     * Key behaviors:
     * - Prevents a potential NullPointerException by checking if `binding` is null.
     * - Makes the viewfinder visible and hides the captured image and button container.
     * - Displays the scan button and associated container, ensuring non-null checks.
     * - Updates the text with a localized string prompting the user to start scanning.
     * - Resets the `lowLightPromptShown` flag to allow re-prompting when needed.
     */
    private void showCameraMode() {
        if (binding == null) return; // Prevent NPE if binding is null

        binding.viewFinder.setVisibility(View.VISIBLE);
        binding.capturedImage.setVisibility(View.GONE);
        binding.buttonContainer.setVisibility(View.GONE);
        binding.buttonScan.setVisibility(View.VISIBLE);
        if (binding.scanButtonContainer != null) {
            binding.scanButtonContainer.setVisibility(View.VISIBLE);
        }
        binding.textCamera.setText(R.string.camera_ready_tap_the_button_to_scan_a_document);

        // Reset low light prompt flag when switching to camera mode
        // This allows the prompt to be shown again if needed
        lowLightPromptShown = false;
    }

    /**
     * Transitions the UI to the review mode.
     * <p>
     * This method updates the visibility and content of various UI components
     * in the fragment to enable the "review mode" feature, where the captured
     * image is displayed for review. It hides the camera view and scan button,
     * shows the captured image along with the associated buttons, and updates
     * the instructional text for user guidance.
     * <p>
     * Key behaviors:
     * - Ensures the `binding` is not null to prevent potential NullPointerExceptions.
     * - Sets the visibility of the camera view to `GONE` and the captured image
     * to `VISIBLE`.
     * - Hides the scan-related buttons and container while showing the button
     * container for review actions.
     * - Updates the text with a localized message guiding users to confirm or
     * retake their scan.
     */
    private void showReviewMode() {
        if (binding == null) return; // Prevent NPE if binding is null

        binding.viewFinder.setVisibility(View.GONE);
        binding.capturedImage.setVisibility(View.VISIBLE);
        binding.buttonContainer.setVisibility(View.VISIBLE);
        binding.buttonScan.setVisibility(View.GONE);
        if (binding.scanButtonContainer != null) {
            binding.scanButtonContainer.setVisibility(View.GONE);
        }
        binding.textCamera.setText(R.string.review_your_scan_tap_confirm_to_proceed_or_retake_to_try_again);
    }

    /**
     * Toggles the flashlight on or off
     */
    private void toggleFlashlight() {
        if (camera == null || !hasFlash || !isAdded()) {
            // If camera is not initialized, device has no flash, or fragment is not attached
            if (isAdded()) {
                UIUtils.showToast(requireContext(), R.string.flashlight_not_available, Toast.LENGTH_SHORT);
            }
            return;
        }

        try {
            isFlashlightOn = !isFlashlightOn;
            camera.getCameraControl().enableTorch(isFlashlightOn);

            // Update the button icon
            if (binding != null && binding.buttonFlash != null) {
                binding.buttonFlash.setImageResource(isFlashlightOn ? R.drawable.ic_flash_on : R.drawable.ic_flash_off);

                // Show a toast message
                UIUtils.showToast(requireContext(), isFlashlightOn ? R.string.flashlight_on : R.string.flashlight_off, Toast.LENGTH_SHORT);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error toggling flashlight: " + e.getMessage(), e);
            if (isAdded()) {
                UIUtils.showToast(requireContext(), "Error toggling flashlight: " + e.getMessage(), Toast.LENGTH_SHORT);
            }
        }
    }

    /**
     * Turns off the flashlight if it's on
     */
    private void turnOffFlashlight() {
        if (camera != null && isFlashlightOn && isAdded()) {
            try {
                camera.getCameraControl().enableTorch(false);
                isFlashlightOn = false;
                if (binding != null && binding.buttonFlash != null) {
                    binding.buttonFlash.setImageResource(R.drawable.ic_flash_off);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error turning off flashlight: " + e.getMessage(), e);
            }
        }
    }

    private void resetCamera() {
        if (binding == null || !isAdded()) return; // Prevent NPE if binding is null or fragment is not attached

        // Show loading indicator
        binding.textCamera.setText(R.string.processing_image);

        // Turn off flashlight when resetting camera
        turnOffFlashlight();

        // Clean up resources
        if (binding.capturedImage.getDrawable() instanceof android.graphics.drawable.BitmapDrawable bitmapDrawable) {
            Bitmap bitmap = bitmapDrawable.getBitmap();
            if (bitmap != null && !bitmap.isRecycled()) {
                // Clear the ImageView before recycling to prevent crashes
                binding.capturedImage.setImageDrawable(null);
                // Recycle the bitmap to free memory
                bitmap.recycle();
            }
        }

        // Update UI to camera mode
        showCameraMode();

        // Reset image URI in view model
        if (cameraViewModel != null) {
            cameraViewModel.setImageUri(null);
        }

        // Reset low light prompt flag
        lowLightPromptShown = false;

        // Unbind and reinitialize camera
        try {
            if (cameraProvider != null) {
                // Unbind all use cases before reinitializing
                cameraProvider.unbindAll();

                // Reinitialize camera on a background thread
                android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
                handler.post(() -> {
                    if (isAdded() && cameraViewModel != null && cameraViewModel.isCameraPermissionGranted().getValue() == Boolean.TRUE) {
                        initializeCamera();
                    }
                });
            } else {
                // If cameraProvider is null, try to initialize camera directly
                if (cameraViewModel != null && cameraViewModel.isCameraPermissionGranted().getValue() == Boolean.TRUE) {
                    initializeCamera();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error resetting camera: " + e.getMessage());
            if (isAdded()) {
                UIUtils.showToast(requireContext(), "Error resetting camera: " + e.getMessage(), Toast.LENGTH_SHORT);
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Turn off flashlight if it's on
        turnOffFlashlight();
        // Unregister sensor listener
        if (sensorManager != null && lightSensor != null) {
            sensorManager.unregisterListener(this);
        }
        if (cameraProvider != null) cameraProvider.unbindAll();
        binding = null;
    }

    /**
     * Initializes the light sensor
     */
    private void initLightSensor() {
        if (!isAdded()) return;

        try {
            // Get the sensor manager
            sensorManager = (SensorManager) requireContext().getSystemService(Context.SENSOR_SERVICE);
            if (sensorManager != null) {
                // Get the light sensor
                lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
                hasLightSensor = (lightSensor != null);

                if (hasLightSensor) {
                    Log.d(TAG, "Light sensor available");
                    // Register the sensor listener
                    sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
                } else {
                    Log.d(TAG, "Light sensor not available on this device");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error initializing light sensor: " + e.getMessage(), e);
            hasLightSensor = false;
        }
    }

    /**
     * Shows a dialog prompting the user to turn on the flashlight
     */
    private void showLowLightPrompt() {
        if (!isAdded() || binding == null) return;

        // Check if we should show the prompt
        long currentTime = System.currentTimeMillis();
        if (lowLightPromptShown || (currentTime - lastPromptTime) < MIN_TIME_BETWEEN_PROMPTS) {
            return;
        }

        // Create and show the dialog
        try {
            AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
            builder.setMessage(R.string.low_light_detected).setPositiveButton(R.string.flashlight_on, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    // Turn on the flashlight
                    if (!isFlashlightOn && hasFlash) {
                        toggleFlashlight();
                    }
                }
            }).setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    // User cancelled the dialog
                    dialog.dismiss();
                }
            });

            // Create and show the AlertDialog
            AlertDialog dialog = builder.create();
            dialog.show();

            // Update state
            lowLightPromptShown = true;
            lastPromptTime = currentTime;
        } catch (Exception e) {
            Log.e(TAG, "Error showing low light prompt: " + e.getMessage(), e);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_LIGHT) {
            float lightLevel = event.values[0];
            Log.d(TAG, "Light level: " + lightLevel + " lux");

            // Check if light level is below threshold and camera is active
            if (lightLevel < LOW_LIGHT_THRESHOLD && binding != null && binding.viewFinder.getVisibility() == View.VISIBLE && !isFlashlightOn && hasFlash) {

                // Show the low light prompt
                showLowLightPrompt();
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Not used, but required by SensorEventListener interface
        if (sensor.getType() == Sensor.TYPE_LIGHT) {
            Log.d(TAG, "Light sensor accuracy changed: " + accuracy);
        }
    }
}
