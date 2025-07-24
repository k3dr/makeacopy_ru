package de.schliweb.makeacopy.ui.camera;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import de.schliweb.makeacopy.ui.BaseViewModel;

/**
 * CameraViewModel is a ViewModel class that provides functionality for managing
 * the state and data relevant to the camera permissions and operations within
 * the corresponding UI component.
 * <p>
 * It extends the BaseViewModel to utilize common ViewModel functionality.
 */
public class CameraViewModel extends BaseViewModel {

    private final MutableLiveData<Boolean> mCameraPermissionGranted;

    public CameraViewModel() {
        super("Camera Fragment");

        mCameraPermissionGranted = new MutableLiveData<>();
        mCameraPermissionGranted.setValue(false);
    }

    public LiveData<Boolean> isCameraPermissionGranted() {
        return mCameraPermissionGranted;
    }

    public void setCameraPermissionGranted(boolean granted) {
        mCameraPermissionGranted.setValue(granted);
    }
}