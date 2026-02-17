package com.hfs.security.services;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import com.hfs.security.ui.LockScreenActivity;
import com.hfs.security.utils.HFSDatabaseHelper;

import java.util.Set;

/**
 * HFS Real-time Detection Service.
 * Replaces polling with event-driven detection for Zero-Flash locking.
 */
public class HFSAccessibilityService extends AccessibilityService {

    private static final String TAG = "HFS_Accessibility";
    private HFSDatabaseHelper db;

    // --- SESSION CONTROL FLAGS (Moved from AppMonitorService) ---
    public static boolean isLockActive = false;
    private static String unlockedPackage = "";
    private static long lastUnlockTimestamp = 0;
    private static final long SESSION_GRACE_MS = 10000; // 10 Seconds

    /**
     * Signals that the owner has successfully bypassed the lock.
     * Called by LockScreenActivity.
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
        Log.d(TAG, "HFS Accessibility Service Connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // We only care about window state changes (App opening/closing)
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            
            if (event.getPackageName() == null) return;
            String currentPkg = event.getPackageName().toString();

            // 1. SELF-PROTECTION: Ignore HFS itself to prevent lock loops
            if (currentPkg.equals(getPackageName())) {
                return;
            }

            // 2. Skip logic if Lock Screen is already visible
            if (isLockActive) {
                return;
            }

            // 3. RE-ARM LOGIC: If user switches apps, reset session
            if (!currentPkg.equals(unlockedPackage)) {
                if (!unlockedPackage.isEmpty()) {
                    Log.d(TAG, "User left protected area. Security Re-armed.");
                    unlockedPackage = "";
                }
            }

            // 4. CHECK IF APP IS PROTECTED
            Set<String> protectedApps = db.getProtectedPackages();
            if (protectedApps.contains(currentPkg)) {
                
                // Check if current session is valid
                boolean isSessionValid = currentPkg.equals(unlockedPackage) && 
                        (System.currentTimeMillis() - lastUnlockTimestamp < SESSION_GRACE_MS);

                if (!isSessionValid) {
                    Log.i(TAG, "Security Breach: Immediate Lock Trigger for " + currentPkg);
                    triggerLockOverlay(currentPkg);
                }
            }
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "HFS Accessibility Service Interrupted");
    }

    /**
     * Launches the Lock Screen Overlay immediately.
     */
    private void triggerLockOverlay(String packageName) {
        String appName = getAppNameFromPackage(packageName);
        
        Intent lockIntent = new Intent(this, LockScreenActivity.class);
        lockIntent.putExtra("TARGET_APP_PACKAGE", packageName);
        lockIntent.putExtra("TARGET_APP_NAME", appName);
        
        // Critical flags for launching from a Service context
        lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                          | Intent.FLAG_ACTIVITY_SINGLE_TOP 
                          | Intent.FLAG_ACTIVITY_CLEAR_TOP
                          | Intent.FLAG_ACTIVITY_NO_USER_ACTION); // Prevents animation overlap
        
        try {
            startActivity(lockIntent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start lock overlay: " + e.getMessage());
        }
    }

    private String getAppNameFromPackage(String packageName) {
        PackageManager pm = getPackageManager();
        try {
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            return (String) pm.getApplicationLabel(ai);
        } catch (PackageManager.NameNotFoundException e) {
            return packageName; 
        }
    }
}