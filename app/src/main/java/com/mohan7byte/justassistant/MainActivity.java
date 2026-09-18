package com.mohan7byte.justassistant;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_PERMISSIONS = 10;
    private static final String[] LIVE_MODELS = {
            "gemini-3.8-live",
            "gemini-3.8-live-extended-thinking",
            "gemini-3.1-flash-live-preview",
            "gemini-3.5-live-translate-preview"
    };

    private EditText apiKey;
    private AutoCompleteTextView model;
    private EditText persona;
    private Button startStop;
    private TextView status;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            getSharedPreferences("just_assistant_crash", MODE_PRIVATE)
                    .edit().putString("last_error", android.util.Log.getStackTraceString(error)).commit();
        });

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(42), dp(24), dp(28));
        root.setBackgroundColor(Color.rgb(12, 12, 18));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Just Assistant");
        title.setTextColor(Color.WHITE);
        title.setTextSize(30);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, lp());

        TextView subtitle = new TextView(this);
        subtitle.setText("Gemini Live voice assistant");
        subtitle.setTextColor(0xFFB8B8C5);
        subtitle.setTextSize(15);
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(subtitle, lp());

        apiKey = edit("Gemini API key");
        apiKey.setInputType(0x81);
        apiKey.setText(SecurePrefs.getApiKey(this));
        root.addView(apiKey, lp());

        model = new AutoCompleteTextView(this);
        model.setHint("Gemini Live model");
        model.setText(SecurePrefs.getModel(this));
        model.setTextColor(Color.WHITE);
        model.setHintTextColor(0xFF777785);
        model.setSingleLine(true);
        model.setPadding(dp(14), dp(16), dp(14), dp(16));
        model.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_dropdown_item_1line, LIVE_MODELS));
        model.setThreshold(1);
        root.addView(model, lp());

        persona = edit("Persona / custom system prompt");
        persona.setMinLines(4);
        persona.setGravity(Gravity.TOP);
        persona.setText(SecurePrefs.getPersona(this));
        root.addView(persona, lp());

        Button prepare = button("Enable global Volume Up shortcut");
        prepare.setOnClickListener(v -> prepareShortcut());
        root.addView(prepare, lp());

        startStop = button(SecurePrefs.isRunning(this) ? "Stop Live" : "Start Live");
        startStop.setOnClickListener(v -> toggleLive());
        root.addView(startStop, lp());

        status = new TextView(this);
        status.setTextColor(0xFF9F9FAD);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(14), 0, 0);
        root.addView(status, lp());

        TextView note = new TextView(this);
        note.setText("The hardware shortcut uses Android Accessibility key-event filtering and an overlay permission so the assistant can be invoked from other screens. The microphone stays inside a foreground service.");
        note.setTextColor(0xFF737381);
        note.setTextSize(12);
        note.setPadding(0, dp(18), 0, 0);
        root.addView(note, lp());

        setContentView(scroll);
        updateStatus();
    }

    @Override protected void onResume() {
        super.onResume();
        updateStatus();
    }

    @Override protected void onPause() {
        saveSettings();
        super.onPause();
    }

    private void toggleLive() {
        saveSettings();
        if (SecurePrefs.isRunning(this)) {
            stopService(new Intent(this, LiveAssistantService.class));
        } else {
            if (!hasMicPermission()) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_PERMISSIONS);
                return;
            }
            try {
                ContextCompat.startForegroundService(this,
                        new Intent(this, LiveAssistantService.class));
            } catch (RuntimeException e) {
                Toast.makeText(this, "Could not start Live: " +
                        (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()),
                        Toast.LENGTH_LONG).show();
            }
        }
        updateStatus();
    }

    private void saveSettings() {
        String key = apiKey.getText().toString().trim();
        String modelName = model.getText().toString().trim();
        String personaText = persona.getText().toString().trim();

        if (!key.isEmpty()) SecurePrefs.saveApiKey(this, key);
        if (modelName.isEmpty()) modelName = "gemini-3.8-live";
        if (personaText.isEmpty()) personaText =
                "You are a helpful voice assistant. Be concise, natural, and conversational.";

        SecurePrefs.saveModel(this, modelName);
        SecurePrefs.savePersona(this, personaText);
    }

    private void prepareShortcut() {
        saveSettings();

        if (!Settings.canDrawOverlays(this)) {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
            Toast.makeText(this, "Allow 'Display over other apps', then return here.", Toast.LENGTH_LONG).show();
            return;
        }

        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        Toast.makeText(this, "Enable Just Assistant under Accessibility, then return here.", Toast.LENGTH_LONG).show();
    }

    private boolean hasMicPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean needsNotificationPermission() {
        return android.os.Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED;
    }

    private void updateStatus() {
        if (status == null) return;
        boolean overlay = Settings.canDrawOverlays(this);
        status.setText(SecurePrefs.isRunning(this)
                ? "● Gemini Live is running"
                : "Ready"
                + (overlay ? "" : "  •  overlay permission not enabled"));
        status.setTextColor(SecurePrefs.isRunning(this) ? 0xFF7CFFB2 : 0xFF9F9FAD);
        if (startStop != null) startStop.setText(
                SecurePrefs.isRunning(this) ? "Stop Live" : "Start Live");
    }

    private EditText edit(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(0xFF777785);
        e.setBackgroundColor(0xFF1B1B26);
        e.setPadding(dp(14), dp(16), dp(14), dp(16));
        return e;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams lp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(12);
        return p;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + .5f);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_PERMISSIONS && hasMicPermission()) {
            toggleLive();
        } else if (requestCode == REQUEST_PERMISSIONS && !hasMicPermission()) {
            Toast.makeText(this, "Microphone permission is required for Gemini Live.", Toast.LENGTH_LONG).show();
        }
    }
}
