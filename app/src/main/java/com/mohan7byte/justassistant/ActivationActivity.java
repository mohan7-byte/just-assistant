package com.mohan7byte.justassistant;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

public class ActivationActivity extends Activity {
    private BroadcastReceiver stateReceiver;
    private TextView stateText;
    private OrbView orb;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);

        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);

        if (android.os.Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x33000000);

        orb = new OrbView(this);
        FrameLayout.LayoutParams orbParams = new FrameLayout.LayoutParams(180, 180, Gravity.CENTER);
        root.addView(orb, orbParams);

        stateText = new TextView(this);
        stateText.setTextColor(Color.WHITE);
        stateText.setTextSize(14);
        stateText.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams textParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        textParams.bottomMargin = dp(90);
        root.addView(stateText, textParams);

        setContentView(root);
        stateText.setText("Connecting…");

        stateReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!LiveState.ACTION.equals(intent.getAction())) return;

                String value = intent.getStringExtra(LiveState.EXTRA_STATE);
                String message = intent.getStringExtra(LiveState.EXTRA_MESSAGE);

                if (LiveState.READY.equals(value)) {
                    stateText.setText("Gemini Live ready");
                    orb.setSpeaking(false);
                    getWindow().getDecorView().postDelayed(() -> finish(), 350);
                } else if (LiveState.SPEAKING.equals(value)) {
                    stateText.setText("Speaking…");
                    orb.setSpeaking(true);
                } else if (LiveState.IDLE.equals(value) || LiveState.CONNECTING.equals(value)) {
                    stateText.setText("Listening…");
                    orb.setSpeaking(false);
                } else if (LiveState.ERROR.equals(value)) {
                    stateText.setText(message == null ? "Gemini Live failed" : message);
                    orb.setSpeaking(false);
                    getWindow().getDecorView().postDelayed(() -> finish(), 2200);
                } else if (LiveState.STOPPED.equals(value)) {
                    finish();
                }
            }
        };

        IntentFilter filter = new IntentFilter(LiveState.ACTION);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }

        if (SecurePrefs.isRunning(this)) {
            stateText.setText("Gemini Live already running");
            getWindow().getDecorView().postDelayed(() -> finish(), 350);
            return;
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone permission is required.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        Intent live = new Intent(this, LiveAssistantService.class);
        try {
            ContextCompat.startForegroundService(this, live);
        } catch (RuntimeException e) {
            Toast.makeText(this,
                    "Android blocked the voice service. Open Just Assistant and tap Start Live once.",
                    Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override protected void onDestroy() {
        try {
            if (stateReceiver != null) unregisterReceiver(stateReceiver);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
