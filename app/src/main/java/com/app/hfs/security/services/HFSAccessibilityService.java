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
 * 
 * FIXED: Task Manager Bypass. 
 * The service now verifies the foreground package more strictly to prevent 
 * the "isLockActive" flag from causing a deadlock when the lock screen 
 * is hidden via the Recent Apps/Task button.
 */
public class HFSAccessibilityService extends AccessibilityService {

    private static final String TAG = "HFS_Accessibility";
    private HFSDatabaseHelper db;

    // --- SESSION CONTROL FLAGS ---
    
    // Flag to prevent re-triggering while the lock screen is already on top
    public static boolean isLockActive = false;
    
    // Tracks which package the owner has successfully unlocked
    private static String unlockedPackage = "";
    
    // Timestamp for the 10-second grace period
    private static long lastUnlockTimestamp = 0;
    
    // Grace period duration: 10 Seconds
    private static final long SESSION_GRACE_MS = 10000;

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
        Log.d(TAG, "HFS Accessibility Service Connected and Monitoring...");
    }

    /**
     * The Heart of the Detection System.
     * This event fires the EXACT moment a new window appears on the screen.
     */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // We only care about TYPE_WINDOW_STATE_CHANGED (App opening/switching)
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            
            if (event.getPackageName() == null) return;
            
            String currentPkg = event.getPackageName().toString();

            // 1. SELF-PROTECTION: Ignore HFS itself to prevent lock loops.
            if (currentPkg.equals(getPackageName())) {
                // If we are back in our own app, we can safely assume the lock UI is active
                isLockActive = true;
                return;
            }

            /*
             * 2. TASK MANAGER BYPASS FIX:
             * If the current package is NOT our lock screen, and NOT the package 
             * that was just unlocked, we must re-evaluate security even if 
             * "isLockActive" is true. This prevents an orphaned lock screen 
             * in the background from muting the service.
             */
            if (isLockActive && !currentPkg.equals(unlockedPackage)) {
                // If the intruder is looking at a protected app, the lock must be forced.
                // We proceed to the protection check.
            } else if (isLockActive) {
                // Otherwise, if lock is active and it's a safe package, return.
                return;
            }

            // 3. RE-ARM LOGIC: If the user switches to a different app,
            // immediately clear the previous unlock session for security.
            if (!currentPkg.equals(unlockedPackage)) {
                if (!unlockedPackage.isEmpty()) {
                    Log.d(TAG, "User switched apps. Security Re-armed.");
                    unlockedPackage = "";
                }
            }

            // 4. PROTECTION LOGIC: Check if the current app is in the protected list.
            Set<String> protectedApps = db.getProtectedPackages();
            
            if (protectedApps.contains(currentPkg)) {
                
                // Verify if the current session is within the 10-second grace window
                boolean isSessionValid = currentPkg.equals(unlockedPackage) && 
                        (System.currentTimeMillis() - lastUnlockTimestamp < SESSION_GRACE_MS);

                if (!isSessionValid) {
                    Log.i(TAG, "Security Breach Detected: Immediate Lock for " + currentPkg);
                    triggerLockOverlay(currentPkg);
                }
            }
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "HFS Accessibility Service Interrupted.");
    }

    /**
     * Launches the Lock Screen Overlay Activity.
     */
    private void triggerLockOverlay(String packageName) {
        String appName = getAppNameFromPackage(packageName);
        
        Intent lockIntent = new Intent(this, LockScreenActivity.class);
        lockIntent.putExtra("TARGET_APP_PACKAGE", packageName);
        lockIntent.putExtra("TARGET_APP_NAME", appName);
        
        // Essential flags for starting an Activity from a Service context
        lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                          | Intent.FLAG_ACTIVITY_SINGLE_TOP 
                          | Intent.FLAG_ACTIVITY_CLEAR_TOP
                          | Intent.FLAG_ACTIVITY_NO_USER_ACTION); 
        
        try {
            startActivity(lockIntent);
            // Explicitly set the flag here to ensure immediate state update
            isLockActive = true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch lock overlay: " + e.getMessage());
        }
    }

    /**
     * Helper to resolve the user-friendly App Name (e.g., "WhatsApp") 
     * from the system package ID.
     */
    private String getAppNameFromPackage(String packageName) {
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
        Log.w(TAG, "HFS Accessibility Service Unbound.");
        return super.onUnbind(intent);
    }
}