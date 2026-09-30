package com.limelight.ui;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.limelight.R;

/**
 * Cards shown over the stream about a gamepad: when it joins the game, when its battery runs low and
 * when it goes on the charger. While pinned (the game menu is open) every gamepad's card stays on screen.
 * Each card carries the player number, the pad's name and its charge, and hides itself after a few
 * seconds. Player 1 (and 3) sits in the bottom-left corner, player 2 (and 4) in the bottom-right,
 * the split-screen convention: the TV cannot tell where a pad physically is.
 *
 * Main thread only.
 */
public final class GamepadNoticeOverlay {
    public static final int REASON_CONNECTED = 0;
    public static final int REASON_LOW_BATTERY = 1;
    public static final int REASON_CHARGING = 2;
    /** Plain charge, for the pinned cards behind the game menu */
    public static final int REASON_STATUS = 3;

    /** At or below this charge a pad counts as low (red icon, warning card). */
    public static final int LOW_BATTERY_PERCENT = 20;

    private static final long CONNECTED_VISIBLE_MS = 4000;
    private static final long LOW_BATTERY_VISIBLE_MS = 6000;
    private static final long FADE_MS = 250;

    private static final class Card {
        View view;
        ViewGroup parent;
        int reason;
        Runnable hide;
        BatteryDrawable battery;
        ImageView batteryView;
        TextView player, name, status;
    }

    private final Activity activity;
    private final ViewGroup left, right;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SparseArray<Card> cards = new SparseArray<>();
    private boolean pinned;

    public GamepadNoticeOverlay(Activity activity) {
        this.activity = activity;
        this.left = activity.findViewById(R.id.gamepadNoticesLeft);
        this.right = activity.findViewById(R.id.gamepadNoticesRight);
    }

    /** False when the activity's layout has no place for the cards. */
    public boolean isAvailable() {
        return left != null && right != null;
    }

    /** Pinned cards do not hide by themselves; unpinning clears them all. */
    public void setPinned(boolean pinned) {
        if (this.pinned == pinned) {
            return;
        }
        this.pinned = pinned;
        if (!pinned) {
            for (int i = cards.size() - 1; i >= 0; i--) {
                fadeOut(cards.keyAt(i));
            }
        }
    }

    /**
     * Shows (or refreshes) the card for one gamepad.
     *
     * @param key          identifies the gamepad (its input device id)
     * @param playerNumber 0-based controller number; even numbers go left, odd numbers right
     * @param percent      charge 0..100, or BatteryDrawable.LEVEL_UNKNOWN
     */
    public void show(int key, int reason, int playerNumber, String name, int percent, boolean charging) {
        if (!isAvailable() || activity.isFinishing()) {
            return;
        }
        ViewGroup parent = (playerNumber & 1) == 0 ? left : right;
        Card card = cards.get(key);
        if (card != null && card.parent != parent) {
            remove(key);
            card = null;
        }
        if (card == null) {
            card = new Card();
            card.view = LayoutInflater.from(activity).inflate(R.layout.gamepad_notice, parent, false);
            card.parent = parent;
            card.player = card.view.findViewById(R.id.gamepadNoticePlayer);
            card.name = card.view.findViewById(R.id.gamepadNoticeName);
            card.status = card.view.findViewById(R.id.gamepadNoticeStatus);
            card.batteryView = card.view.findViewById(R.id.gamepadNoticeBattery);
            card.battery = new BatteryDrawable(activity);
            card.batteryView.setImageDrawable(card.battery);
            parent.addView(card.view);
            cards.put(key, card);
            card.view.setAlpha(0f);
            card.view.animate().alpha(1f).setDuration(FADE_MS).start();
        }
        else {
            handler.removeCallbacks(card.hide);
            card.view.animate().cancel();
            card.view.setAlpha(1f);
        }

        card.reason = reason;
        card.player.setText(activity.getString(R.string.gamepad_notice_player, playerNumber + 1));
        card.name.setText(name);
        bindBattery(card, percent, charging);

        final int hideKey = key;
        card.hide = () -> fadeOut(hideKey);
        if (!pinned) {
            handler.postDelayed(card.hide, reason == REASON_LOW_BATTERY ? LOW_BATTERY_VISIBLE_MS : CONNECTED_VISIBLE_MS);
        }
    }

    /** A later battery reading for a card that is still on screen; nothing happens otherwise. */
    public void update(int key, int percent, boolean charging) {
        Card card = cards.get(key);
        if (card != null) {
            bindBattery(card, percent, charging);
        }
    }

    public void dismiss(int key) {
        if (cards.get(key) != null) {
            fadeOut(key);
        }
    }

    public void dismissAll() {
        for (int i = cards.size() - 1; i >= 0; i--) {
            remove(cards.keyAt(i));
        }
    }

    private void bindBattery(Card card, int percent, boolean charging) {
        boolean known = percent >= 0;
        boolean low = known && !charging && percent <= LOW_BATTERY_PERCENT;
        int labelId;
        switch (card.reason) {
            case REASON_CONNECTED:
                labelId = low ? R.string.gamepad_notice_low_battery : R.string.gamepad_notice_connected;
                break;
            case REASON_LOW_BATTERY:
                labelId = R.string.gamepad_notice_low_battery;
                break;
            case REASON_CHARGING:
                labelId = charging && percent >= 100 ? R.string.gamepad_notice_full : R.string.gamepad_notice_charging;
                break;
            default:
                // Plain status: the percentage alone unless something is worth a word
                labelId = charging ? (percent >= 100 ? R.string.gamepad_notice_full : R.string.gamepad_notice_charging)
                        : low ? R.string.gamepad_notice_low_battery : 0;
                break;
        }
        String text;
        if (!known) {
            text = activity.getString(labelId != 0 ? labelId : R.string.gamepad_notice_no_battery);
        }
        else if (labelId != 0) {
            text = activity.getString(R.string.gamepad_notice_level, activity.getString(labelId), percent);
        }
        else {
            text = activity.getString(R.string.gamepad_notice_percent, percent);
        }
        card.batteryView.setVisibility(known ? View.VISIBLE : View.GONE);
        card.battery.setLevel(percent, charging, low);
        card.status.setText(text);
    }

    private void fadeOut(int key) {
        Card card = cards.get(key);
        if (card == null) {
            return;
        }
        handler.removeCallbacks(card.hide);
        card.view.animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> {
            // Still the same card? A show() in the meantime has replaced or revived it
            if (cards.get(key) == card && card.view.getAlpha() == 0f) {
                remove(key);
            }
        }).start();
    }

    private void remove(int key) {
        Card card = cards.get(key);
        if (card == null) {
            return;
        }
        handler.removeCallbacks(card.hide);
        card.view.animate().cancel();
        card.parent.removeView(card.view);
        cards.remove(key);
    }
}
