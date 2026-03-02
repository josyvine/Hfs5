package com.hfs.security.utils;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Advanced Alert & SMS Transmission Utility.
 * UPDATED for Google Drive Integration & Anti-Theft:
 * 1. Includes Google Drive shareable link in the alert content.
 * 2. Implements "Pending Upload" status for offline scenarios.
 * 3. Strictly follows the 3-msg/5-min cooldown and +91 formatting rules.
 * 4. Decrypts emergency number securely in memory.
 * 5. Uses SimManager for Dual SIM Routing (Survivor Logic).
 * 6. Queues message if no SIM is available (Time Bomb Trap).
 * 7. Visual feedback via Toasts for offline queuing status.
 * 8. NEW: Extracts Intruder's Phone Number from the active SIM card.
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
     * @param alertType "Face Mismatch", "SIM CARD REMOVED", "AIRPLANE MODE ACTIVATED".
     * @param driveLink The shareable link to the photo (null if offline).
     */
    public static void sendAlertSms(Context context, String targetApp, String mapLink, String alertType, String driveLink) {
        
        // 1. VERIFY COOLDOWN STATUS (3 msgs / 5 mins)
        if (!isSmsAllowed(context)) {
            Log.w(TAG, "SMS Limit Reached: Alert suppressed to prevent carrier block.");
            return;
        }

        HFSDatabaseHelper db = HFSDatabaseHelper.getInstance(context);
        
        // --- SECURE NUMBER DECRYPTION ---
        // Decrypts your saved Emergency Number from the hardware vault.
        String savedNumber = db.getEncryptedEmergencyNumber();
        if (savedNumber != null && !savedNumber.isEmpty()) {
            CryptoManager cryptoManager = new CryptoManager();
            savedNumber = cryptoManager.decrypt(savedNumber);
        } else {
            // Legacy fallback if the user hasn't set up the new encrypted UI yet
            savedNumber = db.getTrustedNumber();
        }

        if (savedNumber == null || savedNumber.isEmpty()) {
            Log.e(TAG, "SMS Failure: No trusted number set in settings.");
            return;
        }

        // 2. INTERNATIONAL FORMATTING (+91 Fix)
        String finalRecipient = formatInternationalNumber(savedNumber);

        // 3. CONSTRUCT ENHANCED ALERT TEXT (Strict Format)
        String time = new SimpleDateFormat("dd-MMM HH:mm", Locale.getDefault()).format(new Date());
        
        // --- NEW: ATTEMPT TO EXTRACT INTRUDER'S NUMBER ---
        String intruderNumberInfo = getIntruderPhoneNumber(context);

        StringBuilder smsBody = new StringBuilder();
        smsBody.append("⚠ HFS SECURITY ALERT\n");
        smsBody.append("Breach: ").append(alertType).append("\n");
        smsBody.append("App: ").append(targetApp).append("\n");
        
        // Append Intruder Number if found
        if (!intruderNumberInfo.isEmpty()) {
            smsBody.append(intruderNumberInfo).append("\n");
        }

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

        // 4. EXECUTE SEND WITH DUAL SIM ROUTING
        try {
            // DUAL SIM "SURVIVOR" ROUTING
            // Checks Slot 0 and Slot 1 to find a working cellular path.
            SimManager simManager = new SimManager(context);
            SmsManager smsManager = simManager.getBestSmsManager();

            if (smsManager != null) {
                // We have a working SIM! Send the message.
                java.util.ArrayList<String> parts = smsManager.divideMessage(smsBody.toString());
                smsManager.sendMultipartTextMessage(finalRecipient, null, parts, null, null);
                
                Log.i(TAG, "Full Cloud Alert sent to: " + finalRecipient);
                
                // UPDATE COOLDOWN COUNTER
                trackSmsSent(context);
            } else {
                // THE TIME BOMB QUEUE
                // Hardware radio is dead (Airplane Mode or No SIM). Store the alert.
                db.savePendingMessage(smsBody.toString());
                Log.w(TAG, "No SIM available. SMS queued for auto-send.");

                // VISUAL FEEDBACK: Confirm to user that the trap is set.
                showToastOnMainThread(context, "Network Unavailable: Alert Queued for Auto-Send");
            }
        } catch (Exception e) {
            Log.e(TAG, "Carrier Block or SIM Error: Failed to deliver: " + e.getMessage());
            // If it failed due to some other hardware error, queue it just in case
            db.savePendingMessage(smsBody.toString());
            showToastOnMainThread(context, "SIM Error: Alert Queued for Recovery");
        }
    }

    /**
     * Executes the "Time Bomb" trap.
     * Triggered by SimStateReceiver or AirplaneModeReceiver when connectivity returns.
     */
    public static void sendQueuedMessage(Context context) {
        HFSDatabaseHelper db = HFSDatabaseHelper.getInstance(context);
        
        if (!db.hasPendingMessage()) return;

        String queuedMessage = db.getPendingMessage();
        if (queuedMessage == null || queuedMessage.isEmpty()) return;

        String savedNumber = db.getEncryptedEmergencyNumber();
        if (savedNumber != null && !savedNumber.isEmpty()) {
            CryptoManager cryptoManager = new CryptoManager();
            savedNumber = cryptoManager.decrypt(savedNumber);
        } else {
            savedNumber = db.getTrustedNumber();
        }

        if (savedNumber == null || savedNumber.isEmpty()) return;

        String finalRecipient = formatInternationalNumber(savedNumber);

        try {
            SimManager simManager = new SimManager(context);
            SmsManager smsManager = simManager.getBestSmsManager();

            if (smsManager != null) {
                java.util.ArrayList<String> parts = smsManager.divideMessage(queuedMessage);
                smsManager.sendMultipartTextMessage(finalRecipient, null, parts, null, null);
                
                Log.i(TAG, "TRAP SPRUNG: Queued alert transmitted successfully.");
                
                // Clear the vault queue
                db.clearPendingMessage();
                showToastOnMainThread(context, "Queued Security Alert Transmitted");
            }
        } catch (Exception e) {
            Log.e(TAG, "Retry failed: " + e.getMessage());
        }
    }

    /**
     * NEW: Extracts the phone number from the currently inserted SIM card.
     * This is useful to identify the intruder if they inserted their own SIM.
     */
    private static String getIntruderPhoneNumber(Context context) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return "";
        }

        // On Android 11+, we also need READ_PHONE_NUMBERS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_NUMBERS) != PackageManager.PERMISSION_GRANTED) {
                return "";
            }
        }

        StringBuilder info = new StringBuilder();
        try {
            SubscriptionManager sm = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
            List<SubscriptionInfo> activeSims = sm.getActiveSubscriptionInfoList();

            if (activeSims != null && !activeSims.isEmpty()) {
                for (SubscriptionInfo sim : activeSims) {
                    // Try to get the number stored on the SIM card
                    String number = sim.getNumber();
                    if (number != null && !number.isEmpty()) {
                        info.append("Intruder SIM: ").append(number).append(" ");
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not extract Intruder SIM number: " + e.getMessage());
        }
        return info.toString();
    }

    /**
     * Helper to show Toasts correctly even when called from background threads.
     */
    private static void showToastOnMainThread(Context context, String message) {
        new Handler(Looper.getMainLooper()).post(() -> 
            Toast.makeText(context, "HFS: " + message, Toast.LENGTH_LONG).show()
        );
    }

    /**
     * Normalizes the phone number to bypass carrier routing blocks.
     */
    private static String formatInternationalNumber(String number) {
        String clean = number.replaceAll("[^\\d]", "");
        
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