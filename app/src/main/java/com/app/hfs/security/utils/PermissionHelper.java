package com.hfs.security.utils;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build; 
import android.os.Process;
import android.provider.Settings;
import android.text.TextUtils;

import androidx.core.content.ContextCompat;

import com.hfs.security.services.HFSAccessibilityService;

/**
 * Advanced Permission & System Security Manager.
 * UPDATED: Added Accessibility Service verification for Zero-Flash detection.
 */
public class PermissionHelper {

    /**
     * Verifies if the phone has any system security enabled.
     * Logic: Checks if the user has a PIN, Pattern, Password, or Biometric 
     * enrolled at the OS level. If this returns false, HFS should warn the user.
     */
    public static boolean isDeviceSecure(Context context) {
        KeyguardManager keyguardManager = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return keyguardManager != null && keyguardManager.isDeviceSecure();
        } else {
            return keyguardManager != null && keyguardManager.isKeyguardSecure();
        }
    }

    /**
     * Checks if the app can access GPS coordinates for tracking links.
     */
    public static boolean hasLocationPermissions(Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) 
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Checks if the app can intercept dialed numbers (Oppo Dialer Fix).
     */
    public static boolean hasPhonePermissions(Context context) {
        boolean statePerm = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) 
                == PackageManager.PERMISSION_GRANTED;
        
        boolean callPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.PROCESS_OUTGOING_CALLS) 
                == PackageManager.PERMISSION_GRANTED;

        return statePerm && callPerm;
    }

    /**
     * Checks if the app has permission to show the Lock Screen overlay.
     */
    public static boolean canDrawOverlays(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(context);
        }
        return true; 
    }

    /**
     * Checks if the app can detect foreground app launches.
     * (Kept for legacy compatibility/stats, though Detection is now Accessibility-based).
     */
    public static boolean hasUsageStatsPermission(Context context) {
        AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, 
                Process.myUid(), context.getPackageName());
        
        if (mode == AppOpsManager.MODE_DEFAULT) {
            return context.checkCallingOrSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) 
                    == PackageManager.PERMISSION_GRANTED;
        }
        
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    /**
     * NEW: Checks if the HFS Accessibility Service is enabled in System Settings.
     * Required for Zero-Flash app locking.
     */
    public static boolean isAccessibilityServiceEnabled(Context context) {
        String expectedComponentName = new ComponentName(context, HFSAccessibilityService.class).flattenToString();
        
        String enabledServicesSetting = Settings.Secure.getString(
                context.getContentResolver(), 
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        
        if (enabledServicesSetting == null) return false;

        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabledServicesSetting);

        while (splitter.hasNext()) {
            String componentName = splitter.next();
            if (componentName.equalsIgnoreCase(expectedComponentName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks for standard Runtime Permissions (Camera and SMS).
     * Camera is still required for the silent intruder photo capture.
     */
    public static boolean hasBasePermissions(Context context) {
        boolean camera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) 
                == PackageManager.PERMISSION_GRANTED;
        
        boolean sendSms = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) 
                == PackageManager.PERMISSION_GRANTED;

        return camera && sendSms;
    }

    /**
     * Master check to see if HFS is fully authorized to protect the device.
     * UPDATED: Now requires Accessibility Service to be enabled.
     */
    public static boolean isAllSecurityGranted(Context context) {
        return hasBasePermissions(context) && 
               hasPhonePermissions(context) && 
               hasLocationPermissions(context) && 
               canDrawOverlays(context) &&
               isDeviceSecure(context) &&
               isAccessibilityServiceEnabled(context); 
    }

    /**
     * Helper to open app settings for manual Oppo Auto-startup enabling.
     */
    public static void openAppSettings(Context context) {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        Uri uri = Uri.fromParts("package", context.getPackageName(), null);
        intent.setData(uri);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }
}