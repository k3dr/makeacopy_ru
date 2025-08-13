package de.schliweb.makeacopy;

import android.app.Application;
import android.util.Log;
import de.schliweb.makeacopy.services.CacheCleanupService;
import de.schliweb.makeacopy.utils.OpenCVUtils;

/**
 * Main Application class for MakeACopy.
 * Handles global initialization including OpenCV and background services.
 */
public class MakeACopyApplication extends Application {

    private static final String TAG = "MakeACopyApplication";

    @Override
    public void onCreate() {
        super.onCreate();

        Log.i(TAG, "MakeACopy Application starting...");

        // Initialize OpenCV
        initializeOpenCV();

        // TODO: Prüfen, ob zu entfernen
        if (!isRunningInTest()) {
            initializeCacheCleanupService();
        }
        Log.i(TAG, "MakeACopy Application initialized successfully");
    }

    private boolean isRunningInTest() {
        return "true".equals(System.getProperty("IS_TESTING"));
    }

    /**
     * Initialize OpenCV library
     */
    private void initializeOpenCV() {
        try {
            boolean success = OpenCVUtils.init(this);
            if (success) {
                Log.i(TAG, "OpenCV initialized successfully");
            } else {
                Log.e(TAG, "Failed to initialize OpenCV");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error initializing OpenCV", e);
        }
    }

    /**
     * Initialize and start the Cache Cleanup Service
     */
    private void initializeCacheCleanupService() {
        try {
            // Start the cache cleanup service with default configuration
            CacheCleanupService.startService(this);
            Log.i(TAG, "Cache Cleanup Service started");

            // Configure service for optimal performance
            // Cleanup every 2 hours, keep max 15 debug files, remove temps after 1 hour, trigger at 75% memory
            CacheCleanupService.updateConfiguration(
                this,
                true,  // enabled
                2,     // cleanup interval hours
                15,    // max debug files
                1,     // max temp age hours
                75     // memory threshold percent
            );
            Log.d(TAG, "Cache Cleanup Service configured");

        } catch (Exception e) {
            Log.e(TAG, "Error initializing Cache Cleanup Service", e);
        }
    }

    /**
     * Called when the overall system is running low on memory
     */
    @Override
    public void onLowMemory() {
        super.onLowMemory();

        Log.w(TAG, "System low memory detected - triggering emergency cache cleanup");

        try {
            // Force immediate cache cleanup when system is low on memory
            CacheCleanupService.forceCleanup(this);

            // Force garbage collection
            System.gc();

            Log.i(TAG, "Emergency cache cleanup completed");

        } catch (Exception e) {
            Log.e(TAG, "Error during emergency cache cleanup", e);
        }
    }

    /**
     * Called when the operating system has determined that it is a good
     * time for a process to trim unneeded memory from its process
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);

        Log.d(TAG, "Memory trim requested with level: " + level);

        // Trigger cache cleanup for moderate to critical memory pressure
        if (level >= TRIM_MEMORY_MODERATE || level >= TRIM_MEMORY_RUNNING_MODERATE) {
            try {
                CacheCleanupService.forceCleanup(this);
                Log.i(TAG, "Cache cleanup triggered by memory trim (level: " + level + ")");
            } catch (Exception e) {
                Log.e(TAG, "Error during memory trim cache cleanup", e);
            }
        }
    }
}
