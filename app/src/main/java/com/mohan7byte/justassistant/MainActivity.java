package com.mohan7byte.justassistant;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.*;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_MIC = 10;
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

        Button prepare = button("Enable Volume Up shortcut");
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
        note.setText("Use Accessibility to intercept the double Volume Up shortcut. The assistant uses an Accessibility overlay so the animation can remain visible over other apps and on the lock screen.");
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
            updateStatus();
            return;
        }

        if (!hasMicPermission()) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_MIC);
            return;
        }

        try {
            ContextCompat.startForegroundService(
                    this,
                    new Intent(this, LiveAssistantService.class));
        } catch (RuntimeException e) {
            Toast.makeText(this,
                    "Could not start Live. Try again from the visible app screen.",
                    Toast.LENGTH_LONG).show();
        }

        updateStatus();
    }

    private void saveSettings() {
        String key = apiKey.getText().toString().trim();
        String modelName = model.getText().toString().trim();
        String personaText = persona.getText().toString().trim();

        if (!key.isEmpty()) {
            SecurePrefs.saveApiKey(this, key);
        }

        if (modelName.isEmpty()) modelName = "gemini-3.8-live";
        if (personaText.isEmpty()) {
            personaText = "You are a helpful voice assistant. Be concise, natural, and conversational.";
        }

        SecurePrefs.saveModel(this, modelName);
        SecurePrefs.savePersona(this, personaText);
    }

    private void prepareShortcut() {
        saveSettings();
        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        Toast.makeText(
                this,
                "Enable Just Assistant under Accessibility, then return here.",
                Toast.LENGTH_LONG).show();
    }

    private boolean hasMicPermission() {
        return ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void updateStatus() {
        if (status == null) return;

        boolean running = SecurePrefs.isRunning(this);
        status.setText(running ? "● Gemini Live is running" : "Ready");
        status.setTextColor(running ? 0xFF7CFFB2 : 0xFF9F9FAD);

        if (startStop != null) {
            startStop.setText(running ? "Stop Live" : "Start Live");
        }
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
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(12);
        return p;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_MIC && hasMicPermission()) {
            toggleLive();
        } else if (requestCode == REQUEST_MIC) {
            Toast.makeText(
                    this,
                    "Microphone permission is required for Gemini Live.",
                    Toast.LENGTH_LONG).show();
        }
    }
}
