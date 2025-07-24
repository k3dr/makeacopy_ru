package de.schliweb.makeacopy;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import de.schliweb.makeacopy.databinding.ActivityMainBinding;

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

}