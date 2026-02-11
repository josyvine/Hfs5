package com.hfs.security.receivers;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import android.widget.Toast; 

import androidx.core.app.NotificationCompat;

import com.hfs.security.R;
import com.hfs.security.ui.SplashActivity;
import com.hfs.security.utils.HFSDatabaseHelper;

/**
 * Advanced Stealth Mode Trigger (Oppo/Realme Background Fix).
 * FIXED: 
 * 1. Uses High-Priority FullScreen Intent to bypass Oppo background activity blocks.
 * 2. Added USSD Support: Recognizes PIN, *#PIN#, and #PIN#.
 * 3. Includes the requested Toast confirmation.
 */
public class StealthLaunchReceiver extends BroadcastReceiver {

    private static final String TAG = "HFS_StealthTrigger";
    private static final String CHANNEL_ID = "hfs_stealth_launch_channel";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        
        if (action != null && action.equals(Intent.ACTION_NEW_OUTGOING_CALL)) {
            
            // 1. Get the raw number dialed by the user
            String dialedNumber = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER);
            if (dialedNumber == null) return;

            // 2. Get the CUSTOM PIN from your app settings
            HFSDatabaseHelper db = HFSDatabaseHelper.getInstance(context);
            String savedPin = db.getMasterPin(); 

            if (savedPin == null || savedPin.isEmpty()) return;

            // 3. Normalize strings for comparison (remove spaces/dashes)
            String cleanDialed = dialedNumber.trim();
            String cleanSaved = savedPin.trim();

            // 4. USSD & RAW PIN LOGIC
            // Checks if the dialed string is exactly the PIN, or *#PIN#, or #PIN#
            boolean isMatch = cleanDialed.equals(cleanSaved) || 
                              cleanDialed.equals("*#" + cleanSaved + "#") || 
                              cleanDialed.equals("#" + cleanSaved + "#");

            if (isMatch) {
                Log.i(TAG, "Security PIN Verified: " + cleanDialed);

                // 5. USER REQUEST: Immediate Toast Confirmation
                Toast.makeText(context, "HFS Security: PIN Verified. Opening...", Toast.LENGTH_LONG).show();

                // 6. ABORT THE CALL
                // This stops the Oppo system from actually placing the call
                setResultData(null);
                abortBroadcast();

                // 7. OPPO BACKGROUND BYPASS: Launch via FullScreen Intent Notification
                // This is the highest priority launch possible in Android.
                launchAppHighPriority(context);
            }
        }
    }

    /**
     * Creates a high-priority 'Full Screen Intent' to force the app to the front.
     * This bypasses the background activity restrictions found in Oppo ColorOS.
     */
    private void launchAppHighPriority(Context context) {
        Intent launchIntent = new Intent(context, SplashActivity.class);
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        // Create a PendingIntent for the launch
        PendingIntent fullScreenPendingIntent = PendingIntent.getActivity(
                context, 0, launchIntent, 
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        // Create Notification Channel for Android 8.0+ (Oppo Android 9+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "HFS Stealth Launch", 
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }

        // Build the notification with FullScreen Intent
        // Note: Using a transparent/hidden style so it just opens the app
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.hfs)
                .setContentTitle("HFS Security")
                .setContentText("Identity Verified")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(fullScreenPendingIntent, true) // THE KEY TO BYPASS BLOCKS
                .setAutoCancel(true);

        if (notificationManager != null) {
            notificationManager.notify(3003, builder.build());
            
            // Fallback: Also try direct launch just in case
            context.startActivity(launchIntent);
        }
    }
}