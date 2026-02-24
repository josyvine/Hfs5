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
import android.view.accessibility.AccessibilityNodeInfo;

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
        
        // Dynamic registration for hardware wake events
        screenReceiver = new ScreenReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, filter);
        
        Log.d(TAG, "HFS Guard Connected. Monitoring for App Flashes and System Wake.");
    }

    private class ScreenReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction() != null && intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                if (db.isPhoneProtectionEnabled()) {
                    Log.i(TAG, "Ambush Trigger: Covering System Lock.");
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
        // PART 1: ZERO-FLASH DETECTION LOGIC
        // We monitor Window Changes, Clicks, and Focus to be proactive.
        // ==========================================================
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || 
            eventType == AccessibilityEvent.TYPE_VIEW_CLICKED || 
            eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            
            // System UI Check
            if (!currentPkg.equals("com.android.systemui")) {
                systemPinAttemptCount = 0;
            }

            // 1. Own App Check
            if (currentPkg.equals(getPackageName())) {
                isLockActive = true;
                return;
            }

            // 2. Task Manager Bypass Safety
            if (isLockActive && !currentPkg.equals(unlockedPackage)) {
                // Force verification if the app on screen is not the one we just unlocked
            } else if (isLockActive) {
                return;
            }

            // 3. Re-Arm Check
            if (!currentPkg.equals(unlockedPackage)) {
                if (!unlockedPackage.isEmpty()) {
                    unlockedPackage = "";
                }
            }

            // 4. Protection Check (Predictive)
            Set<String> protectedApps = db.getProtectedPackages();
            if (protectedApps.contains(currentPkg)) {
                
                boolean isSessionValid = currentPkg.equals(unlockedPackage) && 
                        (System.currentTimeMillis() - lastUnlockTimestamp < SESSION_GRACE_MS);

                if (!isSessionValid) {
                    // Start LockScreenActivity immediately to prevent UI flash
                    triggerLockOverlay(currentPkg);
                }
            }
        }

        // ==========================================================
        // PART 2: SYSTEM LOCK WATCHER (Biometric Text / PIN)
        // ==========================================================
        if (currentPkg.equals("com.android.systemui")) {
            long currentTime = System.currentTimeMillis();

            // Watch for error text messages appearing on the Lock Screen
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

            // Watch for PIN pad interaction (Forced 2-attempt trigger)
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
        Log.w(TAG, "HFS Guard Interrupted.");
    }

    /**
     * Launches the visible Lock Screen Overlay.
     * Uses NO_ANIMATION and high-priority flags to stop the target app from flashing.
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
            Log.e(TAG, "Failed to launch overlay: " + e.getMessage());
        }
    }

    private void triggerInvisibleSystemCamera() {
        Intent captureIntent = new Intent(this, SystemCaptureActivity.class);
        captureIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                             | Intent.FLAG_ACTIVITY_MULTIPLE_TASK 
                             | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            startActivity(captureIntent);
        } catch (Exception e) {
            Log.e(TAG, "System capture blocked.");
        }
    }

    private String getAppNameFromPackage(String packageName) {
        if (packageName.equals("System Phone Lock")) return "System Phone Lock";
        
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
            try { unregisterReceiver(screenReceiver); } catch (Exception ignored) {}
        }
        return super.onUnbind(intent);
    }
}