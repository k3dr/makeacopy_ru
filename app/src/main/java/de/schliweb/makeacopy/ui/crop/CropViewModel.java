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
}