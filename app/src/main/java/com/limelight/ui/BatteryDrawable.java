package com.limelight.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * A battery glyph: outline with a cap on the right, filled in proportion to the charge.
 * White when fine, red when low, green when charging; only the outline when the level is unknown.
 */
public final class BatteryDrawable extends Drawable {
    public static final int LEVEL_UNKNOWN = -1;

    private static final int COLOR_OUTLINE = 0xFFFFFFFF;
    private static final int COLOR_FILL = 0xFFFFFFFF;
    private static final int COLOR_LOW = 0xFFFF5252;
    private static final int COLOR_CHARGING = 0xFF66BB6A;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF body = new RectF();
    private final RectF box = new RectF();
    private final float density;

    private int percent = LEVEL_UNKNOWN;
    private boolean charging;
    private boolean low;

    public BatteryDrawable(Context context) {
        density = context.getResources().getDisplayMetrics().density;
    }

    public void setLevel(int percent, boolean charging, boolean low) {
        this.percent = percent < 0 ? LEVEL_UNKNOWN : Math.min(percent, 100);
        this.charging = charging;
        this.low = low;
        invalidateSelf();
    }

    @Override
    public int getIntrinsicWidth() {
        return dp(30);
    }

    @Override
    public int getIntrinsicHeight() {
        return dp(14);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) {
            return;
        }
        float stroke = dp(1.5f);
        float capWidth = dp(2.5f);
        float capHeight = b.height() * 0.45f;
        float radius = dp(2.5f);
        float centerY = b.exactCenterY();

        // Outline of the body, the cap on its right
        body.set(b.left + stroke / 2, b.top + stroke / 2, b.right - capWidth - dp(1) - stroke / 2, b.bottom - stroke / 2);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(stroke);
        paint.setColor(COLOR_OUTLINE);
        canvas.drawRoundRect(body, radius, radius, paint);

        paint.setStyle(Paint.Style.FILL);
        box.set(b.right - capWidth, centerY - capHeight / 2, b.right, centerY + capHeight / 2);
        canvas.drawRoundRect(box, dp(1), dp(1), paint);

        if (percent == LEVEL_UNKNOWN) {
            return;
        }

        // Charge: a bar inside the body, never shorter than a sliver so 1 % is still visible
        float inset = stroke + dp(1);
        float innerLeft = body.left + inset;
        float innerRight = body.right - inset;
        float width = (innerRight - innerLeft) * percent / 100f;
        if (percent > 0) {
            width = Math.max(width, dp(1.5f));
        }
        paint.setColor(charging ? COLOR_CHARGING : low ? COLOR_LOW : COLOR_FILL);
        box.set(innerLeft, body.top + inset, innerLeft + width, body.bottom - inset);
        canvas.drawRoundRect(box, dp(1), dp(1), paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    private int dp(float value) {
        return Math.round(value * density);
    }
}
