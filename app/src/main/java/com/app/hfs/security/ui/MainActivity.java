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
import com.hfs.security.utils.PermissionHelper;

/**
 * The Primary Host Activity for HFS Security.
 * This version fixes the Dashboard crash by stabilizing the Navigation Controller 
 * and properly integrating the custom Toolbar with the Fragments.
 */
public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private NavController navController;
    private HFSDatabaseHelper db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. Initialize ViewBinding
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        db = HFSDatabaseHelper.getInstance(this);

        // 2. Setup the custom Toolbar
        // Note: The crash was caused because the Theme had an Action Bar.
        // File #1 (themes.xml) fixed this, so setSupportActionBar is now safe.
        setSupportActionBar(binding.toolbar);

        // 3. Initialize Navigation Component
        NavHostFragment navHostFragment = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment);
        
        if (navHostFragment != null) {
            navController = navHostFragment.getNavController();

            // Define Top-Level Destinations
            // This prevents the "Back" arrow from appearing on the main tabs
            AppBarConfiguration appBarConfiguration = new AppBarConfiguration.Builder(
                    R.id.nav_home, 
                    R.id.nav_protected_apps, 
                    R.id.nav_history)
                    .build();

            // Link Toolbar and Bottom Navigation to the Controller
            NavigationUI.setupActionBarWithNavController(this, navController, appBarConfiguration);
            NavigationUI.setupWithNavController(binding.bottomNav, navController);
            
            // Fix for the Dashboard Crash:
            // Ensure that re-clicking the current tab doesn't recreate the fragment incorrectly
            binding.bottomNav.setOnItemReselectedListener(item -> {
                // Do nothing when the active tab is clicked again to avoid fragment stack issues
            });
        }

        // 4. Check if we need to show the Setup flow (Face registration)
        if (getIntent().getBooleanExtra("SHOW_SETUP", false)) {
            if (!db.isSetupComplete()) {
                startActivity(new Intent(this, FaceSetupActivity.class));
            }
        }

        // 5. Verify all high-security permissions
        checkRequiredSecurityPermissions();
    }

    /**
     * Inflates the 3-dot menu in the top right corner.
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
     * Handles the back button navigation when inside Settings.
     */
    @Override
    public boolean onSupportNavigateUp() {
        return navController.navigateUp() || super.onSupportNavigateUp();
    }

    /**
     * Verifies the 5 critical permissions.
     * Integrated with the Dialer fix and the Face setup fix.
     */
    private void checkRequiredSecurityPermissions() {
        // 1. Core Permissions: Camera, SMS, and Call Interception
        String[] permissions = {
                Manifest.permission.CAMERA,
                Manifest.permission.SEND_SMS,
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.PROCESS_OUTGOING_CALLS
        };

        // Notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 102);
            }
        }

        // Check if any core permission is missing
        boolean anyMissing = false;
        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                anyMissing = true;
                break;
            }
        }

        if (anyMissing) {
            ActivityCompat.requestPermissions(this, permissions, 101);
        }

        // 2. System Overlay Permission (For App Lock Screen)
        if (!Settings.canDrawOverlays(this)) {
            requestSpecialPermission("Display Overlays Required", 
                    "HFS needs Overlay permission to block access to your protected apps.",
                    new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        }

        // 3. Usage Stats Permission (For App Launch Detection)
        if (!PermissionHelper.hasUsageStatsPermission(this)) {
            requestSpecialPermission("Usage Access Required", 
                    "HFS needs Usage Access to detect when you open private apps like Gallery.",
                    new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        }
    }

    private void requestSpecialPermission(String title, String message, Intent intent) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("Grant", (dialog, which) -> startActivity(intent))
                .setNegativeButton("Exit App", (dialog, which) -> finish())
                .show();
    }

    private void showHelpDialog() {
        new AlertDialog.Builder(this)
                .setTitle("HFS Security Help")
                .setMessage("• Setup: Go to Settings to register your face and trusted number.\n\n" +
                           "• Apps: Select which apps you want to lock.\n\n" +
                           "• Stealth: If you hide the icon, dial your Secret PIN and press the Call button to open the app.\n\n" +
                           "• Alert: HFS will SMS your trusted phone if an intruder is caught.")
                .setPositiveButton("Got it", null)
                .show();
    }
}