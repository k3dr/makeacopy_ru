package de.schliweb.makeacopy;

import android.os.Bundle;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import de.schliweb.makeacopy.databinding.ActivityMainBinding;
import de.schliweb.makeacopy.services.CacheCleanupService;

/**
 * MainActivity represents the entry point of the application.
 * This activity initializes the main view and setups up the UI components.
 * <p>
 * It enables edge-to-edge display mode for a more immersive user interface
 * experience and inflates the main layout using view binding.
 * <p>
 * This class extends AppCompatActivity and overrides the onCreate method
 * to set up the activity's user interface.
 */
public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Enable edge-to-edge display using modern API (Android 15+ compatible)
        EdgeToEdge.enable(this);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Setup donate FAB (temporarily disabled)
        if (binding.fabDonate != null) {
            // Temporarily disable donation feature: no click listener, hidden and not interactive
            binding.fabDonate.setOnClickListener(null);
            binding.fabDonate.setVisibility(View.GONE);
            binding.fabDonate.setEnabled(false);
            binding.fabDonate.setClickable(false);
        }

        // Dynamically position FAB just above the navigation bar based on WindowInsets
        ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), (v, insets) -> {
            if (binding.fabDonate != null) {
                int bottomInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) binding.fabDonate.getLayoutParams();
                int baseMarginPx = (int) TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP, 0, getResources().getDisplayMetrics());
                lp.bottomMargin = bottomInset + baseMarginPx;
                binding.fabDonate.setLayoutParams(lp);
            }
            return insets;
        });
    }

    public void setDonateFabVisible(boolean visible) {
        // Temporarily disabled: always hide, ignore requested visibility
        if (binding != null && binding.fabDonate != null) {
            binding.fabDonate.setVisibility(View.GONE);
        }
    }

    private void openDonationLink() {
        String url = getString(R.string.donation_url);
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.donate_cd, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Called when the system is running low on memory, and actively running processes
     * should tighten their belts.
     */
    @Override
    public void onLowMemory() {
        super.onLowMemory();

        // Trigger immediate cache cleanup when activity detects low memory
        CacheCleanupService.forceCleanup(this);
    }

    /**
     * Called when the operating system has determined that it is a good
     * time for a process to trim unneeded memory from its process.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);

        // Trigger cache cleanup when app is in the background and memory is low (non-deprecated level)
        if (level >= TRIM_MEMORY_BACKGROUND) {
            CacheCleanupService.forceCleanup(this);
        }
    }
}
