package com.mohan7byte.justassistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class SecurePrefs {
    private static final String PREFS = "just_assistant";
    private static final String KEY_ALIAS = "just_assistant_api_key_v1";
    private static final String KEY_API = "api_ciphertext";
    private static final String KEY_IV = "api_iv";
    private static final String KEY_MODEL = "model";
    private static final String KEY_PERSONA = "persona";
    private static final String KEY_RUNNING = "running";

    private SecurePrefs() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void saveModel(Context c, String value) {
        p(c).edit().putString(KEY_MODEL, value).apply();
    }

    public static String getModel(Context c) {
        return p(c).getString(KEY_MODEL, "gemini-3.8-live");
    }

    public static void savePersona(Context c, String value) {
        p(c).edit().putString(KEY_PERSONA, value).apply();
    }

    public static String getPersona(Context c) {
        return p(c).getString(KEY_PERSONA,
                "You are a helpful voice assistant. Be concise, natural, and conversational.");
    }

    public static void saveRunning(Context c, boolean running) {
        p(c).edit().putBoolean(KEY_RUNNING, running).apply();
    }

    public static boolean isRunning(Context c) {
        return p(c).getBoolean(KEY_RUNNING, false);
    }

    public static void saveApiKey(Context c, String apiKey) {
        try {
            SecretKey key = getOrCreateKey();
            byte[] iv = new byte[12];
            java.security.SecureRandom random = new java.security.SecureRandom();
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(apiKey.getBytes(StandardCharsets.UTF_8));

            p(c).edit()
                    .putString(KEY_API, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .putString(KEY_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                    .apply();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to protect API key", e);
        }
    }

    public static String getApiKey(Context c) {
        String cipherText = p(c).getString(KEY_API, null);
        String ivText = p(c).getString(KEY_IV, null);
        if (cipherText == null || ivText == null) return "";

        try {
            SecretKey key = getOrCreateKey();
            byte[] iv = Base64.decode(ivText, Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(cipherText, Base64.NO_WRAP);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
        }

        KeyGenerator generator = KeyGenerator.getInstance(
                "AES", "AndroidKeyStore");
        generator.init(new android.security.keystore.KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT
                        | android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }
}
