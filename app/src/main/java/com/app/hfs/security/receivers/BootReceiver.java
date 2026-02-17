package com.hfs.security.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.hfs.security.utils.HFSDatabaseHelper;

/**
 * System Boot Receiver (Phase 7).
 * Detects device reboots to ensure HFS security status is logged.
 * 
 * Note: Since moving to Accessibility Service, the Android System automatically 
 * handles restarting the service on boot if it was enabled by the user.
 * Manual start via Intent is no longer required.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "HFS_BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        // We listen for both standard boot and "Quick Boot" (used by some manufacturers)
        String action = intent.getAction();
        
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || 
            "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            
            Log.i(TAG, "Device reboot detected. HFS Security System is being managed by Android Accessibility Service.");

            HFSDatabaseHelper db = HFSDatabaseHelper.getInstance(context);

            // Verify setup status after reboot
            if (db.isSetupComplete()) {
                Log.d(TAG, "HFS Setup is verified. System protection remains active as per user settings.");
            }
        }
    }
}