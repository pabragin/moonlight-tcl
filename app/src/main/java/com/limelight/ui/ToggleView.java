package com.limelight.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** An on/off switch for a settings row: the thumb slides and the track tints over 200 ms. Not focusable, the row is. */
public final class ToggleView extends View {
    private static final int TRACK_OFF = 0xFF5C5C63;
    private static final int TRACK_ON = 0xFF4C8DFF;
    private static final int THUMB_OFF = 0xFFD0D0D4;
    private static final int THUMB_ON = 0xFFFFFFFF;
    private static final int DURATION_MS = 200;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private boolean checked;
    private float position;
    private ValueAnimator animator;

    public ToggleView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public boolean isChecked() {
        return checked;
    }

    /** Same value again is a no-op, so a rebind never cuts a running slide short. */
    public void setChecked(boolean checked, boolean animate) {
        if (this.checked == checked) {
            return;
        }
        this.checked = checked;
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        float target = checked ? 1f : 0f;
        if (!animate || !isShown()) {
            position = target;
            invalidate();
            return;
        }
        animator = ValueAnimator.ofFloat(position, target);
        animator.setDuration(DURATION_MS);
        animator.setInterpolator(new DecelerateInterpolator(1.5f));
        animator.addUpdateListener(animation -> {
            position = (Float) animation.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
            animator = null;
            position = checked ? 1f : 0f;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float h = getHeight();
        float w = getWidth();
        float inset = 3 * getResources().getDisplayMetrics().density;
        float radius = h / 2f - inset;

        paint.setColor(blend(TRACK_OFF, TRACK_ON, position));
        rect.set(0, 0, w, h);
        canvas.drawRoundRect(rect, h / 2f, h / 2f, paint);

        // "On" puts the thumb at the end edge: right in LTR, left in RTL, like the system switch
        float travel = getLayoutDirection() == LAYOUT_DIRECTION_RTL ? 1f - position : position;
        float cx = inset + radius + travel * (w - 2 * (inset + radius));
        paint.setColor(blend(THUMB_OFF, THUMB_ON, position));
        canvas.drawCircle(cx, h / 2f, radius, paint);
    }

    private static int blend(int from, int to, float t) {
        int a = Math.round(((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * t);
        int r = Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
