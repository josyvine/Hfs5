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
import com.hfs.security.utils.LocationHelper;
import com.hfs.security.utils.SmsHelper;

/**
 * Connection Watchdog for Airplane Mode toggles.
 * FIXED: Bypasses Oppo/Android 9 background restrictions using Full-Screen Intent logic.
 * Triggers the aggressive Siren Mode lock screen immediately and sends alert 
 * before the cellular radio is fully deactivated.
 */
public class AirplaneModeReceiver extends BroadcastReceiver {

    private static final String TAG = "HFS_AirplaneReceiver";
    private static final String CHANNEL_ID = "hfs_theft_alert_channel";
    private static final int NOTIF_ID = 9001;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) return;

        // The standard action for airplane mode toggle
        if (intent.getAction().equals(Intent.ACTION_AIRPLANE_MODE_CHANGED) || 
            intent.getAction().equals("android.intent.action.AIRPLANE_MODE")) {
            
            Log.d(TAG, "Hardware Connectivity Change Detected.");

            HFSDatabaseHelper db = HFSDatabaseHelper.getInstance(context);

            // 1. Check if Anti-Theft is Armed
            if (!db.isAntiTheftEnabled()) {
                Log.d(TAG, "Watchdogs sleeping: Anti-Theft disabled.");
                return;
            }

            // 2. Read System Settings directly (Most reliable on Oppo/Android 9)
            boolean isAirplaneModeOn = Settings.Global.getInt(
                    context.getContentResolver(), 
                    Settings.Global.AIRPLANE_MODE_ON, 0) != 0;

            if (isAirplaneModeOn) {
                Log.w(TAG, "BREACH: Airplane Mode Activated. Initiating Ambush.");
                
                // 3. Launch the Siren Screen (Immediate)
                triggerTheftMode(context);
                
                // 4. Trigger SMS Alert (Try to beat the antenna shutdown)
                sendImmediateAlert(context);
            }
        }
    }

    /**
     * Attempts to send the SMS alert in the split-second before the radio dies.
     */
    private void sendImmediateAlert(Context context) {
        LocationHelper.getDeviceLocation(context, new LocationHelper.LocationResultCallback() {
            @Override
            public void onLocationFound(String mapLink) {
                // Type is specifically marked so SmsHelper knows to queue it if signal is already gone
                SmsHelper.sendAlertSms(context, "Quick Settings", mapLink, "AIRPLANE MODE ACTIVATED", null);
            }

            @Override
            public void onLocationFailed(String error) {
                SmsHelper.sendAlertSms(context, "Quick Settings", "GPS Lost", "AIRPLANE MODE ACTIVATED", null);
            }
        });
    }

    /**
     * Launches the Lock Screen Activity in "Siren Mode".
     * Uses a Full-Screen Intent Notification to bypass background blocks.
     */
    private void triggerTheftMode(Context context) {
        Intent theftIntent = new Intent(context, LockScreenActivity.class);
        theftIntent.putExtra("EXTRA_MODE", "THEFT_MODE");
        theftIntent.putExtra("TARGET_APP_NAME", "AIRPLANE MODE ACTIVATED");
        
        theftIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                           | Intent.FLAG_ACTIVITY_SINGLE_TOP 
                           | Intent.FLAG_ACTIVITY_CLEAR_TOP
                           | Intent.FLAG_ACTIVITY_NO_USER_ACTION
                           | Intent.FLAG_ACTIVITY_NO_ANIMATION);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }

        PendingIntent fullScreenPendingIntent = PendingIntent.getActivity(
                context, 0, theftIntent, flags);

        NotificationManager notificationManager = (NotificationManager) 
                context.getSystemService(Context.NOTIFICATION_SERVICE);

        // Ensure the high-priority channel exists
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "HFS Emergency Alerts", 
                    NotificationManager.IMPORTANCE_HIGH);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            channel.enableVibration(true);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }

        // The Notification acts as the "Bypass" for background restrictions
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.hfs)
                .setContentTitle("HFS: SECURITY BREACH")
                .setContentText("Airplane Mode Detected! Unlock to stop siren.")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setFullScreenIntent(fullScreenPendingIntent, true) // Forcing screen open
                .setAutoCancel(true)
                .setOngoing(true);

        if (notificationManager != null) {
            try {
                // Try direct activity start first
                context.startActivity(theftIntent);
            } catch (Exception e) {
                Log.e(TAG, "Activity launch blocked by OS, relying on Notification Intent.");
            }
            // Fire the notification which FORCES the activity to show on top
            notificationManager.notify(NOTIF_ID, builder.build());
        }
    }
}