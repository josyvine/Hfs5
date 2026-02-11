package com.hfs.security.ui;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import com.hfs.security.databinding.ActivityStealthUnlockBinding;
import com.hfs.security.utils.HFSDatabaseHelper;

import java.util.concurrent.Executor;

/**
 * NEW: Stealth Unlock Popup (Phase 8 Plan).
 * This activity is triggered when the owner clicks the 'Verified' notification 
 * after dialing their secret PIN.
 * 
 * Functions:
 * 1. Provides 'Unhide' and 'Cancel' options.
 * 2. Requires Fingerprint scan to restore the app icon.
 * 3. Launches HFS Splash screen upon successful verification.
 */
public class StealthUnlockActivity extends AppCompatActivity {

    private ActivityStealthUnlockBinding binding;
    private HFSDatabaseHelper db;
    private Executor executor;
    private BiometricPrompt biometricPrompt;
    private BiometricPrompt.PromptInfo promptInfo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Initialize ViewBinding
        binding = ActivityStealthUnlockBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        db = HFSDatabaseHelper.getInstance(this);

        // Setup the UI Listeners
        binding.btnCancel.setOnClickListener(v -> finish());
        
        binding.btnUnhide.setOnClickListener(v -> {
            // Initiate the Fingerprint scan as requested
            biometricPrompt.authenticate(promptInfo);
        });

        // Initialize Biometric components
        setupBiometricLogic();
    }

    /**
     * Configures the fingerprint scanner logic for unhiding the app.
     */
    private void setupBiometricLogic() {
        executor = ContextCompat.getMainExecutor(this);
        
        biometricPrompt = new BiometricPrompt(this, executor, new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                super.onAuthenticationError(errorCode, errString);
                Toast.makeText(StealthUnlockActivity.this, "Verification Error: " + errString, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                
                // SUCCESS: Perform the unhide and launch logic
                performUnhideAndOpen();
            }

            @Override
            public void onAuthenticationFailed() {
                super.onAuthenticationFailed();
                Toast.makeText(StealthUnlockActivity.this, "Fingerprint not recognized.", Toast.LENGTH_SHORT).show();
            }
        });

        promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("HFS Security Verification")
                .setSubtitle("Confirm fingerprint to unhide and open app")
                .setNegativeButtonText("Cancel")
                .build();
    }

    /**
     * Restores the app icon to the launcher and opens the main dashboard.
     */
    private void performUnhideAndOpen() {
        // 1. Programmatically restore the App Icon (Unhide)
        PackageManager pm = getPackageManager();
        ComponentName componentName = new ComponentName(this, SplashActivity.class);
        
        pm.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
        );

        // 2. Update the internal database state
        db.setStealthMode(false);

        // 3. Launch the App Entry point
        Intent intent = new Intent(this, SplashActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent);

        Toast.makeText(this, "HFS: Icon Restored. Identity Verified.", Toast.LENGTH_LONG).show();

        // 4. Close this popup
        finish();
    }

    @Override
    public void onBackPressed() {
        // Prevent closing via back button to maintain security flow
        super.onBackPressed();
    }
}