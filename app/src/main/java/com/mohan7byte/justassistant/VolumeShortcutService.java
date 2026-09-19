package com.mohan7byte.justassistant;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

public class VolumeShortcutService extends AccessibilityService {
    private static final long DOUBLE_PRESS_MS = 500L;

    private long lastVolumeUpRelease;
    private WindowManager windowManager;
    private OrbView orbView;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!LiveState.ACTION.equals(intent.getAction())) return;
            String state = intent.getStringExtra(LiveState.EXTRA_STATE);
            String message = intent.getStringExtra(LiveState.EXTRA_MESSAGE);

            if (LiveState.SPEAKING.equals(state)) {
                setOrbSpeaking(true);
            } else if (LiveState.READY.equals(state) || LiveState.IDLE.equals(state)
                    || LiveState.CONNECTING.equals(state)) {
                setOrbSpeaking(false);
            } else if (LiveState.ERROR.equals(state) || LiveState.STOPPED.equals(state)) {
                removeOrb();
            }

            if (LiveState.ERROR.equals(state) && message != null && !message.isEmpty()) {
                android.widget.Toast.makeText(VolumeShortcutService.this,
                        message, android.widget.Toast.LENGTH_LONG).show();
            }
        }
    };

    @Override public void onServiceConnected() {
        super.onServiceConnected();

        android.accessibilityservice.AccessibilityServiceInfo info = getServiceInfo();
        info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        setServiceInfo(info);

        IntentFilter filter = new IntentFilter(LiveState.ACTION);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }

        if (SecurePrefs.isRunning(this)) {
            showOrb();
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override public void onInterrupt() {}

    @Override public boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_VOLUME_UP
                || event.isCanceled()) {
            return false;
        }

        if (event.getAction() == KeyEvent.ACTION_UP) {
            long now = SystemClock.elapsedRealtime();

            if (now - lastVolumeUpRelease <= DOUBLE_PRESS_MS) {
                toggleAssistant();
                lastVolumeUpRelease = 0L;
                return true;
            }

            lastVolumeUpRelease = now;
        }

        return false;
    }

    private void toggleAssistant() {
        if (SecurePrefs.isRunning(this)) {
            removeOrb();
            sendBroadcast(new Intent(this, StopReceiver.class));
            return;
        }

        showOrb();

        Intent activation = new Intent(this, ActivationActivity.class);
        activation.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);

        try {
            startActivity(activation);
        } catch (Exception e) {
            removeOrb();
            android.widget.Toast.makeText(this,
                    "Open Just Assistant once, grant microphone permission, and try again.",
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private void showOrb() {
        if (orbView != null) return;

        try {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            OrbView orb = new OrbView(this);
            orb.setContentDescription("Just Assistant. Double Volume Up to stop.");
            orb.setOnClickListener(v -> {
                removeOrb();
                sendBroadcast(new Intent(this, StopReceiver.class));
            });

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    dp(112),
                    dp(112),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            params.y = dp(92);

            windowManager.addView(orb, params);
            orbView = orb;
        } catch (Exception ignored) {
            orbView = null;
        }
    }

    private void removeOrb() {
        if (windowManager != null && orbView != null) {
            try {
                windowManager.removeViewImmediate(orbView);
            } catch (Exception ignored) {
            }
        }
        orbView = null;
    }

    private void setOrbSpeaking(boolean speaking) {
        if (orbView != null) {
            orbView.post(() -> orbView.setSpeaking(speaking));
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override public void onDestroy() {
        try {
            unregisterReceiver(stateReceiver);
        } catch (Exception ignored) {
        }
        removeOrb();
        super.onDestroy();
    }
}
