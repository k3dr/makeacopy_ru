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
import android.view.LayoutInflater;
import android.view.OrientationEventListener;
import android.view.Surface;
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
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
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

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.concurrent.ExecutionException;

import de.schliweb.makeacopy.BuildConfig;
import de.schliweb.makeacopy.R;
import de.schliweb.makeacopy.databinding.FragmentCameraBinding;
import de.schliweb.makeacopy.ui.crop.CropViewModel;
import de.schliweb.makeacopy.utils.UIUtils;

/**
 * CameraFragment is a fragment that manages camera operations, including
 * capturing images, handling camera permissions, managing a light sensor for
 * low-light scenarios, and providing UI transitions between camera and review modes.
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

        // System-Inset-Margin für Button-Container dynamisch setzen
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
     * Handles errors that occur during camera initialization
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
     */
    private void handleCaptureError(Exception exception) {
        if (!isAdded() || binding == null) return;
        UIUtils.showToast(requireContext(), getString(R.string.error_image_capture_failed, exception.getMessage()), Toast.LENGTH_SHORT);
        binding.textCamera.setText(R.string.camera_ready_tap_the_button_to_scan_a_document);
    }

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
     * Camera mode UI
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
     * Review mode UI
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
     * Toggles the flashlight (Torch)
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
     * Initializes the light sensor (capability check only)
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
     * Shows a dialog prompting the user to turn on the flashlight
     */
    private void showLowLightPrompt() {
        if (!isAdded() || binding == null || isLowLightDialogVisible) return;

        long currentTime = System.currentTimeMillis();
        if (lowLightPromptShown || (currentTime - lastPromptTime) < MIN_TIME_BETWEEN_PROMPTS) {
            return;
        }

        try {
            isLowLightDialogVisible = true;
            new AlertDialog.Builder(requireContext())
                    .setMessage(R.string.low_light_detected)
                    .setPositiveButton(R.string.flashlight_on, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            if (!isFlashlightOn && hasFlash) {
                                toggleFlashlight();
                            }
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, (dialog, id) -> dialog.dismiss())
                    .setOnDismissListener(dialog -> isLowLightDialogVisible = false)
                    .show();

            lowLightPromptShown = true;
            lastPromptTime = currentTime;
        } catch (Exception e) {
            Log.e(TAG, "Error showing low light prompt: " + e.getMessage(), e);
            isLowLightDialogVisible = false;
        }
    }

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

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        if (sensor.getType() == Sensor.TYPE_LIGHT) {
            Log.d(TAG, "Light sensor accuracy changed: " + accuracy);
        }
    }
}
