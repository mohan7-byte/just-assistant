package com.mohan7byte.justassistant;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.core.content.ContextCompat;

public class ActivationActivity extends Activity {
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
        root.setBackgroundColor(Color.TRANSPARENT);
        OrbView orb = new OrbView(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(140, 140, Gravity.CENTER);
        root.addView(orb, lp);
        setContentView(root);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            finish();
            return;
        }

        if (SecurePrefs.isRunning(this)) {
            stopService(new Intent(this, LiveAssistantService.class));
            finishDelayed(120);
            return;
        }

        Intent live = new Intent(this, LiveAssistantService.class);
        try {
            ContextCompat.startForegroundService(this, live);
            finishDelayed(450);
        } catch (RuntimeException e) {
            android.widget.Toast.makeText(this,
                    "Could not start Live. Open the app once and start Live from there.",
                    android.widget.Toast.LENGTH_LONG).show();
            finishDelayed(900);
        }
    }

    private void finishDelayed(long ms) {
        rootPost(() -> finish(), ms);
    }

    private void rootPost(Runnable r, long ms) {
        getWindow().getDecorView().postDelayed(r, ms);
    }
}
