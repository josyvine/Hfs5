package com.hfs.security.receivers;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.hfs.security.utils.LocationHelper;
import com.hfs.security.utils.SmsHelper;

/**
 * Device Administration Receiver.
 * FIXED: Implemented 'Lost Phone' tracking logic.
 * This component monitors system-level security events. 
 * If a thief or intruder fails to unlock the phone (wrong finger/PIN), 
 * it automatically triggers the GPS location and sends a high-priority SMS alert.
 */
public class AdminReceiver extends DeviceAdminReceiver {

    private static final String TAG = "HFS_AdminReceiver";

    @Override
    public void onEnabled(@NonNull Context context, @NonNull Intent intent) {
        super.onEnabled(context, intent);
        Toast.makeText(context, "HFS: Lost Phone Protection Enabled", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onDisabled(@NonNull Context context, @NonNull Intent intent) {
        super.onDisabled(context, intent);
        Toast.makeText(context, "HFS: Warning - Anti-Theft Protection Disabled", Toast.LENGTH_SHORT).show();
    }

    /**
     * ENHANCEMENT: THE LOST PHONE TRIGGER.
     * Triggered by the Android System when a screen unlock attempt fails.
     */
    @Override
    public void onPasswordFailed(@NonNull Context context, @NonNull Intent intent) {
        super.onPasswordFailed(context, intent);
        
        Log.e(TAG, "DEVICE UNLOCK FAILED: Potential Intruder/Thief detected.");

        // 1. Check how many times it has failed (Optional threshold)
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        int failedAttempts = dpm.getCurrentFailedPasswordAttempts();

        // 2. TRIGGER THE SECURITY FLOW
        // We fetch the location immediately and send it to the owner.
        LocationHelper.getDeviceLocation(context, new LocationHelper.LocationResultCallback() {
            @Override
            public void onLocationFound(String mapLink) {
                // Send SMS with GPS coordinates
                SmsHelper.sendAlertSms(context, "DEVICE LOCK SCREEN (Lost Phone Mode)", mapLink);
            }

            @Override
            public void onLocationFailed(String error) {
                // Send SMS even if GPS fails, informing the owner of the attempt
                SmsHelper.sendAlertSms(context, "DEVICE LOCK SCREEN (Lost Phone Mode)", "GPS Location Unavailable");
            }
        });

        // 3. LOGGING FOR INTERNALS
        Log.i(TAG, "Intruder Alert triggered via Device Admin. Attempt count: " + failedAttempts);
    }

    @Override
    public void onPasswordSucceeded(@NonNull Context context, @NonNull Intent intent) {
        super.onPasswordSucceeded(context, intent);
        Log.d(TAG, "Device unlocked by legitimate owner.");
    }

    /**
     * Text shown to the user if they try to deactivate HFS Admin.
     */
    @Override
    public CharSequence onDisableRequested(@NonNull Context context, @NonNull Intent intent) {
        return "Warning: Disabling HFS will stop the 'Lost Phone' GPS tracking and Alert system.";
    }
}