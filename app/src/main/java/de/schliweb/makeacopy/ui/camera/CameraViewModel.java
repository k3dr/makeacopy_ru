package de.schliweb.makeacopy.ui.camera;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import de.schliweb.makeacopy.ui.BaseViewModel;

/**
 * ViewModel class for managing camera-related permissions and state.
 * This class extends the BaseViewModel to inherit common ViewModel functionality
 * and provides specific logic related to the camera.
 */
public class CameraViewModel extends BaseViewModel {

    private final MutableLiveData<Boolean> mCameraPermissionGranted;

    public CameraViewModel() {
        super("Camera Fragment");

        mCameraPermissionGranted = new MutableLiveData<>();
        mCameraPermissionGranted.setValue(false);
    }

    /**
     * Checks whether the camera permission is granted.
     *
     * @return A LiveData object containing a Boolean value indicating if the camera permission is granted.
     *         Returns true if the permission is granted, false otherwise.
     */
    public LiveData<Boolean> isCameraPermissionGranted() {
        return mCameraPermissionGranted;
    }

    /**
     * Updates the camera permission status.
     *
     * @param granted A boolean value indicating whether the camera permission has been granted.
     *                Pass true if the permission is granted, false otherwise.
     */
    public void setCameraPermissionGranted(boolean granted) {
        mCameraPermissionGranted.setValue(granted);
    }
}