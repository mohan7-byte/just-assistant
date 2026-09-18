package com.mohan7byte.justassistant;

import android.content.Context;
import android.content.SharedPreferences;

public final class SecurePrefs {
    private static final String PREFS = "just_assistant";
    private static final String KEY_API = "api_key";
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
        if (apiKey == null) apiKey = "";
        p(c).edit().putString(KEY_API, apiKey.trim()).commit();
    }

    public static String getApiKey(Context c) {
        return p(c).getString(KEY_API, "");
    }
}
