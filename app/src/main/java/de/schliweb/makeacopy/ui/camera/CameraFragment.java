package de.schliweb.makeacopy.ui.camera;

import android.Manifest;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
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
import androidx.annotation.OptIn;
import androidx.appcompat.app.AlertDialog;
import androidx.camera.camera2.interop.ExperimentalCamera2Interop;
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
import java.util.concurrent.TimeUnit;

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
    private ActivityResultLauncher<Intent> pickImageLauncher;

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

        // Register the image picker launcher (SAF)
        pickImageLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result == null || result.getData() == null) return;
                    if (result.getResultCode() != android.app.Activity.RESULT_OK) return;
                    Intent data = result.getData();
                    Uri uri = data.getData();
                    if (uri == null) return;
                    // Validate MIME type to be image/*
                    try {
                        String mime = requireContext().getContentResolver().getType(uri);
                        if (mime == null || !mime.startsWith("image/")) {
                            UIUtils.showToast(requireContext(), R.string.error_selected_file_is_not_an_image, Toast.LENGTH_SHORT);
                            return;
                        }
                    } catch (Exception ignored) {
                        // If type cannot be determined, proceed cautiously (SAF already filtered), no-op
                    }
                    try {
                        int takeFlags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        if ((takeFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                            requireContext().getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        }
                    } catch (Exception ignored) {
                    }
                    if (cameraViewModel != null) {
                        // Reset rotations for a new scan imported from storage
                        try {
                            if (cropViewModel != null) {
                                cropViewModel.setUserRotationDegrees(0);
                                cropViewModel.setCaptureRotationDegrees(0);
                            }
                        } catch (Throwable ignored) {
                        }
                        cameraViewModel.setImagePath(null);
                        cameraViewModel.setImageUri(uri);
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

        // Wire Skip OCR (export only) checkbox: persist in SharedPreferences
        try {
            android.content.SharedPreferences prefs = requireContext().getSharedPreferences("export_options", Context.MODE_PRIVATE);
            boolean skipOcr = prefs.getBoolean("skip_ocr", false);
            if (binding.checkboxSkipOcrCamera != null) {
                binding.checkboxSkipOcrCamera.setChecked(skipOcr);
                binding.checkboxSkipOcrCamera.setOnCheckedChangeListener((btn, checked) ->
                        prefs.edit().putBoolean("skip_ocr", checked).apply());
            }
        } catch (Exception ignored) {
        }

        final TextView textView = binding.textCamera;
        cameraViewModel.getText().observe(getViewLifecycleOwner(), textView::setText);

        // Observe the image URI and switch modes accordingly
        cameraViewModel.getImageUri().observe(getViewLifecycleOwner(), uri -> {
            if (uri != null) {
                String path = cameraViewModel.getImagePath().getValue();
                displayCapturedImage(path, uri);
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

        // Set up pick image button
        if (binding.buttonPickImage != null) {
            binding.buttonPickImage.setOnClickListener(v -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                pickImageLauncher.launch(intent);
            });
        }

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
     * Retrieves the value of a system property identified by the given key.
     * If the property is not found, the specified default value is returned.
     *
     * @param key the name of the system property to retrieve
     * @param def the default value to return if the system property is not found
     * @return the value of the system property, or the default value if not found
     */
    private static String getSystemProperty(String key, String def) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            return (String) sp.getMethod("get", String.class, String.class).invoke(null, key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    /**
     * Extracts and returns the major version number from the EMUI version string.
     * The EMUI version string is retrieved from the system property "ro.build.version.emui".
     * If the version string is not in a recognizable format or is unavailable, -1 is returned.
     *
     * @return The major version number of EMUI, or -1 if the version string is invalid or unavailable.
     */
    private static int getEmuiMajor() {
        // Examples: "EmotionUI_10.1.0", "EmotionUI_11.0.0"
        String emui = getSystemProperty("ro.build.version.emui", "");
        if (emui == null) return -1;
        // extract first number after underscore
        int us = emui.indexOf('_');
        if (us >= 0 && us + 1 < emui.length()) {
            String rest = emui.substring(us + 1);
            String num = rest.split("\\.")[0];
            try {
                return Integer.parseInt(num);
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }


    /**
     * Extracts the major version number of Magic UI from system properties or build display information.
     * The method first attempts to retrieve the version from the system property "ro.build.version.magic".
     * If the property is found, it parses the major version from the string. If not, it falls back to
     * parsing the version from the `Build.DISPLAY` field using a regex pattern.
     *
     * @return The major version number of Magic UI as an integer. Returns -1 if the version cannot be determined.
     */
    private static int getMagicUiMajor() {
        String magic = getSystemProperty("ro.build.version.magic", "");
        if (magic != null && !magic.isEmpty()) {
            int us = magic.indexOf('_');
            if (us >= 0 && us + 1 < magic.length()) {
                String rest = magic.substring(us + 1);
                String num = rest.split("\\.")[0];
                try {
                    return Integer.parseInt(num);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        // Fallback: try Build.DISPLAY
        try {
            String disp = android.os.Build.DISPLAY;
            if (disp != null) {
                String up = disp.toUpperCase(java.util.Locale.ROOT);
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("MAGIC\\s?UI[_\\s-]?(\\d+)").matcher(up);
                if (m.find()) return Integer.parseInt(m.group(1));
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }


    /**
     * Determines if the device has a known quirk where the preview screen shows as a black screen.
     * Specifically checks for Huawei devices running EMUI 10 and Honor devices with certain Android and Magic UI/EMUI configurations.
     *
     * @return true if the device is identified as having the black screen preview quirk; false otherwise.
     */
    private boolean isPreviewBlackScreenQuirkDevice() {
        if (isHuaweiEmui10QuirkDevice()) return true;

        String manufacturer = android.os.Build.MANUFACTURER;
        String brand = android.os.Build.BRAND;
        if (manufacturer == null && brand == null) return false;
        boolean isHonorBrand = "HONOR".equalsIgnoreCase(manufacturer) || "HONOR".equalsIgnoreCase(brand);
        if (!isHonorBrand) return false;

        int sdk = android.os.Build.VERSION.SDK_INT;            // Android 10 == 29
        int magic = getMagicUiMajor();                         // e.g., 4 for Magic UI 4.x
        int emui = getEmuiMajor();                             // often 11 on Magic UI 4
        boolean onAndroid10 = (sdk == 29);
        boolean magicOrEmuiNew = (magic >= 4) || (emui >= 11);
        return onAndroid10 && magicOrEmuiNew;
    }

    /**
     * Determines if the current device is a Huawei device running EMUI 10
     * and is part of specific device families such as P30 or P40.
     * <p>
     * The method checks the manufacturer and model strings to confirm the device
     * is a Huawei device and matches the patterns for P30 or P40 series. Additionally,
     * it validates that the EMUI version is 10.
     *
     * @return true if the device is a Huawei device from the P30 or P40 family running EMUI 10,
     * false otherwise.
     */
    private boolean isHuaweiEmui10QuirkDevice() {
        String manufacturer = android.os.Build.MANUFACTURER;
        String model = android.os.Build.MODEL;
        if (manufacturer == null || model == null) return false;
        if (!manufacturer.equalsIgnoreCase("HUAWEI")) return false;
        String m = model.toUpperCase(Locale.ROOT);
        boolean isP30Fam = m.contains("ELE-") || m.contains("VOG-"); // P30 / P30 Pro
        boolean isP40Fam = m.contains("ANA-") || m.contains("ELS-") || m.contains("JNY-"); // P40 / Pro / Lite
        int emui = getEmuiMajor();
        return (isP30Fam || isP40Fam) && emui == 10;
    }

    private Preview preview; // make field to update rotation later

    private boolean streamObserverAttached = false;

    private boolean alreadyReboundCompatibleOnce = false;

    /**
     * Binds the camera use cases to the lifecycle and configures the preview and image capture
     * settings. Includes setup for target aspect ratio, rotation and FPS range adjustments using
     * Camera2 interop. Handles fallback to compatible preview mode if needed.
     *
     * @param forceCompatiblePreview Flag to force the use of compatible preview mode. If true, the
     *                               viewfinder's implementation mode is set to compatible; otherwise,
     *                               it is set to performance. This parameter is used during binding
     *                               retries when initial binding fails.
     */
    @OptIn(markerClass = ExperimentalCamera2Interop.class)
    private void bindUseCases(boolean forceCompatiblePreview) {
        if (binding == null || cameraProvider == null || !isAdded()) return;

        if (!forceCompatiblePreview) alreadyReboundCompatibleOnce = false;

        // 1) ImplementationMode vor SurfaceProvider setzen
        if (forceCompatiblePreview) {
            binding.viewFinder.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        } else {
            binding.viewFinder.setImplementationMode(PreviewView.ImplementationMode.PERFORMANCE);
        }
        binding.viewFinder.setScaleType(PreviewView.ScaleType.FIT_CENTER);

        int rotation = getViewFinderRotation();

        boolean useConservativeRes = forceCompatiblePreview || isPreviewBlackScreenQuirkDevice();

        // 2) Conservative FPS via Camera2Interop

        // Use ResolutionSelector to avoid deprecated setTargetAspectRatio()/setTargetResolution()
        androidx.camera.core.resolutionselector.ResolutionSelector.Builder rsBuilderPreview =
                new androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                        .setAspectRatioStrategy(
                                new androidx.camera.core.resolutionselector.AspectRatioStrategy(
                                        AspectRatio.RATIO_4_3,
                                        androidx.camera.core.resolutionselector.AspectRatioStrategy.FALLBACK_RULE_AUTO
                                )
                        );
        androidx.camera.core.resolutionselector.ResolutionSelector.Builder rsBuilderCapture =
                new androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                        .setAspectRatioStrategy(
                                new androidx.camera.core.resolutionselector.AspectRatioStrategy(
                                        AspectRatio.RATIO_4_3,
                                        androidx.camera.core.resolutionselector.AspectRatioStrategy.FALLBACK_RULE_AUTO
                                )
                        );

        if (useConservativeRes) {
            // Prefer 1440x1080 in conservative mode; fall back to the closest lower, then higher
            android.util.Size preferred = new android.util.Size(1440, 1080);
            rsBuilderPreview.setResolutionStrategy(
                    new androidx.camera.core.resolutionselector.ResolutionStrategy(
                            preferred,
                            androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                    )
            );
            rsBuilderCapture.setResolutionStrategy(
                    new androidx.camera.core.resolutionselector.ResolutionStrategy(
                            preferred,
                            androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                    )
            );
        } else {
            // high resolution (if available) to improve sharpness
            android.util.Size preferredHigh = new android.util.Size(4032, 3024);
            rsBuilderCapture.setResolutionStrategy(
                    new androidx.camera.core.resolutionselector.ResolutionStrategy(
                            preferredHigh,
                            androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
            );
        }

        androidx.camera.core.resolutionselector.ResolutionSelector previewSelector = rsBuilderPreview.build();
        androidx.camera.core.resolutionselector.ResolutionSelector captureSelector = rsBuilderCapture.build();

        Preview.Builder previewBuilder = new Preview.Builder()
                .setResolutionSelector(previewSelector)
                .setTargetRotation(rotation);

        ImageCapture.Builder captureBuilder = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setResolutionSelector(captureSelector)
                .setTargetRotation(rotation)
                .setJpegQuality(98);

        androidx.camera.camera2.interop.Camera2Interop.Extender<Preview> pExt =
                new androidx.camera.camera2.interop.Camera2Interop.Extender<>(previewBuilder);
        androidx.camera.camera2.interop.Camera2Interop.Extender<ImageCapture> cExt =
                new androidx.camera.camera2.interop.Camera2Interop.Extender<>(captureBuilder);

        // AE_TARGET_FPS_RANGE = 15–30 (adjust if needed)
        android.util.Range<Integer> fps = new android.util.Range<>(15, 30);
        pExt.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
        cExt.setCaptureRequestOption(android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);

        // Pro quality: continuous AF + high-quality pipelines
        pExt.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        cExt.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE,
                android.hardware.camera2.CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        cExt.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE,
                android.hardware.camera2.CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY);
        cExt.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.EDGE_MODE,
                android.hardware.camera2.CaptureRequest.EDGE_MODE_HIGH_QUALITY);
        cExt.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                android.hardware.camera2.CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY);
        cExt.setCaptureRequestOption(
                android.hardware.camera2.CaptureRequest.SHADING_MODE,
                android.hardware.camera2.CaptureRequest.SHADING_MODE_HIGH_QUALITY);

        imageCapture = captureBuilder
                //.setJpegQuality(90) // optional
                .build();

        preview = previewBuilder.build();

        CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

        try {
            cameraProvider.unbindAll();
            camera = cameraProvider.bindToLifecycle(getViewLifecycleOwner(), cameraSelector, preview, imageCapture);
            preview.setSurfaceProvider(binding.viewFinder.getSurfaceProvider());
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "bindToLifecycle failed: " + e.getMessage(), e);
            // Last resort: try compatible once
            if (!forceCompatiblePreview) {
                bindUseCases(true);
                return;
            }
            throw e;
        }

        // 3) Orientation listener updates BOTH capture + preview
        if (orientationListener == null) {
            orientationListener = new OrientationEventListener(requireContext()) {
                @Override
                public void onOrientationChanged(int orientation) {
                    if (!isAdded() || imageCapture == null || binding == null) return;
                    int rot = getViewFinderRotation();
                    try {
                        imageCapture.setTargetRotation(rot);
                        if (preview != null) preview.setTargetRotation(rot);
                    } catch (Exception ignored) {
                    }
                }
            };
            orientationListener.enable();
        }

        // 4) StreamState-Watchdog: wenn nach 1500ms nicht STREAMING -> COMPATIBLE Rebind
        if (!streamObserverAttached) {
            binding.viewFinder.getPreviewStreamState()
                    .observe(getViewLifecycleOwner(), state -> Log.d(TAG, "Preview stream state: " + state));
            streamObserverAttached = true;
        }

        binding.viewFinder.postDelayed(() -> {
            if (!isAdded() || binding == null) return;
            PreviewView.StreamState s = binding.viewFinder.getPreviewStreamState().getValue();
            boolean streaming = (s == PreviewView.StreamState.STREAMING);
            if (!streaming) {
                if (!alreadyReboundCompatibleOnce) {
                    Log.w(TAG, "Preview watchdog: not STREAMING → rebinding in COMPATIBLE mode");
                    alreadyReboundCompatibleOnce = true;
                    bindUseCases(true);
                } else {
                    Log.e(TAG, "Still not STREAMING after COMPATIBLE rebind – please collect logs");
                    UIUtils.showToast(requireContext(), R.string.error_camera_preview_failed, Toast.LENGTH_SHORT);
                }
            }
        }, 1500);
    }

    /**
     * Initializes the camera for the fragment with proper configuration and bindings.
     * This method ensures that necessary prerequisites (like binding and fragment attachment)
     * are satisfied before proceeding. It retrieves an instance of the {@link ProcessCameraProvider},
     * binds required use cases, and configures the flash functionality if available.
     * <br>
     * If the initialization is successful, the camera is set up and ready to scan. Otherwise,
     * appropriate error messages or warnings are displayed to the user.
     * <br>
     * The behavior of this method includes:
     * - Setting up camera use cases and verifying the availability of a flash unit.
     * - Displaying appropriate UI updates during initialization and completion.
     * - Handling various errors and exceptions during the camera setup process.
     * <br>
     * The execution relies on asynchronous callbacks provided by the {@link ListenableFuture}
     * to properly manage the camera provider setup.
     * <p>
     * Preconditions:
     * - The method checks if `binding` is null or if the fragment is not currently attached. If either condition is true, it skips the initialization process.
     * <p>
     * Error Handling:
     * - If the camera provider is unavailable or null, the user is notified via a toast message and an appropriate status message is displayed in the UI.
     * - If any unexpected errors occur during initialization, they are handled gracefully by calling the {@code handleCameraInitializationError(Exception e)} method.
     * <p>
     * Threading:
     * - Uses {@link ContextCompat#getMainExecutor} for executing asynchronous callback tasks on the main thread.
     * <p>
     * Flashlight:
     * - Checks for the presence of a flash unit in the camera. If available, updates the flash button visibility and its associated state.
     */
    private void initializeCamera() {
        if (binding == null || !isAdded()) {
            Log.d(TAG, "initializeCamera: Skipping - binding is null or fragment not attached");
            return;
        }
        binding.textCamera.setText(R.string.initializing_camera);

        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(requireContext());

        final boolean forceCompatible = isPreviewBlackScreenQuirkDevice();

        cameraProviderFuture.addListener(() -> {
            try {
                if (binding == null || !isAdded() || getView() == null) return;
                cameraProvider = cameraProviderFuture.get();
                if (cameraProvider == null) {
                    UIUtils.showToast(requireContext(), R.string.error_camera_provider_null, Toast.LENGTH_SHORT);
                    binding.textCamera.setText(R.string.camera_ready_tap_the_button_to_scan_a_document);
                    return;
                }
                bindUseCases(forceCompatible);
                hasFlash = camera.getCameraInfo().hasFlashUnit();
                if (binding.buttonFlash != null) {
                    binding.buttonFlash.setVisibility(hasFlash ? View.VISIBLE : View.GONE);
                    isFlashlightOn = false;
                    binding.buttonFlash.setImageResource(R.drawable.ic_flash_off);
                }
                binding.textCamera.setText(R.string.camera_ready_tap_the_button_to_scan_a_document);

                binding.viewFinder.setOnTouchListener((v, event) -> {
                    if (event.getAction() == MotionEvent.ACTION_UP && camera != null) {
                        MeteringPointFactory mpf = binding.viewFinder.getMeteringPointFactory();
                        MeteringPoint pt = mpf.createPoint(event.getX(), event.getY());
                        FocusMeteringAction action = new FocusMeteringAction.Builder(
                                pt,
                                FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE | FocusMeteringAction.FLAG_AWB
                        ).setAutoCancelDuration(3, TimeUnit.SECONDS).build();

                        camera.getCameraControl().startFocusAndMetering(action);

                        // Important for accessibility:
                        v.performClick();
                        return true;   // consume only the tap
                    }
                    return false;      // do not block other gestures
                });

                binding.viewFinder.setOnClickListener(v -> {
                    // optional: centered AF/AE, identical to your pre-focus

                    try {
                        MeteringPointFactory mpf = binding.viewFinder.getMeteringPointFactory();
                        MeteringPoint c = mpf.createPoint(binding.viewFinder.getWidth() / 2f, binding.viewFinder.getHeight() / 2f);
                        FocusMeteringAction a = new FocusMeteringAction.Builder(c,
                                FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE | FocusMeteringAction.FLAG_AWB)
                                .setAutoCancelDuration(3, TimeUnit.SECONDS).build();
                        camera.getCameraControl().startFocusAndMetering(a);
                    } catch (Exception ignored) {
                    }
                });

            } catch (Exception e) {
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
            setProcessing(true);
            binding.textCamera.setText(R.string.processing_image);

            // Speicherort im app-eigenen Ordner
            File outputDir = new File(requireContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES), "MakeACopy");
            if (!outputDir.exists()) {
                //noinspection ResultOfMethodCallIgnored
                outputDir.mkdirs();
            }

            // Datei mit Zeitstempel erstellen
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(System.currentTimeMillis());
            File photoFile = new File(outputDir, "MakeACopy_" + timestamp + ".jpg");

            ImageCapture.OutputFileOptions outputOptions =
                    new ImageCapture.OutputFileOptions.Builder(photoFile).build();

            Log.d(TAG, "Taking picture...");

            // Pre-focus centered (AF/AE), then trigger capture
            MeteringPointFactory mpf = binding.viewFinder.getMeteringPointFactory();
            MeteringPoint center = mpf.createPoint(
                    binding.viewFinder.getWidth() / 2f,
                    binding.viewFinder.getHeight() / 2f
            );

            FocusMeteringAction fma = new FocusMeteringAction.Builder(
                    center,
                    FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE
            ).setAutoCancelDuration(3, TimeUnit.SECONDS).build();

            com.google.common.util.concurrent.ListenableFuture<FocusMeteringResult> fut =
                    camera.getCameraControl().startFocusAndMetering(fma);

            fut.addListener(() -> {
                try {
                    FocusMeteringResult result = fut.get(); // does not block because the listener is only invoked after completion
                    if (result != null && result.isFocusSuccessful()) {
                        binding.viewFinder.postDelayed(() -> doTakePicture(outputOptions, photoFile), 150);
                    } else {
                        doTakePicture(outputOptions, photoFile);
                    }
                } catch (Exception e) {
                    doTakePicture(outputOptions, photoFile);
                }
            }, ContextCompat.getMainExecutor(requireContext()));

        } catch (Exception e) {
            Log.e(TAG, "Unexpected error in captureImage: " + e.getMessage(), e);
            handleCaptureError(e);
        }
    }

    private void doTakePicture(ImageCapture.OutputFileOptions outputOptions, File photoFile) {
        imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(requireContext()),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults outputFileResults) {
                        Log.d(TAG, "Image saved to: " + photoFile.getAbsolutePath() + ", size=" + photoFile.length());
                        Uri imageUri = FileProvider.getUriForFile(
                                requireContext(),
                                BuildConfig.APPLICATION_ID + ".fileprovider",
                                photoFile
                        );
                        if (cameraViewModel != null && isAdded()) {
                            try {
                                if (cropViewModel != null) {
                                    cropViewModel.setUserRotationDegrees(0);
                                    int captureDeg = toDegrees(getViewFinderRotation());
                                    cropViewModel.setCaptureRotationDegrees(captureDeg);
                                }
                            } catch (Throwable ignored) {
                            }
                            cameraViewModel.setImagePath(photoFile.getAbsolutePath());
                            cameraViewModel.setImageUri(imageUri);
                        }
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        Log.e(TAG, "Image capture failed: " + exception.getMessage(), exception);
                        handleCaptureError(exception);
                    }
                });
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
        setProcessing(false);
    }

    /**
     * Enables/disables action buttons during processing to prevent duplicate operations.
     * Specifically affects the Scan and Load (Pick Image) buttons if present.
     */
    private void setProcessing(boolean processing) {
        if (binding == null) return;
        try {
            if (binding.buttonScan != null) binding.buttonScan.setEnabled(!processing);
        } catch (Throwable ignored) {
        }
        try {
            if (binding.buttonPickImage != null) binding.buttonPickImage.setEnabled(!processing);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Displays the image captured by the camera in the interface and transitions the UI to review mode.
     * Loads via ImageLoader with path-first strategy; falls back to URI if needed.
     */
    private void displayCapturedImage(String imagePath, Uri imageUri) {
        if (binding == null || !isAdded()) return;

        setProcessing(true);
        binding.textCamera.setText(R.string.processing_image);
        // Clear previous image to avoid showing stale bitmap while loading new one
        binding.capturedImage.setImageDrawable(null);

        de.schliweb.makeacopy.utils.ImageLoader.decodeAsync(requireContext(), imagePath, imageUri,
                new de.schliweb.makeacopy.utils.ImageLoader.Callback() {
                    @Override
                    public void onLoaded(Bitmap bitmap) {
                        if (binding == null || !isAdded()) return;
                        Bitmap safe = de.schliweb.makeacopy.utils.BitmapUtils.ensureDisplaySafe(bitmap);
                        binding.capturedImage.setImageBitmap(safe);
                        setProcessing(false);
                        showReviewMode();
                    }

                    @Override
                    public void onError(Throwable error) {
                        if (!isAdded()) return;
                        String msg = error != null ? error.getMessage() : "unknown";
                        UIUtils.showToast(requireContext(), getString(R.string.error_displaying_image, msg), Toast.LENGTH_SHORT);
                        setProcessing(false);
                        // On preview load failure, reset to camera mode instead of navigating forward
                        resetCamera();
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

        setProcessing(false);
        binding.viewFinder.setVisibility(View.VISIBLE);
        binding.capturedImage.setVisibility(View.GONE);
        binding.buttonContainer.setVisibility(View.GONE);
        binding.buttonScan.setVisibility(View.VISIBLE);
        if (binding.scanButtonContainer != null) {
            binding.scanButtonContainer.setVisibility(View.VISIBLE);
        }
        // Show Skip OCR toggle only in camera mode
        if (binding.checkboxSkipOcrCamera != null) {
            binding.checkboxSkipOcrCamera.setVisibility(View.VISIBLE);
        }
        binding.textCamera.setText(R.string.camera_ready_tap_the_button_to_scan_a_document);

        // Reset rotations for a new scan/page
        try {
            if (cropViewModel != null) {
                cropViewModel.setUserRotationDegrees(0);
                cropViewModel.setCaptureRotationDegrees(0);
            }
        } catch (Throwable ignored) {
        }

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
        // Hide Skip OCR toggle in review/confirm mode
        if (binding.checkboxSkipOcrCamera != null) {
            binding.checkboxSkipOcrCamera.setVisibility(View.GONE);
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
            cameraViewModel.setImagePath(null);
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
        streamObserverAttached = false;
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
            dialog.setOnShowListener(dlg -> de.schliweb.makeacopy.utils.DialogUtils.improveAlertDialogButtonContrastForNight(dialog, requireContext()));

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

    @androidx.annotation.VisibleForTesting
    ImageCapture getImageCaptureForTest() {
        return imageCapture;
    }
}
