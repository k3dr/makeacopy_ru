package de.schliweb.makeacopy;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
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

        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
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

        // Trigger cache cleanup for moderate to critical memory pressure
        if (level >= TRIM_MEMORY_MODERATE || level >= TRIM_MEMORY_RUNNING_MODERATE) {
            CacheCleanupService.forceCleanup(this);
        }
    }
}
