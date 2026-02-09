package com.hfs.security.ui;

import android.Manifest;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.navigation.NavController;
import androidx.navigation.fragment.NavHostFragment;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import com.hfs.security.R;
import com.hfs.security.databinding.ActivityMainBinding;
import com.hfs.security.utils.HFSDatabaseHelper;

/**
 * The primary host activity for HFS - Hybrid File Security.
 * Manages fragment navigation and ensures all critical security 
 * permissions are granted before operation.
 */
public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private NavController navController;
    private HFSDatabaseHelper db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize ViewBinding for the activity layout
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        db = HFSDatabaseHelper.getInstance(this);

        // Setup the material toolbar
        setSupportActionBar(binding.toolbar);

        // Setup the Jetpack Navigation Component
        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment);
        
        if (navHostFragment != null) {
            navController = navHostFragment.getNavController();

            // Configure top-level destinations (no back button on these)
            AppBarConfiguration appBarConfiguration = new AppBarConfiguration.Builder(
                    R.id.nav_home, 
                    R.id.nav_protected_apps, 
                    R.id.nav_history)
                    .build();

            // Link Toolbar and Bottom Navigation to the Controller
            NavigationUI.setupActionBarWithNavController(this, navController, appBarConfiguration);
            NavigationUI.setupWithNavController(binding.bottomNav, navController);
        }

        // Check if we need to start the Setup Wizard (Phase 1)
        if (getIntent().getBooleanExtra("SHOW_SETUP", false)) {
            // Logic to navigate to Setup Screen can be placed here
        }

        // Verify that the app has all required permissions to detect intruders
        checkEssentialPermissions();
    }

    /**
     * Creates the top-right options menu.
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    /**
     * Handles selection of menu items (Settings and Help).
     */
    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_settings) {
            navController.navigate(R.id.nav_settings);
            return true;
        } else if (id == R.id.action_help) {
            showHelpDialog();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    /**
     * Checks for the 4 critical permissions required for HFS to work silently.
     */
    private void checkEssentialPermissions() {
        // 1. Standard Permissions (Camera, SMS, Notifications)
        String[] permissions = {
                Manifest.permission.CAMERA,
                Manifest.permission.SEND_SMS,
                Manifest.permission.RECEIVE_SMS
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Add notification permission for Android 13+
            String[] extended = new String[4];
            System.arraycopy(permissions, 0, extended, 0, 3);
            extended[3] = Manifest.permission.POST_NOTIFICATIONS;
            permissions = extended;
        }

        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, permissions, 1001);
                break;
            }
        }

        // 2. System Overlay Permission (For the Lock Screen)
        if (!Settings.canDrawOverlays(this)) {
            showPermissionExplanation("Display Overlays", 
                    "HFS needs Overlay permission to show the lock screen when a protected app is accessed.",
                    new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        }

        // 3. Usage Stats Permission (To detect app launches)
        if (!hasUsageStatsPermission()) {
            showPermissionExplanation("Usage Access", 
                    "HFS needs Usage Access to monitor when someone tries to open your private apps.",
                    new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        }
    }

    private boolean hasUsageStatsPermission() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, 
                Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private void showPermissionExplanation(String title, String message, Intent intent) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("Grant", (dialog, which) -> startActivity(intent))
                .setNegativeButton("Exit", (dialog, which) -> finish())
                .show();
    }

    private void showHelpDialog() {
        new AlertDialog.Builder(this)
                .setTitle("HFS Help")
                .setMessage("1. Register your face in Settings.\n\n" +
                           "2. Select apps to protect in 'Protected Apps'.\n\n" +
                           "3. Set your trusted phone number for SMS alerts.\n\n" +
                           "4. Enable 'Silent Detection' on the Dashboard.")
                .setPositiveButton("Got it", null)
                .show();
    }

    @Override
    public boolean onSupportNavigateUp() {
        return navController.navigateUp() || super.onSupportNavigateUp();
    }
}