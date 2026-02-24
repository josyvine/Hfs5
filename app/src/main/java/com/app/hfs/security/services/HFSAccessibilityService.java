package com.hfs.security.services;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import com.hfs.security.ui.LockScreenActivity;
import com.hfs.security.ui.SystemCaptureActivity;
import com.hfs.security.utils.HFSDatabaseHelper;

import java.util.Set;

/**
 * HFS Real-time Detection Service.
 * Replaces polling with event-driven detection for Zero-Flash locking.
 * 
 * UPDATED LOGIC:
 * 1. Predictive Launch: Detects view clicks and focus changes to stop app flashes.
 * 2. Pre-Emptive Ambush: Monitors screen wake events for system lock protection.
 * 3. Self-correcting flags: Prevents apps from opening freely via task manager.
 */
public class HFSAccessibilityService extends AccessibilityService {

    private static final String TAG = "HFS_Accessibility";
    private HFSDatabaseHelper db;
    private ScreenReceiver screenReceiver;

    // --- SESSION CONTROL FLAGS ---
    public static boolean isLockActive = false;
    private static String unlockedPackage = "";
    private static long lastUnlockTimestamp = 0;
    private static final long SESSION_GRACE_MS = 10000; // 10 Seconds

    // --- SYSTEM LOCK TRACKERS ---
    private int systemPinAttemptCount = 0;
    private long lastSystemAlertTime = 0;
    private static final long SYSTEM_COOLDOWN_MS = 5000; 

    /**
     * Signals that the owner has successfully bypassed the lock (Biometric/PIN).
     * This method is called from LockScreenActivity.
     */
    public static void unlockSession(String packageName) {
        unlockedPackage = packageName;
        lastUnlockTimestamp = System.currentTimeMillis();
        Log.d(TAG, "Owner Verified. Grace Period active for: " + packageName);
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        db = HFSDatabaseHelper.getInstance(this);
        
        // REGISTER SCREEN RECEIVER (The Ambush Trigger)
        screenReceiver = new ScreenReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, filter);
        
        Log.d(TAG, "HFS Accessibility Service Connected. Screen Monitor Active.");
    }

    /**
     * Inner Class: Listens for Power Button / Wake events to cover System Lock.
     */
    private class ScreenReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction() != null && intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                // Check if the user has enabled "Phone Protection" (Now Defaults to TRUE)
                if (db.isPhoneProtectionEnabled()) {
                    Log.i(TAG, "Screen Woke Up: Triggering Pre-Emptive HFS Lock.");
                    triggerLockOverlay("System Phone Lock");
                }
            }
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getPackageName() == null) return;
        String currentPkg = event.getPackageName().toString();

        int eventType = event.getEventType();

        // ==========================================================
        // PART 1: NORMAL HFS LOCK LOGIC (For Protected Apps)
        // FIXED: Added VIEW_CLICKED and VIEW_FOCUSED to stop the "Flash"
        // ==========================================================
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || 
            eventType == AccessibilityEvent.TYPE_VIEW_CLICKED || 
            eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            
            // Reset PIN counter if we left the lock screen
            if (!currentPkg.equals("com.android.systemui")) {
                systemPinAttemptCount = 0;
            }

            // 1. SELF-PROTECTION: Verify if we are already showing the lock screen
            if (currentPkg.equals(getPackageName())) {
                isLockActive = true;
                return;
            }

            // 2. TASK MANAGER BYPASS FIX
            if (isLockActive && !currentPkg.equals(unlockedPackage)) {
                // Force re-verification
            } else if (isLockActive) {
                return;
            }

            // 3. RE-ARM LOGIC: If user switched apps, clear the session
            if (!currentPkg.equals(unlockedPackage)) {
                if (!unlockedPackage.isEmpty()) {
                    Log.d(TAG, "User switched apps. Security Re-armed.");
                    unlockedPackage = "";
                }
            }

            // 4. PROTECTION LOGIC (Strict Check)
            Set<String> protectedApps = db.getProtectedPackages();
            if (protectedApps.contains(currentPkg)) {
                
                boolean isSessionValid = currentPkg.equals(unlockedPackage) && 
                        (System.currentTimeMillis() - lastUnlockTimestamp < SESSION_GRACE_MS);

                if (!isSessionValid) {
                    Log.i(TAG, "Security Breach Detected: Immediate Lock for " + currentPkg);
                    triggerLockOverlay(currentPkg);
                }
            }
        }

        // ==========================================================
        // PART 2: FALLBACK SYSTEM WATCHER (Biometric Text / PIN Clicks)
        // ==========================================================
        if (currentPkg.equals("com.android.systemui")) {
            long currentTime = System.currentTimeMillis();

            // A. WATCH FOR TEXT ERRORS (Fingerprint/Face)
            if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                if (event.getText() != null) {
                    for (CharSequence text : event.getText()) {
                        String screenText = text.toString().toLowerCase();
                        if (screenText.contains("not recognized") || 
                            screenText.contains("mismatch") || 
                            screenText.contains("incorrect") ||
                            screenText.contains("try again")) {
                            
                            if (currentTime - lastSystemAlertTime > SYSTEM_COOLDOWN_MS) {
                                triggerInvisibleSystemCamera();
                                lastSystemAlertTime = currentTime;
                                systemPinAttemptCount = 0;
                            }
                        }
                    }
                }
            }

            // B. WATCH FOR PIN CLICKS
            if (eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                systemPinAttemptCount++;
                if (systemPinAttemptCount >= 2) {
                    if (currentTime - lastSystemAlertTime > SYSTEM_COOLDOWN_MS) {
                        triggerInvisibleSystemCamera();
                        lastSystemAlertTime = currentTime;
                    }
                    systemPinAttemptCount = 0; 
                }
            }
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "HFS Accessibility Service Interrupted.");
    }

    /**
     * Launches the visible Lock Screen Overlay.
     * Added NO_ANIMATION to prevent "Flash".
     */
    private void triggerLockOverlay(String packageName) {
        String appName = getAppNameFromPackage(packageName);
        
        Intent lockIntent = new Intent(this, LockScreenActivity.class);
        lockIntent.putExtra("TARGET_APP_PACKAGE", packageName);
        lockIntent.putExtra("TARGET_APP_NAME", appName);
        
        lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                          | Intent.FLAG_ACTIVITY_SINGLE_TOP 
                          | Intent.FLAG_ACTIVITY_CLEAR_TOP
                          | Intent.FLAG_ACTIVITY_NO_USER_ACTION
                          | Intent.FLAG_ACTIVITY_NO_ANIMATION); 
        
        try {
            startActivity(lockIntent);
            isLockActive = true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch lock overlay: " + e.getMessage());
        }
    }

    /**
     * Launches the Invisible Camera Activity (Fallback for System Lock).
     */
    private void triggerInvisibleSystemCamera() {
        Intent captureIntent = new Intent(this, SystemCaptureActivity.class);
        captureIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                             | Intent.FLAG_ACTIVITY_MULTIPLE_TASK 
                             | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            startActivity(captureIntent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch invisible system capture: " + e.getMessage());
        }
    }

    private String getAppNameFromPackage(String packageName) {
        if (packageName.equals("System Phone Lock")) {
            return "System Phone Lock";
        }
        
        PackageManager pm = getPackageManager();
        try {
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            return (String) pm.getApplicationLabel(ai);
        } catch (PackageManager.NameNotFoundException e) {
            return packageName; 
        }
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (screenReceiver != null) {
            try {
                unregisterReceiver(screenReceiver);
            } catch (Exception e) {
                Log.e(TAG, "Receiver already unregistered");
            }
        }
        Log.w(TAG, "HFS Accessibility Service Unbound.");
        return super.onUnbind(intent);
    }
}