package de.schliweb.makeacopy.utils;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;

import java.io.File;
import java.util.ArrayList;

/**
 * A utility class for facilitating sharing of documents using Android's sharing intents.
 * This class provides methods to share documents, including optional associated text files,
 * through supported applications on an Android device.
 * <p>
 * The class is not intended to be instantiated, as it only contains static methods.
 */
public final class ShareIntentHelper {
    private ShareIntentHelper() {
    }

    /**
     * Shares a document with optional accompanying text file using Android's sharing intents.
     * The method constructs and launches a share intent for the provided document and associated
     * details such as file name and optional text file.
     *
     * @param fragment    The {@link Fragment} from which the share intent will be started. Cannot be null.
     * @param documentUri The {@link Uri} of the document to be shared. Cannot be null.
     * @param txtUri      The {@link Uri} of a complementary text file to be shared along with the main document.
     *                    Can be null if no text file is to be included.
     * @param fileName    The name of the document file being shared. Used for labeling purposes, and influences MIME type detection.
     *                    Can be null, but MIME type may not be inferred correctly if omitted.
     */
    public static void shareDocument(Fragment fragment, Uri documentUri, Uri txtUri, String fileName) {
        if (fragment == null || documentUri == null) return;
        Context ctx = fragment.requireContext();

        String lowerName = (fileName != null) ? fileName.toLowerCase() : "";
        boolean isPdf = lowerName.endsWith(".pdf");
        boolean isJpeg = lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg");
        boolean isZip = lowerName.endsWith(".zip");
        String primaryMime = isZip ? "application/zip" : (isJpeg ? "image/jpeg" : "application/pdf");

        boolean hasTxtFile = txtUri != null;
        Intent shareIntent = hasTxtFile ? new Intent(Intent.ACTION_SEND_MULTIPLE) : new Intent(Intent.ACTION_SEND);
        shareIntent.setType(hasTxtFile ? "*/*" : primaryMime);

        Uri contentUri = ensureContentUri(ctx, documentUri);

        String label = hasTxtFile ? (fileName + " + OCR TXT") : fileName;
        shareIntent.putExtra(Intent.EXTRA_TITLE, label);
        shareIntent.putExtra(Intent.EXTRA_SUBJECT, label);
        shareIntent.putExtra(Intent.EXTRA_TEXT, label);

        if (hasTxtFile) {
            Uri txtContentUri = ensureContentUri(ctx, txtUri);
            ArrayList<Uri> uriList = new ArrayList<>();
            uriList.add(contentUri);
            uriList.add(txtContentUri);
            ClipData clipData = new ClipData(label, new String[]{primaryMime, "text/plain"}, new ClipData.Item(contentUri));
            clipData.addItem(new ClipData.Item(txtContentUri));
            shareIntent.setClipData(clipData);
            shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uriList);
        } else {
            ClipData clipData = ClipData.newUri(ctx.getContentResolver(), label, contentUri);
            shareIntent.setClipData(clipData);
            shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri);
        }

        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        fragment.startActivity(Intent.createChooser(shareIntent, "Share " + label));
    }

    /**
     * Ensures that the provided URI is converted to a content URI if it is not already one.
     * If the URI is null, it directly returns null. If the URI has a "content" scheme, it is directly returned.
     * Otherwise, it generates a content URI using the provided context's file provider.
     *
     * @param ctx The context used to access the FileProvider and to create the content URI. Cannot be null.
     * @param uri The URI to check and potentially convert to a content URI. Can be null.
     * @return The content URI equivalent of the provided URI, or null if the input URI is null.
     */
    private static Uri ensureContentUri(Context ctx, Uri uri) {
        if (uri == null) return null;
        if ("content".equalsIgnoreCase(uri.getScheme())) return uri;
        String authority = ctx.getPackageName() + ".fileprovider";
        File file = new File(uri.getPath());
        return FileProvider.getUriForFile(ctx, authority, file);
    }
}
