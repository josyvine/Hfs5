package com.hfs.security.ui;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
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

import com.hfs.security.services.DriveUploadWorker;
import com.hfs.security.utils.DriveHelper;
import com.hfs.security.utils.FileSecureHelper;
import com.hfs.security.utils.HFSDatabaseHelper;
import com.hfs.security.utils.LocationHelper;
import com.hfs.security.utils.SmsHelper;

import java.io.File;
import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Invisible System Capture Module.
 * This Activity is launched by HFSAccessibilityService when a Phone Lock Screen failure
 * (Fingerprint mismatch, Face mismatch, or 2 PIN failures) is detected.
 * 
 * FIXED: Keeps activity alive in the background just long enough for Google Drive
 * to return the URL, preventing the "Pending Upload" error.
 */
public class SystemCaptureActivity extends AppCompatActivity {

    private static final String TAG = "HFS_SystemCapture";

    private ExecutorService cameraExecutor;
    private HFSDatabaseHelper db;
    
    private boolean isCameraCaptured = false;
    private File intruderFile = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        /*
         * 1. WINDOW FLAGS
         * These flags allow this invisible activity to run directly over top of the 
         * locked phone screen without the OS blocking it.
         */
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);

        // Notice: We deliberately DO NOT call setContentView() here. 
        // Because of the Theme.Translucent.NoTitleBar set in the Manifest, 
        // not setting a view makes this Activity 100% invisible.

        db = HFSDatabaseHelper.getInstance(this);
        cameraExecutor = Executors.newSingleThreadExecutor();

        // 2. Start the invisible background camera
        startInvisibleCamera();
    }

    /**
     * Initializes CameraX in "Analysis Only" mode. 
     * Since we don't have a UI, we don't need a Preview. We just grab the frame.
     */
    private void startInvisibleCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = 
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, image -> {
                    if (!isCameraCaptured) {
                        isCameraCaptured = true;
                        
                        // 1. Capture the photo silently
                        intruderFile = FileSecureHelper.saveIntruderCaptureAndGetFile(this, image);
                        image.close();

                        // 2. Unbind camera to free up resources instantly
                        cameraProvider.unbindAll();

                        // 3. Move to GPS and Alert stage
                        triggerIntruderAlert();
                    } else {
                        image.close();
                    }
                });

                CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;
                cameraProvider.unbindAll();
                
                // Bind ONLY the ImageAnalysis (No preview screen needed)
                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "CameraX Initialization Error");
                closeInvisibleActivity();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /**
     * Fetches GPS location before proceeding to the SMS and Upload stage.
     */
    private void triggerIntruderAlert() {
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

    /**
     * Determines whether to upload online immediately or queue for later.
     */
    private void processIntruderResponse(String mapLink) {
        String appName = "SYSTEM PHONE LOCK";

        boolean isDriveReady = db.isDriveEnabled() && db.getGoogleAccount() != null;

        if (isDriveReady && isNetworkAvailable()) {
            // FIX: This method now handles its own closing so it doesn't die too early.
            uploadToCloudAndSms(appName, mapLink);
        } else {
            if (isDriveReady) {
                queueBackgroundUpload();
            }
            SmsHelper.sendAlertSms(getApplicationContext(), appName, mapLink, "System Unlock Failure", null);
            
            // If offline, we are done. Close the invisible activity.
            closeInvisibleActivity();
        }
    }

    /**
     * Handles Google Drive upload on a background thread.
     * FIXED: Will not close the Activity until the URL is generated.
     */
    private void uploadToCloudAndSms(String appName, String mapLink) {
        cameraExecutor.execute(() -> {
            try {
                if (intruderFile == null || !intruderFile.exists()) {
                    SmsHelper.sendAlertSms(getApplicationContext(), appName, mapLink, "System Unlock Failure", null);
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
                
                // This blocks until the upload finishes
                String driveLink = driveHelper.uploadFileAndGetLink(intruderFile);

                // Send the final SMS with the real Drive link
                SmsHelper.sendAlertSms(getApplicationContext(), appName, mapLink, "System Unlock Failure", driveLink);

            } catch (Exception e) {
                Log.e(TAG, "Cloud upload failed: " + e.getMessage());
                queueBackgroundUpload();
                SmsHelper.sendAlertSms(getApplicationContext(), appName, mapLink, "System Unlock Failure", null);
            } finally {
                // FIX: Ensure the activity closes itself ONLY after everything is finished.
                closeInvisibleActivity();
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

    /**
     * Shuts down the background threads and kills the invisible activity.
     */
    private void closeInvisibleActivity() {
        runOnUiThread(() -> {
            if (cameraExecutor != null && !cameraExecutor.isShutdown()) {
                cameraExecutor.shutdown();
            }
            finish();
            // Remove any exit animation so it remains perfectly stealthy
            overridePendingTransition(0, 0);
        });
    }

    @Override
    protected void onDestroy() {
        if (cameraExecutor != null && !cameraExecutor.isShutdown()) {
            cameraExecutor.shutdown();
        }
        super.onDestroy();
    }

    // Block back button presses while it's secretly running
    @Override
    public void onBackPressed() {}
}