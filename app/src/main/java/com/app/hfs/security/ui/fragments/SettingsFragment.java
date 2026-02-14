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
import androidx.fragment.app.Fragment;

import com.hfs.security.R;
import com.hfs.security.databinding.FragmentSettingsBinding;
import com.hfs.security.receivers.AdminReceiver;
import com.hfs.security.ui.SplashActivity;
import com.hfs.security.utils.HFSDatabaseHelper;

/**
 * Advanced Settings Screen for HFS Security.
 * FIXED: 
 * 1. Sets 'Setup Complete' flag upon saving credentials to stop the toast loop.
 * 2. Removed all dead references to Face/Rescan logic.
 * 3. Manages MPIN, Trusted Number, Stealth Mode, and Anti-Uninstall.
 */
public class SettingsFragment extends Fragment {

    private FragmentSettingsBinding binding;
    private HFSDatabaseHelper db;
    private DevicePolicyManager devicePolicyManager;
    private ComponentName adminComponent;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // Initialize ViewBinding for the settings layout
        binding = FragmentSettingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        db = HFSDatabaseHelper.getInstance(requireContext());
        
        // Initialize Device Admin components to prevent unauthorized uninstallation
        devicePolicyManager = (DevicePolicyManager) requireContext().getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(requireContext(), AdminReceiver.class);

        // CLEANUP: Ensure the Rescan button is hidden as it is no longer used in the Zero-Fail plan
        if (binding.btnRescanFace != null) {
            binding.btnRescanFace.setVisibility(View.GONE);
        }

        loadSettings();
        setupListeners();
    }

    /**
     * Fills the UI fields with currently saved security data.
     */
    private void loadSettings() {
        // Load the secondary phone number that receives alert SMS
        binding.etTrustedNumber.setText(db.getTrustedNumber());
        
        // Load the Master PIN (MPIN)
        String currentPin = db.getMasterPin();
        if (!currentPin.equals("0000")) {
            binding.etSecretPin.setText(currentPin);
        }
        
        // Check if the phone's Device Admin is active for HFS
        boolean isAdminActive = devicePolicyManager.isAdminActive(adminComponent);
        binding.switchAntiUninstall.setChecked(isAdminActive);

        // Load feature toggles for Stealth and Decoy modes
        binding.switchStealthMode.setChecked(db.isStealthModeEnabled());
        binding.switchFakeGallery.setChecked(db.isFakeGalleryEnabled());
    }

    /**
     * Configures the click and toggle listeners for all settings.
     */
    private void setupListeners() {
        // 1. SAVE BUTTON: The primary trigger to set up security
        binding.btnSaveSettings.setOnClickListener(v -> {
            String number = binding.etTrustedNumber.getText().toString().trim();
            String pin = binding.etSecretPin.getText().toString().trim();

            if (TextUtils.isEmpty(number) || pin.length() < 4) {
                Toast.makeText(getContext(), "Provide a valid Trusted Number and 4-digit PIN", Toast.LENGTH_SHORT).show();
                return;
            }

            // Save to persistent storage
            db.saveTrustedNumber(number);
            db.saveMasterPin(pin);
            
            // FIX: Mark setup as complete so the 'Welcome' toast in MainActivity disappears
            db.setSetupComplete(true);
            
            Toast.makeText(getContext(), "HFS Security Configured Successfully", Toast.LENGTH_SHORT).show();
        });

        // 2. STEALTH MODE TOGGLE (Dialer Launch)
        binding.switchStealthMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            db.setStealthMode(isChecked);
            if (isChecked) {
                showStealthWarning();
            } else {
                setAppIconVisible(true);
                Toast.makeText(getContext(), "App Icon Unhidden", Toast.LENGTH_SHORT).show();
            }
        });

        // 3. ANTI-UNINSTALL TOGGLE (Device Admin)
        binding.switchAntiUninstall.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                activateDeviceAdmin();
            } else {
                deactivateDeviceAdmin();
            }
        });

        // 4. DECOY SYSTEM TOGGLE (Fake Gallery)
        binding.switchFakeGallery.setOnCheckedChangeListener((buttonView, isChecked) -> {
            db.setFakeGalleryEnabled(isChecked);
        });
    }

    /**
     * Alerts the user that the icon will vanish and reminds them of the dialer PIN.
     */
    private void showStealthWarning() {
        String currentPin = db.getMasterPin();
        new AlertDialog.Builder(requireContext(), R.style.Theme_HFS_Dialog)
                .setTitle("Stealth Mode Enabled")
                .setMessage("The app icon will be hidden. To open HFS, dial your PIN (" + currentPin + ") and press CALL.")
                .setPositiveButton("I UNDERSTAND", (dialog, which) -> setAppIconVisible(false))
                .setNegativeButton("CANCEL", (dialog, which) -> binding.switchStealthMode.setChecked(false))
                .setCancelable(false)
                .show();
    }

    /**
     * Uses the System PackageManager to enable/disable the launcher icon.
     */
    private void setAppIconVisible(boolean visible) {
        PackageManager pm = requireContext().getPackageManager();
        ComponentName componentName = new ComponentName(requireContext(), SplashActivity.class);
        
        int state = visible ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED 
                           : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        
        pm.setComponentEnabledSetting(componentName, state, PackageManager.DONT_KILL_APP);
    }

    private void activateDeviceAdmin() {
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Required to prevent intruders from uninstalling HFS.");
        startActivity(intent);
    }

    private void deactivateDeviceAdmin() {
        devicePolicyManager.removeActiveAdmin(adminComponent);
        Toast.makeText(getContext(), "Anti-Uninstall Protection Disabled", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}