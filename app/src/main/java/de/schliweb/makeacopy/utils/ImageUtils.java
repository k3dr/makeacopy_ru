package de.schliweb.makeacopy.utils;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A utility class for handling image operations such as loading images from a URI
 * and applying the correct orientation based on the EXIF information.
 */
public class ImageUtils {
    private static final String TAG = "ImageUtils";
    private static final ExecutorService executor = Executors.newCachedThreadPool();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ImageUtils() {
        // private because utility class
    }

    /**
     * Asynchronously loads an image from a URI and provides the result via a callback.
     * The method uses a background thread to load the image and posts the result
     * on the main thread using the provided callback.
     *
     * @param context  The Context to use for accessing resources and loading the image. Must not be null.
     * @param uri      The URI of the image to be loaded. Must not be null.
     * @param callback The callback to receive the result of the image loading process.
     *                 The callback will be invoked with a loaded Bitmap or an error message if the loading fails.
     */
    public static void loadImageFromUriAsync(Context context, Uri uri, ImageLoadCallback callback) {
        if (context == null || uri == null) {
            mainHandler.post(() -> callback.onImageLoadFailed("Context or URI is null"));
            return;
        }

        executor.execute(() -> {
            Bitmap bitmap = loadImageFromUri(context, uri);
            mainHandler.post(() -> {
                if (bitmap != null) {
                    callback.onImageLoaded(bitmap);
                } else {
                    callback.onImageLoadFailed("Failed to load image");
                }
            });
        });
    }

    /**
     * Loads an image from the provided URI and returns it as a Bitmap.
     * This method decodes the image and applies rotation based on the image's EXIF orientation data.
     *
     * @param context The Context to use for accessing resources. Must not be null.
     * @param uri     The URI of the image to load. Must not be null.
     * @return A Bitmap representing the loaded image, or null if the image could not be loaded.
     */
    public static Bitmap loadImageFromUri(Context context, Uri uri) {
        if (context == null || uri == null) {
            Log.e(TAG, "Context or URI is null");
            return null;
        }

        try {
            ContentResolver resolver = context.getContentResolver();
            InputStream inputStream = resolver.openInputStream(uri);

            if (inputStream == null) {
                Log.e(TAG, "Failed to open input stream for URI: " + uri);
                return null;
            }

            // Decode the image
            Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
            inputStream.close();

            if (bitmap == null) {
                Log.e(TAG, "Failed to decode bitmap from URI: " + uri);
                return null;
            }

            // Get the orientation of the image
            int orientation = getImageOrientation(resolver, uri);

            // Rotate the bitmap if needed
            if (orientation != 0) {
                Matrix matrix = new Matrix();
                matrix.postRotate(orientation);
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                Log.d(TAG, "Rotated bitmap by " + orientation + " degrees");
            }

            return bitmap;
        } catch (IOException e) {
            Log.e(TAG, "Error loading image from URI: " + uri, e);
            return null;
        }
    }

    /**
     * Retrieves the orientation of an image based on its EXIF metadata.
     * This method extracts the orientation tag from the EXIF data of the image
     * and returns the rotation angle in degrees.
     *
     * @param resolver The ContentResolver to use for accessing the image. Must not be null.
     * @param uri      The URI of the image whose orientation is to be determined. Must not be null.
     * @return The rotation angle of the image in degrees (0, 90, 180, or 270). Returns 0 if the orientation
     * cannot be determined or an error occurs.
     */
    private static int getImageOrientation(ContentResolver resolver, Uri uri) {
        try (InputStream inputStream = resolver.openInputStream(uri)) {
            if (inputStream == null) return 0;

            ExifInterface exif = new ExifInterface(inputStream);
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);

            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    return 90;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    return 180;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    return 270;
                default:
                    return 0;
            }
        } catch (IOException e) {
            Log.e(TAG, "Error reading EXIF orientation", e);
            return 0;
        }
    }

    /**
     * A callback interface for handling the result of an asynchronous image loading operation.
     * Classes implementing this interface can define behavior for both successful and failed
     * image loading events.
     */
    public interface ImageLoadCallback {
        void onImageLoaded(Bitmap bitmap);

        void onImageLoadFailed(String error);
    }
}