package com.hfs.security.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.telephony.SmsManager;
import android.util.Log;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Advanced Alert & SMS Transmission Utility.
 * UPDATED for Google Drive Integration:
 * 1. Includes Google Drive shareable link in the alert content.
 * 2. Implements "Pending Upload" status for offline scenarios.
 * 3. Strictly follows the 3-msg/5-min cooldown and +91 formatting rules.
 */
public class SmsHelper {

    private static final String TAG = "HFS_SmsHelper";
    private static final String PREF_SMS_LIMITER = "hfs_sms_limiter_prefs";
    private static final long WINDOW_MS = 5 * 60 * 1000; // 5 Minutes
    private static final int MAX_MSGS = 3; // Exactly 3 messages limit

    /**
     * Sends a detailed security alert SMS with Cloud Drive and Map links.
     * 
     * @param context App context.
     * @param targetApp Name of the app triggered.
     * @param mapLink Google Maps URL.
     * @param alertType "Face Mismatch" or "Fingerprint Failure".
     * @param driveLink The shareable link to the photo (null if offline).
     */
    public static void sendAlertSms(Context context, String targetApp, String mapLink, String alertType, String driveLink) {
        
        // 1. VERIFY COOLDOWN STATUS (3 msgs / 5 mins)
        if (!isSmsAllowed(context)) {
            Log.w(TAG, "SMS Limit Reached: Alert suppressed to prevent carrier block.");
            return;
        }

        HFSDatabaseHelper db = HFSDatabaseHelper.getInstance(context);
        String savedNumber = db.getTrustedNumber();

        if (savedNumber == null || savedNumber.isEmpty()) {
            Log.e(TAG, "SMS Failure: No trusted number set in settings.");
            return;
        }

        // 2. INTERNATIONAL FORMATTING (+91 Fix)
        String finalRecipient = formatInternationalNumber(savedNumber);

        // 3. CONSTRUCT ENHANCED ALERT TEXT (Strict Format)
        String time = new SimpleDateFormat("dd-MMM HH:mm", Locale.getDefault()).format(new Date());
        
        StringBuilder smsBody = new StringBuilder();
        smsBody.append("⚠ HFS SECURITY ALERT\n");
        smsBody.append("Breach: ").append(alertType).append("\n");
        smsBody.append("App: ").append(targetApp).append("\n");
        smsBody.append("Time: ").append(time).append("\n");

        // Map Link Logic
        if (mapLink != null && !mapLink.isEmpty()) {
            smsBody.append("Map: ").append(mapLink).append("\n");
        } else {
            smsBody.append("Map: GPS signal pending\n");
        }

        // Google Drive Link Logic
        if (driveLink != null && !driveLink.isEmpty()) {
            smsBody.append("Drive: ").append(driveLink);
        } else {
            // As per instruction: Show 'Pending Upload' if offline
            smsBody.append("Drive: Pending Upload");
        }

        // 4. EXECUTE SEND
        try {
            SmsManager smsManager;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                smsManager = context.getSystemService(SmsManager.class);
            } else {
                smsManager = SmsManager.getDefault();
            }

            if (smsManager != null) {
                // Divide message into parts to ensure delivery of long URLs
                java.util.ArrayList<String> parts = smsManager.divideMessage(smsBody.toString());
                smsManager.sendMultipartTextMessage(finalRecipient, null, parts, null, null);
                
                Log.i(TAG, "Full Cloud Alert sent to: " + finalRecipient);
                
                // 5. UPDATE COOLDOWN COUNTER
                trackSmsSent(context);
            }
        } catch (Exception e) {
            Log.e(TAG, "Carrier Block: Failed to deliver external SMS: " + e.getMessage());
        }
    }

    /**
     * Normalizes the phone number to bypass carrier routing blocks.
     */
    private static String formatInternationalNumber(String number) {
        String clean = number.replaceAll("[^\\d]", "");
        
        // If the number doesn't start with '+', we prepend the standard code
        if (!number.startsWith("+")) {
            if (clean.length() == 10) {
                return "+91" + clean;
            }
        }
        return number.startsWith("+") ? number : "+" + number;
    }

    /**
     * Logic: Implements the 3-msg/5-min safety window.
     */
    private static boolean isSmsAllowed(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_SMS_LIMITER, Context.MODE_PRIVATE);
        long windowStart = prefs.getLong("start_time", 0);
        int currentCount = prefs.getInt("msg_count", 0);
        long now = System.currentTimeMillis();

        if (now - windowStart > WINDOW_MS) {
            prefs.edit().putLong("start_time", now).putInt("msg_count", 0).apply();
            return true;
        }

        return currentCount < MAX_MSGS;
    }

    private static void trackSmsSent(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_SMS_LIMITER, Context.MODE_PRIVATE);
        int count = prefs.getInt("msg_count", 0);
        prefs.edit().putInt("msg_count", count + 1).apply();
    }

    /**
     * Internal Placeholder for future MMS Photo Packaging.
     */
    public static void sendMmsPhoto(Context context, File image) {
        if (image == null || !image.exists()) return;
        Log.d(TAG, "MMS Queue: Intruder photo detected, ready for packaging.");
    }
}