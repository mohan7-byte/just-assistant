package com.mohan7byte.justassistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class SecurePrefs {
    private static final String PREFS = "just_assistant";
    private static final String KEY_API = "api_key";
    private static final String LEGACY_API = "api_ciphertext";
    private static final String LEGACY_IV = "api_iv";
    private static final String LEGACY_ALIAS = "just_assistant_api_key_v1";
    private static final String KEY_MODEL = "model";
    private static final String KEY_PERSONA = "persona";
    private static final String KEY_RUNNING = "running";

    private SecurePrefs() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void saveModel(Context c, String value) {
        p(c).edit().putString(KEY_MODEL, value).commit();
    }

    public static String getModel(Context c) {
        return p(c).getString(KEY_MODEL, "gemini-3.8-live");
    }

    public static void savePersona(Context c, String value) {
        p(c).edit().putString(KEY_PERSONA, value).commit();
    }

    public static String getPersona(Context c) {
        return p(c).getString(KEY_PERSONA,
                "You are a helpful voice assistant. Be concise, natural, and conversational.");
    }

    public static void saveRunning(Context c, boolean running) {
        p(c).edit().putBoolean(KEY_RUNNING, running).commit();
    }

    public static boolean isRunning(Context c) {
        return p(c).getBoolean(KEY_RUNNING, false);
    }

    public static void saveApiKey(Context c, String apiKey) {
        p(c).edit().putString(KEY_API, apiKey == null ? "" : apiKey.trim()).commit();
    }

    public static String getApiKey(Context c) {
        String current = p(c).getString(KEY_API, "");
        if (current != null && !current.trim().isEmpty()) {
            return current.trim();
        }

        String migrated = tryReadLegacyApiKey(c);
        if (!migrated.isEmpty()) {
            saveApiKey(c, migrated);
            return migrated;
        }
        return "";
    }

    private static String tryReadLegacyApiKey(Context c) {
        String encryptedText = p(c).getString(LEGACY_API, null);
        String ivText = p(c).getString(LEGACY_IV, null);
        if (encryptedText == null || ivText == null) return "";

        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (!ks.containsAlias(LEGACY_ALIAS)) return "";

            KeyStore.Entry entry = ks.getEntry(LEGACY_ALIAS, null);
            if (!(entry instanceof KeyStore.SecretKeyEntry)) return "";

            SecretKey key = ((KeyStore.SecretKeyEntry) entry).getSecretKey();
            byte[] iv = Base64.decode(ivText, Base64.NO_WRAP);
            byte[] encrypted = Base64.decode(encryptedText, Base64.NO_WRAP);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8).trim();
        } catch (Exception ignored) {
            return "";
        }
    }
}
