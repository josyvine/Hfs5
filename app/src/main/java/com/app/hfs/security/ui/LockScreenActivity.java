package com.hfs.security.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricPrompt;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.hfs.security.databinding.ActivityLockScreenBinding;
import com.hfs.security.utils.FaceAuthHelper;
import com.hfs.security.utils.FileSecureHelper;
import com.hfs.security.utils.HFSDatabaseHelper;
import com.hfs.security.utils.SmsHelper;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Security Overlay Activity (Lock Screen).
 * FIXED: Implemented a 2000ms (2s) strict timeout to prevent the 'Verifying Identity' loop.
 * If the owner is not recognized instantly, the system locks the app and logs the intruder.
 */
public class LockScreenActivity extends AppCompatActivity {

    private static final String TAG = "HFS_LockScreen";
    private ActivityLockScreenBinding binding;
    private ExecutorService cameraExecutor;
    private FaceAuthHelper faceAuthHelper;
    private HFSDatabaseHelper db;
    
    private boolean isProcessing = false;
    private boolean isActionTaken = false;
    private final Handler watchdogHandler = new Handler(Looper.getMainLooper());

    // Biometric (Fingerprint) Variables
    private Executor biometricExecutor;
    private BiometricPrompt biometricPrompt;
    private BiometricPrompt.PromptInfo promptInfo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Flag setting for Oppo/Realme to ensure overlay appears over everything
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        binding = ActivityLockScreenBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        db = HFSDatabaseHelper.getInstance(this);
        faceAuthHelper = new FaceAuthHelper(this);
        cameraExecutor = Executors.newSingleThreadExecutor();

        // 1. Initial UI State (Hidden lock, active scanning)
        binding.lockContainer.setVisibility(View.GONE);
        binding.scanningIndicator.setVisibility(View.VISIBLE);

        // 2. Initialize Biometrics and Camera
        setupBiometricAuth();
        startInvisibleCamera();

        // 3. FIX: THE WATCHDOG TIMER (2 Seconds)
        // This is what stops the 'Verifying Identity' loop. 
        // If after 2 seconds no 'Match' is found, we trigger the lock.
        watchdogHandler.postDelayed(() -> {
            if (!isActionTaken && !isFinishing()) {
                Log.w(TAG, "Watchdog: Identity verification timed out. Triggering Lockdown.");
                handleIntrusionDetection(null); 
            }
        }, 2000);

        // Button Listeners
        binding.btnUnlockPin.setOnClickListener(v -> checkPinAndUnlock());
        binding.btnFingerprint.setOnClickListener(v -> biometricPrompt.authenticate(promptInfo));
    }

    private void setupBiometricAuth() {
        biometricExecutor = ContextCompat.getMainExecutor(this);
        biometricPrompt = new BiometricPrompt(this, biometricExecutor, 
                new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                // Owner used fingerprint - Close lock screen
                finish();
            }
        });

        promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("HFS Security")
                .setSubtitle("Confirm fingerprint to unlock")
                .setNegativeButtonText("Use PIN")
                .build();
    }

    private void startInvisibleCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = 
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                // Setup 1x1 invisible preview
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(binding.invisiblePreview.getSurfaceProvider());

                // Setup Image Analysis
                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, this::processCameraFrame);

                CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "CameraX Initialization failed: " + e.getMessage());
                // If camera hardware fails, trigger lock for safety
                handleIntrusionDetection(null);
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
                // SUCCESS: Owner identified. Cancel timeout and close.
                watchdogHandler.removeCallbacksAndMessages(null);
                runOnUiThread(() -> finish());
            }

            @Override
            public void onMismatchFound() {
                // FAILURE: Unknown face (Mom/Intruder). Lock immediately.
                handleIntrusionDetection(imageProxy);
            }

            @Override
            public void onError(String error) {
                isProcessing = false;
                imageProxy.close();
            }
        });
    }

    /**
     * Phase 3 Logic: Triggers the intruder UI, saves photo, and sends SMS.
     */
    private void handleIntrusionDetection(ImageProxy imageProxy) {
        if (isActionTaken) return;
        isActionTaken = true;

        // Clear the 2s timer as we are taking action now
        watchdogHandler.removeCallbacksAndMessages(null);

        runOnUiThread(() -> {
            // 1. Update UI to Forbidden State
            binding.scanningIndicator.setVisibility(View.GONE);
            binding.lockContainer.setVisibility(View.VISIBLE);
            
            // 2. Secretly save the intruder's photo if available
            if (imageProxy != null) {
                FileSecureHelper.saveIntruderCapture(LockScreenActivity.this, imageProxy);
            }

            // 3. Send the Alert SMS to the Trusted Number
            String appName = getIntent().getStringExtra("TARGET_APP_NAME");
            if (appName == null) appName = "Protected App";
            SmsHelper.sendAlertSms(LockScreenActivity.this, appName);

            Toast.makeText(this, "⚠ Unauthorized Access Detected", Toast.LENGTH_LONG).show();
            
            // 4. Force biometric prompt for the owner to regain control
            biometricPrompt.authenticate(promptInfo);
        });
    }

    private void checkPinAndUnlock() {
        String input = binding.etPinInput.getText().toString();
        if (input.equals(db.getMasterPin())) {
            finish();
        } else {
            binding.tvErrorMsg.setText("Invalid PIN. Access Denied.");
            binding.etPinInput.setText("");
        }
    }

    @Override
    protected void onDestroy() {
        watchdogHandler.removeCallbacksAndMessages(null);
        cameraExecutor.shutdown();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Prevent back button bypass
    }
}