package com.limelight.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.limelight.R;
import com.limelight.binding.input.GamepadBatteryMonitor;

import java.util.List;

/**
 * A row of small gamepad glyphs, each with a battery icon, for the top of the PC and app lists:
 * which pads are attached and how charged, no names. Hidden when no pad is attached.
 */
public final class GamepadStatusStrip extends LinearLayout {
    private static final int ICON_TINT = 0xCCFFFFFF;

    public GamepadStatusStrip(Context context) {
        super(context);
        init();
    }

    public GamepadStatusStrip(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setFocusable(false);
        setVisibility(GONE);
    }

    public void setGamepads(List<GamepadBatteryMonitor.Entry> gamepads) {
        removeAllViews();
        if (gamepads == null || gamepads.isEmpty()) {
            setVisibility(GONE);
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        for (GamepadBatteryMonitor.Entry pad : gamepads) {
            ImageView glyph = new ImageView(getContext());
            glyph.setImageResource(R.drawable.ic_gamepad_small);
            glyph.setColorFilter(ICON_TINT);
            LayoutParams glyphParams = new LayoutParams(Math.round(24 * density), Math.round(24 * density));
            glyphParams.setMarginStart(getChildCount() == 0 ? 0 : Math.round(18 * density));
            addView(glyph, glyphParams);

            BatteryDrawable battery = new BatteryDrawable(getContext());
            boolean low = pad.percent >= 0 && !pad.charging && pad.percent <= GamepadNoticeOverlay.LOW_BATTERY_PERCENT;
            battery.setLevel(pad.percent, pad.charging, low);
            ImageView level = new ImageView(getContext());
            level.setImageDrawable(battery);
            LayoutParams levelParams = new LayoutParams(Math.round(26 * density), Math.round(12 * density));
            levelParams.setMarginStart(Math.round(6 * density));
            addView(level, levelParams);
        }
        setVisibility(View.VISIBLE);
    }
}
