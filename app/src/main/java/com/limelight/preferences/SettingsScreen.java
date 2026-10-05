package com.limelight.preferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;

import com.limelight.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the settings screen shows: every option as a row in a category, with its key, strings, kind and
 * default. The keys are the ones PreferenceConfiguration reads, so nothing about how the app behaves
 * changed when the AndroidX preference screen was replaced by SettingsActivity. Rows that depend on the
 * device (native resolutions, HDR) are adjusted by the activity after {@link #build}.
 */
public final class SettingsScreen {
    public static final int TYPE_TOGGLE = 0;
    public static final int TYPE_CHOICE = 1;
    public static final int TYPE_SLIDER = 2;
    public static final int TYPE_TEXT = 3;
    public static final int TYPE_ACTION = 4;
    public static final int TYPE_LINK = 5;

    /** Returns false to keep the old value */
    public interface ChangeListener {
        boolean onChange(Item item, Object newValue);
    }

    public static final class Item {
        public final int type;
        public final String key;
        public final int title;
        public int summary;
        /** Key of a toggle this row is greyed out under while that toggle is off */
        public String dependency;
        public boolean visible = true;
        /** Profile editing hides the rows that act on files or the web */
        public boolean inProfiles = true;

        public boolean defBool;

        public final List<String> names = new ArrayList<>();
        public final List<String> values = new ArrayList<>();
        public String defValue;

        public int defInt, min, max, step = 1, keyStep, divisor = 1;
        public String suffix;
        public int dialogMessage;

        public String defText;
        public int inputType = InputType.TYPE_CLASS_TEXT;
        public int maxLength;

        public Runnable action;
        public String url, fallbackUrl;
        /** For a pseudo row the activity builds: which category it stands for */
        public int page;

        public ChangeListener onChange;

        Item(int type, String key, int title, int summary) {
            this.type = type;
            this.key = key;
            this.title = title;
            this.summary = summary;
        }

        public String nameOf(String value) {
            int i = values.indexOf(value);
            return i >= 0 && i < names.size() ? names.get(i) : value;
        }
    }

    public static final class Category {
        public final int title;
        public final List<Item> items = new ArrayList<>();

        Category(int title) {
            this.title = title;
        }

        Category add(Item... rows) {
            items.addAll(Arrays.asList(rows));
            return this;
        }

        public Item find(String key) {
            for (Item item : items) {
                if (key.equals(item.key)) {
                    return item;
                }
            }
            return null;
        }
    }

    private SettingsScreen() {
    }

    // ---- Row factories ----

    static Item toggle(String key, int title, int summary, boolean def) {
        Item item = new Item(TYPE_TOGGLE, key, title, summary);
        item.defBool = def;
        return item;
    }

    static Item choice(Context c, String key, int title, int summary, String def, int namesArray, int valuesArray) {
        Item item = new Item(TYPE_CHOICE, key, title, summary);
        item.names.addAll(Arrays.asList(c.getResources().getStringArray(namesArray)));
        item.values.addAll(Arrays.asList(c.getResources().getStringArray(valuesArray)));
        item.defValue = def;
        return item;
    }

    static Item slider(String key, int title, int summary, int def, int min, int max, int step, int keyStep, int divisor, String suffix) {
        Item item = new Item(TYPE_SLIDER, key, title, summary);
        item.defInt = def;
        item.min = min;
        item.max = max;
        item.step = step;
        item.keyStep = keyStep;
        item.divisor = divisor;
        item.suffix = suffix;
        return item;
    }

    static Item text(String key, int title, int summary, String def, int dialogMessage, int inputType, int maxLength) {
        Item item = new Item(TYPE_TEXT, key, title, summary);
        item.defText = def;
        item.dialogMessage = dialogMessage;
        item.inputType = inputType;
        item.maxLength = maxLength;
        return item;
    }

    static Item action(String key, int title, int summary, Runnable action) {
        Item item = new Item(TYPE_ACTION, key, title, summary);
        item.action = action;
        return item;
    }

    static Item link(String key, int title, int summary, String url, String fallbackUrl) {
        Item item = new Item(TYPE_LINK, key, title, summary);
        item.url = url;
        item.fallbackUrl = fallbackUrl;
        return item;
    }

    private static Item dep(Item item, String dependency) {
        item.dependency = dependency;
        return item;
    }

    private static Item notInProfiles(Item item) {
        item.inProfiles = false;
        return item;
    }

    /**
     * The whole screen; the actions of the rows that need the activity are filled in by it. Options that only
     * make sense on a phone (touchpad, metered network, clipboard, floating button, in-app language) are gone:
     * their keys keep their defaults in PreferenceConfiguration.
     */
    public static List<Category> build(Context c) {
        List<Category> categories = new ArrayList<>();
        String mbps = c.getString(R.string.suffix_seekbar_bitrate_mbps);

        categories.add(new Category(R.string.settings_cat_video).add(
                choice(c, "list_resolution", R.string.title_resolution_list, R.string.summary_resolution_list, PreferenceConfiguration.DEFAULT_RESOLUTION, R.array.resolution_names, R.array.resolution_values),
                choice(c, "list_fps", R.string.title_fps_list, R.string.summary_fps_list, PreferenceConfiguration.DEFAULT_FPS, R.array.fps_names, R.array.fps_values),
                toggle("checkbox_unlock_fps", R.string.title_unlock_fps, R.string.summary_unlock_fps, false),
                slider("seekbar_bitrate_kbps", R.string.title_seekbar_bitrate, R.string.summary_seekbar_bitrate, PreferenceConfiguration.getDefaultBitrate(c), 500, 300000, 500, 1000, 1000, mbps),
                choice(c, "video_format", R.string.title_video_format, R.string.summary_video_format, "auto", R.array.video_format_names, R.array.video_format_values),
                choice(c, "frame_pacing", R.string.title_frame_pacing, R.string.summary_frame_pacing, "latency", R.array.video_frame_pacing_names, R.array.video_frame_pacing_values),
                toggle("checkbox_enable_hdr", R.string.title_enable_hdr, R.string.summary_enable_hdr, true),
                toggle("checkbox_full_range", R.string.title_full_range, R.string.summary_full_range, false),
                choice(c, "list_video_scale_mode", R.string.title_video_scale_mode, R.string.summary_video_scale_mode, "fit", R.array.video_scale_mode_names, R.array.video_scale_mode_values),
                toggle("checkbox_use_virtual_display", R.string.title_checkbox_use_virtual_display, R.string.summary_checkbox_use_virtual_display, false),
                dep(slider("seekbar_resolution_scale_factor", R.string.title_resolution_scale_factor, R.string.summary_resolution_scale_factor, 100, 20, 200, 1, 0, 1, "%"), "checkbox_use_virtual_display"),
                toggle("checkbox_enable_sops", R.string.title_checkbox_enable_sops, R.string.summary_checkbox_enable_sops, true)
        ));

        categories.add(new Category(R.string.settings_cat_audio).add(
                choice(c, "list_audio_config", R.string.title_audio_config_list, R.string.summary_audio_config_list, "2", R.array.audio_config_names, R.array.audio_config_values),
                toggle("checkbox_aaudio_renderer", R.string.title_checkbox_aaudio_renderer, R.string.summary_checkbox_aaudio_renderer, true),
                toggle("checkbox_host_audio", R.string.title_checkbox_host_audio, R.string.summary_checkbox_host_audio, false)
        ));

        categories.add(new Category(R.string.settings_cat_gamepad).add(
                slider("seekbar_deadzone", R.string.title_seekbar_deadzone, R.string.summary_seekbar_deadzone, 5, -20, 20, 1, 0, 1, c.getString(R.string.suffix_seekbar_deadzone)),
                toggle("checkbox_multi_controller", R.string.title_checkbox_multi_controller, R.string.summary_checkbox_multi_controller, true),
                toggle("checkbox_enable_rumble", R.string.title_enable_rumble, R.string.summary_enable_rumble, true),
                toggle("checkbox_gamepad_notices", R.string.title_checkbox_gamepad_notices, R.string.summary_checkbox_gamepad_notices, true),
                toggle("checkbox_gamepad_enable_battery_report", R.string.title_checkbox_gamepad_enable_battery_report, R.string.summary_checkbox_gamepad_enable_battery_report, false),
                toggle("checkbox_mouse_emulation", R.string.title_checkbox_mouse_emulation, R.string.summary_checkbox_mouse_emulation, true),
                dep(choice(c, "analog_scrolling", R.string.title_analog_scrolling, R.string.summary_analog_scrolling, "right", R.array.analog_scrolling_names, R.array.analog_scrolling_values), "checkbox_mouse_emulation"),
                toggle("checkbox_flip_face_buttons", R.string.title_checkbox_flip_face_buttons, R.string.summary_checkbox_flip_face_buttons, false),
                toggle("checkbox_gamepad_touchpad_as_mouse", R.string.title_checkbox_gamepad_touchpad_as_mouse, R.string.summary_checkbox_gamepad_touchpad_as_mouse, false),
                toggle("checkbox_gamepad_motion_sensors", R.string.title_checkbox_gamepad_motion_sensors, R.string.summary_checkbox_gamepad_motion_sensors, true),
                toggle("checkbox_enable_joyconfix", R.string.title_joyconfix, R.string.summary_joyconfix, false),
                toggle("checkbox_usb_driver", R.string.title_checkbox_xb1_driver, R.string.summary_checkbox_xb1_driver, true),
                dep(toggle("checkbox_usb_bind_all", R.string.title_checkbox_usb_bind_all, R.string.summary_checkbox_usb_bind_all, true), "checkbox_usb_driver")
        ));

        categories.add(new Category(R.string.settings_cat_input).add(
                toggle("checkbox_mouse_local_cursor", R.string.title_checkbox_mouse_local_cursor, R.string.summary_checkbox_mouse_local_cursor, false),
                toggle("checkbox_mouse_nav_buttons", R.string.title_checkbox_mouse_nav_buttons, R.string.summary_checkbox_mouse_nav_buttons, false),
                toggle("checkbox_absolute_mouse_mode", R.string.title_checkbox_absolute_mouse_mode, R.string.summary_checkbox_absolute_mouse_mode, false),
                toggle("checkbox_force_qwerty", R.string.title_checkbox_force_qwerty, R.string.summary_checkbox_force_qwerty, true),
                toggle("checkbox_back_as_meta", R.string.title_checkbox_back_as_meta, R.string.summary_back_as_meta, false),
                toggle("checkbox_back_as_guide", R.string.title_back_as_guide, R.string.summary_back_as_guide, false)
        ));

        categories.add(new Category(R.string.settings_cat_ui).add(
                toggle("checkbox_enable_quit_dialog", R.string.title_quit_dialog, R.string.summary_quit_dialog, true),
                toggle("checkbox_resume_without_confirm", R.string.title_checkbox_resume_without_confirm, R.string.summary_checkbox_resume_without_confirm, false),
                toggle("checkbox_enable_perf_overlay", R.string.title_enable_perf_overlay, R.string.summary_enable_perf_overlay, false),
                dep(toggle("checkbox_enable_perf_overlay_lite", R.string.title_checkbox_enable_perf_overlay_lite, R.string.summary_checkbox_enable_perf_overlay_lite, false), "checkbox_enable_perf_overlay"),
                toggle("checkbox_disable_warnings", R.string.title_checkbox_disable_warnings, R.string.summary_checkbox_disable_warnings, false),
                toggle("checkbox_enable_post_stream_toast", R.string.title_enable_post_stream_toast, R.string.summary_enable_post_stream_toast, false)
        ));

        categories.add(new Category(R.string.settings_cat_advanced).add(
                toggle("checkbox_prevent_packet_loss", R.string.title_prevent_packet_loss, R.string.summary_prevent_packet_loss, false),
                toggle("checkbox_latency_test", R.string.title_latency_test, R.string.summary_latency_test, false),
                notInProfiles(action("pref_debug_info", R.string.title_debug_info, R.string.summary_debug_info, null))
        ));

        categories.add(new Category(R.string.settings_cat_about).add(
                notInProfiles(link("option_software_release", R.string.title_software_update, R.string.summary_software_update,
                        "https://github.com/pabragin/moonlight-tcl/releases", null)),
                notInProfiles(link("option_follow_update", R.string.title_follow_update, R.string.summary_follow_update,
                        c.getString(R.string.obtainium_deep_link), c.getString(R.string.obtainium_app_url)))
        ));

        return categories;
    }

    /** Writes the default of every stored row that has no value yet (what PreferenceManager.setDefaultValues did). */
    public static void applyDefaults(Context c) {
        SharedPreferences prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(c);
        SharedPreferences.Editor editor = prefs.edit();
        boolean changed = false;
        for (Category category : build(c)) {
            for (Item item : category.items) {
                if (item.key == null || prefs.contains(item.key)) {
                    continue;
                }
                switch (item.type) {
                    case TYPE_TOGGLE:
                        editor.putBoolean(item.key, item.defBool);
                        changed = true;
                        break;
                    case TYPE_CHOICE:
                        editor.putString(item.key, item.defValue);
                        changed = true;
                        break;
                    case TYPE_SLIDER:
                        editor.putInt(item.key, item.defInt);
                        changed = true;
                        break;
                    case TYPE_TEXT:
                        editor.putString(item.key, item.defText);
                        changed = true;
                        break;
                    default:
                        break;
                }
            }
        }
        if (changed) {
            editor.apply();
        }
    }

    /**
     * SharedPreferences over a map: the profile editor works on a copy of a profile's options and the
     * activity saves the map when the user asks.
     */
    public static final class MemoryPreferences implements SharedPreferences {
        private final Map<String, Object> values;

        public MemoryPreferences(Map<String, ?> initial) {
            values = new HashMap<>(initial == null ? new HashMap<>() : initial);
        }

        @Override
        public Map<String, ?> getAll() {
            return new HashMap<>(values);
        }

        @Override
        public String getString(String key, String defValue) {
            Object v = values.get(key);
            return v instanceof String ? (String) v : defValue;
        }

        @Override
        public Set<String> getStringSet(String key, Set<String> defValues) {
            Object v = values.get(key);
            //noinspection unchecked
            return v instanceof Set ? (Set<String>) v : defValues;
        }

        @Override
        public int getInt(String key, int defValue) {
            Object v = values.get(key);
            return v instanceof Number ? ((Number) v).intValue() : defValue;
        }

        @Override
        public long getLong(String key, long defValue) {
            Object v = values.get(key);
            return v instanceof Number ? ((Number) v).longValue() : defValue;
        }

        @Override
        public float getFloat(String key, float defValue) {
            Object v = values.get(key);
            return v instanceof Number ? ((Number) v).floatValue() : defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            Object v = values.get(key);
            return v instanceof Boolean ? (Boolean) v : defValue;
        }

        @Override
        public boolean contains(String key) {
            return values.containsKey(key);
        }

        @Override
        public Editor edit() {
            return new Editor() {
                private final Map<String, Object> changes = new HashMap<>();
                private boolean clear;

                @Override public Editor putString(String key, String value) { changes.put(key, value); return this; }
                @Override public Editor putStringSet(String key, Set<String> value) { changes.put(key, value); return this; }
                @Override public Editor putInt(String key, int value) { changes.put(key, value); return this; }
                @Override public Editor putLong(String key, long value) { changes.put(key, value); return this; }
                @Override public Editor putFloat(String key, float value) { changes.put(key, value); return this; }
                @Override public Editor putBoolean(String key, boolean value) { changes.put(key, value); return this; }
                @Override public Editor remove(String key) { changes.put(key, null); return this; }
                @Override public Editor clear() { clear = true; return this; }

                @Override
                public boolean commit() {
                    apply();
                    return true;
                }

                @Override
                public void apply() {
                    if (clear) {
                        values.clear();
                    }
                    for (Map.Entry<String, Object> e : changes.entrySet()) {
                        if (e.getValue() == null) {
                            values.remove(e.getKey());
                        }
                        else {
                            values.put(e.getKey(), e.getValue());
                        }
                    }
                    changes.clear();
                }
            };
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        }
    }
}
