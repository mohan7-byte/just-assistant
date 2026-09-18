package com.mohan7byte.justassistant;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_MIC = 10;
    private EditText apiKey, model, persona;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 48, 32, 32);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("Just Assistant");
        title.setTextSize(28);
        root.addView(title);

        apiKey = field("Gemini API key");
        apiKey.setInputType(0x81);
        model = field("Gemini Live model");
        persona = field("Persona / system instruction");
        root.addView(apiKey); root.addView(model); root.addView(persona);

        Button start = new Button(this);
        start.setText("Start Live");
        root.addView(start);
        start.setOnClickListener(v -> startLive());

        setContentView(root);
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(false);
        e.setPadding(0, 18, 0, 18);
        return e;
    }

    private void startLive() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MIC);
            return;
        }
        Intent i = new Intent(this, LiveAssistantService.class)
                .putExtra("apiKey", apiKey.getText().toString().trim())
                .putExtra("model", model.getText().toString().trim())
                .putExtra("persona", persona.getText().toString());
        ContextCompat.startForegroundService(this, i);
        Toast.makeText(this, "Starting Gemini Live…", Toast.LENGTH_SHORT).show();
    }
}
