package com.hfs.security.ui;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.common.util.concurrent.ListenableFuture;

import com.hfs.security.databinding.ActivityLockScreenBinding;
import com.hfs.security.services.DriveUploadWorker;
import com.hfs.security.services.HFSAccessibilityService;
import com.hfs.security.utils.DriveHelper;
import com.hfs.security.utils.FileSecureHelper;
import com.hfs.security.utils.HFSDatabaseHelper;
import com.hfs.security.utils.LocationHelper;
import com.hfs.security.utils.SmsHelper;

import java.io.File;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Security Overlay Activity.
 * UPDATED: Now communicates with HFSAccessibilityService for session management.
 * 
 * Logic Preserved:
 * 1. Biometric/PIN Authentication.
 * 2. Background Intruder Capture.
 * 3. Google Drive Cloud Sync.
 * 4. SMS Alerting.
 */
public class LockScreenActivity extends AppCompatActivity {

    private static final String TAG = "HFS_LockScreen";
    private static final int SYSTEM_CREDENTIAL_REQUEST_CODE = 505;

    private ActivityLockScreenBinding binding;
    private ExecutorService cameraExecutor;
    private HFSDatabaseHelper db;
    private String targetPackage;
    
    private boolean isActionTaken = false;
    private boolean isCameraCaptured = false;
    private File intruderFile = null;

    private Executor biometricExecutor;
    private BiometricPrompt biometricPrompt;
    private BiometricPrompt.PromptInfo promptInfo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Notify Service that lock screen is visible
        HFSAccessibilityService.isLockActive = true;

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        binding = ActivityLockScreenBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        db = HFSDatabaseHelper.getInstance(this);
        cameraExecutor = Executors.newSingleThreadExecutor();
        targetPackage = getIntent().getStringExtra("TARGET_APP_PACKAGE");

        binding.lockContainer.setVisibility(View.VISIBLE);

        // 1. Initialize background camera capture
        startInvisibleCamera();

        // 2. Configure System Biometrics
        setupSystemSecurity();

        // 3. Start authentication immediately
        triggerSystemAuth();

        binding.btnUnlockPin.setOnClickListener(v -> checkMpinAndUnlock());
        
        binding.btnFingerprint.setOnClickListener(v -> triggerSystemAuth());
    }

    private void setupSystemSecurity() {
        biometricExecutor = ContextCompat.getMainExecutor(this);
        biometricPrompt = new BiometricPrompt(this, biometricExecutor, 
                new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                onOwnerVerified();
            }

            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                super.onAuthenticationError(errorCode, errString);
                if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    showSystemCredentialPicker();
                } else if (errorCode != BiometricPrompt.ERROR_USER_CANCELED) {
                    triggerIntruderAlert();
                }
            }
        });

        BiometricPrompt.PromptInfo.Builder builder = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("HFS Security")
                .setSubtitle("Authenticate to access your app");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG 
                                            | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
        } else {
            builder.setNegativeButtonText("Use System PIN");
        }

        promptInfo = builder.build();
    }

    private void triggerSystemAuth() {
        try {
            biometricPrompt.authenticate(promptInfo);
        } catch (Exception e) {
            showSystemCredentialPicker();
        }
    }

    private void triggerIntruderAlert() {
        if (isActionTaken) return;
        isActionTaken = true;

        LocationHelper.getDeviceLocation(this, new LocationHelper.LocationResultCallback() {
            @Override
            public void onLocationFound(String mapLink) {
                processIntruderResponse(mapLink);
            }

            @Override
            public void onLocationFailed(String error) {
                processIntruderResponse("GPS Signal Lost");
            }
        });
    }

    private void processIntruderResponse(String mapLink) {
        String appName = getIntent().getStringExtra("TARGET_APP_NAME");
        if (appName == null) appName = "Protected Files";

        boolean isDriveReady = db.isDriveEnabled() && db.getGoogleAccount() != null;

        if (isDriveReady && isNetworkAvailable()) {
            uploadToCloudAndSms(appName, mapLink);
        } else if (isDriveReady) {
            queueBackgroundUpload();
            SmsHelper.sendAlertSms(this, appName, mapLink, "Security Breach", null);
        } else {
            SmsHelper.sendAlertSms(this, appName, mapLink, "Security Breach", null);
        }

        runOnUiThread(() -> {
            binding.lockContainer.setVisibility(View.VISIBLE);
            Toast.makeText(this, "⚠ Security Breach Recorded", Toast.LENGTH_LONG).show();
            biometricPrompt.authenticate(promptInfo);
        });
    }

    private void uploadToCloudAndSms(String appName, String mapLink) {
        cameraExecutor.execute(() -> {
            try {
                if (intruderFile == null || !intruderFile.exists()) return;

                GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(this);
                if (account == null) throw new Exception("Google Account Disconnected");

                GoogleAccountCredential credential = GoogleAccountCredential.usingOAuth2(
                        this, Collections.singleton(DriveScopes.DRIVE_FILE));
                credential.setSelectedAccount(account.getAccount());

                Drive driveService = new Drive.Builder(
                        AndroidHttp.newCompatibleTransport(),
                        new GsonFactory(),
                        credential)
                        .setApplicationName("HFS Security")
                        .build();

                DriveHelper driveHelper = new DriveHelper(this, driveService);
                String driveLink = driveHelper.uploadFileAndGetLink(intruderFile);

                SmsHelper.sendAlertSms(this, appName, mapLink, "Security Breach", driveLink);

            } catch (Exception e) {
                Log.e(TAG, "Cloud upload failed: " + e.getMessage());
                queueBackgroundUpload();
                SmsHelper.sendAlertSms(this, appName, mapLink, "Security Breach", null);
            }
        });
    }

    private void queueBackgroundUpload() {
        if (intruderFile == null) return;

        Data inputData = new Data.Builder()
                .putString("file_path", intruderFile.getAbsolutePath())
                .build();

        OneTimeWorkRequest uploadRequest = new OneTimeWorkRequest.Builder(DriveUploadWorker.class)
                .setInputData(inputData)
                .build();

        WorkManager.getInstance(this).enqueue(uploadRequest);
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnectedOrConnecting();
    }

    private void startInvisibleCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = 
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(binding.invisiblePreview.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, image -> {
                    if (!isCameraCaptured) {
                        isCameraCaptured = true;
                        // FIX: Calling the new method that returns the File object
                        intruderFile = FileSecureHelper.saveIntruderCaptureAndGetFile(this, image);
                        image.close();
                    } else {
                        image.close();
                    }
                });

                CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "CameraX Initialization Error");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void onOwnerVerified() {
        // Reset lock state in the Service
        HFSAccessibilityService.isLockActive = false;
        
        if (targetPackage != null) {
            // Signal the Service to whitelist this app for 10 seconds
            HFSAccessibilityService.unlockSession(targetPackage);
        }
        finish();
    }

    private void showSystemCredentialPicker() {
        KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        if (km != null && km.isDeviceSecure()) {
            Intent intent = km.createConfirmDeviceCredentialIntent("HFS Security", "Authenticate to proceed");
            if (intent != null) startActivityForResult(intent, SYSTEM_CREDENTIAL_REQUEST_CODE);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SYSTEM_CREDENTIAL_REQUEST_CODE) {
            if (resultCode == RESULT_OK) onOwnerVerified();
            else triggerIntruderAlert();
        }
    }

    private void checkMpinAndUnlock() {
        if (binding.etPinInput.getText().toString().equals(db.getMasterPin())) onOwnerVerified();
        else {
            binding.tvErrorMsg.setText("Incorrect HFS MPIN");
            binding.etPinInput.setText("");
            triggerIntruderAlert();
        }
    }

    @Override
    protected void onDestroy() {
        cameraExecutor.shutdown();
        // Ensure lock flag is cleared when activity is destroyed
        HFSAccessibilityService.isLockActive = false;
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {}
}