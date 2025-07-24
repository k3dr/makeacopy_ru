package de.schliweb.makeacopy.utils;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * A utility class that provides various methods to assist with UI-related tasks.
 * This class is not intended to be instantiated.
 */
public class UIUtils {
    private static final String TAG = "UIUtils";

    private UIUtils() {
        // private because utility class
    }

    /**
     * Adjusts the margin of a view to account for system insets
     *
     * @param view         The view to adjust
     * @param baseMarginDp The base margin in dp (will be added to the system inset)
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
     * Adjusts the top margin of a TextView to account for status bar height
     *
     * @param textView     The TextView to adjust
     * @param baseMarginDp The base margin in dp (will be added to the status bar height)
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
     * Shows a toast message using the application context to prevent memory leaks and context-related issues.
     * This method is safe to use from background threads or after fragment/activity lifecycle changes.
     *
     * @param context  Any context (activity, fragment, or application)
     * @param message  The message to display
     * @param duration The duration of the toast (Toast.LENGTH_SHORT or Toast.LENGTH_LONG)
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
     * Shows a toast message using the application context to prevent memory leaks and context-related issues.
     * This method is safe to use from background threads or after fragment/activity lifecycle changes.
     *
     * @param context  Any context (activity, fragment, or application)
     * @param resId    The resource ID of the message to display
     * @param duration The duration of the toast (Toast.LENGTH_SHORT or Toast.LENGTH_LONG)
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