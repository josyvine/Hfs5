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
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
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
import com.hfs.security.utils.CryptoManager;
import com.hfs.security.utils.HFSDatabaseHelper;
import com.hfs.security.utils.SimManager;

import java.util.concurrent.Executor;

/**
 * Advanced Settings Screen for HFS Security.
 * UPDATED: Integrated Anti-Theft UI (Encrypted Emergency Number & Dual SIM Vault)
 * without altering existing Google Drive or App Lock logic.
 */
public class SettingsFragment extends Fragment {

    private FragmentSettingsBinding binding;
    private HFSDatabaseHelper db;
    private DevicePolicyManager devicePolicyManager;
    private ComponentName adminComponent;

    // Google Drive Auth variables
    private GoogleSignInClient googleSignInClient;
    private ActivityResultLauncher<Intent> driveSignInLauncher;

    // --- NEW: ANTI-THEFT VARIABLES ---
    private CryptoManager cryptoManager;
    private SimManager simManager;
    private Executor biometricExecutor;
    private BiometricPrompt biometricPrompt;
    private BiometricPrompt.PromptInfo promptInfo;

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

        // Initialize new Hardware Security Managers
        cryptoManager = new CryptoManager();
        simManager = new SimManager(requireContext());

        setupGoogleSignInClient();
        loadSettings();
        setupListeners();
        
        // --- NEW: Initialize Anti-Theft Interface ---
        setupBiometricsForEditing();
        setupAntiTheftUI();
    }

    private void setupGoogleSignInClient() {
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(new Scope(DriveScopes.DRIVE_FILE))
                .build();
        googleSignInClient = GoogleSignIn.getClient(requireActivity(), gso);
    }

    private void loadSettings() {
        // Legacy Plain Text loading (Untouched)
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

    // =========================================================================
    // --- NEW: ANTI-THEFT UI METHODS (Does not affect code above) ---
    // =========================================================================

    private void setupBiometricsForEditing() {
        biometricExecutor = ContextCompat.getMainExecutor(requireContext());
        biometricPrompt = new BiometricPrompt(this, biometricExecutor, new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                unlockEmergencyField();
            }

            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                super.onAuthenticationError(errorCode, errString);
                Toast.makeText(getContext(), "Authentication required to edit", Toast.LENGTH_SHORT).show();
            }
        });

        promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Security Verification")
                .setSubtitle("Verify identity to access Hardware Vault")
                .setNegativeButtonText("Cancel")
                .build();
    }

    private void setupAntiTheftUI() {
        // Prevent crashes if UI elements aren't added to XML yet
        if (binding.switchAntiTheft == null || binding.etEmergencyNumber == null) return;

        // 1. Master Anti-Theft Switch
        binding.switchAntiTheft.setChecked(db.isAntiTheftEnabled());
        binding.switchAntiTheft.setOnCheckedChangeListener((btn, isChecked) -> {
            db.setAntiTheftEnabled(isChecked);
            if (isChecked) {
                Toast.makeText(getContext(), "Hardware Watchdogs Armed", Toast.LENGTH_SHORT).show();
            }
        });

        // 2. Encrypted Emergency Number Initial State
        lockEmergencyField();

        // 3. Edit / Cancel Button
        binding.btnEditEmergency.setOnClickListener(v -> {
            if (binding.btnEditEmergency.getText().toString().equals("UNLOCK")) {
                biometricPrompt.authenticate(promptInfo);
            } else {
                // If already unlocked, clicking it again cancels the edit
                lockEmergencyField();
            }
        });

        // 4. Save & Encrypt Button
        binding.btnSaveEmergency.setOnClickListener(v -> {
            String rawNumber = binding.etEmergencyNumber.getText().toString().trim();
            if (rawNumber.length() < 5) {
                Toast.makeText(getContext(), "Invalid Number", Toast.LENGTH_SHORT).show();
                return;
            }

            String cipherText = cryptoManager.encrypt(rawNumber);
            if (cipherText != null) {
                db.saveEncryptedEmergencyNumber(cipherText);
                Toast.makeText(getContext(), "Number Encrypted & Locked in Vault", Toast.LENGTH_LONG).show();
                lockEmergencyField();
            } else {
                Toast.makeText(getContext(), "Hardware Encryption Failed", Toast.LENGTH_SHORT).show();
            }
        });

        // 5. Update Trusted SIMs Button
        binding.btnUpdateSims.setOnClickListener(v -> {
            if (simManager.hasPhoneStatePermission(requireContext())) {
                simManager.scanAndEncryptCurrentSims();
                Toast.makeText(getContext(), "Current SIMs Scanned & Encrypted", Toast.LENGTH_SHORT).show();
            } else {
                simManager.requestPhoneStatePermission(requireActivity());
            }
        });
    }

    private void unlockEmergencyField() {
        String cipherText = db.getEncryptedEmergencyNumber();
        if (cipherText != null) {
            String plainText = cryptoManager.decrypt(cipherText);
            binding.etEmergencyNumber.setText(plainText != null ? plainText : "");
        } else {
            binding.etEmergencyNumber.setText("");
        }
        
        binding.etEmergencyNumber.setEnabled(true);
        binding.btnEditEmergency.setText("CANCEL");
        binding.btnSaveEmergency.setVisibility(View.VISIBLE);
    }

    private void lockEmergencyField() {
        String encryptedNum = db.getEncryptedEmergencyNumber();
        
        binding.etEmergencyNumber.setEnabled(false);
        binding.btnSaveEmergency.setVisibility(View.GONE);
        binding.btnEditEmergency.setText("UNLOCK");
        
        if (encryptedNum != null && !encryptedNum.isEmpty()) {
            binding.etEmergencyNumber.setText("•••• ••• •••"); // Masked
        } else {
            binding.etEmergencyNumber.setText("");
            binding.etEmergencyNumber.setHint("Tap UNLOCK to setup");
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}