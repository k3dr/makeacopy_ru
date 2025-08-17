package de.schliweb.makeacopy.utils;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * A utility class containing helper methods for common user interface tasks.
 * This class provides functions to adjust view margins for system insets, handle
 * status bar height, and display Toast messages safely.
 * <p>
 * This class is not intended to be instantiated.
 */
public class UIUtils {
    private static final String TAG = "UIUtils";

    private UIUtils() {
        // private because utility class
    }

    /**
     * Adjusts the bottom margin of the given view to account for system insets, such as
     * the navigation bar, while also applying an additional base margin specified in dp.
     *
     * @param view         The view whose bottom margin should be adjusted. If null, the method does nothing.
     * @param baseMarginDp The base margin in dp to be added to the system insets. This value is
     *                     converted to pixels before being applied.
     */
    public static void adjustMarginForSystemInsets(View view, int baseMarginDp) {
        if (view == null) {
            return;
        }

        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        if (params == null) {
            return;
        }

        int bottomInset = 0;
        WindowInsetsCompat windowInsets = ViewCompat.getRootWindowInsets(view);
        if (windowInsets != null) {
            bottomInset = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
        }

        // Convert dp to pixels
        float density = view.getResources().getDisplayMetrics().density;
        int baseMarginPx = (int) (baseMarginDp * density);

        params.bottomMargin = baseMarginPx + bottomInset;
        view.setLayoutParams(params);
    }

    /**
     * Adjusts the top margin of the given TextView to account for the status bar's height, while also
     * including an additional base margin specified in dp. The method calculates the status bar height
     * using system insets and combines it with the provided base margin before applying the resulting
     * value to the TextView's top margin.
     *
     * @param textView     The TextView whose top margin should be adjusted. If null, the method does nothing.
     * @param baseMarginDp The base margin in dp to be added to the status bar's height. This value is
     *                     converted to pixels before being applied.
     */
    public static void adjustTextViewTopMarginForStatusBar(TextView textView, int baseMarginDp) {
        if (textView == null) {
            return;
        }

        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) textView.getLayoutParams();
        if (params == null) {
            return;
        }

        int topInset = 0;
        WindowInsetsCompat windowInsets = ViewCompat.getRootWindowInsets(textView);
        if (windowInsets != null) {
            topInset = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).top;
        }

        // Convert dp to pixels
        float density = textView.getResources().getDisplayMetrics().density;
        int baseMarginPx = (int) (baseMarginDp * density);

        params.topMargin = baseMarginPx + topInset;
        textView.setLayoutParams(params);
    }

    /**
     * Displays a toast message using the provided message and duration.
     * It ensures the application context is used to avoid memory leaks
     * or context-related issues. If the context or message is null, the method does nothing.
     *
     * @param context  The context from which the toast is triggered. If null, no action is taken.
     * @param message  The message to display in the toast. If null, no action is taken.
     * @param duration The duration for which the toast should be displayed.
     *                 Should be either Toast.LENGTH_SHORT or Toast.LENGTH_LONG.
     */
    public static void showToast(Context context, String message, int duration) {
        if (context == null || message == null) {
            return;
        }

        // Always use the application context to prevent memory leaks and context-related issues
        Context appContext = context.getApplicationContext();
        Toast.makeText(appContext, message, duration).show();
    }

    /**
     * Displays a toast message using the string resource ID and duration provided.
     * Ensures that the application context is used to avoid memory leaks or
     * context-related issues. If the context is null, the method does nothing.
     *
     * @param context  The context from which the toast is triggered. If null, no action is taken.
     * @param resId    The resource ID of the string to display in the toast.
     * @param duration The duration for which the toast should be displayed.
     *                 Should be either Toast.LENGTH_SHORT or Toast.LENGTH_LONG.
     */
    public static void showToast(Context context, int resId, int duration) {
        if (context == null) {
            return;
        }

        // Always use the application context to prevent memory leaks and context-related issues
        Context appContext = context.getApplicationContext();
        Toast.makeText(appContext, resId, duration).show();
    }
}