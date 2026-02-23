package com.hfs.security.ui;

import android.Manifest;
import android.app.KeyguardManager;
import android.app.WallpaperManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import androidx.core.app.ActivityCompat;
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
 * FEATURES:
 * 1. "Chameleon" UI: Native wallpaper background (Android 9 safe).
 * 2. Stable Cloud Sync: Fixed background timing for Drive URLs.
 * 3. Fearful SOS: Uses ToneGenerator for hardware emergency signals.
 * 4. Robust Security: Task Manager bypass prevention.
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

    // --- THEFT MODE & SOS ---
    private boolean isTheftMode = false;
    private ToneGenerator toneGenerator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Notify Service that lock screen is active
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
        
        // Mode Switch: Check if triggered by Hardware Watchdogs
        isTheftMode = "THEFT_MODE".equals(getIntent().getStringExtra("EXTRA_MODE"));

        if (isTheftMode) {
            // Activate aggressive SOS logic
            activateFearfulSiren();
        } else {
            // Normal App Lock: Apply native look
            applySystemWallpaperBackground();
        }

        binding.lockContainer.setVisibility(View.VISIBLE);

        // 1. Start background capture
        startInvisibleCamera();

        // 2. Setup Security
        setupSystemSecurity();

        // 3. Start authentication
        triggerSystemAuth();

        binding.btnUnlockPin.setOnClickListener(v -> checkMpinAndUnlock());
        binding.btnFingerprint.setOnClickListener(v -> triggerSystemAuth());
    }

    /**
     * SOS INVENTION:
     * Uses hardware-level ToneGenerator to create a fearful, high-pitched 
     * emergency alarm that bypasses normal ringtone settings.
     */
    private void activateFearfulSiren() {
        try {
            binding.lockContainer.setBackgroundColor(Color.parseColor("#AA0000"));

            WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
            layoutParams.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL;
            getWindow().setAttributes(layoutParams);

            AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (audioManager != null) {
                int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM);
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0);
            }

            // Initialize hardware ToneGenerator on the ALARM stream at 100% volume
            toneGenerator = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
            
            // Continuous high-pitched emergency ringback tone
            toneGenerator.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK);

        } catch (Exception e) {
            Log.e(TAG, "SOS Alarm failed: " + e.getMessage());
        }
    }

    private void stopFearfulSiren() {
        if (toneGenerator != null) {
            toneGenerator.stopTone();
            toneGenerator.release();
            toneGenerator = null;
        }
    }

    /**
     * Chameleon UI for Android 9 & 10.
     */
    private void applySystemWallpaperBackground() {
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) 
                    == PackageManager.PERMISSION_GRANTED) {
                
                WallpaperManager wallpaperManager = WallpaperManager.getInstance(this);
                Drawable wallpaper;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    wallpaper = wallpaperManager.getDrawable(WallpaperManager.FLAG_LOCK);
                    if (wallpaper == null) wallpaper = wallpaperManager.getDrawable(WallpaperManager.FLAG_SYSTEM);
                } else {
                    wallpaper = wallpaperManager.getDrawable();
                }

                if (wallpaper != null) {
                    binding.lockContainer.setBackground(wallpaper);
                    binding.lockContainer.getBackground().setAlpha(230);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Wallpaper failure: " + e.getMessage());
        }
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
            
            @Override
            public void onAuthenticationFailed() {
                super.onAuthenticationFailed();
                triggerIntruderAlert();
            }
        });

        BiometricPrompt.PromptInfo.Builder builder = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("HFS Security")
                .setSubtitle("Unlock to proceed");

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

        // DRIVE LINK FIX:
        // We only send the SMS immediately if Drive is NOT ready.
        // If Drive IS ready, we let the background thread handle the SMS to ensure the link is included.
        if (isDriveReady && isNetworkAvailable()) {
            uploadToCloudAndSms(appName, mapLink);
        } else {
            if (isDriveReady) queueBackgroundUpload();
            SmsHelper.sendAlertSms(getApplicationContext(), appName, mapLink, "Security Breach", null);
        }

        runOnUiThread(() -> {
            Toast.makeText(this, "⚠ Security Breach Recorded", Toast.LENGTH_LONG).show();
            isActionTaken = false; 
            triggerSystemAuth();
        });
    }

    private void uploadToCloudAndSms(String appName, String mapLink) {
        cameraExecutor.execute(() -> {
            try {
                if (intruderFile == null || !intruderFile.exists()) {
                    SmsHelper.sendAlertSms(getApplicationContext(), appName, mapLink, "Security Breach", null);
                    return;
                }

                GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(getApplicationContext());
                if (account == null) throw new Exception("Google Account Disconnected");

                GoogleAccountCredential credential = GoogleAccountCredential.usingOAuth2(
                        getApplicationContext(), Collections.singleton(DriveScopes.DRIVE_FILE));
                credential.setSelectedAccount(account.getAccount());

                Drive driveService = new Drive.Builder(
                        AndroidHttp.newCompatibleTransport(),
                        new GsonFactory(),
                        credential)
                        .setApplicationName("HFS Security")
                        .build();

                DriveHelper driveHelper = new DriveHelper(getApplicationContext(), driveService);
                
                // CRITICAL FIX: The SMS is now strictly inside this block.
                // It will WAIT here until the link is returned from Google Drive.
                String driveLink = driveHelper.uploadFileAndGetLink(intruderFile);
                
                SmsHelper.sendAlertSms(getApplicationContext(), appName, mapLink, "Security Breach", driveLink);

            } catch (Exception e) {
                Log.e(TAG, "Cloud upload error: " + e.getMessage());
                queueBackgroundUpload();
                // Send SMS with null link only if the upload definitively failed
                SmsHelper.sendAlertSms(getApplicationContext(), appName, mapLink, "Security Breach", null);
            }
        });
    }

    private void queueBackgroundUpload() {
        if (intruderFile == null) return;
        Data inputData = new Data.Builder().putString("file_path", intruderFile.getAbsolutePath()).build();
        OneTimeWorkRequest uploadRequest = new OneTimeWorkRequest.Builder(DriveUploadWorker.class).setInputData(inputData).build();
        WorkManager.getInstance(this).enqueue(uploadRequest);
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnectedOrConnecting();
    }

    private void startInvisibleCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
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
                        intruderFile = FileSecureHelper.saveIntruderCaptureAndGetFile(this, image);
                        image.close();
                    } else {
                        image.close();
                    }
                });

                CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);
            } catch (Exception e) {
                Log.e(TAG, "CameraX Error");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void onOwnerVerified() {
        stopFearfulSiren(); // Kill noise instantly
        HFSAccessibilityService.isLockActive = false;
        if (targetPackage != null) {
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
    protected void onStop() {
        super.onStop();
        HFSAccessibilityService.isLockActive = false;
    }

    @Override
    protected void onDestroy() {
        stopFearfulSiren(); // Clean up hardware tones
        cameraExecutor.shutdown();
        HFSAccessibilityService.isLockActive = false;
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {}
}