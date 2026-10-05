package com.limelight.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** The thin track with a round thumb of a slider row. Brighter on the row under the cursor; a step glides over 160 ms. */
public final class SliderTrackView extends View {
    private static final int DURATION_MS = 160;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private float fraction;
    private float target;
    private ValueAnimator animator;

    public SliderTrackView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void setFraction(float fraction) {
        setFraction(fraction, false);
    }

    public void setFraction(float fraction, boolean animate) {
        fraction = Math.max(0f, Math.min(1f, fraction));
        if (fraction == target && (animator != null || fraction == this.fraction)) {
            return;
        }
        target = fraction;
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
        if (!animate || !isShown()) {
            this.fraction = fraction;
            invalidate();
            return;
        }
        animator = ValueAnimator.ofFloat(this.fraction, fraction);
        animator.setDuration(DURATION_MS);
        animator.setInterpolator(new DecelerateInterpolator(1.5f));
        animator.addUpdateListener(animation -> {
            this.fraction = (Float) animation.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) {
            animator.cancel();
            animator = null;
            fraction = target;
        }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        boolean lit = isSelected();
        float density = getResources().getDisplayMetrics().density;
        float thumb = 5 * density;
        float track = 1.5f * density;
        float cy = getHeight() / 2f;
        float left = thumb;
        float right = getWidth() - thumb;
        // The filled part grows from the start edge: left in LTR, right in RTL
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        float x = rtl ? right - (right - left) * fraction : left + (right - left) * fraction;

        paint.setColor(lit ? 0x66FFFFFF : 0xFF4A4A50);
        rect.set(left, cy - track, right, cy + track);
        canvas.drawRoundRect(rect, track, track, paint);

        paint.setColor(lit ? 0xFFFFFFFF : 0xFFD6D6DA);
        if (rtl) {
            rect.set(x, cy - track, right, cy + track);
        }
        else {
            rect.set(left, cy - track, x, cy + track);
        }
        canvas.drawRoundRect(rect, track, track, paint);
        canvas.drawCircle(x, cy, thumb, paint);
    }
}
