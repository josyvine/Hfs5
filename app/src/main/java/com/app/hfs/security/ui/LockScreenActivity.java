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
 * FIXED:
 * 1. Rapid-Fire SOS: Removed 5-second delay using a high-frequency Handler loop.
 * 2. Drive URL: Ensuring SMS waits for background upload completion.
 * 3. Chameleon UI: Android 9 safe wallpaper extraction.
 * 4. Task Manager Bypass: Added onPause and onUserLeaveHint for instant flag reset.
 * 5. INFINITE LOOP CRASH FIX: Prevents BiometricPrompt from restarting when hardware is locked out.
 * 6. TASK BINDING SUPPORT: Optimized lifecycle to work with Manifest taskAffinity glue.
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

    // --- THEFT MODE & RAPID SOS ---
    private boolean isTheftMode = false;
    private ToneGenerator toneGenerator;
    private Handler sosHandler = new Handler(Looper.getMainLooper());
    private Runnable sosRunnable;

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
        
        // Check for hardware breach mode
        isTheftMode = "THEFT_MODE".equals(getIntent().getStringExtra("EXTRA_MODE"));

        if (isTheftMode) {
            activateFearfulSiren();
        } else {
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
     * RAPID SOS INNOVATION:
     * Uses a looping Runnable to oscillate the hardware ToneGenerator every 200ms.
     * This creates a fearful "rapid-fire" emergency beep that cannot be silenced 
     * by volume buttons and has zero silence delay.
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

            toneGenerator = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
            
            sosRunnable = new Runnable() {
                @Override
                public void run() {
                    if (toneGenerator != null) {
                        // High-pitched guard alert tone for 200ms
                        toneGenerator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200);
                        // Repeat every 400ms (200ms on, 200ms off)
                        sosHandler.postDelayed(this, 400);
                    }
                }
            };
            sosHandler.post(sosRunnable);

        } catch (Exception e) {
            Log.e(TAG, "Rapid SOS failure: " + e.getMessage());
        }
    }

    private void stopFearfulSiren() {
        if (sosHandler != null && sosRunnable != null) {
            sosHandler.removeCallbacks(sosRunnable);
        }
        if (toneGenerator != null) {
            toneGenerator.stopTone();
            toneGenerator.release();
            toneGenerator = null;
        }
    }

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
            Log.e(TAG, "Chameleon UI Error: " + e.getMessage());
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
                } else if (errorCode == BiometricPrompt.ERROR_LOCKOUT || errorCode == BiometricPrompt.ERROR_LOCKOUT_PERMANENT) {
                    // INFINITE LOOP CRASH FIX: 
                    // Hardware sensor is locked out. We record the breach but DO NOT try to restart the sensor.
                    triggerIntruderAlert(false);
                } else if (errorCode != BiometricPrompt.ERROR_USER_CANCELED) {
                    triggerIntruderAlert(true);
                }
            }
            
            @Override
            public void onAuthenticationFailed() {
                super.onAuthenticationFailed();
                triggerIntruderAlert(true);
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

    /**
     * Legacy trigger method for backwards compatibility within this class.
     */
    private void triggerIntruderAlert() {
        triggerIntruderAlert(true);
    }

    /**
     * Overloaded trigger method. 
     * @param restartAuth If true, restarts the BiometricPrompt. If false, leaves it disabled.
     */
    private void triggerIntruderAlert(boolean restartAuth) {
        if (isActionTaken) return;
        isActionTaken = true;

        LocationHelper.getDeviceLocation(this, new LocationHelper.LocationResultCallback() {
            @Override
            public void onLocationFound(String mapLink) {
                processIntruderResponse(mapLink, restartAuth);
            }

            @Override
            public void onLocationFailed(String error) {
                processIntruderResponse("GPS Signal Lost", restartAuth);
            }
        });
    }

    private void processIntruderResponse(String mapLink, boolean restartAuth) {
        String appName = getIntent().getStringExtra("TARGET_APP_NAME");
        if (appName == null) appName = "Protected Files";

        boolean isDriveReady = db.isDriveEnabled() && db.getGoogleAccount() != null;

        if (isDriveReady && isNetworkAvailable()) {
            uploadToCloudAndSms(appName, mapLink);
        } else {
            if (isDriveReady) queueBackgroundUpload();
            SmsHelper.sendAlertSms(getApplicationContext(), appName, mapLink, "Security Breach", null);
        }

        runOnUiThread(() -> {
            Toast.makeText(this, "⚠ Security Breach Recorded", Toast.LENGTH_LONG).show();
            isActionTaken = false; 
            if (restartAuth) {
                triggerSystemAuth();
            } else {
                Log.w(TAG, "Biometric lockout active. Halting automatic prompt restart to prevent crash.");
            }
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
                
                // Block and wait for real Drive URL
                String driveLink = driveHelper.uploadFileAndGetLink(intruderFile);
                
                SmsHelper.sendAlertSms(getApplicationContext(), appName, mapLink, "Security Breach", driveLink);

            } catch (Exception e) {
                Log.e(TAG, "Cloud Sync Error: " + e.getMessage());
                queueBackgroundUpload();
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
                Log.e(TAG, "CameraX Hardware Error");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void onOwnerVerified() {
        stopFearfulSiren();
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
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        // Task Manager Bypass Fix: Wipes flag the exact moment the Home button is pressed
        HFSAccessibilityService.isLockActive = false;
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Task Manager Bypass Fix: Wipes flag immediately when activity loses focus
        HFSAccessibilityService.isLockActive = false;
    }

    @Override
    protected void onStop() {
        super.onStop();
        HFSAccessibilityService.isLockActive = false;
    }

    @Override
    protected void onDestroy() {
        stopFearfulSiren();
        cameraExecutor.shutdown();
        HFSAccessibilityService.isLockActive = false;
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {}
}