package com.limelight.ui;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Looper;
import android.speech.SpeechRecognizer;
import android.util.AttributeSet;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import com.limelight.R;
import com.limelight.nvstream.input.KeyboardPacket;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Keyboard drawn over the stream and driven from a gamepad or the TV remote. The system IME is of no
 * use here: on a TV it needs a text field and the gamepad buttons never reach it, so the stream
 * activity draws its own.
 *
 * Three pages: a US QWERTY keyboard, a Russian ЙЦУКЕН one and a page of function keys and shortcuts.
 * Latin letters, digits, punctuation and the special keys go to the host as key presses, exactly like
 * a physical US keyboard plugged into the PC, so games and shortcuts see real keys and the host's own
 * layout decides the character. Cyrillic letters go as text, which types the same letter whatever
 * layout the host is in. Shift, Ctrl and Alt are sticky: one tap holds them for the next key, a second
 * tap locks them, a third releases. Win is a plain key, so a tap opens the Start menu as on a real
 * keyboard; the Win shortcuts live on the Fn page.
 *
 * Gamepad: d-pad or left stick moves, A presses, B closes, X is Backspace, Y is Space, LB/RB switch
 * the page, LT is Shift, RT and Start are Enter, View (Select) dictates. Remote: d-pad moves, OK presses,
 * Back closes. Touch works too. Dictation ({@link VoiceInput}) goes through the TV's speech recognizer and
 * arrives as text, in Russian on the Cyrillic page and in the TV's language otherwise (English when that
 * language is itself Cyrillic). Main thread only, apart from {@link #hide(boolean)}.
 */
public final class OnScreenKeyboardView extends View {

    public interface Listener {
        /** A key the host treats as a physical key: down and, a moment later, up. */
        void onOskKey(int keyCode, boolean down, byte modifiers);
        /** Text typed regardless of the host's keyboard layout. */
        void onOskText(String text);
        /** A shortcut such as Alt+Tab: all keys down in order, then up in reverse. */
        void onOskCombo(int[] keyCodes);
        void onOskClosed();
    }

    /** Request code of the RECORD_AUDIO permission prompt; the activity passes the answer to {@link #onMicPermissionResult}. */
    public static final int REQUEST_RECORD_AUDIO = 0x5A1D;

    private static final int PAGE_LATIN = 0;
    private static final int PAGE_CYRILLIC = 1;
    private static final int PAGE_FN = 2;
    private static final int PAGE_COUNT = 3;

    private static final int KIND_KEY = 0;
    private static final int KIND_TEXT = 1;
    private static final int KIND_MOD = 2;
    private static final int KIND_PAGE = 3;
    private static final int KIND_COMBO = 4;
    private static final int KIND_CLOSE = 5;
    private static final int KIND_VOICE = 6;

    private static final int MOD_SHIFT = 0;
    private static final int MOD_CTRL = 1;
    private static final int MOD_ALT = 2;
    private static final int[] MOD_KEYCODES = {
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_ALT_LEFT
    };
    private static final byte[] MOD_BITS = {
            KeyboardPacket.MODIFIER_SHIFT, KeyboardPacket.MODIFIER_CTRL, KeyboardPacket.MODIFIER_ALT
    };
    private static final int MOD_OFF = 0;
    private static final int MOD_ONCE = 1;
    private static final int MOD_LOCKED = 2;

    private static final int DIR_NONE = 0;
    private static final int DIR_LEFT = 1;
    private static final int DIR_RIGHT = 2;
    private static final int DIR_UP = 3;
    private static final int DIR_DOWN = 4;

    private static final long REPEAT_FIRST_MS = 350;
    private static final long REPEAT_MS = 90;
    /** Same spacing as GameMenu.KEY_UP_DELAY: a game polling the keyboard every frame must see the key down. */
    private static final long KEY_UP_DELAY_MS = 25;
    private static final long FLASH_MS = 120;
    private static final long FADE_MS = 150;
    private static final long VOICE_STATUS_MS = 2500;

    private static final float ROW_UNITS = 15f;
    private static final int ROWS = 5;
    private static final int MAX_WIDTH_DP = 820;

    private static final int COLOR_PANEL = 0xE6161616;
    private static final int COLOR_KEY = 0xFF2E2E31;
    private static final int COLOR_KEY_SPECIAL = 0xFF222225;
    private static final int COLOR_KEY_CURSOR = 0xFF4B4B50;
    private static final int COLOR_KEY_FLASH = 0xFF8A8A90;
    private static final int COLOR_ACCENT = 0xFF4C8DFF;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_TEXT_DIM = 0xFFB8B8BC;
    private static final int COLOR_TEXT_SMALL = 0xFF8E8E93;
    private static final int COLOR_BADGE_A = 0xFF107C10;
    private static final int COLOR_BADGE_B = 0xFFD41F26;
    private static final int COLOR_BADGE_X = 0xFF0F6FC5;
    private static final int COLOR_BADGE_Y = 0xFFF7C600;
    private static final int COLOR_BADGE_GREY = 0xFF55555A;

    private static final class Key {
        int kind;
        String label;
        /** Label while Shift is active (letters: the capital; symbols: the shifted symbol) */
        String shiftLabel;
        /** The shifted symbol is drawn small in the corner, like on a real keycap */
        boolean cornerLabel;
        int keyCode;
        String text;
        String shiftText;
        int mod;
        int page;
        int[] combo;
        float units;
        /** Word-labelled keys: darker and in the small font */
        boolean special;
        final RectF rect = new RectF();

        String labelFor(boolean shift) {
            return shift && shiftLabel != null ? shiftLabel : label;
        }
    }

    private static final class Hint {
        final String badge;
        final int badgeColor;
        final boolean darkBadgeText;
        final String text;

        Hint(String badge, int badgeColor, boolean darkBadgeText, String text) {
            this.badge = badge;
            this.badgeColor = badgeColor;
            this.darkBadgeText = darkBadgeText;
            this.text = text;
        }
    }

    private final List<List<Key[]>> pages = new ArrayList<>(PAGE_COUNT);
    private final List<Hint> hints = new ArrayList<>();
    private final int[] modState = new int[MOD_KEYCODES.length];

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();

    private final float density;
    private float padding, unit, keyHeight, gap, radius, hintHeight;
    private float labelSize, smallLabelSize, cornerSize, hintSize;

    private Listener listener;
    private int page = PAGE_LATIN;
    private int cursorRow = 2;
    private float cursorX = -1;
    private Key flashKey;

    private int dirKeys = DIR_NONE, dirHat = DIR_NONE, dirStick = DIR_NONE, activeDir = DIR_NONE;
    private boolean leftTriggerDown, rightTriggerDown;
    private Runnable holdAction;

    private VoiceInput voice;
    private int voiceState = VoiceInput.STATE_IDLE;
    /** Language of the utterance in progress; the page may change while the microphone is open */
    private Locale voiceLocaleActive;
    private float voiceLevel;
    /** Live transcript while listening, or a status message shown for a moment in place of the hints */
    private String voiceText;
    private boolean voiceTextIsError;
    private final Runnable clearVoiceText = () -> {
        voiceText = null;
        voiceTextIsError = false;
        invalidate();
    };

    private final Runnable repeat = new Runnable() {
        @Override
        public void run() {
            if (activeDir != DIR_NONE) {
                move(activeDir);
                postDelayed(this, REPEAT_MS);
            }
        }
    };

    private final Runnable holdRepeat = new Runnable() {
        @Override
        public void run() {
            if (holdAction != null) {
                holdAction.run();
                postDelayed(this, REPEAT_MS);
            }
        }
    };

    private final Runnable clearFlash = () -> {
        flashKey = null;
        invalidate();
    };

    public OnScreenKeyboardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        setFocusable(false);
        setClickable(true);

        stroke.setStyle(Paint.Style.STROKE);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.DEFAULT);

        buildPages();
        buildHints();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public boolean isShowing() {
        return getVisibility() == VISIBLE;
    }

    public void show() {
        if (isShowing()) {
            return;
        }
        if (cursorX < 0 && getWidth() > 0) {
            cursorX = getWidth() / 2f;
        }
        setAlpha(0f);
        setVisibility(VISIBLE);
        animate().alpha(1f).setDuration(FADE_MS).start();
    }

    /**
     * @param releaseModifiers send key-ups for sticky modifiers still held on the host; false when
     *                         the connection is going away anyway
     */
    public void hide(boolean releaseModifiers) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post(() -> hide(releaseModifiers));
            return;
        }
        if (!isShowing()) {
            return;
        }
        stopNavigation();
        cancelVoice();
        if (releaseModifiers) {
            releaseAllModifiers();
        }
        else {
            for (int m = 0; m < modState.length; m++) {
                modState[m] = MOD_OFF;
            }
        }
        animate().cancel();
        setVisibility(GONE);
        if (listener != null) {
            listener.onOskClosed();
        }
    }

    // ---- Input from the stream activity ----

    /** Gamepad buttons and remote keys while the keyboard is open. False lets the event through (physical keyboards). */
    public boolean handleKey(KeyEvent event) {
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean first = down && event.getRepeatCount() == 0;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return direction(down, event.getRepeatCount(), DIR_LEFT);
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return direction(down, event.getRepeatCount(), DIR_RIGHT);
            case KeyEvent.KEYCODE_DPAD_UP:
                return direction(down, event.getRepeatCount(), DIR_UP);
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return direction(down, event.getRepeatCount(), DIR_DOWN);

            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                if (first) {
                    pressCursor();
                }
                return true;

            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BACK:
                if (!down) {
                    // While dictating, Back only drops the utterance
                    if (voiceActive()) {
                        cancelVoice();
                    }
                    else {
                        hide(true);
                    }
                }
                return true;

            case KeyEvent.KEYCODE_BUTTON_X:
                if (first) {
                    tapKey(KeyEvent.KEYCODE_DEL);
                    startHold(() -> tapKey(KeyEvent.KEYCODE_DEL));
                }
                else if (!down) {
                    stopHold();
                }
                return true;

            case KeyEvent.KEYCODE_BUTTON_Y:
                if (first) {
                    tapKey(KeyEvent.KEYCODE_SPACE);
                }
                return true;

            case KeyEvent.KEYCODE_BUTTON_L1:
                if (first) {
                    setPage((page + PAGE_COUNT - 1) % PAGE_COUNT);
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_R1:
                if (first) {
                    setPage((page + 1) % PAGE_COUNT);
                }
                return true;

            case KeyEvent.KEYCODE_BUTTON_L2:
                if (first) {
                    toggleModifier(MOD_SHIFT);
                }
                return true;
            case KeyEvent.KEYCODE_BUTTON_R2:
            case KeyEvent.KEYCODE_BUTTON_START:
                if (first) {
                    tapKey(KeyEvent.KEYCODE_ENTER);
                }
                return true;

            case KeyEvent.KEYCODE_BUTTON_SELECT:
            case KeyEvent.KEYCODE_SEARCH:
            case KeyEvent.KEYCODE_VOICE_ASSIST:
            case KeyEvent.KEYCODE_ASSIST:
                if (first) {
                    toggleVoice();
                }
                return true;

            // Swallowed so they do not reach the host while typing
            case KeyEvent.KEYCODE_BUTTON_MODE:
            case KeyEvent.KEYCODE_BUTTON_THUMBL:
            case KeyEvent.KEYCODE_BUTTON_THUMBR:
            case KeyEvent.KEYCODE_BUTTON_C:
            case KeyEvent.KEYCODE_BUTTON_Z:
            case KeyEvent.KEYCODE_MENU:
                return true;

            default:
                return false;
        }
    }

    /** Joystick motion (hat, left stick, triggers) while the keyboard is open. */
    public void handleJoystick(MotionEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_CLASS_JOYSTICK) == 0) {
            return;
        }
        dirHat = toDirection(event.getAxisValue(MotionEvent.AXIS_HAT_X), event.getAxisValue(MotionEvent.AXIS_HAT_Y), 0.5f);
        dirStick = toDirection(event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y), 0.6f);
        updateDirection();

        float lt = Math.max(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE));
        float rt = Math.max(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS));
        boolean ltDown = lt > 0.5f;
        boolean rtDown = rt > 0.5f;
        if (ltDown != leftTriggerDown) {
            leftTriggerDown = ltDown;
            if (ltDown) {
                toggleModifier(MOD_SHIFT);
            }
        }
        if (rtDown != rightTriggerDown) {
            rightTriggerDown = rtDown;
            if (rtDown) {
                tapKey(KeyEvent.KEYCODE_ENTER);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            Key key = keyAt(event.getX(), event.getY());
            if (key != null) {
                cursorRow = rowOf(key);
                cursorX = key.rect.centerX();
                pressKey(key);
            }
        }
        return true;
    }

    // ---- Navigation ----

    private boolean direction(boolean down, int repeatCount, int dir) {
        if (down) {
            // Android repeats the key itself; the keyboard paces its own repeat instead
            if (repeatCount == 0) {
                dirKeys = dir;
                updateDirection();
            }
        }
        else if (dirKeys == dir) {
            dirKeys = DIR_NONE;
            updateDirection();
        }
        return true;
    }

    private static int toDirection(float x, float y, float threshold) {
        if (Math.abs(x) < threshold && Math.abs(y) < threshold) {
            return DIR_NONE;
        }
        if (Math.abs(x) >= Math.abs(y)) {
            return x < 0 ? DIR_LEFT : DIR_RIGHT;
        }
        return y < 0 ? DIR_UP : DIR_DOWN;
    }

    /** A d-pad reported both as keys and as a hat moves once: only a change of the combined direction counts. */
    private void updateDirection() {
        int dir = dirKeys != DIR_NONE ? dirKeys : dirHat != DIR_NONE ? dirHat : dirStick;
        if (dir == activeDir) {
            return;
        }
        activeDir = dir;
        removeCallbacks(repeat);
        if (dir != DIR_NONE) {
            move(dir);
            postDelayed(repeat, REPEAT_FIRST_MS);
        }
    }

    private void stopNavigation() {
        dirKeys = dirHat = dirStick = activeDir = DIR_NONE;
        leftTriggerDown = rightTriggerDown = false;
        removeCallbacks(repeat);
        stopHold();
    }

    private void startHold(Runnable action) {
        holdAction = action;
        removeCallbacks(holdRepeat);
        postDelayed(holdRepeat, REPEAT_FIRST_MS);
    }

    private void stopHold() {
        holdAction = null;
        removeCallbacks(holdRepeat);
    }

    private void move(int dir) {
        List<Key[]> rows = pages.get(page);
        Key[] row = rows.get(cursorRow);
        int col = cursorCol();
        switch (dir) {
            case DIR_LEFT:
                col = (col + row.length - 1) % row.length;
                cursorX = row[col].rect.centerX();
                break;
            case DIR_RIGHT:
                col = (col + 1) % row.length;
                cursorX = row[col].rect.centerX();
                break;
            case DIR_UP:
                cursorRow = (cursorRow + rows.size() - 1) % rows.size();
                break;
            case DIR_DOWN:
                cursorRow = (cursorRow + 1) % rows.size();
                break;
            default:
                return;
        }
        invalidate();
    }

    /** The key under the remembered x position in the cursor row: moving up and down keeps the column. */
    private int cursorCol() {
        Key[] row = pages.get(page).get(cursorRow);
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < row.length; i++) {
            RectF r = row[i].rect;
            if (cursorX >= r.left - gap && cursorX < r.right + gap) {
                return i;
            }
            float d = Math.abs(r.centerX() - cursorX);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    private Key cursorKey() {
        if (cursorX < 0) {
            cursorX = getWidth() / 2f;
        }
        return pages.get(page).get(cursorRow)[cursorCol()];
    }

    private Key keyAt(float x, float y) {
        for (Key[] row : pages.get(page)) {
            for (Key key : row) {
                if (key.rect.contains(x, y)) {
                    return key;
                }
            }
        }
        return null;
    }

    private int rowOf(Key key) {
        List<Key[]> rows = pages.get(page);
        for (int r = 0; r < rows.size(); r++) {
            for (Key k : rows.get(r)) {
                if (k == key) {
                    return r;
                }
            }
        }
        return cursorRow;
    }

    private Key findKind(int kind) {
        for (Key[] row : pages.get(page)) {
            for (Key key : row) {
                if (key.kind == kind) {
                    return key;
                }
            }
        }
        return null;
    }

    private Key findKey(int keyCode) {
        for (Key[] row : pages.get(page)) {
            for (Key key : row) {
                if (key.kind == KIND_KEY && key.keyCode == keyCode) {
                    return key;
                }
            }
        }
        return null;
    }

    private void setPage(int newPage) {
        if (newPage == page) {
            return;
        }
        page = newPage;
        flashKey = null;
        invalidate();
    }

    // ---- Pressing ----

    private void pressCursor() {
        pressKey(cursorKey());
    }

    private void pressKey(Key key) {
        switch (key.kind) {
            case KIND_KEY:
                if (key.shiftText != null && shiftActive()) {
                    // A digit whose shifted symbol depends on the layout is typed as text instead
                    sendText(key.shiftText);
                }
                else {
                    sendKey(key.keyCode);
                }
                flash(key);
                break;
            case KIND_TEXT:
                sendText(shiftActive() && key.shiftText != null ? key.shiftText : key.text);
                flash(key);
                break;
            case KIND_MOD:
                toggleModifier(key.mod);
                break;
            case KIND_PAGE:
                setPage(key.page);
                break;
            case KIND_COMBO:
                if (listener != null) {
                    listener.onOskCombo(key.combo);
                }
                releaseOnceModifiers();
                flash(key);
                break;
            case KIND_CLOSE:
                hide(true);
                break;
            case KIND_VOICE:
                toggleVoice();
                break;
            default:
                break;
        }
    }

    // ---- Dictation ----

    private boolean voiceActive() {
        return voice != null && voice.isActive();
    }

    /** Mic key, View button or the remote's microphone key: start dictating, or stop and take what was said. */
    private void toggleVoice() {
        if (voiceActive()) {
            voice.stop();
            return;
        }
        if (!VoiceInput.isAvailable(getContext())) {
            showVoiceStatus(getResources().getString(R.string.osk_voice_unavailable), true);
            return;
        }
        if (getContext().checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (getContext() instanceof Activity) {
                ((Activity) getContext()).requestPermissions(new String[] { Manifest.permission.RECORD_AUDIO }, REQUEST_RECORD_AUDIO);
            }
            else {
                showVoiceStatus(getResources().getString(R.string.osk_voice_no_permission), true);
            }
            return;
        }
        startVoice();
    }

    /** The activity's answer to the RECORD_AUDIO prompt raised by {@link #toggleVoice}. */
    public void onMicPermissionResult(boolean granted) {
        if (!isShowing()) {
            return;
        }
        if (granted) {
            startVoice();
        }
        else {
            showVoiceStatus(getResources().getString(R.string.osk_voice_no_permission), true);
        }
    }

    private void startVoice() {
        if (voice == null) {
            voice = new VoiceInput(getContext(), voiceCallbacks);
        }
        removeCallbacks(clearVoiceText);
        voiceText = null;
        voiceTextIsError = false;
        voiceLevel = 0;
        voiceLocaleActive = voiceLocale();
        voice.start(voiceLocaleActive);
        invalidate();
    }

    private void cancelVoice() {
        if (voice != null) {
            voice.cancel();
        }
        voiceState = VoiceInput.STATE_IDLE;
        voiceLevel = 0;
        removeCallbacks(clearVoiceText);
        voiceText = null;
        voiceTextIsError = false;
        invalidate();
    }

    /** Russian on the Cyrillic page; otherwise the TV's language, or English when that language is itself Cyrillic. */
    private Locale voiceLocale() {
        if (page == PAGE_CYRILLIC) {
            return new Locale("ru", "RU");
        }
        Locale system = Locale.getDefault();
        switch (system.getLanguage()) {
            case "ru": case "uk": case "be": case "bg": case "kk": case "ky": case "mk": case "mn": case "sr": case "tg":
                return Locale.US;
            default:
                return system;
        }
    }

    private void showVoiceStatus(String text, boolean error) {
        voiceText = text;
        voiceTextIsError = error;
        removeCallbacks(clearVoiceText);
        postDelayed(clearVoiceText, VOICE_STATUS_MS);
        invalidate();
    }

    private final VoiceInput.Listener voiceCallbacks = new VoiceInput.Listener() {
        @Override
        public void onVoiceState(int state) {
            voiceState = state;
            if (state == VoiceInput.STATE_IDLE) {
                voiceLevel = 0;
            }
            invalidate();
        }

        @Override
        public void onVoiceLevel(float level) {
            voiceLevel = level;
            invalidate();
        }

        @Override
        public void onVoicePartial(String text) {
            voiceText = text;
            voiceTextIsError = false;
            invalidate();
        }

        @Override
        public void onVoiceResult(String text) {
            voiceState = VoiceInput.STATE_IDLE;
            voiceLevel = 0;
            sendText(text);
            Key mic = findKind(KIND_VOICE);
            if (mic != null) {
                flash(mic);
            }
            showVoiceStatus(text, false);
        }

        @Override
        public void onVoiceError(int error) {
            voiceState = VoiceInput.STATE_IDLE;
            voiceLevel = 0;
            int message;
            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    message = R.string.osk_voice_no_match;
                    break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    message = R.string.osk_voice_no_permission;
                    break;
                case SpeechRecognizer.ERROR_AUDIO:
                    message = R.string.osk_voice_audio;
                    break;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                case SpeechRecognizer.ERROR_SERVER:
                case SpeechRecognizer.ERROR_SERVER_DISCONNECTED:
                    message = R.string.osk_voice_network;
                    break;
                default:
                    message = R.string.osk_voice_unavailable;
                    break;
            }
            showVoiceStatus(getResources().getString(message), true);
        }
    };

    @Override
    protected void onDetachedFromWindow() {
        if (voice != null) {
            voice.destroy();
            voice = null;
        }
        voiceState = VoiceInput.STATE_IDLE;
        super.onDetachedFromWindow();
    }

    /** A shortcut button standing in for a key: typed and, when the key is on this page, lit up. */
    private void tapKey(int keyCode) {
        sendKey(keyCode);
        Key key = findKey(keyCode);
        if (key != null) {
            flash(key);
        }
    }

    private void sendKey(int keyCode) {
        if (listener == null) {
            return;
        }
        final byte modifiers = modifierBits();
        listener.onOskKey(keyCode, true, modifiers);
        postDelayed(() -> {
            if (listener != null) {
                listener.onOskKey(keyCode, false, modifiers);
            }
            // One-shot modifiers come up after the key they were held for
            releaseOnceModifiers();
        }, KEY_UP_DELAY_MS);
    }

    private void sendText(String s) {
        if (listener != null) {
            listener.onOskText(s);
        }
        releaseOnceModifiers();
    }

    private void flash(Key key) {
        flashKey = key;
        removeCallbacks(clearFlash);
        postDelayed(clearFlash, FLASH_MS);
        invalidate();
    }

    // ---- Sticky modifiers ----

    private boolean shiftActive() {
        return modState[MOD_SHIFT] != MOD_OFF;
    }

    private byte modifierBits() {
        byte bits = 0;
        for (int m = 0; m < modState.length; m++) {
            if (modState[m] != MOD_OFF) {
                bits |= MOD_BITS[m];
            }
        }
        return bits;
    }

    private void toggleModifier(int mod) {
        switch (modState[mod]) {
            case MOD_OFF:
                modState[mod] = MOD_ONCE;
                sendModifier(mod, true);
                break;
            case MOD_ONCE:
                modState[mod] = MOD_LOCKED;
                break;
            default:
                modState[mod] = MOD_OFF;
                sendModifier(mod, false);
                break;
        }
        invalidate();
    }

    private void releaseOnceModifiers() {
        boolean changed = false;
        for (int m = 0; m < modState.length; m++) {
            if (modState[m] == MOD_ONCE) {
                modState[m] = MOD_OFF;
                sendModifier(m, false);
                changed = true;
            }
        }
        if (changed) {
            invalidate();
        }
    }

    private void releaseAllModifiers() {
        for (int m = 0; m < modState.length; m++) {
            if (modState[m] != MOD_OFF) {
                modState[m] = MOD_OFF;
                sendModifier(m, false);
            }
        }
        invalidate();
    }

    /** The modifier byte follows a physical keyboard: the key's own bit is set on its down, cleared on its up. */
    private void sendModifier(int mod, boolean down) {
        if (listener != null) {
            listener.onOskKey(MOD_KEYCODES[mod], down, modifierBits());
        }
    }

    // ---- Geometry and drawing ----

    private float dp(float v) {
        return v * density;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int available = MeasureSpec.getSize(widthMeasureSpec);
        int width = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
                ? (int) dp(MAX_WIDTH_DP) : Math.min(available, (int) dp(MAX_WIDTH_DP));

        padding = dp(14);
        gap = dp(4);
        radius = dp(6);
        unit = (width - 2 * padding) / ROW_UNITS;
        keyHeight = unit * 0.75f;
        hintHeight = dp(24);
        labelSize = unit * 0.36f;
        smallLabelSize = unit * 0.24f;
        cornerSize = unit * 0.2f;
        hintSize = dp(13);

        int height = (int) (2 * padding + ROWS * keyHeight + dp(10) + hintHeight);
        setMeasuredDimension(width, height);
        layoutKeys();
    }

    private void layoutKeys() {
        for (List<Key[]> rows : pages) {
            float y = padding;
            for (Key[] row : rows) {
                float x = padding;
                for (Key key : row) {
                    float w = key.units * unit;
                    key.rect.set(x + gap / 2, y + gap / 2, x + w - gap / 2, y + keyHeight - gap / 2);
                    x += w;
                }
                y += keyHeight;
            }
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        fill.setColor(COLOR_PANEL);
        tmp.set(0, 0, getWidth(), getHeight());
        canvas.drawRoundRect(tmp, dp(10), dp(10), fill);

        boolean shift = shiftActive();
        Key cursor = cursorKey();
        for (Key[] row : pages.get(page)) {
            for (Key key : row) {
                drawKey(canvas, key, key == cursor, shift);
            }
        }
        drawHints(canvas);
    }

    private void drawKey(Canvas canvas, Key key, boolean isCursor, boolean shift) {
        RectF r = key.rect;
        int modState = key.kind == KIND_MOD ? this.modState[key.mod] : MOD_OFF;

        int color;
        if (key == flashKey) {
            color = COLOR_KEY_FLASH;
        }
        else if (modState == MOD_LOCKED || key.kind == KIND_VOICE && voiceActive()) {
            color = COLOR_ACCENT;
        }
        else if (isCursor) {
            color = COLOR_KEY_CURSOR;
        }
        else {
            color = key.special || key.kind != KIND_KEY && key.kind != KIND_TEXT ? COLOR_KEY_SPECIAL : COLOR_KEY;
        }
        fill.setColor(color);
        canvas.drawRoundRect(r, radius, radius, fill);

        if (modState == MOD_ONCE) {
            stroke.setColor(COLOR_ACCENT);
            stroke.setStrokeWidth(dp(2));
            tmp.set(r.left + dp(1), r.top + dp(1), r.right - dp(1), r.bottom - dp(1));
            canvas.drawRoundRect(tmp, radius, radius, stroke);
        }
        if (isCursor) {
            stroke.setColor(COLOR_TEXT);
            stroke.setStrokeWidth(dp(2));
            tmp.set(r.left + dp(1), r.top + dp(1), r.right - dp(1), r.bottom - dp(1));
            canvas.drawRoundRect(tmp, radius, radius, stroke);
        }

        if (key.kind == KIND_VOICE) {
            drawMic(canvas, r.centerX(), r.centerY(), keyHeight * 0.5f, isCursor ? COLOR_TEXT : COLOR_TEXT_DIM);
            // Which language the microphone hears: the page's alphabet decides, so say it on the keycap
            Locale lang = voiceActive() && voiceLocaleActive != null ? voiceLocaleActive : voiceLocale();
            text.setTextSize(cornerSize);
            text.setColor(COLOR_TEXT_SMALL);
            text.setFakeBoldText(false);
            canvas.drawText(lang.getLanguage().toUpperCase(Locale.ROOT), r.right - cornerSize * 1.0f, r.top + cornerSize * 1.2f, text);
            return;
        }

        String label = key.labelFor(shift);
        if (label.isEmpty()) {
            return;
        }
        boolean small = key.special || label.length() > 2;
        text.setTextSize(small ? smallLabelSize : labelSize);
        text.setColor(key.special && !isCursor ? COLOR_TEXT_DIM : COLOR_TEXT);
        text.setFakeBoldText(false);
        float cy = r.centerY() - (text.ascent() + text.descent()) / 2;
        canvas.drawText(label, r.centerX(), cy, text);

        if (key.cornerLabel && key.shiftLabel != null) {
            // The other symbol of the keycap, small in the top-right corner
            String other = shift ? key.label : key.shiftLabel;
            text.setTextSize(cornerSize);
            text.setColor(COLOR_TEXT_SMALL);
            canvas.drawText(other, r.right - cornerSize * 0.7f, r.top + cornerSize * 1.2f, text);
        }
    }

    /** A microphone glyph: capsule, cradle, stem and foot, fitting a box of the given height. */
    private void drawMic(Canvas canvas, float cx, float cy, float h, int color) {
        float capsuleW = h * 0.34f;
        float capsuleH = h * 0.56f;
        float top = cy - h * 0.5f;
        fill.setColor(color);
        tmp.set(cx - capsuleW / 2, top, cx + capsuleW / 2, top + capsuleH);
        canvas.drawRoundRect(tmp, capsuleW / 2, capsuleW / 2, fill);

        stroke.setColor(color);
        stroke.setStrokeWidth(Math.max(dp(1.5f), h * 0.07f));
        float cradleR = capsuleW * 0.95f;
        float cradleCy = top + capsuleH - cradleR * 0.55f;
        tmp.set(cx - cradleR, cradleCy - cradleR, cx + cradleR, cradleCy + cradleR);
        canvas.drawArc(tmp, 20, 140, false, stroke);
        float stemTop = cradleCy + cradleR;
        float foot = top + h;
        canvas.drawLine(cx, stemTop, cx, foot - h * 0.04f, stroke);
        canvas.drawLine(cx - capsuleW * 0.7f, foot, cx + capsuleW * 0.7f, foot, stroke);
    }

    /** The row under the keys: button hints, or the dictation state while the microphone is busy or has just answered. */
    private void drawHints(Canvas canvas) {
        float y = padding + ROWS * keyHeight + dp(10);
        float cy = y + hintHeight / 2;
        if (voiceActive() || voiceText != null) {
            drawVoiceStatus(canvas, cy);
            return;
        }
        drawButtonHints(canvas, y, cy);
    }

    private void drawVoiceStatus(Canvas canvas, float cy) {
        float iconH = hintHeight * 0.9f;
        float x = padding + dp(6);
        int color = voiceTextIsError ? COLOR_BADGE_B : voiceActive() ? COLOR_ACCENT : COLOR_TEXT_DIM;
        drawMic(canvas, x + iconH * 0.3f, cy, iconH, color);
        x += iconH * 0.6f + dp(10);

        // Level bars on the right while listening
        float barsW = 0;
        if (voiceActive()) {
            int bars = 5;
            float barW = dp(4);
            float barGap = dp(3);
            barsW = bars * barW + (bars - 1) * barGap + dp(10);
            float bx = getWidth() - padding - dp(6) - bars * barW - (bars - 1) * barGap;
            fill.setColor(COLOR_ACCENT);
            for (int i = 0; i < bars; i++) {
                float threshold = (i + 0.5f) / bars;
                float barH = hintHeight * (0.25f + 0.55f * (i + 1) / bars);
                fill.setAlpha(voiceLevel >= threshold ? 255 : 70);
                tmp.set(bx, cy - barH / 2, bx + barW, cy + barH / 2);
                canvas.drawRoundRect(tmp, barW / 2, barW / 2, fill);
                bx += barW + barGap;
            }
            fill.setAlpha(255);
        }

        String status;
        if (voiceText != null) {
            status = voiceText;
        }
        else if (voiceState == VoiceInput.STATE_LISTENING) {
            Locale lang = voiceLocaleActive != null ? voiceLocaleActive : voiceLocale();
            status = getResources().getString(R.string.osk_voice_listening) + " (" + lang.getDisplayLanguage() + ")";
        }
        else if (voiceState == VoiceInput.STATE_PROCESSING) {
            status = getResources().getString(R.string.osk_voice_processing);
        }
        else {
            status = "…";
        }
        text.setTextSize(hintSize);
        text.setFakeBoldText(false);
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(voiceTextIsError ? COLOR_BADGE_B : COLOR_TEXT);
        float maxW = getWidth() - padding - x - barsW;
        // Keep the tail of a long transcript, which is what the speaker is saying now
        while (status.length() > 1 && text.measureText("…" + status) > maxW) {
            status = status.substring(1);
            if (text.measureText("…" + status) <= maxW) {
                status = "…" + status;
                break;
            }
        }
        canvas.drawText(status, x, cy - (text.ascent() + text.descent()) / 2, text);
        text.setTextAlign(Paint.Align.CENTER);
    }

    private void drawButtonHints(Canvas canvas, float y, float cy) {
        float badgeH = hintHeight * 0.8f;
        text.setTextSize(hintSize);

        // Measure first so the row can be centred
        float total = 0;
        float[] widths = new float[hints.size()];
        text.setTextAlign(Paint.Align.LEFT);
        for (int i = 0; i < hints.size(); i++) {
            Hint h = hints.get(i);
            text.setFakeBoldText(true);
            float badgeW = Math.max(badgeH, text.measureText(h.badge) + dp(10));
            text.setFakeBoldText(false);
            widths[i] = badgeW + dp(6) + text.measureText(h.text);
            total += widths[i] + dp(18);
        }
        float available = getWidth() - 2 * padding;
        if (total > available) {
            // Eight hints on a narrow panel: shrink the type until the row fits
            text.setTextSize(hintSize * available / total);
            total = 0;
            text.setTextAlign(Paint.Align.LEFT);
            for (int i = 0; i < hints.size(); i++) {
                Hint h = hints.get(i);
                text.setFakeBoldText(true);
                float badgeW = Math.max(badgeH, text.measureText(h.badge) + dp(10));
                text.setFakeBoldText(false);
                widths[i] = badgeW + dp(6) + text.measureText(h.text);
                total += widths[i] + dp(18);
            }
        }
        float x = Math.max(padding, (getWidth() - total) / 2);
        for (int i = 0; i < hints.size(); i++) {
            Hint h = hints.get(i);
            text.setFakeBoldText(true);
            float badgeW = Math.max(badgeH, text.measureText(h.badge) + dp(10));
            fill.setColor(h.badgeColor);
            tmp.set(x, cy - badgeH / 2, x + badgeW, cy + badgeH / 2);
            canvas.drawRoundRect(tmp, badgeH / 2, badgeH / 2, fill);
            text.setColor(h.darkBadgeText ? 0xFF000000 : COLOR_TEXT);
            text.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(h.badge, tmp.centerX(), cy - (text.ascent() + text.descent()) / 2, text);

            text.setFakeBoldText(false);
            text.setTextAlign(Paint.Align.LEFT);
            text.setColor(COLOR_TEXT_DIM);
            canvas.drawText(h.text, x + badgeW + dp(6), cy - (text.ascent() + text.descent()) / 2, text);
            x += widths[i] + dp(18);
        }
        text.setTextAlign(Paint.Align.CENTER);
    }

    // ---- Layouts ----

    private void buildHints() {
        hints.add(new Hint("A", COLOR_BADGE_A, false, getResources().getString(R.string.osk_hint_press)));
        hints.add(new Hint("B", COLOR_BADGE_B, false, getResources().getString(R.string.osk_hint_close)));
        hints.add(new Hint("X", COLOR_BADGE_X, false, "Backspace"));
        hints.add(new Hint("Y", COLOR_BADGE_Y, true, getResources().getString(R.string.osk_hint_space)));
        hints.add(new Hint("LB RB", COLOR_BADGE_GREY, false, getResources().getString(R.string.osk_hint_layout)));
        hints.add(new Hint("LT", COLOR_BADGE_GREY, false, "Shift"));
        hints.add(new Hint("RT", COLOR_BADGE_GREY, false, "Enter"));
        hints.add(new Hint("View", COLOR_BADGE_GREY, false, getResources().getString(R.string.osk_hint_voice)));
    }

    private void buildPages() {
        pages.add(latinPage());
        pages.add(cyrillicPage());
        pages.add(fnPage());
    }

    private static Key key(int kind, String label, float units) {
        Key k = new Key();
        k.kind = kind;
        k.label = label;
        k.units = units;
        return k;
    }

    /** A letter: lower-case label, capital while Shift is active, always a key event. */
    private static Key letter(char c, int keyCode) {
        Key k = key(KIND_KEY, String.valueOf(c), 1f);
        k.shiftLabel = String.valueOf(c).toUpperCase(Locale.ROOT);
        k.keyCode = keyCode;
        return k;
    }

    /** A symbol key with its shifted symbol in the corner (US layout), always a key event. */
    private static Key sym(String label, String shiftLabel, int keyCode) {
        Key k = key(KIND_KEY, label, 1f);
        k.shiftLabel = shiftLabel;
        k.cornerLabel = true;
        k.keyCode = keyCode;
        return k;
    }

    /** A digit: key event unshifted; with Shift the Russian layout's symbol is typed as text. */
    private static Key digitRu(String label, String shiftSymbol, int keyCode) {
        Key k = sym(label, shiftSymbol, keyCode);
        k.shiftText = shiftSymbol;
        return k;
    }

    /** A word-labelled key (Esc, Tab, Enter...), always a key event. */
    private static Key special(String label, int keyCode, float units) {
        Key k = key(KIND_KEY, label, units);
        k.keyCode = keyCode;
        k.special = true;
        return k;
    }

    /** A character typed as text, whatever the host layout; Shift gives the capital. */
    private static Key txt(String c) {
        Key k = key(KIND_TEXT, c, 1f);
        k.text = c;
        k.shiftText = c.toUpperCase(Locale.ROOT);
        k.shiftLabel = k.shiftText;
        return k;
    }

    private static Key txt(String c, String shifted) {
        Key k = key(KIND_TEXT, c, 1f);
        k.text = c;
        k.shiftText = shifted;
        k.shiftLabel = shifted;
        k.cornerLabel = true;
        return k;
    }

    private static Key mod(String label, int mod, float units) {
        Key k = key(KIND_MOD, label, units);
        k.mod = mod;
        k.special = true;
        return k;
    }

    private static Key page(String label, int page, float units) {
        Key k = key(KIND_PAGE, label, units);
        k.page = page;
        k.special = true;
        return k;
    }

    private static Key combo(String label, float units, int... keyCodes) {
        Key k = key(KIND_COMBO, label, units);
        k.combo = keyCodes;
        k.special = true;
        return k;
    }

    private static Key close(float units) {
        Key k = key(KIND_CLOSE, "✕", units);
        k.special = true;
        return k;
    }

    private static Key voice(float units) {
        Key k = key(KIND_VOICE, "", units);
        k.special = true;
        return k;
    }

    private static Key[] bottomRow(Key pageKey) {
        return new Key[] {
                mod("Ctrl", MOD_CTRL, 1.5f), special("Win", KeyEvent.KEYCODE_META_LEFT, 1.25f), mod("Alt", MOD_ALT, 1.25f),
                special("", KeyEvent.KEYCODE_SPACE, 4.5f), voice(1f),
                pageKey, close(1f),
                special("←", KeyEvent.KEYCODE_DPAD_LEFT, 1f), special("↓", KeyEvent.KEYCODE_DPAD_DOWN, 1f),
                special("→", KeyEvent.KEYCODE_DPAD_RIGHT, 1f)
        };
    }

    private static List<Key[]> latinPage() {
        List<Key[]> rows = new ArrayList<>(ROWS);
        rows.add(new Key[] {
                special("Esc", KeyEvent.KEYCODE_ESCAPE, 1f),
                sym("`", "~", KeyEvent.KEYCODE_GRAVE),
                sym("1", "!", KeyEvent.KEYCODE_1), sym("2", "@", KeyEvent.KEYCODE_2), sym("3", "#", KeyEvent.KEYCODE_3),
                sym("4", "$", KeyEvent.KEYCODE_4), sym("5", "%", KeyEvent.KEYCODE_5), sym("6", "^", KeyEvent.KEYCODE_6),
                sym("7", "&", KeyEvent.KEYCODE_7), sym("8", "*", KeyEvent.KEYCODE_8), sym("9", "(", KeyEvent.KEYCODE_9),
                sym("0", ")", KeyEvent.KEYCODE_0), sym("-", "_", KeyEvent.KEYCODE_MINUS), sym("=", "+", KeyEvent.KEYCODE_EQUALS),
                special("⌫", KeyEvent.KEYCODE_DEL, 1f)
        });
        rows.add(new Key[] {
                special("Tab", KeyEvent.KEYCODE_TAB, 1.5f),
                letter('q', KeyEvent.KEYCODE_Q), letter('w', KeyEvent.KEYCODE_W), letter('e', KeyEvent.KEYCODE_E),
                letter('r', KeyEvent.KEYCODE_R), letter('t', KeyEvent.KEYCODE_T), letter('y', KeyEvent.KEYCODE_Y),
                letter('u', KeyEvent.KEYCODE_U), letter('i', KeyEvent.KEYCODE_I), letter('o', KeyEvent.KEYCODE_O),
                letter('p', KeyEvent.KEYCODE_P),
                sym("[", "{", KeyEvent.KEYCODE_LEFT_BRACKET), sym("]", "}", KeyEvent.KEYCODE_RIGHT_BRACKET),
                wide(sym("\\", "|", KeyEvent.KEYCODE_BACKSLASH), 1.5f)
        });
        rows.add(new Key[] {
                page("Fn", PAGE_FN, 1.75f),
                letter('a', KeyEvent.KEYCODE_A), letter('s', KeyEvent.KEYCODE_S), letter('d', KeyEvent.KEYCODE_D),
                letter('f', KeyEvent.KEYCODE_F), letter('g', KeyEvent.KEYCODE_G), letter('h', KeyEvent.KEYCODE_H),
                letter('j', KeyEvent.KEYCODE_J), letter('k', KeyEvent.KEYCODE_K), letter('l', KeyEvent.KEYCODE_L),
                sym(";", ":", KeyEvent.KEYCODE_SEMICOLON), sym("'", "\"", KeyEvent.KEYCODE_APOSTROPHE),
                special("Enter", KeyEvent.KEYCODE_ENTER, 2.25f)
        });
        rows.add(new Key[] {
                mod("Shift", MOD_SHIFT, 2.25f),
                letter('z', KeyEvent.KEYCODE_Z), letter('x', KeyEvent.KEYCODE_X), letter('c', KeyEvent.KEYCODE_C),
                letter('v', KeyEvent.KEYCODE_V), letter('b', KeyEvent.KEYCODE_B), letter('n', KeyEvent.KEYCODE_N),
                letter('m', KeyEvent.KEYCODE_M),
                sym(",", "<", KeyEvent.KEYCODE_COMMA), sym(".", ">", KeyEvent.KEYCODE_PERIOD), sym("/", "?", KeyEvent.KEYCODE_SLASH),
                special("↑", KeyEvent.KEYCODE_DPAD_UP, 1f), special("Del", KeyEvent.KEYCODE_FORWARD_DEL, 1.75f)
        });
        rows.add(bottomRow(page("АБВ", PAGE_CYRILLIC, 1.5f)));
        return rows;
    }

    private static List<Key[]> cyrillicPage() {
        List<Key[]> rows = new ArrayList<>(ROWS);
        rows.add(new Key[] {
                special("Esc", KeyEvent.KEYCODE_ESCAPE, 1f),
                txt("ё"),
                digitRu("1", "!", KeyEvent.KEYCODE_1), digitRu("2", "\"", KeyEvent.KEYCODE_2), digitRu("3", "№", KeyEvent.KEYCODE_3),
                digitRu("4", ";", KeyEvent.KEYCODE_4), digitRu("5", "%", KeyEvent.KEYCODE_5), digitRu("6", ":", KeyEvent.KEYCODE_6),
                digitRu("7", "?", KeyEvent.KEYCODE_7), digitRu("8", "*", KeyEvent.KEYCODE_8), digitRu("9", "(", KeyEvent.KEYCODE_9),
                digitRu("0", ")", KeyEvent.KEYCODE_0), digitRu("-", "_", KeyEvent.KEYCODE_MINUS), digitRu("=", "+", KeyEvent.KEYCODE_EQUALS),
                special("⌫", KeyEvent.KEYCODE_DEL, 1f)
        });
        rows.add(new Key[] {
                special("Tab", KeyEvent.KEYCODE_TAB, 1.5f),
                txt("й"), txt("ц"), txt("у"), txt("к"), txt("е"), txt("н"), txt("г"), txt("ш"), txt("щ"), txt("з"), txt("х"), txt("ъ"),
                wide(txt("\\", "/"), 1.5f)
        });
        rows.add(new Key[] {
                page("Fn", PAGE_FN, 1.75f),
                txt("ф"), txt("ы"), txt("в"), txt("а"), txt("п"), txt("р"), txt("о"), txt("л"), txt("д"), txt("ж"), txt("э"),
                special("Enter", KeyEvent.KEYCODE_ENTER, 2.25f)
        });
        rows.add(new Key[] {
                mod("Shift", MOD_SHIFT, 2.25f),
                txt("я"), txt("ч"), txt("с"), txt("м"), txt("и"), txt("т"), txt("ь"), txt("б"), txt("ю"),
                txt(".", ","),
                special("↑", KeyEvent.KEYCODE_DPAD_UP, 1f), special("Del", KeyEvent.KEYCODE_FORWARD_DEL, 1.75f)
        });
        rows.add(bottomRow(page("ABC", PAGE_LATIN, 1.5f)));
        return rows;
    }

    private static List<Key[]> fnPage() {
        List<Key[]> rows = new ArrayList<>(ROWS);
        Key[] top = new Key[14];
        top[0] = special("Esc", KeyEvent.KEYCODE_ESCAPE, 1f);
        for (int i = 0; i < 12; i++) {
            top[1 + i] = special("F" + (i + 1), KeyEvent.KEYCODE_F1 + i, 1f);
        }
        top[13] = special("⌫", KeyEvent.KEYCODE_DEL, 2f);
        rows.add(top);
        rows.add(new Key[] {
                special("Tab", KeyEvent.KEYCODE_TAB, 1.5f),
                special("Ins", KeyEvent.KEYCODE_INSERT, 1.5f), special("Del", KeyEvent.KEYCODE_FORWARD_DEL, 1.5f),
                special("Home", KeyEvent.KEYCODE_MOVE_HOME, 1.5f), special("End", KeyEvent.KEYCODE_MOVE_END, 1.5f),
                special("PgUp", KeyEvent.KEYCODE_PAGE_UP, 1.5f), special("PgDn", KeyEvent.KEYCODE_PAGE_DOWN, 1.5f),
                special("PrtSc", KeyEvent.KEYCODE_SYSRQ, 1.5f), special("ScrLk", KeyEvent.KEYCODE_SCROLL_LOCK, 1.5f),
                special("Pause", KeyEvent.KEYCODE_BREAK, 1.5f)
        });
        rows.add(new Key[] {
                page("ABC", PAGE_LATIN, 1.75f),
                special("Caps", KeyEvent.KEYCODE_CAPS_LOCK, 1.5f), special("NumLk", KeyEvent.KEYCODE_NUM_LOCK, 1.5f),
                special("Menu", KeyEvent.KEYCODE_MENU, 1.5f),
                combo("Alt+Tab", 1.75f, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_TAB),
                combo("Alt+F4", 1.75f, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_F4),
                combo("Win+D", 1.5f, KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_D),
                combo("Win+R", 1.5f, KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_R),
                special("Enter", KeyEvent.KEYCODE_ENTER, 2.25f)
        });
        rows.add(new Key[] {
                mod("Shift", MOD_SHIFT, 2.25f),
                combo("Ctrl+C", 1.625f, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_C),
                combo("Ctrl+V", 1.625f, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_V),
                combo("Ctrl+Z", 1.625f, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_Z),
                combo("Ctrl+A", 1.625f, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_A),
                combo("Win+Tab", 1.625f, KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_TAB),
                combo("Win+E", 1.625f, KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_E),
                special("↑", KeyEvent.KEYCODE_DPAD_UP, 1f), special("Del", KeyEvent.KEYCODE_FORWARD_DEL, 2f)
        });
        rows.add(bottomRow(page("АБВ", PAGE_CYRILLIC, 1.5f)));
        return rows;
    }

    private static Key wide(Key key, float units) {
        key.units = units;
        return key;
    }
}
