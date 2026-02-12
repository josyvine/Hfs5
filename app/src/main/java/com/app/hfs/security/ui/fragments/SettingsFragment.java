package com.hfs.security.ui.fragments;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.hfs.security.databinding.FragmentSettingsBinding;
import com.hfs.security.receivers.AdminReceiver;
import com.hfs.security.ui.FaceSetupActivity;
import com.hfs.security.ui.SplashActivity;
import com.hfs.security.utils.HFSDatabaseHelper;

import java.util.concurrent.Executor;

/**
 * Advanced Settings Screen.
 * UPDATED: 
 * 1. Added Biometric Gate: Fingerprint scan is now required before performing 'RE-SCAN'.
 * 2. Maintained Dual-Field Setup for Trusted Number and Secret PIN.
 * 3. Handles Anti-Uninstall and Stealth Mode toggles.
 */
public class SettingsFragment extends Fragment {

    private FragmentSettingsBinding binding;
    private HFSDatabaseHelper db;
    private DevicePolicyManager devicePolicyManager;
    private ComponentName adminComponent;

    // Biometric variables for the Re-scan Gate
    private Executor executor;
    private BiometricPrompt biometricPrompt;
    private BiometricPrompt.PromptInfo promptInfo;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentSettingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        db = HFSDatabaseHelper.getInstance(requireContext());
        
        devicePolicyManager = (DevicePolicyManager) requireContext().getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(requireContext(), AdminReceiver.class);

        // Initialize the Biometric Gate logic
        setupBiometricGate();
        
        loadSavedData();
        setupClickListeners();
    }

    private void loadSavedData() {
        binding.etTrustedNumber.setText(db.getTrustedNumber());
        binding.etSecretPin.setText(db.getMasterPin());
        
        boolean isAdminActive = devicePolicyManager.isAdminActive(adminComponent);
        binding.switchAntiUninstall.setChecked(isAdminActive);

        binding.switchStealthMode.setChecked(db.isStealthModeEnabled());
        binding.switchFakeGallery.setChecked(db.isFakeGalleryEnabled());
    }

    /**
     * Prepares the fingerprint scanner specifically for protecting the 'RE-SCAN' function.
     */
    private void setupBiometricGate() {
        executor = ContextCompat.getMainExecutor(requireContext());
        
        biometricPrompt = new BiometricPrompt(this, executor, new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                super.onAuthenticationError(errorCode, errString);
            }

            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                // GATE OPEN: Proceed to the Face Setup Activity
                Intent intent = new Intent(requireActivity(), FaceSetupActivity.class);
                startActivity(intent);
            }

            @Override
            public void onAuthenticationFailed() {
                super.onAuthenticationFailed();
                Toast.makeText(getContext(), "Authentication failed. Access Denied.", Toast.LENGTH_SHORT).show();
            }
        });

        promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Identity Verification Required")
                .setSubtitle("Confirm fingerprint to modify biometric data")
                .setNegativeButtonText("Cancel")
                .build();
    }

    private void setupClickListeners() {
        // SAVE BUTTON: Updates credentials
        binding.btnSaveSettings.setOnClickListener(v -> {
            String number = binding.etTrustedNumber.getText().toString().trim();
            String pin = binding.etSecretPin.getText().toString().trim();

            if (TextUtils.isEmpty(number) || pin.length() < 4) {
                Toast.makeText(getContext(), "Enter a valid Phone Number and 4-digit PIN", Toast.LENGTH_SHORT).show();
                return;
            }

            db.saveTrustedNumber(number);
            db.saveMasterPin(pin);
            Toast.makeText(getContext(), "Security Data Saved", Toast.LENGTH_SHORT).show();
        });

        // RE-SCAN BUTTON: Now protected by the Biometric Gate
        binding.btnRescanFace.setOnClickListener(v -> {
            // Instead of opening the camera, we first check for the owner's fingerprint
            biometricPrompt.authenticate(promptInfo);
        });

        // STEALTH MODE TOGGLE
        binding.switchStealthMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            db.setStealthMode(isChecked);
            if (isChecked) {
                showStealthWarningDialog();
            } else {
                toggleAppIconVisibility(true);
            }
        });

        // ANTI-UNINSTALL TOGGLE
        binding.switchAntiUninstall.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                activateAdmin();
            } else {
                deactivateAdmin();
            }
        });

        // DECOY SYSTEM TOGGLE
        binding.switchFakeGallery.setOnCheckedChangeListener((buttonView, isChecked) -> {
            db.setFakeGalleryEnabled(isChecked);
        });
    }

    private void showStealthWarningDialog() {
        String currentCode = db.getMasterPin();
        new AlertDialog.Builder(requireContext(), com.hfs.security.R.style.Theme_HFS_Dialog)
                .setTitle("Stealth Mode Active")
                .setMessage("Icon will be hidden. Dial " + currentCode + " and press CALL to open/unhide.")
                .setPositiveButton("I UNDERSTAND", (dialog, which) -> toggleAppIconVisibility(false))
                .setNegativeButton("CANCEL", (dialog, which) -> binding.switchStealthMode.setChecked(false))
                .setCancelable(false)
                .show();
    }

    private void toggleAppIconVisibility(boolean show) {
        PackageManager pm = requireContext().getPackageManager();
        ComponentName componentName = new ComponentName(requireContext(), SplashActivity.class);
        pm.setComponentEnabledSetting(componentName,
                show ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }

    private void activateAdmin() {
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Required for Anti-Uninstall protection.");
        startActivity(intent);
    }

    private void deactivateAdmin() {
        devicePolicyManager.removeActiveAdmin(adminComponent);
        Toast.makeText(getContext(), "Protection Disabled", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}