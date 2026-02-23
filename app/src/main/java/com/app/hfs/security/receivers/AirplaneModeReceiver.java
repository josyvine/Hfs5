package com.hfs.security.receivers;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import android.util.Log; 

import androidx.core.app.NotificationCompat;

import com.hfs.security.R;
import com.hfs.security.ui.LockScreenActivity;
import com.hfs.security.utils.HFSDatabaseHelper;

/**
 * Connection Watchdog for Airplane Mode toggles.
 * FIXED: Bypasses Oppo/Android 9 background restrictions using Full-Screen Intent logic.
 * Triggers the aggressive Siren Mode lock screen immediately when network is cut.
 */
public class AirplaneModeReceiver extends BroadcastReceiver {

    private static final String TAG = "HFS_AirplaneReceiver";
    private static final String CHANNEL_ID = "hfs_theft_alert_channel";
    private static final int NOTIF_ID = 9001;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        if (intent.getAction().equals(Intent.ACTION_AIRPLANE_MODE_CHANGED)) {
            Log.d(TAG, "Airplane Mode toggle detected by Connection Watchdog.");

            HFSDatabaseHelper db = HFSDatabaseHelper.getInstance(context);

            // 1. Master Switch Check: Only proceed if Anti-Theft is armed
            if (!db.isAntiTheftEnabled()) {
                Log.d(TAG, "Anti-Theft is disabled. Ignoring Airplane Mode toggle.");
                return;
            }

            // 2. Verify State via System Settings (Most reliable method)
            boolean isAirplaneModeOn = Settings.Global.getInt(
                    context.getContentResolver(), 
                    Settings.Global.AIRPLANE_MODE_ON, 0) != 0;

            if (isAirplaneModeOn) {
                Log.w(TAG, "AIRPLANE MODE ACTIVATED! Initiating Siren Mode.");
                
                // 3. Trigger the Ambush
                triggerTheftMode(context);
            } else {
                Log.d(TAG, "Airplane Mode deactivated. Safe state resumed.");
            }
        }
    }

    /**
     * Launches the Lock Screen Activity in the aggressive "Siren Mode".
     * Uses a Full-Screen Intent Notification to bypass Oppo background launch blocks.
     */
    private void triggerTheftMode(Context context) {
        // Create the Intent for Siren Mode
        Intent theftIntent = new Intent(context, LockScreenActivity.class);
        theftIntent.putExtra("EXTRA_MODE", "THEFT_MODE");
        theftIntent.putExtra("TARGET_APP_NAME", "AIRPLANE MODE ACTIVATED");
        
        // Flags for instant overlay launch
        theftIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                           | Intent.FLAG_ACTIVITY_SINGLE_TOP 
                           | Intent.FLAG_ACTIVITY_CLEAR_TOP
                           | Intent.FLAG_ACTIVITY_NO_USER_ACTION);

        // Build a high-priority PendingIntent
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent fullScreenPendingIntent = PendingIntent.getActivity(
                context, 0, theftIntent, flags);

        // Setup Notification Manager & Channel
        NotificationManager notificationManager = (NotificationManager) 
                context.getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "HFS Theft Alerts", 
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }

        // Build the "Ambush" Notification
        // Even if the phone blocks background activities, it cannot block a Full-Screen Intent.
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.hfs)
                .setContentTitle("HFS SECURITY BREACH")
                .setContentText("Airplane Mode Detected. Unlock immediately.")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setFullScreenIntent(fullScreenPendingIntent, true) // FORCES the screen to open
                .setAutoCancel(true);

        if (notificationManager != null) {
            // Trigger both: The direct activity start AND the full-screen intent backup
            try {
                context.startActivity(theftIntent);
            } catch (Exception e) {
                Log.e(TAG, "Direct launch blocked, relying on Full-Screen Intent.");
            }
            notificationManager.notify(NOTIF_ID, builder.build());
        }
    }
}