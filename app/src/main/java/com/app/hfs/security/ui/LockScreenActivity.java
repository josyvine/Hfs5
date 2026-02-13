package com.hfs.security.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricPrompt;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.hfs.security.R;
import com.hfs.security.databinding.ActivityLockScreenBinding;
import com.hfs.security.services.AppMonitorService;
import com.hfs.security.utils.FaceAuthHelper;
import com.hfs.security.utils.FileSecureHelper;
import com.hfs.security.utils.HFSDatabaseHelper;
import com.hfs.security.utils.LocationHelper;
import com.hfs.security.utils.PermissionHelper;
import com.hfs.security.utils.SmsHelper;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Security Overlay Activity.
 * UPDATED for "Zero-Fail" Plan:
 * 1. Step 1: Detects Class 3 Biometric Hardware to use system Face Unlock.
 * 2. Step 4: 1.5-Second Waterfall Handover (ML Kit to Fingerprint).
 * 3. Session Management: Signals AppMonitorService to kill re-locking loop.
 */
public class LockScreenActivity extends AppCompatActivity {

    private static final String TAG = "HFS_LockScreen";
    private ActivityLockScreenBinding binding;
    private ExecutorService cameraExecutor;
    private FaceAuthHelper faceAuthHelper;
    private HFSDatabaseHelper db;
    private String targetPackage;
    
    private boolean isActionTaken = false;
    private boolean isProcessing = false;
    
    // Step 4: 1500ms Waterfall Timer
    private final Handler waterfallHandler = new Handler(Looper.getMainLooper());

    private Executor biometricExecutor;
    private BiometricPrompt biometricPrompt;
    private BiometricPrompt.PromptInfo promptInfo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Standard HFS Security Window Flags
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        binding = ActivityLockScreenBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        db = HFSDatabaseHelper.getInstance(this);
        faceAuthHelper = new FaceAuthHelper(this);
        cameraExecutor = Executors.newSingleThreadExecutor();
        targetPackage = getIntent().getStringExtra("TARGET_APP_PACKAGE");

        // UI Setup
        binding.lockContainer.setVisibility(View.GONE);
        binding.scanningIndicator.setVisibility(View.VISIBLE);

        setupBiometricAuth();

        /*
         * STEP 1: Hardware Capability Check
         * If the phone has Class 3 (Secure 3D/IR) Face hardware, 
         * we trigger the system prompt immediately for Zero-Fail security.
         */
        if (PermissionHelper.hasClass3Biometrics(this)) {
            Log.i(TAG, "Class 3 Hardware detected. Using System Biometrics.");
            biometricPrompt.authenticate(promptInfo);
        } else {
            // FALLBACK: Use custom Normalized ML Kit logic
            startInvisibleCamera();
            startWaterfallTimer();
        }

        binding.btnUnlockPin.setOnClickListener(v -> checkPinAndUnlock());
        binding.btnFingerprint.setOnClickListener(v -> biometricPrompt.authenticate(promptInfo));
    }

    /**
     * Step 4: The Waterfall Handover.
     * If the camera logic does not confirm the owner within 1.5s, 
     * we stop 'guessing' and force the hardware fingerprint gate.
     */
    private void startWaterfallTimer() {
        waterfallHandler.postDelayed(() -> {
            if (!isActionTaken && !isFinishing()) {
                Log.w(TAG, "Waterfall: ML Kit took > 1.5s. Handing over to Fingerprint.");
                // Immediately show the biometric prompt
                biometricPrompt.authenticate(promptInfo);
            }
        }, 1500);
    }

    private void setupBiometricAuth() {
        biometricExecutor = ContextCompat.getMainExecutor(this);
        biometricPrompt = new BiometricPrompt(this, biometricExecutor, 
                new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                onSecurityVerified();
            }

            @Override
            public void onAuthenticationFailed() {
                super.onAuthenticationFailed();
                // If a thief tries their finger on the sensor, trigger alert flow
                triggerIntruderAlert(null);
            }
        });

        promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("HFS Identity Check")
                .setSubtitle("Authenticate to access your app")
                .setNegativeButtonText("Use PIN")
                .build();
    }

    private void onSecurityVerified() {
        waterfallHandler.removeCallbacksAndMessages(null);
        if (targetPackage != null) {
            // Signal service to grant 30s grace period
            AppMonitorService.unlockSession(targetPackage);
        }
        finish();
    }

    private void showDiagnosticError(String errorDetail) {
        runOnUiThread(() -> {
            new AlertDialog.Builder(this, R.style.Theme_HFS_Dialog)
                .setTitle("Biometric Identity Failure")
                .setMessage(errorDetail)
                .setCancelable(false)
                .setPositiveButton("CLOSE", (dialog, which) -> dialog.dismiss())
                .show();
        });
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

                imageAnalysis.setAnalyzer(cameraExecutor, this::processCameraFrame);

                CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                triggerIntruderAlert(null);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void processCameraFrame(@NonNull ImageProxy imageProxy) {
        if (isProcessing || isActionTaken) {
            imageProxy.close();
            return;
        }

        isProcessing = true;
        faceAuthHelper.authenticate(imageProxy, new FaceAuthHelper.AuthCallback() {
            @Override
            public void onMatchFound() {
                runOnUiThread(() -> onSecurityVerified());
            }

            @Override
            public void onMismatchFound() {
                // Known intruder caught by the 5-point triangulation math
                String diagnostic = faceAuthHelper.getLastDiagnosticInfo();
                showDiagnosticError(diagnostic);
                triggerIntruderAlert(imageProxy);
            }

            @Override
            public void onError(String error) {
                isProcessing = false;
                imageProxy.close();
            }
        });
    }

    private void triggerIntruderAlert(ImageProxy imageProxy) {
        if (isActionTaken) return;
        isActionTaken = true;
        waterfallHandler.removeCallbacksAndMessages(null);

        runOnUiThread(() -> {
            binding.scanningIndicator.setVisibility(View.GONE);
            binding.lockContainer.setVisibility(View.VISIBLE);

            if (imageProxy != null) {
                FileSecureHelper.saveIntruderCapture(LockScreenActivity.this, imageProxy);
            }

            // Fetch Location and Send Alert
            getGPSAndSendAlert();
            
            // Demand Fingerprint again for verification
            biometricPrompt.authenticate(promptInfo);
        });
    }

    private void getGPSAndSendAlert() {
        String rawAppName = getIntent().getStringExtra("TARGET_APP_NAME");
        final String finalAppName = (rawAppName == null) ? "Protected App" : rawAppName;

        LocationHelper.getDeviceLocation(this, new LocationHelper.LocationResultCallback() {
            @Override
            public void onLocationFound(String mapLink) {
                SmsHelper.sendAlertSms(LockScreenActivity.this, finalAppName, mapLink);
            }

            @Override
            public void onLocationFailed(String error) {
                SmsHelper.sendAlertSms(LockScreenActivity.this, finalAppName, "GPS Signal Error");
            }
        });
    }

    private void checkPinAndUnlock() {
        String input = binding.etPinInput.getText().toString();
        if (input.equals(db.getMasterPin())) {
            onSecurityVerified();
        } else {
            binding.tvErrorMsg.setText("Incorrect Master PIN");
            binding.etPinInput.setText("");
        }
    }

    @Override
    protected void onDestroy() {
        waterfallHandler.removeCallbacksAndMessages(null);
        cameraExecutor.shutdown();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {}
}