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
 * Utility class for handling image operations such as loading and correcting orientation.
 * This class provides methods to load images asynchronously or synchronously while applying
 * the correct orientation based on the image's EXIF data.
 */
public class ImageUtils {
    private static final String TAG = "ImageUtils";
    private static final ExecutorService executor = Executors.newCachedThreadPool();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ImageUtils() {
        // private because utility class
    }

    /**
     * Loads an image from a URI and applies the correct orientation asynchronously
     *
     * @param context  The context
     * @param uri      The URI of the image to load
     * @param callback The callback to receive the loaded bitmap or error
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
     * Loads an image from a URI and applies the correct orientation
     * This method should be called from a background thread
     *
     * @param context The context
     * @param uri     The URI of the image to load
     * @return The loaded and correctly oriented bitmap, or null if loading failed
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
     * Gets the orientation of an image from its URI
     *
     * @param resolver The content resolver
     * @param uri      The URI of the image
     * @return The orientation in degrees (0, 90, 180, or 270)
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
     * Interface for image loading callback
     */
    public interface ImageLoadCallback {
        void onImageLoaded(Bitmap bitmap);

        void onImageLoadFailed(String error);
    }
}