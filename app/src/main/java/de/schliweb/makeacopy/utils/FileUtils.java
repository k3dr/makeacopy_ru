package de.schliweb.makeacopy.utils;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

/**
 * A utility class providing functionality to interact with files and URIs, including methods
 * for extracting display names from URIs. This class is designed to accommodate different schemes
 * such as "content" and "file", and provides fallback mechanisms for unrecognized schemes.
 * <p>
 * This class is not intended to be instantiated.
 */
public class FileUtils {
    private static final String TAG = "FileUtils";

    private FileUtils() {
        // private because utility class
    }

    /**
     * Extracts and returns the display name from the given URI. Handles URIs with content
     * and file schemes, and falls back to returning the URI string if the display name cannot
     * be determined.
     *
     * @param context The application context used to access resources and content providers.
     * @param uri     The URI from which to extract the display name.
     * @return The extracted display name, or the URI string if the display name cannot be determined.
     */
    public static String getDisplayNameFromUri(Context context, Uri uri) {
        if (uri == null) {
            Log.d(TAG, "getDisplayNameFromUri: URI is null");
            return null;
        }

        Log.d(TAG, "getDisplayNameFromUri: Input URI: " + uri);
        Log.d(TAG, "getDisplayNameFromUri: URI scheme: " + uri.getScheme());

        String result = null;

        // For content scheme URIs, try to query the content provider for the display name
        if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            Log.d(TAG, "getDisplayNameFromUri: Processing content:// URI");
            try {
                // Try to get the display name from the OpenableColumns
                Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);

                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex != -1) {
                        String displayName = cursor.getString(nameIndex);
                        Log.d(TAG, "getDisplayNameFromUri: Found display name from cursor: " + displayName);
                        cursor.close();
                        result = displayName;
                    } else {
                        Log.d(TAG, "getDisplayNameFromUri: DISPLAY_NAME column not found in cursor");
                        cursor.close();
                    }
                } else {
                    Log.d(TAG, "getDisplayNameFromUri: Cursor is null or empty");
                    if (cursor != null) cursor.close();
                }

                // If we couldn't get the display name, try to get it from the last path segment
                if (result == null) {
                    String lastPathSegment = uri.getLastPathSegment();
                    Log.d(TAG, "getDisplayNameFromUri: Last path segment: " + lastPathSegment);
                    if (lastPathSegment != null) {
                        result = lastPathSegment;
                        Log.d(TAG, "getDisplayNameFromUri: Using last path segment as filename: " + result);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error getting display name from content URI", e);
            }
        }
        // For file scheme URIs, extract the filename from the path
        else if (ContentResolver.SCHEME_FILE.equals(uri.getScheme())) {
            Log.d(TAG, "getDisplayNameFromUri: Processing file:// URI");
            String path = uri.getPath();
            Log.d(TAG, "getDisplayNameFromUri: File path: " + path);
            if (path != null) {
                int lastSlashIndex = path.lastIndexOf('/');
                if (lastSlashIndex != -1 && lastSlashIndex < path.length() - 1) {
                    result = path.substring(lastSlashIndex + 1);
                    Log.d(TAG, "getDisplayNameFromUri: Extracted filename from path: " + result);
                } else {
                    Log.d(TAG, "getDisplayNameFromUri: Could not extract filename from path");
                }
            }
        } else {
            Log.d(TAG, "getDisplayNameFromUri: Unknown URI scheme: " + uri.getScheme());
        }

        // If all else fails, return the URI string
        if (result == null) {
            result = uri.toString();
            Log.d(TAG, "getDisplayNameFromUri: Falling back to URI string: " + result);
        }

        Log.d(TAG, "getDisplayNameFromUri: Final result: " + result);
        return result;
    }
}