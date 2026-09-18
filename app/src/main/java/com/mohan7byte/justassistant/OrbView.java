package com.mohan7byte.justassistant;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

public class OrbView extends View {
    private final Paint core = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ValueAnimator animator;
    private float phase;
    private boolean speaking;

    public OrbView(Context context) {
        super(context);
        setClickable(true);
        core.setShader(new RadialGradient(42, 42, 42,
                new int[]{0xFFFFFFFF, 0xFFB99CFF, 0xFF6B4EFF},
                new float[]{0f, .45f, 1f}, Shader.TileMode.CLAMP));
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(3f);
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(1500);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            phase = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    public void setSpeaking(boolean speaking) {
        this.speaking = speaking;
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float base = Math.min(getWidth(), getHeight()) * .28f;
        float pulse = .78f + .22f * (float) Math.sin(phase * Math.PI * 2);
        if (speaking) pulse *= 1.10f;

        ring.setColor(0x556B4EFF);
        for (int i = 0; i < 3; i++) {
            float p = (phase + i / 3f) % 1f;
            ring.setAlpha((int) (80 * (1f - p)));
            canvas.drawCircle(cx, cy, base * (1.6f + p * 1.1f), ring);
        }

        canvas.drawCircle(cx, cy, base * pulse, core);
    }
}
