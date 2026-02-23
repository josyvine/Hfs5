package com.hfs.security.utils;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Handles Hardware-Backed Encryption for Anti-Theft features.
 * Uses AES-256-GCM via the Android Keystore System.
 * Ensures the Emergency Number and SIM ICCIDs are never stored in plain text.
 */
public class CryptoManager {

    private static final String TAG = "HFS_CryptoManager";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "hfs_anti_theft_key";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    public CryptoManager() {
        // Ensure the secure hardware key exists when this manager is initialized
        try {
            initKeyStore();
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize Keystore: " + e.getMessage());
        }
    }

    /**
     * Creates a new AES-256 key inside the secure hardware if one does not already exist.
     */
    private void initKeyStore() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);
        
        if (!keyStore.containsAlias(ALIAS)) {
            KeyGenerator keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
            KeyGenParameterSpec keySpec = new KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build();
            keyGenerator.init(keySpec);
            keyGenerator.generateKey();
            Log.i(TAG, "New secure hardware key generated successfully.");
        }
    }

    /**
     * Retrieves the secret key from the Android Keystore.
     */
    private SecretKey getSecretKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);
        return ((KeyStore.SecretKeyEntry) keyStore.getEntry(ALIAS, null)).getSecretKey();
    }

    /**
     * Encrypts plain text (like a phone number or SIM ICCID) into a secure Base64 string.
     * 
     * @param plainText The raw string to encrypt.
     * @return The encrypted string in format "IV:CipherText", or null if it fails.
     */
    public String encrypt(String plainText) {
        if (plainText == null || plainText.isEmpty()) return null;

        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, getSecretKey());

            byte[] iv = cipher.getIV();
            byte[] encryptedBytes = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            // We must store the IV (Initialization Vector) alongside the encrypted data to decrypt it later
            String ivString = Base64.encodeToString(iv, Base64.NO_WRAP);
            String cipherString = Base64.encodeToString(encryptedBytes, Base64.NO_WRAP);

            return ivString + ":" + cipherString;

        } catch (Exception e) {
            Log.e(TAG, "Encryption failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Decrypts a secure Base64 string back into plain text.
     * 
     * @param encryptedData The string in format "IV:CipherText".
     * @return The original plain text string, or null if it fails.
     */
    public String decrypt(String encryptedData) {
        if (encryptedData == null || !encryptedData.contains(":")) return null;

        try {
            String[] parts = encryptedData.split(":");
            if (parts.length != 2) return null;

            byte[] iv = Base64.decode(parts[0], Base64.NO_WRAP);
            byte[] cipherText = Base64.decode(parts[1], Base64.NO_WRAP);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec spec = new GCMParameterSpec(128, iv);
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec);

            byte[] decodedBytes = cipher.doFinal(cipherText);
            return new String(decodedBytes, StandardCharsets.UTF_8);

        } catch (Exception e) {
            Log.e(TAG, "Decryption failed: " + e.getMessage());
            return null;
        }
    }
}