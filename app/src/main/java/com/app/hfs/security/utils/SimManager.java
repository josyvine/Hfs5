package com.hfs.security.utils;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telephony.SmsManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import java.util.List;

/**
 * Handles Dual SIM Detection and "Survivor" Routing for Anti-Theft alerts.
 */
public class SimManager {

    private static final String TAG = "HFS_SimManager";
    private final Context context;
    private final HFSDatabaseHelper db;
    private final CryptoManager cryptoManager;
    private final SubscriptionManager subscriptionManager;

    public SimManager(Context context) {
        this.context = context;
        this.db = HFSDatabaseHelper.getInstance(context);
        this.cryptoManager = new CryptoManager();
        this.subscriptionManager = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
    }

    /**
     * Checks if the app has the required permissions to read SIM hardware IDs.
     */
    public boolean hasPhoneStatePermission(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ requires READ_PHONE_NUMBERS for deep SIM info
            return ActivityCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED &&
                   ActivityCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ActivityCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;
        }
    }

    /**
     * Helper to request permissions from an Activity context (Used by SettingsFragment).
     */
    public void requestPhoneStatePermission(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ActivityCompat.requestPermissions(activity, new String[]{
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.READ_PHONE_NUMBERS
            }, 105);
        } else {
            ActivityCompat.requestPermissions(activity, new String[]{
                    Manifest.permission.READ_PHONE_STATE
            }, 105);
        }
    }

    /**
     * Scans the currently inserted SIM cards, encrypts their ICCIDs, 
     * and saves them as the "Trusted" baseline in the database.
     */
    public void scanAndEncryptCurrentSims() {
        if (!hasPhoneStatePermission(context)) {
            Log.e(TAG, "Cannot scan SIMs: Missing permissions.");
            return;
        }

        try {
            List<SubscriptionInfo> activeSims = subscriptionManager.getActiveSubscriptionInfoList();
            
            // Clear existing slots first
            db.saveEncryptedIccid(0, null);
            db.saveEncryptedIccid(1, null);

            if (activeSims != null) {
                for (SubscriptionInfo simInfo : activeSims) {
                    int slotIndex = simInfo.getSimSlotIndex();
                    
                    // Android 10+ restricts raw ICCID access for 3rd party apps. 
                    // We use getSubscriptionId() as a reliable, unique hardware proxy.
                    String uniqueHardwareId = String.valueOf(simInfo.getSubscriptionId());

                    // Encrypt the ID so it cannot be read from the XML
                    String encryptedId = cryptoManager.encrypt(uniqueHardwareId);
                    
                    // Save to corresponding slot
                    db.saveEncryptedIccid(slotIndex, encryptedId);
                    Log.i(TAG, "Secured SIM in Slot " + slotIndex);
                }
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Security Exception reading SIMs: " + e.getMessage());
        }
    }

    /**
     * Used by SimStateReceiver to check if the currently inserted SIMs 
     * match the ones saved by the owner in the vault.
     * 
     * @return true if an alien SIM is found or a trusted SIM is missing. false if everything is safe.
     */
    public boolean isSimBreachDetected() {
        if (!hasPhoneStatePermission(context)) return false;

        try {
            List<SubscriptionInfo> activeSims = subscriptionManager.getActiveSubscriptionInfoList();
            
            String trustedSimSlot0 = db.getEncryptedIccid(0);
            String trustedSimSlot1 = db.getEncryptedIccid(1);
            
            // If the user hasn't set up the vault yet, don't trigger false alarms
            if (trustedSimSlot0 == null && trustedSimSlot1 == null) return false;

            // Flag to track if we found at least one trusted SIM
            boolean foundTrustedSim = false;

            if (activeSims != null) {
                for (SubscriptionInfo simInfo : activeSims) {
                    String currentHardwareId = String.valueOf(simInfo.getSubscriptionId());
                    
                    // Check against Slot 0
                    if (trustedSimSlot0 != null) {
                        String decryptedSlot0 = cryptoManager.decrypt(trustedSimSlot0);
                        if (currentHardwareId.equals(decryptedSlot0)) {
                            foundTrustedSim = true;
                            continue;
                        }
                    }
                    
                    // Check against Slot 1
                    if (trustedSimSlot1 != null) {
                        String decryptedSlot1 = cryptoManager.decrypt(trustedSimSlot1);
                        if (currentHardwareId.equals(decryptedSlot1)) {
                            foundTrustedSim = true;
                            continue;
                        }
                    }
                    
                    // If the current SIM doesn't match Slot 0 OR Slot 1, it's an Alien SIM!
                    Log.w(TAG, "ALIEN SIM DETECTED. Breach Confirmed.");
                    return true;
                }
            }

            // If we checked all active SIMs and didn't find ANY of our trusted ones, 
            // it means the thief pulled our SIMs out.
            if (!foundTrustedSim && (trustedSimSlot0 != null || trustedSimSlot1 != null)) {
                Log.w(TAG, "TRUSTED SIM REMOVED. Breach Confirmed.");
                return true;
            }

        } catch (SecurityException e) {
            Log.e(TAG, "Failed to verify SIM state: " + e.getMessage());
        }

        return false;
    }

    /**
     * Dual SIM "Survivor" Routing.
     * Called by SmsHelper to find the best pipe to send the SMS out of.
     * If Slot 1 is removed, it automatically returns the SmsManager for Slot 2.
     * 
     * @return SmsManager for the active SIM, or null if no SIM is inserted.
     */
    public SmsManager getBestSmsManager() {
        if (!hasPhoneStatePermission(context)) {
            // Fallback to default if we lack permissions to route intelligently
            return SmsManager.getDefault();
        }

        try {
            List<SubscriptionInfo> activeSims = subscriptionManager.getActiveSubscriptionInfoList();
            
            if (activeSims != null && !activeSims.isEmpty()) {
                // Grab the very first active SIM available and force the SMS through it
                int activeSubscriptionId = activeSims.get(0).getSubscriptionId();
                Log.i(TAG, "Routing SMS through Subscription ID: " + activeSubscriptionId);
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    return context.getSystemService(SmsManager.class).createForSubscriptionId(activeSubscriptionId);
                } else {
                    return SmsManager.getSmsManagerForSubscriptionId(activeSubscriptionId);
                }
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to route Dual SIM SMS: " + e.getMessage());
        }

        // Return null if NO SIMs are available (triggers the Offline Queue Time Bomb in SmsHelper)
        return null;
    }
}