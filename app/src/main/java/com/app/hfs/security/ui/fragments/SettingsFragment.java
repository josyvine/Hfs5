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

import com.hfs.security.databinding.FragmentSettingsBinding;
import com.hfs.security.receivers.AdminReceiver;
import com.hfs.security.ui.FaceSetupActivity;
import com.hfs.security.ui.SplashActivity;
import com.hfs.security.utils.HFSDatabaseHelper;

/**
 * Advanced Settings Screen.
 * 1. Manages Customizable Dial Code / Secret PIN.
 * 2. Manages Trusted Alert Number.
 * 3. Handles Face Re-scan logic.
 * 4. Manages Stealth Mode and Anti-Uninstall.
 */
public class SettingsFragment extends Fragment {

    private FragmentSettingsBinding binding;
    private HFSDatabaseHelper db;
    private DevicePolicyManager devicePolicyManager;
    private ComponentName adminComponent;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // Initialize ViewBinding
        binding = FragmentSettingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        db = HFSDatabaseHelper.getInstance(requireContext());
        
        // Initialize Device Admin components for Anti-Uninstall
        devicePolicyManager = (DevicePolicyManager) requireContext().getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(requireContext(), AdminReceiver.class);

        loadSavedData();
        setupClickListeners();
    }

    /**
     * Pulls the user-customized settings from the database.
     */
    private void loadSavedData() {
        // Field 1: The phone number that receives alerts
        binding.etTrustedNumber.setText(db.getTrustedNumber());
        
        // Field 2: The customizable PIN used for SMS commands AND the Dialer
        binding.etSecretPin.setText(db.getMasterPin());
        
        // Anti-Uninstall Status
        boolean isAdminActive = devicePolicyManager.isAdminActive(adminComponent);
        binding.switchAntiUninstall.setChecked(isAdminActive);

        // Stealth and Fake Gallery Toggles
        binding.switchStealthMode.setChecked(db.isStealthModeEnabled());
        binding.switchFakeGallery.setChecked(db.isFakeGalleryEnabled());
    }

    private void setupClickListeners() {
        // SAVE BUTTON: Saves the Trusted Number and the Custom Dial Code
        binding.btnSaveSettings.setOnClickListener(v -> {
            String number = binding.etTrustedNumber.getText().toString().trim();
            String pin = binding.etSecretPin.getText().toString().trim();

            if (TextUtils.isEmpty(number) || pin.length() < 4) {
                Toast.makeText(getContext(), "Please enter a valid Phone Number and 4-digit PIN", Toast.LENGTH_SHORT).show();
                return;
            }

            // Save values - The PIN saved here is what the Dialer will now look for
            db.saveTrustedNumber(number);
            db.saveMasterPin(pin);
            Toast.makeText(getContext(), "Security Credentials Updated Successfully", Toast.LENGTH_SHORT).show();
        });

        // RESCAN BUTTON: Fixed to actually open the Face Registration screen
        binding.btnRescanFace.setOnClickListener(v -> {
            Intent intent = new Intent(requireActivity(), FaceSetupActivity.class);
            startActivity(intent);
        });

        // STEALTH MODE: Hides icon and explains the custom dial logic
        binding.switchStealthMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            db.setStealthMode(isChecked);
            if (isChecked) {
                showStealthWarningDialog();
            } else {
                toggleAppIconVisibility(true);
                Toast.makeText(getContext(), "App Icon Unhidden", Toast.LENGTH_SHORT).show();
            }
        });

        // ANTI-UNINSTALL: Device Admin logic
        binding.switchAntiUninstall.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                activateAdmin();
            } else {
                deactivateAdmin();
            }
        });

        // DECOY SYSTEM: Fake Gallery Toggle
        binding.switchFakeGallery.setOnCheckedChangeListener((buttonView, isChecked) -> {
            db.setFakeGalleryEnabled(isChecked);
        });
    }

    /**
     * Fixed Dialog: The text is now visible thanks to the Theme fix.
     */
    private void showStealthWarningDialog() {
        String currentCode = db.getMasterPin();
        
        new AlertDialog.Builder(requireContext())
                .setTitle("Stealth Mode Active")
                .setMessage("The app icon will be hidden. To open HFS, dial your secret PIN (" + currentCode + ") and press the CALL button.")
                .setPositiveButton("I UNDERSTAND", (dialog, which) -> toggleAppIconVisibility(false))
                .setNegativeButton("CANCEL", (dialog, which) -> binding.switchStealthMode.setChecked(false))
                .setCancelable(false)
                .show();
    }

    private void toggleAppIconVisibility(boolean show) {
        PackageManager pm = requireContext().getPackageManager();
        ComponentName componentName = new ComponentName(requireContext(), SplashActivity.class);
        
        int state = show ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED 
                         : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        
        pm.setComponentEnabledSetting(componentName, state, PackageManager.DONT_KILL_APP);
    }

    private void activateAdmin() {
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Protection against unauthorized uninstallation.");
        startActivity(intent);
    }

    private void deactivateAdmin() {
        devicePolicyManager.removeActiveAdmin(adminComponent);
        Toast.makeText(getContext(), "Uninstall Protection Disabled", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}