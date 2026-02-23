package com.hfs.security.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.hfs.security.ui.LockScreenActivity;
import com.hfs.security.utils.HFSDatabaseHelper;

/**
 * Connection Watchdog for Airplane Mode toggles.
 * Detects if an intruder tries to cut off network access to prevent tracking.
 * Triggers the aggressive Siren Mode lock screen immediately.
 */
public class AirplaneModeReceiver extends BroadcastReceiver {

    private static final String TAG = "HFS_AirplaneReceiver";
    private static final String ACTION_AIRPLANE_MODE = "android.intent.action.AIRPLANE_MODE";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        if (intent.getAction().equals(ACTION_AIRPLANE_MODE)) {
            Log.d(TAG, "Airplane Mode toggle detected by Connection Watchdog.");

            HFSDatabaseHelper db = HFSDatabaseHelper.getInstance(context);

            // 1. Master Switch Check: Only proceed if Anti-Theft is armed
            if (!db.isAntiTheftEnabled()) {
                Log.d(TAG, "Anti-Theft is disabled. Ignoring Airplane Mode toggle.");
                return;
            }

            // 2. Verify State: Check if Airplane mode is being turned ON
            // The intent carries a boolean extra "state" which is true if Airplane Mode is ON
            boolean isAirplaneModeOn = intent.getBooleanExtra("state", false);

            if (isAirplaneModeOn) {
                Log.w(TAG, "AIRPLANE MODE ACTIVATED! Possible network cut-off attempt. Initiating Siren Mode.");
                triggerTheftMode(context);
            } else {
                Log.d(TAG, "Airplane Mode deactivated. Safe state resumed.");
            }
        }
    }

    /**
     * Launches the Lock Screen Activity in the aggressive "Siren Mode".
     */
    private void triggerTheftMode(Context context) {
        Intent theftIntent = new Intent(context, LockScreenActivity.class);
        
        // Tell LockScreenActivity to activate Siren instead of Silent App Lock
        theftIntent.putExtra("EXTRA_MODE", "THEFT_MODE");
        
        // Change the app name for the SMS payload so the owner knows why the alarm went off
        theftIntent.putExtra("TARGET_APP_NAME", "AIRPLANE MODE ACTIVATED");

        // Essential flags to launch activity from a background receiver instantly
        theftIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                           | Intent.FLAG_ACTIVITY_SINGLE_TOP 
                           | Intent.FLAG_ACTIVITY_CLEAR_TOP
                           | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
                           
        try {
            context.startActivity(theftIntent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch Theft Mode: " + e.getMessage());
        }
    }
}