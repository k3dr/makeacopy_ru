package de.schliweb.makeacopy.ui.camera;

import android.Manifest;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.*;
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
import de.schliweb.makeacopy.BuildConfig;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.databinding.FragmentCameraBinding;
import de.schliweb.makeacopy.ui.crop.CropViewModel;
import de.schliweb.makeacopy.utils.UIUtils;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

/**
 * CameraFragment is responsible for handling the camera functionality and user interactions
 * within the application. It includes methods for initializing the camera, managing light
 * sensor interactions, handling orientation, capturing images, and managing flashlight
 * functionality. It interacts with CameraViewModel and CropViewModel to manage state and
 * data associated with the camera and image operations.
 * <p>
 * Fields:
 * - TAG: String constant for logging purposes.
 * - CAMERA_PERMISSION_REQUEST_CODE: Integer constant for camera permission requests.
 * - LOW_LIGHT_THRESHOLD: Threshold value for detecting low light conditions.
 * - MIN_TIME_BETWEEN_PROMPTS: Minimum time interval between showing prompts.
 * - binding: Data binding object for the fragment's layout.
 * - cameraViewModel: ViewModel to manage camera-related state.
 * - imageCapture: ImageCapture instance for handling image capture operations.
 * - cameraProvider: CameraProvider for managing camera lifecycle.
 * - camera: Camera instance representing the currently active camera.
 * - isFlashlightOn: Boolean flag indicating if the flashlight is currently on.
 * - hasFlash: Boolean flag indicating if the device has a flash available.
 * - sensorManager: SensorManager instance for managing hardware sensors.
 * - lightSensor: Sensor instance representing the device's light sensor.
 * - hasLightSensor: Boolean flag indicating if the device has a light sensor.
 * - lowLightPromptShown: Boolean flag indicating if the low-light prompt has been shown.
 * - lastPromptTime: Long value representing the timestamp of the last shown prompt.
 * - isLowLightDialogVisible: Boolean flag indicating if the low-light dialog is currently visible.
 * - requestPermissionLauncher: ActivityResultLauncher for handling runtime permissions.
 * - orientationListener: Listener for handling orientation changes.
 * - reinitScheduled: Boolean flag indicating if camera reinitialization is scheduled.
 * - cropViewModel: ViewModel for managing image cropping functionality.
 * <p>
 * Methods:
 * - onCreateView: Inflates the layout and initializes UI components for the fragment.
 * - onResume: Registers necessary lifecycle components such as the sensor listener.
 * - onPause: Unregisters lifecycle components such as the sensor listener.
 * - checkCameraPermission: Checks and requests camera permission if not already granted.
 * - getViewFinderRotation: Obtains the current rotation of the viewfinder.
 * - initializeCamera: Sets up the camera, including configuring preview and image capture use cases.
 * - handleCameraInitializationError: Handles exceptions occurring during camera initialization.
 * - captureImage: Captures an image using the camera and processes it.
 * - handleCaptureError: Handles exceptions occurring during image capture operations.
 * - displayCapturedImage: Displays the captured image using a URI.
 * - showCameraMode: Updates the UI to show camera mode.
 * - showReviewMode: Updates the UI to show image review mode.
 * - toggleFlashlight: Toggles the flashlight on or off.
 * - turnOffFlashlight: Turns off the flashlight if it is currently on.
 * - resetCamera: Resets camera components and associated states.
 * - onDestroyView: Cleans up resources when the fragment view is destroyed.
 * - initLightSensor: Initializes and checks for availability of the light sensor.
 * - showLowLightPrompt: Displays a dialog to prompt the user to turn on the flashlight in low-light conditions.
 * - onSensorChanged: Responds to changes in sensor values, such as light level changes.
 * - onAccuracyChanged: Handles changes in the accuracy of the sensors.
 */
public class CameraFragment extends Fragment implements SensorEventListener {

    private static final String TAG = "CameraFragment";
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 100;

    // Light sensor constants
    private static final float LOW_LIGHT_THRESHOLD = 10.0f; // Lux value below which light is considered low
    private static final long MIN_TIME_BETWEEN_PROMPTS = 60000; // 1 minute

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
    private boolean isLowLightDialogVisible = false; // (9) Entprellung

    private ActivityResultLauncher<String> requestPermissionLauncher;

    // (1) Orientation listener to keep target rotation in sync
    private OrientationEventListener orientationListener;

    // (5) Prevent repeated re-initialization queues
    private boolean reinitScheduled = false;

    private CropViewModel cropViewModel;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        cameraViewModel = new ViewModelProvider(requireActivity()).get(CameraViewModel.class);

        requestPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    if (isGranted) {
                        cameraViewModel.setCameraPermissionGranted(true);
                    } else {
                        UIUtils.showToast(requireContext(), R.string.msg_camera_permission_required, Toast.LENGTH_LONG);
                    }
                });

        binding = FragmentCameraBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        ViewCompat.setOnApplyWindowInsetsListener(binding.buttonContainer, (v, insets) -> {
            de.schliweb.makeacopy.utils.UIUtils.adjustMarginForSystemInsets(binding.buttonContainer, 8);
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
            // when null, UI will be reset explicitly when needed
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
                if (isAdded() && binding != null) {
                    v.setEnabled(false);
                    UIUtils.showToast(requireContext(), R.string.resetting_camera, Toast.LENGTH_SHORT); // (7) keine Hardcodes
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        resetCamera();
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            if (isAdded() && binding != null && binding.buttonRetake != null) {
                                binding.buttonRetake.setEnabled(true);
                            }
                        }, 1000);
                    }, 100);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error handling retake button click: " + e.getMessage());
                if (isAdded()) {
                    UIUtils.showToast(requireContext(), getString(R.string.error_resetting_camera, e.getMessage()), Toast.LENGTH_SHORT);
                    v.setEnabled(true);
                }
            }
        });

        cropViewModel = new ViewModelProvider(requireActivity()).get(CropViewModel.class);

        binding.buttonConfirm.setOnClickListener(v -> {
            if (isAdded() && getView() != null) {
                cropViewModel.setImageCropped(false);

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

        // Only capability detection here; registration happens in onResume (3)
        initLightSensor();

        // Handle back: in review mode -> reset, sonst default
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (binding != null && binding.capturedImage.getVisibility() == View.VISIBLE) {
                    resetCamera();
                } else {
                    this.setEnabled(false);
                    // (10) Fallback, kein rekursives Muster – Dispatcher erneut aufrufen
                    requireActivity().getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        return root;
    }

    // (3) Sensor-Registrierung lifecycle-freundlich
    @Override
    public void onResume() {
        super.onResume();
        if (hasLightSensor && sensorManager != null && lightSensor != null) {
            sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
        }
        if (orientationListener != null) {
            orientationListener.enable();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (orientationListener != null) {
            orientationListener.disable();
        }
    }

    /**
     * Verifies if the application has been granted the camera permission by the user. If the
     * permission is not yet granted, it utilizes a permission launcher to request it. If the
     * permission is already granted, it updates the camera view model to reflect this status.
     * <p>
     * This method ensures that the application's camera functionality is only initialized or
     * activated when the necessary permission is obtained.
     * <p>
     * Preconditions:
     * - The fragment is added to its host activity.
     * <p>
     * Behavior:
     * - If the permission is not granted:
     * - Launches the permission request dialog using the `requestPermissionLauncher`.
     * - If the permission is granted:
     * - Updates the `cameraViewModel` to indicate that the camera permission is granted.
     */
    private void checkCameraPermission() {
        if (!isAdded()) return;

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

    // (1) Orientation direkt vom PreviewView / Display beziehen
    private int getViewFinderRotation() {
        if (binding != null && binding.viewFinder.getDisplay() != null) {
            return binding.viewFinder.getDisplay().getRotation();
        }
        return Surface.ROTATION_0;
    }

    /**
     * Initializes the camera and configures the necessary use cases such as preview and image capture.
     * This method ensures that the camera is correctly set up and bound to the fragment's lifecycle.
     * It handles different scenarios, such as verifying prerequisites, initializing the required
     * camera components, and managing orientation changes.
     * <p>
     * Preconditions:
     * - The fragment is added to its host activity.
     * - The binding and view hierarchy are not null.
     * <p>
     * Behavior:
     * - Configures the `PreviewView` for performance mode and scale type.
     * - Fetches an instance of `ProcessCameraProvider` asynchronously to manage camera lifecycle.
     * - Unbinds any existing use cases before binding new preview and image capture use cases.
     * - Initializes the `Preview` with the appropriate target rotation.
     * - Sets up the `ImageCapture` use case with target rotation, flash mode, and minimal latency.
     * - Sets the surface provider for preview and dynamically updates the target rotation based on
     * device orientation using an orientation change listener.
     * - Manages the availability of the flash unit and updates the UI accordingly.
     * <p>
     * Exception Handling:
     * - Catches and logs `ExecutionException` and `InterruptedException` during camera provider retrieval.
     * - Logs and handles any unexpected exceptions that occur during the initialization process.
     * - Displays a user-facing message if camera initialization fails.
     * <p>
     * Note:
     * - The camera initialization will be skipped if the fragment is not attached, the binding is null,
     * or the view is null.
     * - This method is designed to dynamically configure and handle camera-related states for optimal
     * performance and user experience.
     */
    private void initializeCamera() {
        if (binding == null || !isAdded()) {
            Log.d(TAG, "initializeCamera: Skipping - binding is null or fragment not attached");
            return;
        }

        binding.textCamera.setText(R.string.initializing_camera);

        PreviewView viewFinder = binding.viewFinder;
        viewFinder.setImplementationMode(PreviewView.ImplementationMode.PERFORMANCE);
        viewFinder.setScaleType(PreviewView.ScaleType.FIT_CENTER);

        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext());

        cameraProviderFuture.addListener(() -> {
            try {
                if (binding == null || !isAdded() || getView() == null) {
                    Log.d(TAG, "Camera init callback: Skipping - binding null, fragment not attached, or view null");
                    return;
                }

                Log.d(TAG, "Getting camera provider from future");
                cameraProvider = cameraProviderFuture.get();
                if (cameraProvider == null) {
                    Log.e(TAG, "Failed to get camera provider - provider is null");
                    if (isAdded()) {
                        UIUtils.showToast(requireContext(), R.string.error_camera_provider_null, Toast.LENGTH_SHORT);
                        binding.textCamera.setText(R.string.camera_ready_tap_the_button_to_scan_a_document);
                    }
                    return;
                }

                Log.d(TAG, "Creating preview and image capture use cases");

                int rotation = getViewFinderRotation();

                Preview preview = new Preview.Builder()
                        .setTargetRotation(rotation)
                        .build();

                // (4) Capture-Flash-Modus explizit setzen (Torch bleibt separat)
                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .setTargetRotation(rotation)
                        .setFlashMode(ImageCapture.FLASH_MODE_OFF)
                        .build();

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                Log.d(TAG, "Unbinding any existing use cases");
                cameraProvider.unbindAll();

                Log.d(TAG, "Binding use cases to lifecycle");
                camera = cameraProvider.bindToLifecycle(getViewLifecycleOwner(), cameraSelector, preview, imageCapture);

                // (2) Flash-Verfügbarkeit über CameraInfo (präziser als PM Feature)
                hasFlash = camera.getCameraInfo().hasFlashUnit();
                if (binding.buttonFlash != null) {
                    binding.buttonFlash.setVisibility(hasFlash ? View.VISIBLE : View.GONE);
                    isFlashlightOn = false;
                    binding.buttonFlash.setImageResource(R.drawable.ic_flash_off);
                }

                // Set surface provider for preview
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

                // (1) Orientation listener setzt TargetRotation dynamisch
                if (orientationListener == null) {
                    orientationListener = new OrientationEventListener(requireContext()) {
                        @Override
                        public void onOrientationChanged(int orientation) {
                            if (imageCapture == null || binding == null || !isAdded()) return;
                            int rot = getViewFinderRotation();
                            imageCapture.setTargetRotation(rot);
                        }
                    };
                    orientationListener.enable();
                }

                Log.d(TAG, "Camera initialized successfully");
                binding.textCamera.setText(R.string.camera_ready_tap_the_button_to_scan_a_document);

            } catch (ExecutionException e) {
                Log.e(TAG, "Error initializing camera (ExecutionException): " + e.getMessage(), e);
                handleCameraInitializationError(e);
            } catch (InterruptedException e) {
                Log.e(TAG, "Error initializing camera (InterruptedException): " + e.getMessage(), e);
                Thread.currentThread().interrupt();
                handleCameraInitializationError(e);
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error initializing camera: " + e.getMessage(), e);
                handleCameraInitializationError(e);
            }
        }, ContextCompat.getMainExecutor(requireContext()));
    }

    /**
     * Handles errors that occur during the initialization of the camera.
     * This method provides appropriate user feedback and retries the initialization process
     * after a short delay if conditions permit.
     *
     * @param e The exception that was thrown during camera initialization.
     */
    private void handleCameraInitializationError(Exception e) {
        if (!isAdded() || binding == null) return;

        Log.e(TAG, "Camera initialization error: " + e.getMessage());
        UIUtils.showToast(requireContext(), getString(R.string.error_initializing_camera, e.getMessage()), Toast.LENGTH_SHORT);
        binding.textCamera.setText(R.string.camera_ready_tap_the_button_to_scan_a_document);

        if (!reinitScheduled) { // (5) kein multiples Queuen
            reinitScheduled = true;
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                reinitScheduled = false;
                if (isAdded() && cameraViewModel != null &&
                        Boolean.TRUE.equals(cameraViewModel.isCameraPermissionGranted().getValue())) {
                    initializeCamera();
                }
            }, 3000);
        }
    }

    /**
     * Captures an image using the camera
     */
    private void captureImage() {
        if (!isAdded() || binding == null) {
            Log.d(TAG, "captureImage: Skipping - fragment not attached or binding is null");
            return;
        }

        if (imageCapture == null) {
            Log.e(TAG, "captureImage: Camera not initialized");
            UIUtils.showToast(requireContext(), R.string.error_camera_not_initialized, Toast.LENGTH_SHORT);
            initializeCamera();
            return;
        }

        try {
            binding.textCamera.setText(R.string.processing_image);

            // Speicherort im app-eigenen Ordner
            File outputDir = new File(requireContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES), "MakeACopy");
            if (!outputDir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                outputDir.mkdirs();
            }

            // Datei mit Zeitstempel erstellen
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis());
            File photoFile = new File(outputDir, "MakeACopy_" + timestamp + ".jpg");

            ImageCapture.OutputFileOptions outputOptions =
                    new ImageCapture.OutputFileOptions.Builder(photoFile).build();

            Log.d(TAG, "Taking picture...");
            imageCapture.takePicture(
                    outputOptions,
                    ContextCompat.getMainExecutor(requireContext()),
                    new ImageCapture.OnImageSavedCallback() {
                        @Override
                        public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                            Log.d(TAG, "Image saved to: " + photoFile.getAbsolutePath());

                            // (8) FileProvider-Authority zentral
                            Uri imageUri = FileProvider.getUriForFile(
                                    requireContext(),
                                    BuildConfig.APPLICATION_ID + ".fileprovider",
                                    photoFile
                            );

                            if (cameraViewModel != null && isAdded()) {
                                int captureDeg = toDegrees(getViewFinderRotation());
                                if (cropViewModel != null) {
                                    cropViewModel.setCaptureRotationDegrees(captureDeg);
                                }
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
     * Handles errors occurring during the image capture process by providing user feedback
     * and updating the UI to indicate the camera is ready for another action.
     *
     * @param exception The exception that was thrown during the image capture process.
     */
    private void handleCaptureError(Exception exception) {
        if (!isAdded() || binding == null) return;
        UIUtils.showToast(requireContext(), getString(R.string.error_image_capture_failed, exception.getMessage()), Toast.LENGTH_SHORT);
        binding.textCamera.setText(R.string.camera_ready_tap_the_button_to_scan_a_document);
    }

    /**
     * Displays the image captured by the camera in the interface and transitions the UI to review mode.
     * Attempts to load the image from the given {@code imageUri} asynchronously and handles both
     * successful image loading and potential failures. If the image loading is successful, the image
     * is displayed in the corresponding view. In case of failure, a toast message is shown, and the
     * user is navigated to the crop screen.
     *
     * @param imageUri The {@link Uri} of the image to be displayed. The URI is used to fetch the image
     *                 asynchronously for rendering in the UI.
     */
    private void displayCapturedImage(Uri imageUri) {
        if (binding == null || !isAdded()) return;

        binding.textCamera.setText(R.string.processing_image);

        de.schliweb.makeacopy.utils.ImageUtils.loadImageFromUriAsync(requireContext(), imageUri,
                new de.schliweb.makeacopy.utils.ImageUtils.ImageLoadCallback() {
                    @Override
                    public void onImageLoaded(Bitmap bitmap) {
                        if (binding == null || !isAdded()) return;
                        binding.capturedImage.setImageBitmap(bitmap);
                        showReviewMode();
                    }

                    @Override
                    public void onImageLoadFailed(String error) {
                        if (!isAdded()) return;
                        UIUtils.showToast(requireContext(), getString(R.string.error_displaying_image, error), Toast.LENGTH_SHORT);
                        if (getView() != null) {
                            Navigation.findNavController(requireView()).navigate(R.id.navigation_crop);
                        }
                    }
                });
    }

    /**
     * Configures the UI to display the camera mode and ensures the interface is
     * ready for capturing an image. This method toggles visibility for specific
     * UI elements and updates the displayed text to guide the user for scanning
     * a document. Additionally, it resets the state tracking for low-light
     * conditions to prepare for a fresh start in this mode.
     * <p>
     * Preconditions:
     * - The `binding` property must not be null.
     * <p>
     * Behavior:
     * - Shows the camera view (`viewFinder`) and hides the captured image preview
     * (`capturedImage`).
     * - Updates visibility of buttons, enabling the scan-related buttons and
     * disabling others.
     * - Adjusts visibility of `scanButtonContainer` if it exists in the binding.
     * - Sets the text to notify the user of the camera readiness for scanning.
     * - Resets the `lowLightPromptShown` variable to `false`.
     */
    private void showCameraMode() {
        if (binding == null) return;

        binding.viewFinder.setVisibility(View.VISIBLE);
        binding.capturedImage.setVisibility(View.GONE);
        binding.buttonContainer.setVisibility(View.GONE);
        binding.buttonScan.setVisibility(View.VISIBLE);
        if (binding.scanButtonContainer != null) {
            binding.scanButtonContainer.setVisibility(View.VISIBLE);
        }
        binding.textCamera.setText(R.string.camera_ready_tap_the_button_to_scan_a_document);

        lowLightPromptShown = false;
    }

    /**
     * Configures the UI to transition to the review mode after an image is captured.
     * This method updates the visibility of various UI components and provides the
     * user with instructions for confirming or retaking the captured image.
     * <p>
     * Preconditions:
     * - The `binding` property must not be null.
     * <p>
     * Behavior:
     * - Hides the camera view (`viewFinder`) and displays the captured image preview (`capturedImage`).
     * - Reveals the button container (`buttonContainer`) while hiding the scan button (`buttonScan`).
     * - Adjusts visibility of the optional `scanButtonContainer` if it exists in the binding.
     * - Updates the displayed text to guide the user for either confirming or retaking the captured image.
     */
    private void showReviewMode() {
        if (binding == null) return;

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
     * Toggles the flashlight functionality of the camera.
     * <p>
     * This method checks the prerequisites such as the availability of the camera,
     * flashlight hardware, and the fragment's attachment to the activity. If the
     * prerequisites are satisfied, it toggles the flashlight state between on and off.
     * The UI is then updated to reflect the flashlight state, and appropriate user feedback
     * is displayed through toast messages.
     * <p>
     * Behavior:
     * - If the camera or flashlight hardware is not available, or the fragment is not added:
     * - Displays a toast message indicating that the flashlight is not available (if the fragment is added).
     * - Exits without making changes.
     * - If the prerequisites are met:
     * - Toggles the `isFlashlightOn` state.
     * - Updates the flashlight state using the camera's `enableTorch()` method.
     * - Updates the flashlight button's image to represent the current state (on/off).
     * - Displays a toast message indicating the flashlight's current state.
     * <p>
     * Exception Handling:
     * - Captures any exceptions that occur during the toggling process.
     * - Logs the error and displays a user-facing message with details of the error, if the fragment is added.
     * <p>
     * Preconditions:
     * - `camera` must not be null.
     * - The `hasFlash` property must be true.
     * - The fragment must be added to its host activity (`isAdded()` returns true).
     * <p>
     * Postconditions:
     * - The flashlight state is toggled, and the UI is updated accordingly if the prerequisites are met.
     * - No changes are made if the prerequisites are not satisfied.
     */
    private void toggleFlashlight() {
        if (camera == null || !hasFlash || !isAdded()) {
            if (isAdded()) {
                UIUtils.showToast(requireContext(), R.string.flashlight_not_available, Toast.LENGTH_SHORT);
            }
            return;
        }

        try {
            isFlashlightOn = !isFlashlightOn;
            camera.getCameraControl().enableTorch(isFlashlightOn);
            if (binding != null && binding.buttonFlash != null) {
                binding.buttonFlash.setImageResource(isFlashlightOn ? R.drawable.ic_flash_on : R.drawable.ic_flash_off);
                UIUtils.showToast(requireContext(), isFlashlightOn ? R.string.flashlight_on : R.string.flashlight_off, Toast.LENGTH_SHORT);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error toggling flashlight: " + e.getMessage(), e);
            if (isAdded()) {
                UIUtils.showToast(requireContext(), getString(R.string.error_toggling_flashlight, e.getMessage()), Toast.LENGTH_SHORT);
            }
        }
    }

    /**
     * Turns off the flashlight if it is currently enabled. This method ensures that the flashlight
     * state is updated and the corresponding UI elements are adjusted to reflect the off state.
     * <p>
     * Preconditions:
     * - The `camera` object is not null.
     * - The flashlight is currently on (`isFlashlightOn` is true).
     * - The fragment is added to its host activity (`isAdded()` returns true).
     * <p>
     * Behavior:
     * - Disables the flashlight using the `enableTorch(false)` method of the camera's control.
     * - Updates the `isFlashlightOn` state to false.
     * - Changes the flashlight button's icon to indicate the off state if the required UI elements are present.
     * <p>
     * Exception Handling:
     * - Captures any exceptions that occur during the process of turning off the flashlight.
     * - Logs the error with details for debugging purposes.
     * <p>
     * Postconditions:
     * - The flashlight is turned off and the UI is updated accordingly if all preconditions are met.
     * - No changes are made if the preconditions are not satisfied.
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

    /**
     * Resets the camera to its initial state, preparing it for a new operation.
     * This method handles UI updates, frees up resources, and reinitializes
     * the camera if necessary.
     * <p>
     * Steps performed in this method:
     * 1. Ensures the binding is not null and the fragment is added before proceeding.
     * 2. Updates the UI by resetting the text and clearing the captured image.
     * 3. Turns off the flashlight to ensure it is disabled during the reset process.
     * 4. Safely recycles any existing bitmap from the captured image to free memory.
     * 5. Switches the camera display to its default mode.
     * 6. Clears any stored image URI in the ViewModel.
     * 7. Sets the low-light prompt state to false.
     * 8. Unbinds all previously bound use cases from the camera provider, if applicable.
     * 9. Reinitializes the camera only if the permission to access the camera is granted.
     * 10. Handles exceptions by logging the error and showing a user-friendly message.
     * <p>
     * Note: This method ensures that the camera and related resources are properly reset
     * and prepared to avoid resource leaks or inconsistent states during usage.
     */
    private void resetCamera() {
        if (binding == null || !isAdded()) return;

        binding.textCamera.setText(R.string.processing_image);

        // Turn off flashlight when resetting camera
        turnOffFlashlight();

        // Clean up bitmap safely (6)
        Drawable d = binding.capturedImage.getDrawable();
        binding.capturedImage.setImageDrawable(null);
        if (d instanceof BitmapDrawable) {
            Bitmap bm = ((BitmapDrawable) d).getBitmap();
            if (bm != null && !bm.isRecycled()) {
                bm.recycle();
            }
        }

        showCameraMode();

        if (cameraViewModel != null) {
            cameraViewModel.setImageUri(null);
        }

        lowLightPromptShown = false;

        try {
            if (cameraProvider != null) {
                cameraProvider.unbindAll();
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (isAdded() && cameraViewModel != null &&
                            Boolean.TRUE.equals(cameraViewModel.isCameraPermissionGranted().getValue())) {
                        initializeCamera();
                    }
                });
            } else {
                if (cameraViewModel != null &&
                        Boolean.TRUE.equals(cameraViewModel.isCameraPermissionGranted().getValue())) {
                    initializeCamera();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error resetting camera: " + e.getMessage());
            if (isAdded()) {
                UIUtils.showToast(requireContext(), getString(R.string.error_resetting_camera, e.getMessage()), Toast.LENGTH_SHORT);
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        turnOffFlashlight();
        if (sensorManager != null && lightSensor != null) {
            sensorManager.unregisterListener(this); // (3) defensiv
        }
        if (cameraProvider != null) cameraProvider.unbindAll();
        if (orientationListener != null) {
            orientationListener.disable();
        }
        binding = null;
    }

    /**
     * Initializes the light sensor for the application.
     * <p>
     * This method checks if the light sensor is available on the device and sets up
     * the required sensor references. It ensures that the light sensor manager is
     * initialized properly if the fragment is added to the activity. If the sensor
     * is unavailable or there is an error during initialization, appropriate logging
     * messages are generated.
     * <p>
     * Key Operations:
     * - Verifies if the fragment is currently added to an activity.
     * - Retrieves the sensor manager from the system context.
     * - Checks for the availability of the light sensor.
     * - Updates the sensor's availability status and logs messages accordingly.
     * - Handles exceptions gracefully and logs errors.
     * <p>
     * Preconditions:
     * - The fragment must be attached to the activity for the context to be valid.
     * <p>
     * Postconditions:
     * - The `hasLightSensor` flag indicates whether the light sensor is available.
     * - The `lightSensor` field is populated if a light sensor is present.
     * - Logs provide debugging information regarding sensor availability and errors.
     */
    private void initLightSensor() {
        if (!isAdded()) return;

        try {
            sensorManager = (SensorManager) requireContext().getSystemService(Context.SENSOR_SERVICE);
            if (sensorManager != null) {
                lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
                hasLightSensor = (lightSensor != null);
                Log.d(TAG, hasLightSensor ? "Light sensor available" : "Light sensor not available on this device");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error initializing light sensor: " + e.getMessage(), e);
            hasLightSensor = false;
        }
    }

    /**
     * Displays a warning prompt to the user when low light conditions are detected. The prompt
     * encourages the user to turn on the flashlight for better visibility.
     * <p>
     * The method ensures the dialog is not repeatedly shown by checking the following conditions:
     * - The fragment is added and active.
     * - A binding reference exists.
     * - A dialog is not already visible.
     * - A minimum time interval has elapsed since the last prompt.
     * <p>
     * If the conditions are met, an AlertDialog is displayed with options to/**
     * turn * on Displays the a flashlight prompt
     * indicating * that or low cancel light the conditions prompt have. been The detected status and of the dialog visibility is updated accordingly to prevent
     * duplicate provides dialogs the.
     * user *
     * * with Any an exceptions option during to the turn dialog on display the are flashlight logged. to This aid method in ensures debugging the.
     * prompt
     */
    private void showLowLightPrompt() {
        if (!isAdded() || binding == null || isLowLightDialogVisible) return;

        long currentTime = System.currentTimeMillis();
        if (lowLightPromptShown || (currentTime - lastPromptTime) < MIN_TIME_BETWEEN_PROMPTS) {
            return;
        }

        try {
            isLowLightDialogVisible = true;
            AlertDialog dialog = new AlertDialog.Builder(requireContext())
                    .setMessage(R.string.low_light_detected)
                    .setPositiveButton(R.string.flashlight_on, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            if (!isFlashlightOn && hasFlash) {
                                toggleFlashlight();
                            }
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, (dialogInterface, id) -> dialogInterface.dismiss())
                    .create();

            dialog.setOnDismissListener(d -> isLowLightDialogVisible = false);

            // Improve dark mode contrast for dialog buttons
            dialog.setOnShowListener(d -> {
                int nightModeFlags = requireContext().getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
                if (nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
                    try {
                        int white = androidx.core.content.ContextCompat.getColor(requireContext(), android.R.color.white);
                        if (dialog.getButton(AlertDialog.BUTTON_POSITIVE) != null) {
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(white);
                        }
                        if (dialog.getButton(AlertDialog.BUTTON_NEGATIVE) != null) {
                            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(white);
                        }
                        if (dialog.getButton(AlertDialog.BUTTON_NEUTRAL) != null) {
                            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(white);
                        }
                    } catch (Exception ignored) {
                    }
                }
            });

            dialog.show();

            lowLightPromptShown = true;
            lastPromptTime = currentTime;
        } catch (Exception e) {
            Log.e(TAG, "Error showing low light prompt: " + e.getMessage(), e);
            isLowLightDialogVisible = false;
        }
    }

    /**
     * Callback method that is triggered when there is a change in sensor data. Specifically, this method
     * listens for changes in the light sensor data and performs actions based on the detected light level.
     *
     * @param event the SensorEvent containing details about the sensor data, such as the sensor type
     *              and its current values. In this case, the method focuses on events from the light sensor.
     */
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_LIGHT) {
            float lightLevel = event.values[0];
            Log.d(TAG, "Light level: " + lightLevel + " lux");

            if (lightLevel < LOW_LIGHT_THRESHOLD
                    && binding != null
                    && binding.viewFinder.getVisibility() == View.VISIBLE
                    && !isFlashlightOn
                    && hasFlash) {

                showLowLightPrompt();
            }
        }
    }

    /**
     * Called when the accuracy of the registered sensor has changed.
     *
     * @param sensor   The sensor for which the accuracy has changed.
     * @param accuracy The new accuracy of the sensor, represented as one of the predefined sensor accuracy constants.
     */
    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (sensor.getType() == Sensor.TYPE_LIGHT) {
            Log.d(TAG, "Light sensor accuracy changed: " + accuracy);
        }
    }

    /**
     * Converts a given surface rotation value to its corresponding degree representation.
     *
     * @param surfaceRotation the surface rotation value, typically one of the predefined constants
     *                        (e.g., Surface.ROTATION_0, Surface.ROTATION_90, etc.).
     * @return the equivalent degree value for the given rotation: 0, 90, 180, or 270. Returns 0 for unrecognized values.
     */
    private static int toDegrees(int surfaceRotation) {
        switch (surfaceRotation) {
            case Surface.ROTATION_0:
                return 0;
            case Surface.ROTATION_90:
                return 90;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_270:
                return 270;
            default:
                return 0;
        }
    }
}
