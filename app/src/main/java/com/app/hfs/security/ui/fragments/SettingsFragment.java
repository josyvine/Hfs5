package com.hfs.security.ui.fragments;

import android.app.Activity;
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

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Task;
import com.google.api.services.drive.DriveScopes;
import com.hfs.security.R;
import com.hfs.security.databinding.FragmentSettingsBinding;
import com.hfs.security.receivers.AdminReceiver;
import com.hfs.security.ui.SplashActivity;
import com.hfs.security.utils.HFSDatabaseHelper;

/**
 * Advanced Settings Screen for HFS Security.
 * UPDATED for Google Drive:
 * 1. Implemented Google Sign-In with Drive.File scope.
 * 2. Manages Cloud Sync toggle and account status.
 * 3. Maintains MPIN, Trusted Number, and Anti-Uninstall logic.
 */
public class SettingsFragment extends Fragment {

    private FragmentSettingsBinding binding;
    private HFSDatabaseHelper db;
    private DevicePolicyManager devicePolicyManager;
    private ComponentName adminComponent;

    // Google Drive Auth variables
    private GoogleSignInClient googleSignInClient;
    private ActivityResultLauncher<Intent> driveSignInLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Prepare the Google Sign-In Result Launcher
        driveSignInLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        handleSignInResult(result.getData());
                    } else {
                        Toast.makeText(getContext(), "Cloud Connection Canceled", Toast.LENGTH_SHORT).show();
                    }
                }
        );
    }

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

        setupGoogleSignInClient();
        loadSettings();
        setupListeners();
    }

    private void setupGoogleSignInClient() {
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(new Scope(DriveScopes.DRIVE_FILE))
                .build();
        googleSignInClient = GoogleSignIn.getClient(requireActivity(), gso);
    }

    private void loadSettings() {
        binding.etTrustedNumber.setText(db.getTrustedNumber());
        binding.etSecretPin.setText(db.getMasterPin());
        
        // Anti-Uninstall Status
        boolean isAdminActive = devicePolicyManager.isAdminActive(adminComponent);
        binding.switchAntiUninstall.setChecked(isAdminActive);

        // Feature Toggles
        binding.switchStealthMode.setChecked(db.isStealthModeEnabled());
        binding.switchFakeGallery.setChecked(db.isFakeGalleryEnabled());

        // Cloud Drive Status
        binding.switchCloudSync.setChecked(db.isDriveEnabled());
        updateDriveAccountUI();
    }

    private void updateDriveAccountUI() {
        String account = db.getGoogleAccount();
        if (account != null) {
            binding.tvDriveAccountStatus.setText("Connected: " + account);
            binding.btnConnectDrive.setText("Change Account");
        } else {
            binding.tvDriveAccountStatus.setText("Cloud Storage: Disconnected");
            binding.btnConnectDrive.setText("Connect Google Drive");
        }
    }

    private void setupListeners() {
        // Core Security Save
        binding.btnSaveSettings.setOnClickListener(v -> {
            String number = binding.etTrustedNumber.getText().toString().trim();
            String pin = binding.etSecretPin.getText().toString().trim();

            if (TextUtils.isEmpty(number) || pin.length() < 4) {
                Toast.makeText(getContext(), "Provide valid Number and 4-digit PIN", Toast.LENGTH_SHORT).show();
                return;
            }

            db.saveTrustedNumber(number);
            db.saveMasterPin(pin);
            db.setSetupComplete(true);
            Toast.makeText(getContext(), "HFS Credentials Updated", Toast.LENGTH_SHORT).show();
        });

        // Google Drive Connection Button
        binding.btnConnectDrive.setOnClickListener(v -> {
            Intent signInIntent = googleSignInClient.getSignInIntent();
            driveSignInLauncher.launch(signInIntent);
        });

        // Cloud Sync Toggle
        binding.switchCloudSync.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && db.getGoogleAccount() == null) {
                Toast.makeText(getContext(), "Please connect your Drive account first", Toast.LENGTH_LONG).show();
                binding.switchCloudSync.setChecked(false);
                return;
            }
            db.setDriveEnabled(isChecked);
        });

        // Stealth Mode Toggle
        binding.switchStealthMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            db.setStealthMode(isChecked);
            if (isChecked) {
                showStealthWarning();
            } else {
                setAppIconVisible(true);
            }
        });

        // Anti-Uninstall Toggle
        binding.switchAntiUninstall.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                activateDeviceAdmin();
            } else {
                deactivateDeviceAdmin();
            }
        });
    }

    private void handleSignInResult(Intent data) {
        Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
        task.addOnSuccessListener(account -> {
            db.saveGoogleAccount(account.getEmail());
            db.setDriveEnabled(true);
            binding.switchCloudSync.setChecked(true);
            updateDriveAccountUI();
            Toast.makeText(getContext(), "Drive Connected: " + account.getEmail(), Toast.LENGTH_SHORT).show();
        }).addOnFailureListener(e -> {
            Toast.makeText(getContext(), "Sign-in Failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        });
    }

    private void showStealthWarning() {
        String currentPin = db.getMasterPin();
        new AlertDialog.Builder(requireContext(), R.style.Theme_HFS_Dialog)
                .setTitle("Stealth Mode Enabled")
                .setMessage("The icon will be hidden. Dial PIN (" + currentPin + ") and press CALL to open.")
                .setPositiveButton("I UNDERSTAND", (dialog, which) -> setAppIconVisible(false))
                .setNegativeButton("CANCEL", (dialog, which) -> binding.switchStealthMode.setChecked(false))
                .setCancelable(false)
                .show();
    }

    private void setAppIconVisible(boolean visible) {
        PackageManager pm = requireContext().getPackageManager();
        ComponentName componentName = new ComponentName(requireContext(), SplashActivity.class);
        pm.setComponentEnabledSetting(componentName,
                visible ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }

    private void activateDeviceAdmin() {
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Protects HFS from being uninstalled.");
        startActivity(intent);
    }

    private void deactivateDeviceAdmin() {
        devicePolicyManager.removeActiveAdmin(adminComponent);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}