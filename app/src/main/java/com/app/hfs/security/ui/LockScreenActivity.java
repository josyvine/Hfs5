package com.hfs.security.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.hfs.security.R;
import com.hfs.security.databinding.ActivityLockScreenBinding;
import com.hfs.security.services.AppMonitorService;
import com.hfs.security.utils.FileSecureHelper;
import com.hfs.security.utils.HFSDatabaseHelper;
import com.hfs.security.utils.LocationHelper;
import com.hfs.security.utils.SmsHelper;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Security Overlay Activity.
 * FIXED BUILD ERRORS:
 * 1. Removed reference to 'scanningIndicator' (View was removed from XML).
 * 2. Fixed SmsHelper call to include all 4 required arguments.
 * 3. Maintains System-Native Unlock and Invisible Photo capture.
 */
public class LockScreenActivity extends AppCompatActivity {

    private static final String TAG = "HFS_LockScreen";
    private ActivityLockScreenBinding binding;
    private ExecutorService cameraExecutor;
    private HFSDatabaseHelper db;
    private String targetPackage;
    
    private boolean isActionTaken = false;
    private boolean isCameraCaptured = false;
    private ImageProxy lastCapturedFrame = null;

    private Executor biometricExecutor;
    private BiometricPrompt biometricPrompt;
    private BiometricPrompt.PromptInfo promptInfo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Signal service to prevent re-triggering while this activity is open
        AppMonitorService.isLockActive = true;

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        binding = ActivityLockScreenBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        db = HFSDatabaseHelper.getInstance(this);
        cameraExecutor = Executors.newSingleThreadExecutor();
        targetPackage = getIntent().getStringExtra("TARGET_APP_PACKAGE");

        // FIX: Removed scanningIndicator.setVisibility as it no longer exists in XML
        binding.lockContainer.setVisibility(View.VISIBLE);

        // 1. Initialize the Invisible Camera for immediate intruder capture
        startInvisibleCamera();

        // 2. Setup the System Biometric/Credential Logic
        setupSystemSecurity();

        // 3. Automatically trigger the system unlock dialog
        triggerSystemAuth();

        // Button Listeners
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
            public void onAuthenticationFailed() {
                super.onAuthenticationFailed();
                Log.w(TAG, "System security attempt failed.");
            }

            @Override
            public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                super.onAuthenticationError(errorCode, errString);
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    triggerIntruderAlert();
                }
            }
        });

        BiometricPrompt.PromptInfo.Builder builder = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("HFS Security")
                .setSubtitle("Use your phone's screen lock to unlock")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG 
                                        | BiometricManager.Authenticators.DEVICE_CREDENTIAL);

        promptInfo = builder.build();
    }

    private void triggerSystemAuth() {
        try {
            biometricPrompt.authenticate(promptInfo);
        } catch (Exception e) {
            Log.e(TAG, "System Auth Unavailable: " + e.getMessage());
        }
    }

    private void onOwnerVerified() {
        AppMonitorService.isLockActive = false;
        if (targetPackage != null) {
            AppMonitorService.unlockSession(targetPackage);
        }
        
        if (lastCapturedFrame != null) {
            lastCapturedFrame.close();
        }
        
        finish();
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
                        lastCapturedFrame = image;
                    } else {
                        image.close();
                    }
                });

                CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "CameraX Error: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void checkMpinAndUnlock() {
        String input = binding.etPinInput.getText().toString();
        if (input.equals(db.getMasterPin())) {
            onOwnerVerified();
        } else {
            binding.tvErrorMsg.setText("Incorrect HFS MPIN");
            binding.etPinInput.setText("");
            triggerIntruderAlert();
        }
    }

    private void triggerIntruderAlert() {
        if (isActionTaken) return;
        isActionTaken = true;

        runOnUiThread(() -> {
            if (lastCapturedFrame != null) {
                FileSecureHelper.saveIntruderCapture(LockScreenActivity.this, lastCapturedFrame);
            }

            fetchLocationAndSendAlert();
            Toast.makeText(this, "⚠ Security Breach Recorded", Toast.LENGTH_LONG).show();
        });
    }

    private void fetchLocationAndSendAlert() {
        String appName = getIntent().getStringExtra("TARGET_APP_NAME");
        final String finalAppName = (appName == null) ? "a Protected App" : appName;

        LocationHelper.getDeviceLocation(this, new LocationHelper.LocationResultCallback() {
            @Override
            public void onLocationFound(String mapLink) {
                // FIX: Added the 4th argument "Security Breach" to match SmsHelper signature
                SmsHelper.sendAlertSms(LockScreenActivity.this, finalAppName, mapLink, "Security Breach");
            }

            @Override
            public void onLocationFailed(String error) {
                // FIX: Added the 4th argument "Security Breach" to match SmsHelper signature
                SmsHelper.sendAlertSms(LockScreenActivity.this, finalAppName, "GPS Unavailable", "Security Breach");
            }
        });
    }

    @Override
    protected void onDestroy() {
        cameraExecutor.shutdown();
        AppMonitorService.isLockActive = false;
        if (lastCapturedFrame != null) {
            lastCapturedFrame.close();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {}
}