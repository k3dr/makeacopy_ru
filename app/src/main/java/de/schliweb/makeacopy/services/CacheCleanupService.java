package de.schliweb.makeacopy.services;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background service for centralized cache cleanup management.
 * Handles cleanup of temporary files, debug images, old camera captures,
 * and memory management across the entire application.
 *
 * This service runs independently of UI components and provides
 * consistent cache management regardless of app state.
 */
public class CacheCleanupService extends Service {

    private static final String TAG = "CacheCleanupService";

    // Configuration constants
    private static final String PREFS_NAME = "cache_cleanup_prefs";
    private static final String PREF_LAST_CLEANUP = "last_cleanup_time";
    private static final String PREF_CLEANUP_ENABLED = "cleanup_enabled";
    private static final String PREF_CLEANUP_INTERVAL_HOURS = "cleanup_interval_hours";
    private static final String PREF_MAX_DEBUG_FILES = "max_debug_files";
    private static final String PREF_MAX_TEMP_AGE_HOURS = "max_temp_age_hours";
    private static final String PREF_MEMORY_THRESHOLD_PERCENT = "memory_threshold_percent";

    // Default values
    private static final long DEFAULT_CLEANUP_INTERVAL_HOURS = 2; // Every 2 hours
    private static final int DEFAULT_MAX_DEBUG_FILES = 20;
    private static final int DEFAULT_MAX_TEMP_AGE_HOURS = 2;
    private static final int DEFAULT_MEMORY_THRESHOLD_PERCENT = 75;

    private ScheduledExecutorService scheduledExecutor;
    private Handler mainHandler;
    private SharedPreferences preferences;

    // Configuration
    private boolean cleanupEnabled = true;
    private long cleanupIntervalHours = DEFAULT_CLEANUP_INTERVAL_HOURS;
    private int maxDebugFiles = DEFAULT_MAX_DEBUG_FILES;
    private int maxTempAgeHours = DEFAULT_MAX_TEMP_AGE_HOURS;
    private int memoryThresholdPercent = DEFAULT_MEMORY_THRESHOLD_PERCENT;

    @Override
    public void onCreate() {
        super.onCreate();

        Log.i(TAG, "CacheCleanupService created");

        mainHandler = new Handler(Looper.getMainLooper());
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        loadConfiguration();

        if (cleanupEnabled) {
            startScheduledCleanup();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "CacheCleanupService started");

        if (intent != null) {
            String action = intent.getAction();
            if ("FORCE_CLEANUP".equals(action)) {
                performImmediateCleanup();
            } else if ("UPDATE_CONFIG".equals(action)) {
                updateConfiguration(intent);
            }
        }

        return START_STICKY; // Restart if killed
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        Log.i(TAG, "CacheCleanupService destroyed");

        if (scheduledExecutor != null && !scheduledExecutor.isShutdown()) {
            scheduledExecutor.shutdown();
            try {
                if (!scheduledExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduledExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduledExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // No binding needed
    }

    /**
     * Loads configuration from SharedPreferences
     */
    private void loadConfiguration() {
        cleanupEnabled = preferences.getBoolean(PREF_CLEANUP_ENABLED, true);
        cleanupIntervalHours = preferences.getLong(PREF_CLEANUP_INTERVAL_HOURS, DEFAULT_CLEANUP_INTERVAL_HOURS);
        maxDebugFiles = preferences.getInt(PREF_MAX_DEBUG_FILES, DEFAULT_MAX_DEBUG_FILES);
        maxTempAgeHours = preferences.getInt(PREF_MAX_TEMP_AGE_HOURS, DEFAULT_MAX_TEMP_AGE_HOURS);
        memoryThresholdPercent = preferences.getInt(PREF_MEMORY_THRESHOLD_PERCENT, DEFAULT_MEMORY_THRESHOLD_PERCENT);

        Log.d(TAG, String.format("Configuration loaded: enabled=%b, interval=%dh, maxDebugFiles=%d, maxTempAge=%dh, memoryThreshold=%d%%",
                cleanupEnabled, cleanupIntervalHours, maxDebugFiles, maxTempAgeHours, memoryThresholdPercent));
    }

    /**
     * Updates configuration from intent extras
     */
    private void updateConfiguration(Intent intent) {
        SharedPreferences.Editor editor = preferences.edit();

        if (intent.hasExtra(PREF_CLEANUP_ENABLED)) {
            cleanupEnabled = intent.getBooleanExtra(PREF_CLEANUP_ENABLED, true);
            editor.putBoolean(PREF_CLEANUP_ENABLED, cleanupEnabled);
        }

        if (intent.hasExtra(PREF_CLEANUP_INTERVAL_HOURS)) {
            cleanupIntervalHours = intent.getLongExtra(PREF_CLEANUP_INTERVAL_HOURS, DEFAULT_CLEANUP_INTERVAL_HOURS);
            editor.putLong(PREF_CLEANUP_INTERVAL_HOURS, cleanupIntervalHours);
        }

        if (intent.hasExtra(PREF_MAX_DEBUG_FILES)) {
            maxDebugFiles = intent.getIntExtra(PREF_MAX_DEBUG_FILES, DEFAULT_MAX_DEBUG_FILES);
            editor.putInt(PREF_MAX_DEBUG_FILES, maxDebugFiles);
        }

        if (intent.hasExtra(PREF_MAX_TEMP_AGE_HOURS)) {
            maxTempAgeHours = intent.getIntExtra(PREF_MAX_TEMP_AGE_HOURS, DEFAULT_MAX_TEMP_AGE_HOURS);
            editor.putInt(PREF_MAX_TEMP_AGE_HOURS, maxTempAgeHours);
        }

        if (intent.hasExtra(PREF_MEMORY_THRESHOLD_PERCENT)) {
            memoryThresholdPercent = intent.getIntExtra(PREF_MEMORY_THRESHOLD_PERCENT, DEFAULT_MEMORY_THRESHOLD_PERCENT);
            editor.putInt(PREF_MEMORY_THRESHOLD_PERCENT, memoryThresholdPercent);
        }

        editor.apply();

        Log.i(TAG, "Configuration updated");

        // Restart scheduled cleanup with new configuration
        if (cleanupEnabled) {
            restartScheduledCleanup();
        } else {
            stopScheduledCleanup();
        }
    }

    /**
     * Starts the scheduled cleanup task
     */
    private void startScheduledCleanup() {
        if (scheduledExecutor != null && !scheduledExecutor.isShutdown()) {
            scheduledExecutor.shutdown();
        }

        scheduledExecutor = Executors.newSingleThreadScheduledExecutor();

        long intervalMs = cleanupIntervalHours * 60 * 60 * 1000;

        scheduledExecutor.scheduleAtFixedRate(
            this::performScheduledCleanup,
            intervalMs, // Initial delay
            intervalMs, // Period
            TimeUnit.MILLISECONDS
        );

        Log.i(TAG, "Scheduled cleanup started with interval: " + cleanupIntervalHours + " hours");
    }

    /**
     * Stops the scheduled cleanup task
     */
    private void stopScheduledCleanup() {
        if (scheduledExecutor != null && !scheduledExecutor.isShutdown()) {
            scheduledExecutor.shutdown();
            Log.i(TAG, "Scheduled cleanup stopped");
        }
    }

    /**
     * Restarts the scheduled cleanup with updated configuration
     */
    private void restartScheduledCleanup() {
        stopScheduledCleanup();
        startScheduledCleanup();
    }

    /**
     * Performs scheduled cleanup check
     */
    private void performScheduledCleanup() {
        Log.d(TAG, "Performing scheduled cleanup check");

        try {
            // Check if cleanup is needed based on time
            long lastCleanup = preferences.getLong(PREF_LAST_CLEANUP, 0);
            long currentTime = System.currentTimeMillis();
            long timeSinceLastCleanup = currentTime - lastCleanup;
            long cleanupIntervalMs = cleanupIntervalHours * 60 * 60 * 1000;

            boolean shouldCleanupByTime = timeSinceLastCleanup >= cleanupIntervalMs;
            boolean shouldCleanupByMemory = isMemoryUsageHigh();

            if (shouldCleanupByTime || shouldCleanupByMemory) {
                Log.i(TAG, String.format("Cleanup triggered: byTime=%b, byMemory=%b", shouldCleanupByTime, shouldCleanupByMemory));
                performComprehensiveCleanup();

                // Update last cleanup time
                preferences.edit().putLong(PREF_LAST_CLEANUP, currentTime).apply();
            } else {
                Log.d(TAG, "No cleanup needed at this time");
            }

        } catch (Exception e) {
            Log.e(TAG, "Error during scheduled cleanup", e);
        }
    }

    /**
     * Performs immediate cleanup (called via intent)
     */
    private void performImmediateCleanup() {
        Log.i(TAG, "Performing immediate cleanup");

        // Run cleanup on background thread
        if (scheduledExecutor == null || scheduledExecutor.isShutdown()) {
            // Create temporary executor if scheduled one is not available
            Executors.newSingleThreadExecutor().submit(() -> {
                performComprehensiveCleanup();
                preferences.edit().putLong(PREF_LAST_CLEANUP, System.currentTimeMillis()).apply();
            });
        } else {
            scheduledExecutor.submit(() -> {
                performComprehensiveCleanup();
                preferences.edit().putLong(PREF_LAST_CLEANUP, System.currentTimeMillis()).apply();
            });
        }
    }

    /**
     * Checks if memory usage is above threshold
     */
    private boolean isMemoryUsageHigh() {
        try {
            Runtime runtime = Runtime.getRuntime();
            long usedMemory = runtime.totalMemory() - runtime.freeMemory();
            long maxMemory = runtime.maxMemory();
            double memoryUsagePercent = (double) usedMemory / maxMemory * 100;

            Log.d(TAG, String.format("Current memory usage: %.1f%% (threshold: %d%%)",
                  memoryUsagePercent, memoryThresholdPercent));

            return memoryUsagePercent > memoryThresholdPercent;

        } catch (Exception e) {
            Log.e(TAG, "Error checking memory usage", e);
            return false;
        }
    }

    /**
     * Performs comprehensive cleanup of all cache types
     */
    private void performComprehensiveCleanup() {
        Log.i(TAG, "Starting comprehensive cache cleanup");

        long startTime = System.currentTimeMillis();

        try {
            // Log memory before cleanup
            logMemoryUsage("before cleanup");

            // Cleanup different cache types
            int debugFilesCleanup = cleanupDebugImages();
            int cameraFilesCleanup = cleanupOldCameraImages();
            int tempFilesCleanup = cleanupTempFiles();

            // Force garbage collection
            System.gc();

            // Log memory after cleanup
            logMemoryUsage("after cleanup");

            long duration = System.currentTimeMillis() - startTime;

            Log.i(TAG, String.format("Cache cleanup completed in %dms. Files removed: debug=%d, camera=%d, temp=%d",
                  duration, debugFilesCleanup, cameraFilesCleanup, tempFilesCleanup));

        } catch (Exception e) {
            Log.e(TAG, "Error during comprehensive cleanup", e);
        }
    }

    /**
     * Cleans up debug images
     */
    private int cleanupDebugImages() {
        try {
            File externalDir = getExternalFilesDir(null);
            if (externalDir == null || !externalDir.exists()) return 0;

            File[] debugFiles = externalDir.listFiles((file, name) ->
                name.startsWith("debug_") && name.endsWith(".png"));
            if (debugFiles == null) return 0;

            // Sort by last modified date (oldest first)
            Arrays.sort(debugFiles, Comparator.comparingLong(File::lastModified));

            // Keep only the most recent debug files
            int filesToDelete = Math.max(0, debugFiles.length - maxDebugFiles);
            int deletedCount = 0;

            for (int i = 0; i < filesToDelete; i++) {
                if (debugFiles[i].delete()) {
                    deletedCount++;
                }
            }

            if (deletedCount > 0) {
                Log.d(TAG, "Cleaned up " + deletedCount + " debug images");
            }

            return deletedCount;

        } catch (Exception e) {
            Log.e(TAG, "Error cleaning up debug images", e);
            return 0;
        }
    }

    /**
     * Cleans up old camera images
     */
    private int cleanupOldCameraImages() {
        try {
            File picturesDir = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "MakeACopy");
            if (!picturesDir.exists()) return 0;

            File[] imageFiles = picturesDir.listFiles((file, name) ->
                name.startsWith("MakeACopy_") && name.endsWith(".jpg"));
            if (imageFiles == null) return 0;

            long maxAgeMs = maxTempAgeHours * 60 * 60 * 1000L;
            long cutoffTime = System.currentTimeMillis() - maxAgeMs;
            int deletedCount = 0;

            for (File imageFile : imageFiles) {
                if (imageFile.lastModified() < cutoffTime && imageFile.delete()) {
                    deletedCount++;
                }
            }

            if (deletedCount > 0) {
                Log.d(TAG, "Cleaned up " + deletedCount + " old camera images");
            }

            return deletedCount;

        } catch (Exception e) {
            Log.e(TAG, "Error cleaning up old camera images", e);
            return 0;
        }
    }

    /**
     * Cleans up other temporary files
     */
    private int cleanupTempFiles() {
        try {
            // Cleanup app cache directory
            File cacheDir = getCacheDir();
            return cleanupDirectoryRecursively(cacheDir);

        } catch (Exception e) {
            Log.e(TAG, "Error cleaning up temp files", e);
            return 0;
        }
    }

    /**
     * Recursively cleans up files in a directory older than maxTempAgeHours
     */
    private int cleanupDirectoryRecursively(File directory) {
        if (directory == null || !directory.exists() || !directory.isDirectory()) {
            return 0;
        }

        int deletedCount = 0;
        long maxAgeMs = maxTempAgeHours * 60 * 60 * 1000L;
        long cutoffTime = System.currentTimeMillis() - maxAgeMs;

        File[] files = directory.listFiles();
        if (files == null) return 0;

        for (File file : files) {
            if (file.isDirectory()) {
                deletedCount += cleanupDirectoryRecursively(file);
                // Remove empty directories
                if (file.list() != null && file.list().length == 0) {
                    if (file.delete()) deletedCount++;
                }
            } else {
                if (file.lastModified() < cutoffTime && file.delete()) {
                    deletedCount++;
                }
            }
        }

        return deletedCount;
    }

    /**
     * Logs current memory usage
     */
    private void logMemoryUsage(String context) {
        try {
            Runtime runtime = Runtime.getRuntime();
            long usedMemory = runtime.totalMemory() - runtime.freeMemory();
            long maxMemory = runtime.maxMemory();
            double memoryUsagePercent = (double) usedMemory / maxMemory * 100;

            Log.d(TAG, String.format("Memory usage %s: %.1f%% (%d MB / %d MB)",
                  context, memoryUsagePercent, usedMemory / (1024 * 1024), maxMemory / (1024 * 1024)));

        } catch (Exception e) {
            Log.e(TAG, "Error logging memory usage", e);
        }
    }

    /**
     * Static utility method to start the service
     */
    public static void startService(Context context) {
        Intent intent = new Intent(context, CacheCleanupService.class);
        context.startService(intent);
    }

    /**
     * Static utility method to force immediate cleanup
     * Android 8.0+ compatible - uses direct cleanup to avoid BackgroundServiceStartNotAllowedException
     */
    public static void forceCleanup(Context context) {
        try {
            // Try to start service first (works when app is in foreground)
            Intent intent = new Intent(context, CacheCleanupService.class);
            intent.setAction("FORCE_CLEANUP");
            context.startService(intent);
            Log.i(TAG, "Service-based cleanup initiated");

        } catch (Exception e) {
            // Fallback to direct cleanup if service start fails (background restrictions)
            Log.w(TAG, "Service start failed, using direct cleanup: " + e.getMessage());
            performDirectCleanup(context);
        }
    }

    /**
     * Static utility method to update service configuration
     */
    public static void updateConfiguration(Context context,
                                         boolean enabled,
                                         long intervalHours,
                                         int maxDebugFiles,
                                         int maxTempAgeHours,
                                         int memoryThresholdPercent) {
        try {
            Intent intent = new Intent(context, CacheCleanupService.class);
            intent.setAction("UPDATE_CONFIG");
            intent.putExtra(PREF_CLEANUP_ENABLED, enabled);
            intent.putExtra(PREF_CLEANUP_INTERVAL_HOURS, intervalHours);
            intent.putExtra(PREF_MAX_DEBUG_FILES, maxDebugFiles);
            intent.putExtra(PREF_MAX_TEMP_AGE_HOURS, maxTempAgeHours);
            intent.putExtra(PREF_MEMORY_THRESHOLD_PERCENT, memoryThresholdPercent);
            context.startService(intent);
        } catch (Exception e) {
            Log.w(TAG, "Service configuration update failed: " + e.getMessage());
        }
    }

    /**
     * MEMORY-SAFE: Direct cache cleanup without starting service
     * This method can be called even when the app is in background
     * Android 8.0+ compatible alternative to service-based cleanup
     */
    public static void performDirectCleanup(Context context) {
        Log.i(TAG, "Direct cache cleanup requested (background-safe)");

        try {
            // Perform cleanup directly without starting service
            performCacheCleanupDirect(context);

            // Update preferences to reflect cleanup time
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putLong(PREF_LAST_CLEANUP, System.currentTimeMillis()).apply();

            Log.i(TAG, "Direct cache cleanup completed successfully");

        } catch (Exception e) {
            Log.e(TAG, "Error during direct cache cleanup", e);
        }
    }

    /**
     * Performs cache cleanup directly without service context
     * Background-safe alternative for memory pressure situations
     */
    private static void performCacheCleanupDirect(Context context) {
        long startTime = System.currentTimeMillis();

        try {
            // Log memory before cleanup
            logMemoryUsageStatic("before direct cleanup");

            // Cleanup different cache types directly
            int debugFilesCleanup = cleanupDebugImagesDirect(context);
            int cameraFilesCleanup = cleanupOldCameraImagesDirect(context);
            int tempFilesCleanup = cleanupTempFilesDirect(context);

            // Force garbage collection
            System.gc();

            // Log memory after cleanup
            logMemoryUsageStatic("after direct cleanup");

            long duration = System.currentTimeMillis() - startTime;

            Log.i(TAG, String.format("Direct cache cleanup completed in %dms. Files removed: debug=%d, camera=%d, temp=%d",
                  duration, debugFilesCleanup, cameraFilesCleanup, tempFilesCleanup));

        } catch (Exception e) {
            Log.e(TAG, "Error during direct cache cleanup", e);
        }
    }

    /**
     * Static version of cleanupDebugImages for direct cleanup
     */
    private static int cleanupDebugImagesDirect(Context context) {
        try {
            File externalDir = context.getExternalFilesDir(null);
            if (externalDir == null || !externalDir.exists()) return 0;

            File[] debugFiles = externalDir.listFiles((file, name) ->
                name.startsWith("debug_") && name.endsWith(".png"));
            if (debugFiles == null) return 0;

            // Use default max files value
            int maxDebugFiles = DEFAULT_MAX_DEBUG_FILES;

            // Sort by last modified date (oldest first)
            Arrays.sort(debugFiles, Comparator.comparingLong(File::lastModified));

            // Keep only the most recent debug files
            int filesToDelete = Math.max(0, debugFiles.length - maxDebugFiles);
            int deletedCount = 0;

            for (int i = 0; i < filesToDelete; i++) {
                if (debugFiles[i].delete()) {
                    deletedCount++;
                }
            }

            if (deletedCount > 0) {
                Log.d(TAG, "Direct cleanup: Cleaned up " + deletedCount + " debug images");
            }

            return deletedCount;

        } catch (Exception e) {
            Log.e(TAG, "Error in direct debug images cleanup", e);
            return 0;
        }
    }

    /**
     * Static version of cleanupOldCameraImages for direct cleanup
     */
    private static int cleanupOldCameraImagesDirect(Context context) {
        try {
            File picturesDir = new File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES), "MakeACopy");
            if (!picturesDir.exists()) return 0;

            File[] imageFiles = picturesDir.listFiles((file, name) ->
                name.startsWith("MakeACopy_") && name.endsWith(".jpg"));
            if (imageFiles == null) return 0;

            // Use default max age value
            long maxAgeMs = DEFAULT_MAX_TEMP_AGE_HOURS * 60 * 60 * 1000L;
            long cutoffTime = System.currentTimeMillis() - maxAgeMs;
            int deletedCount = 0;

            for (File imageFile : imageFiles) {
                if (imageFile.lastModified() < cutoffTime && imageFile.delete()) {
                    deletedCount++;
                }
            }

            if (deletedCount > 0) {
                Log.d(TAG, "Direct cleanup: Cleaned up " + deletedCount + " old camera images");
            }

            return deletedCount;

        } catch (Exception e) {
            Log.e(TAG, "Error in direct camera images cleanup", e);
            return 0;
        }
    }

    /**
     * Static version of cleanupTempFiles for direct cleanup
     */
    private static int cleanupTempFilesDirect(Context context) {
        try {
            // Cleanup app cache directory
            File cacheDir = context.getCacheDir();
            return cleanupDirectoryRecursivelyDirect(cacheDir);

        } catch (Exception e) {
            Log.e(TAG, "Error in direct temp files cleanup", e);
            return 0;
        }
    }

    /**
     * Static version of cleanupDirectoryRecursively for direct cleanup
     */
    private static int cleanupDirectoryRecursivelyDirect(File directory) {
        if (directory == null || !directory.exists() || !directory.isDirectory()) {
            return 0;
        }

        int deletedCount = 0;
        long maxAgeMs = DEFAULT_MAX_TEMP_AGE_HOURS * 60 * 60 * 1000L;
        long cutoffTime = System.currentTimeMillis() - maxAgeMs;

        File[] files = directory.listFiles();
        if (files == null) return 0;

        for (File file : files) {
            if (file.isDirectory()) {
                deletedCount += cleanupDirectoryRecursivelyDirect(file);
                // Remove empty directories
                if (file.list() != null && file.list().length == 0) {
                    if (file.delete()) deletedCount++;
                }
            } else {
                if (file.lastModified() < cutoffTime && file.delete()) {
                    deletedCount++;
                }
            }
        }

        return deletedCount;
    }

    /**
     * Static version of logMemoryUsage for direct cleanup
     */
    private static void logMemoryUsageStatic(String context) {
        try {
            Runtime runtime = Runtime.getRuntime();
            long usedMemory = runtime.totalMemory() - runtime.freeMemory();
            long maxMemory = runtime.maxMemory();
            double memoryUsagePercent = (double) usedMemory / maxMemory * 100;

            Log.d(TAG, String.format("Memory usage %s: %.1f%% (%d MB / %d MB)",
                  context, memoryUsagePercent, usedMemory / (1024 * 1024), maxMemory / (1024 * 1024)));

        } catch (Exception e) {
            Log.e(TAG, "Error logging memory usage", e);
        }
    }
}
