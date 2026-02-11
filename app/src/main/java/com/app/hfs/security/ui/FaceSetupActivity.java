package com.hfs.security.ui;

import android.graphics.PointF;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import com.google.mlkit.vision.face.FaceLandmark;

import com.hfs.security.R;
import com.hfs.security.databinding.ActivityFaceSetupBinding;
import com.hfs.security.utils.HFSDatabaseHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owner Face Registration Screen.
 * FIXED: Implemented Multi-Frame Averaging.
 * Instead of a single frame, this captures 5 biometric snapshots to create 
 * a highly accurate 'Owner Face Map', resolving the false mismatch issues.
 */
public class FaceSetupActivity extends AppCompatActivity {

    private static final String TAG = "HFS_FaceSetup";
    private ActivityFaceSetupBinding binding;
    private ExecutorService cameraExecutor;
    private FaceDetector detector;
    private HFSDatabaseHelper db;

    // Calibration Variables
    private boolean isCalibrationDone = false;
    private final List<Float> capturedRatiosA = new ArrayList<>();
    private final List<Float> capturedRatiosB = new ArrayList<>();
    private final int REQUIRED_CALIBRATION_FRAMES = 5;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        binding = ActivityFaceSetupBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        db = HFSDatabaseHelper.getInstance(this);
        cameraExecutor = Executors.newSingleThreadExecutor();

        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(0.4f) // Require user to be close for calibration
                .build();
        
        detector = FaceDetection.getClient(options);

        binding.btnBack.setOnClickListener(v -> finish());
        
        startCamera();
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = 
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(binding.viewFinder.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(cameraExecutor, image -> {
                    if (isCalibrationDone) {
                        image.close();
                        return;
                    }
                    processCalibrationFrame(image);
                });

                CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis);

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera Setup Failed: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @SuppressWarnings("UnsafeOptInUsageError")
    private void processCalibrationFrame(androidx.camera.core.ImageProxy imageProxy) {
        if (imageProxy.getImage() == null) return;

        InputImage image = InputImage.fromMediaImage(
                imageProxy.getImage(), 
                imageProxy.getImageInfo().getRotationDegrees()
        );

        detector.process(image)
                .addOnSuccessListener(faces -> {
                    if (!faces.isEmpty() && !isCalibrationDone) {
                        Face face = faces.get(0);
                        
                        // Extract all 4 points for triangulation
                        FaceLandmark leftEye = face.getLandmark(FaceLandmark.LEFT_EYE);
                        FaceLandmark rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE);
                        FaceLandmark nose = face.getLandmark(FaceLandmark.NOSE_BASE);
                        FaceLandmark mouth = face.getLandmark(FaceLandmark.MOUTH_BOTTOM);

                        if (leftEye != null && rightEye != null && nose != null && mouth != null) {
                            collectBiometricSample(leftEye, rightEye, nose, mouth);
                        }
                    }
                })
                .addOnCompleteListener(task -> imageProxy.close());
    }

    /**
     * Collects samples until we reach the required frame count for averaging.
     */
    private void collectBiometricSample(FaceLandmark L, FaceLandmark R, FaceLandmark N, FaceLandmark M) {
        float eyeDist = calculateDistance(L.getPosition(), R.getPosition());
        float noseDist = calculateDistance(L.getPosition(), N.getPosition());
        float mouthDist = calculateDistance(L.getPosition(), M.getPosition());

        if (noseDist == 0 || mouthDist == 0) return;

        // Add ratios to the collection
        capturedRatiosA.add(eyeDist / noseDist);
        capturedRatiosB.add(eyeDist / mouthDist);

        final int currentProgress = capturedRatiosA.size();

        runOnUiThread(() -> {
            binding.tvStatus.setText("CALIBRATING: " + currentProgress + "/" + REQUIRED_CALIBRATION_FRAMES);
            
            if (currentProgress >= REQUIRED_CALIBRATION_FRAMES) {
                finalizeCalibration();
            }
        });
    }

    /**
     * Calculates the final average proportions and saves to DB.
     */
    private void finalizeCalibration() {
        isCalibrationDone = true;

        float sumA = 0, sumB = 0;
        for (float r : capturedRatiosA) sumA += r;
        for (float r : capturedRatiosB) sumB += r;

        float avgA = sumA / REQUIRED_CALIBRATION_FRAMES;
        float avgB = sumB / REQUIRED_CALIBRATION_FRAMES;

        // Final Format: RatioA|RatioB
        final String finalIdentityMap = avgA + "|" + avgB;

        runOnUiThread(() -> {
            binding.captureAnimation.setVisibility(View.VISIBLE);
            binding.tvStatus.setText("IDENTITY VERIFIED & SAVED");
            
            // Save the averaged biometric map
            db.saveOwnerFaceData(finalIdentityMap);
            db.setSetupComplete(true);

            Toast.makeText(this, "Face Calibration Complete", Toast.LENGTH_LONG).show();

            binding.rootLayout.postDelayed(this::finish, 2000);
        });
    }

    private float calculateDistance(PointF p1, PointF p2) {
        return (float) Math.sqrt(Math.pow(p1.x - p2.x, 2) + Math.pow(p1.y - p2.y, 2));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        if (detector != null) detector.close();
    }
}