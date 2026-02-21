package com.hfs.security.utils;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.Set;

/**
 * Manages local persistent storage for HFS Security.
 * UPDATED: Added KEY_PHONE_PROTECTION for the new System Unlock Ambush feature.
 */
public class HFSDatabaseHelper {

    private static final String PREF_NAME = "hfs_security_prefs";
    
    // Core Security Keys
    private static final String KEY_PROTECTED_PACKAGES = "protected_packages";
    private static final String KEY_MASTER_PIN = "master_pin";
    private static final String KEY_TRUSTED_NUMBER = "trusted_number";
    private static final String KEY_SETUP_COMPLETE = "setup_complete";
    private static final String KEY_STEALTH_MODE = "stealth_mode_enabled";
    private static final String KEY_FAKE_GALLERY = "fake_gallery_enabled";
    private static final String KEY_OWNER_FACE_DATA = "owner_face_template";

    // NEW: System Lock Screen Protection Key
    private static final String KEY_PHONE_PROTECTION = "phone_protection_enabled";

    // Google Drive Cloud Sync Keys
    private static final String KEY_DRIVE_ENABLED = "drive_sync_enabled";
    private static final String KEY_GOOGLE_ACCOUNT = "google_account_email";
    private static final String KEY_DRIVE_FOLDER_ID = "google_drive_folder_id";

    private static HFSDatabaseHelper instance;
    private final SharedPreferences prefs;
    private final Gson gson;

    private HFSDatabaseHelper(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        gson = new Gson();
    }

    public static synchronized HFSDatabaseHelper getInstance(Context context) {
        if (instance == null) {
            instance = new HFSDatabaseHelper(context.getApplicationContext());
        }
        return instance;
    }

    // --- SYSTEM PHONE UNLOCK PROTECTION (NEW) ---

    public void setPhoneProtectionEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_PHONE_PROTECTION, enabled).apply();
    }

    public boolean isPhoneProtectionEnabled() {
        return prefs.getBoolean(KEY_PHONE_PROTECTION, false);
    }

    // --- GOOGLE DRIVE / CLOUD SETTINGS ---

    public void setDriveEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_DRIVE_ENABLED, enabled).apply();
    }

    public boolean isDriveEnabled() {
        return prefs.getBoolean(KEY_DRIVE_ENABLED, false);
    }

    public void saveGoogleAccount(String email) {
        prefs.edit().putString(KEY_GOOGLE_ACCOUNT, email).apply();
    }

    public String getGoogleAccount() {
        return prefs.getString(KEY_GOOGLE_ACCOUNT, null);
    }

    public void saveDriveFolderId(String folderId) {
        prefs.edit().putString(KEY_DRIVE_FOLDER_ID, folderId).apply();
    }

    public String getDriveFolderId() {
        return prefs.getString(KEY_DRIVE_FOLDER_ID, null);
    }

    // --- PROTECTED APPS STORAGE ---

    public void saveProtectedPackages(Set<String> packages) {
        String json = gson.toJson(packages);
        prefs.edit().putString(KEY_PROTECTED_PACKAGES, json).apply();
    }

    public Set<String> getProtectedPackages() {
        String json = prefs.getString(KEY_PROTECTED_PACKAGES, null);
        if (json == null) {
            return new HashSet<>();
        }
        Type type = new TypeToken<HashSet<String>>() {}.getType();
        return gson.fromJson(json, type);
    }

    public int getProtectedAppsCount() {
        return getProtectedPackages().size();
    }

    // --- SECURITY CREDENTIALS ---

    public void saveMasterPin(String pin) {
        prefs.edit().putString(KEY_MASTER_PIN, pin).apply();
    }

    public String getMasterPin() {
        return prefs.getString(KEY_MASTER_PIN, "0000");
    }

    public void saveTrustedNumber(String number) {
        prefs.edit().putString(KEY_TRUSTED_NUMBER, number).apply();
    }

    public String getTrustedNumber() {
        return prefs.getString(KEY_TRUSTED_NUMBER, "");
    }

    // --- APP SETUP STATUS ---

    public boolean isSetupComplete() {
        boolean flag = prefs.getBoolean(KEY_SETUP_COMPLETE, false);
        String pin = getMasterPin();
        return flag && !pin.equals("0000") && !pin.isEmpty();
    }

    public void setSetupComplete(boolean status) {
        prefs.edit().putBoolean(KEY_SETUP_COMPLETE, status).apply();
    }

    // --- FEATURE TOGGLES ---

    public void setStealthMode(boolean enabled) {
        prefs.edit().putBoolean(KEY_STEALTH_MODE, enabled).apply();
    }

    public boolean isStealthModeEnabled() {
        return prefs.getBoolean(KEY_STEALTH_MODE, false);
    }

    public void setFakeGalleryEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_FAKE_GALLERY, enabled).apply();
    }

    public boolean isFakeGalleryEnabled() {
        return prefs.getBoolean(KEY_FAKE_GALLERY, false);
    }

    // --- LEGACY DATA ---

    public void saveOwnerFaceData(String faceData) {
        prefs.edit().putString(KEY_OWNER_FACE_DATA, faceData).apply();
    }

    public String getOwnerFaceData() {
        return prefs.getString(KEY_OWNER_FACE_DATA, "");
    }

    public void clearDatabase() {
        prefs.edit().clear().apply();
    }
}