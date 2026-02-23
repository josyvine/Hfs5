package com.hfs.security.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.hfs.security.ui.LockScreenActivity;
import com.hfs.security.utils.HFSDatabaseHelper;
import com.hfs.security.utils.SimManager;
import com.hfs.security.utils.SmsHelper;

/**
 * Hardware Watchdog for SIM Card changes.
 * Listens for SIM removal or Alien SIM insertion.
 * Triggers Siren Mode and executes Offline SMS Queues (Time Bomb trap).
 */
public class SimStateReceiver extends BroadcastReceiver {

    private static final String TAG = "HFS_SimStateReceiver";
    private static final String ACTION_SIM_STATE_CHANGED = "android.intent.action.SIM_STATE_CHANGED";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        if (intent.getAction().equals(ACTION_SIM_STATE_CHANGED)) {
            Log.d(TAG, "SIM State Change Detected by Hardware Watchdog.");

            HFSDatabaseHelper db = HFSDatabaseHelper.getInstance(context);

            // 1. Master Switch Check: Only proceed if Anti-Theft is armed
            if (!db.isAntiTheftEnabled()) {
                Log.d(TAG, "Anti-Theft is disabled. Ignoring SIM change.");
                return;
            }

            SimManager simManager = new SimManager(context);

            // 2. THE TIME BOMB TRAP (Offline Queue execution)
            // If the thief removed the SIMs earlier, the alert was queued.
            // Now that a SIM state change happened (they inserted their own SIM), we strike.
            if (db.hasPendingMessage()) {
                Log.i(TAG, "Executing Queued Alert using newly detected network connection...");
                SmsHelper.sendQueuedMessage(context);
            }

            // 3. THE BREACH VERIFICATION
            // Compare the currently inserted SIMs against the encrypted vault.
            if (simManager.isSimBreachDetected()) {
                Log.w(TAG, "SIM BREACH CONFIRMED! Initiating Siren Mode.");
                triggerTheftMode(context);
            } else {
                Log.d(TAG, "SIM verification passed. Hardware matches trusted vault.");
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
        theftIntent.putExtra("TARGET_APP_NAME", "SIM CARD REMOVED / SWAPPED");

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