package com.hfs.security.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.hfs.security.ui.SplashActivity;

/**
 * Stealth Mode Entry Point (Phase 8).
 * This receiver listens for the system "Secret Code" broadcast.
 * 
 * When the user dials *#*#7392#*#* on their phone's dialpad:
 * 1. The Android system sends a SECRET_CODE broadcast.
 * 2. This receiver catches it based on the host "7392" defined in the Manifest.
 * 3. HFS launches the SplashActivity to grant the owner access.
 */
public class StealthLaunchReceiver extends BroadcastReceiver {

    private static final String TAG = "HFS_StealthLaunch";

    @Override
    public void onReceive(Context context, Intent intent) {
        // Verify the action is indeed a secret code trigger
        if (intent != null && "android.provider.Telephony.SECRET_CODE".equals(intent.getAction())) {
            
            Log.i(TAG, "Stealth Dial Code Detected. Launching HFS Security...");

            // Logic: Create an intent to open the app entry point
            Intent launchIntent = new Intent(context, SplashActivity.class);
            
            /*
             * FLAG_ACTIVITY_NEW_TASK: 
             * Required because we are starting an Activity from a BroadcastReceiver.
             * 
             * FLAG_ACTIVITY_CLEAR_TOP:
             * Ensures we open a fresh instance of the app.
             */
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            
            try {
                context.startActivity(launchIntent);
            } catch (Exception e) {
                Log.e(TAG, "Failed to launch HFS via Stealth Code: " + e.getMessage());
            }
        }
    }
}