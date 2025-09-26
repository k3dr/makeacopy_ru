package de.schliweb.makeacopy.ui.crop;

import android.graphics.Bitmap;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import de.schliweb.makeacopy.ui.BaseViewModel;

/**
 * ViewModel class for managing image cropping operations.
 * Provides LiveData to observe the state and data of the image cropping process.
 * Extends the functionality of BaseViewModel.
 */
public class CropViewModel extends BaseViewModel {

    private final MutableLiveData<Boolean> mImageLoaded;
    private final MutableLiveData<Bitmap> mImageBitmap;
    private final MutableLiveData<Bitmap> mOriginalImageBitmap;
    private final MutableLiveData<Boolean> mImageCropped;

    // Rotation inferred from capture/EXIF (pre-crop)
    private final MutableLiveData<Integer> captureRotationDegrees = new MutableLiveData<>(0);
    // User-requested rotation after cropping (applied before OCR/export)
    private final MutableLiveData<Integer> userRotationDegrees = new MutableLiveData<>(0);

    public CropViewModel() {
        super("Crop Fragment");

        mImageLoaded = new MutableLiveData<>();
        mImageLoaded.setValue(false);

        mImageBitmap = new MutableLiveData<>();
        mOriginalImageBitmap = new MutableLiveData<>();

        mImageCropped = new MutableLiveData<>();
        mImageCropped.setValue(false);
    }

    public LiveData<Boolean> isImageLoaded() {
        return mImageLoaded;
    }

    public void setImageLoaded(boolean loaded) {
        mImageLoaded.setValue(loaded);
    }

    /**
     * Gets the bitmap of the image to crop
     *
     * @return LiveData containing the image bitmap
     */
    public LiveData<Bitmap> getImageBitmap() {
        return mImageBitmap;
    }

    /**
     * Sets the bitmap of the image to crop
     *
     * @param bitmap The bitmap of the image
     */
    public void setImageBitmap(Bitmap bitmap) {
        mImageBitmap.setValue(bitmap);
        setImageLoaded(bitmap != null);
    }

    /**
     * Checks if the image has been cropped
     *
     * @return LiveData containing the cropped status
     */
    public LiveData<Boolean> isImageCropped() {
        return mImageCropped;
    }

    /**
     * Sets whether the image has been cropped
     *
     * @param cropped True if the image has been cropped, false otherwise
     */
    public void setImageCropped(boolean cropped) {
        mImageCropped.setValue(cropped);
    }

    /**
     * Gets the original uncropped bitmap of the image
     *
     * @return LiveData containing the original image bitmap
     */
    public LiveData<Bitmap> getOriginalImageBitmap() {
        return mOriginalImageBitmap;
    }

    /**
     * Sets the original uncropped bitmap of the image
     *
     * @param bitmap The original bitmap of the image
     */
    public void setOriginalImageBitmap(Bitmap bitmap) {
        mOriginalImageBitmap.setValue(bitmap);
    }

    /**
     * Retrieves the rotation degrees applied to the captured image.
     *
     * @return LiveData containing the rotation degrees as an Integer.
     */
    public LiveData<Integer> getCaptureRotationDegrees() {
        return captureRotationDegrees;
    }

    /**
     * Sets the rotation degrees for the captured image.
     * The value is normalized to be within the range [0, 360).
     *
     * @param deg The rotation degrees to set, can be any integer. The value will be adjusted to fall within 0 to 359 degrees.
     */
    public void setCaptureRotationDegrees(int deg) {
        captureRotationDegrees.setValue(((deg % 360) + 360) % 360);
    }

    /**
     * Returns the user-requested rotation degrees (applied after crop, before OCR/export).
     */
    public LiveData<Integer> getUserRotationDegrees() {
        return userRotationDegrees;
    }

    /**
     * Sets an absolute user rotation value in degrees (normalized to [0, 360)).
     */
    public void setUserRotationDegrees(int deg) {
        userRotationDegrees.setValue(((deg % 360) + 360) % 360);
    }

    /**
     * Convenience: rotate 90° clockwise (right).
     */
    public void rotateRight() {
        Integer v = userRotationDegrees.getValue();
        int cur = (v == null ? 0 : v);
        setUserRotationDegrees(cur + 90);
    }

    /**
     * Convenience: rotate 90° counter-clockwise (left).
     */
    public void rotateLeft() {
        Integer v = userRotationDegrees.getValue();
        int cur = (v == null ? 0 : v);
        setUserRotationDegrees(cur - 90);
    }

}