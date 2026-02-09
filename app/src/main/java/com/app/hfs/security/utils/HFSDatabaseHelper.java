package com.hfs.security.utils;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.Set;

/**
 * Manages local persistent storage for HFS - Hybrid File Security.
 * Handles the storage and retrieval of:
 * 1) Selected Protected App package names.
 * 2) Master Security PIN.
 * 3) Trusted Secondary Phone Number.
 * 4) Feature states (Stealth Mode, Fake Gallery).
 */
public class HFSDatabaseHelper {

    private static final String PREF_NAME = "hfs_security_prefs";
    
    // Keys for SharedPreferences
    private static final String KEY_PROTECTED_PACKAGES = "protected_packages";
    private static final String KEY_MASTER_PIN = "master_pin";
    private static final String KEY_TRUSTED_NUMBER = "trusted_number";
    private static final String KEY_SETUP_COMPLETE = "setup_complete";
    private static final String KEY_STEALTH_MODE = "stealth_mode_enabled";
    private static final String KEY_FAKE_GALLERY = "fake_gallery_enabled";
    private static final String KEY_OWNER_FACE_DATA = "owner_face_template";

    private static HFSDatabaseHelper instance;
    private final SharedPreferences prefs;
    private final Gson gson;

    /**
     * Private constructor for Singleton pattern.
     */
    private HFSDatabaseHelper(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        gson = new Gson();
    }

    /**
     * Returns a thread-safe singleton instance of the database helper.
     */
    public static synchronized HFSDatabaseHelper getInstance(Context context) {
        if (instance == null) {
            instance = new HFSDatabaseHelper(context.getApplicationContext());
        }
        return instance;
    }

    // --- PROTECTED APPS STORAGE ---

    /**
     * Saves the set of package names (e.g., com.whatsapp) that should trigger the lock.
     */
    public void saveProtectedPackages(Set<String> packages) {
        String json = gson.toJson(packages);
        prefs.edit().putString(KEY_PROTECTED_PACKAGES, json).apply();
    }

    /**
     * Retrieves the set of currently protected package names.
     */
    public Set<String> getProtectedPackages() {
        String json = prefs.getString(KEY_PROTECTED_PACKAGES, null);
        if (json == null) {
            return new HashSet<>();
        }
        Type type = new TypeToken<HashSet<String>>() {}.getType();
        return gson.fromJson(json, type);
    }

    /**
     * Returns the total number of apps currently under HFS protection.
     */
    public int getProtectedAppsCount() {
        return getProtectedPackages().size();
    }

    // --- SECURITY CREDENTIALS ---

    public void saveMasterPin(String pin) {
        prefs.edit().putString(KEY_MASTER_PIN, pin).apply();
    }

    public String getMasterPin() {
        // Default PIN is 0000 if not set
        return prefs.getString(KEY_MASTER_PIN, "0000");
    }

    public void saveTrustedNumber(String number) {
        prefs.edit().putString(KEY_TRUSTED_NUMBER, number).apply();
    }

    public String getTrustedNumber() {
        return prefs.getString(KEY_TRUSTED_NUMBER, "");
    }

    // --- APP SETUP STATUS ---

    /**
     * Returns true if the user has completed the Face & PIN registration.
     */
    public boolean isSetupComplete() {
        return prefs.getBoolean(KEY_SETUP_COMPLETE, false);
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

    // --- FACE DATA STORAGE ---

    /**
     * Saves the owner's face biometric template as a String/JSON.
     */
    public void saveOwnerFaceData(String faceData) {
        prefs.edit().putString(KEY_OWNER_FACE_DATA, faceData).apply();
    }

    public String getOwnerFaceData() {
        return prefs.getString(KEY_OWNER_FACE_DATA, "");
    }

    /**
     * Completely resets the app settings.
     */
    public void clearDatabase() {
        prefs.edit().clear().apply();
    }
}