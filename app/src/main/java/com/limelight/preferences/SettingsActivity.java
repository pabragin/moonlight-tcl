package com.limelight.preferences;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.MediaCodecInfo;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputFilter;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Range;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.DebugInfoActivity;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.input.BluetoothHidRumble;
import com.limelight.binding.video.MediaCodecHelper;
import com.limelight.profiles.ProfilesManager;
import com.limelight.profiles.SettingsProfile;
import com.limelight.ui.PanelMenu;
import com.limelight.ui.SliderTrackView;
import com.limelight.ui.ToggleView;
import com.limelight.utils.Dialog;
import com.limelight.utils.HelpLauncher;
import com.limelight.utils.UiHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Settings in two panes: categories on the left, the rows of the chosen category on the right. Every row has
 * the same shape: title and explanation on the left, its control centred in a column of fixed width on the
 * right, so switches, values and chevrons line up. The row under the cursor gets the soft highlight of the
 * game menu through the list's own selected state (it cannot stick to a recycled row). Left and Back return
 * from the rows to the categories; a press flips a switch, opens a chooser's list or a slider's dialog, or
 * runs an action, and the control animates to its new value. Rows come from {@link SettingsScreen};
 * this activity adds what depends on the device (native resolutions and frame rates, HDR, decoder limits)
 * and the few rows that act (share logs, import special keys, debug info).
 *
 * The same screen edits a settings profile when started with {@link #EXTRA_PROFILE_UUID} or
 * {@link #EXTRA_NEW_PROFILE}: it then works on a copy of the profile's options, marks the rows that differ
 * from the current settings and saves on request. That replaced EditProfileActivity.
 */
public class SettingsActivity extends Activity {
    public static final String EXTRA_PROFILE_UUID = "profileUuid";
    public static final String EXTRA_NEW_PROFILE = "newProfile";

    private static final int BT_PERMISSION_REQUEST_CODE = 0x4255;

    private SharedPreferences prefs;
    private boolean profileMode;
    private SettingsProfile profile;
    private String pendingProfileName;
    private Map<String, ?> baseline;

    private List<SettingsScreen.Category> categories = new ArrayList<>();
    private int categoryIndex;
    private ListView categoryList, itemList;
    private CategoryAdapter categoryAdapter;
    private ItemAdapter itemAdapter;
    private TextView title;

    private int nativeResolutionStartIndex = Integer.MAX_VALUE;
    private boolean nativeFramerateShown;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        UiHelper.notifyNewRootView(this);

        title = findViewById(R.id.settingsTitle);
        categoryList = findViewById(R.id.categoryList);
        itemList = findViewById(R.id.itemList);

        String profileUuid = getIntent().getStringExtra(EXTRA_PROFILE_UUID);
        boolean newProfile = getIntent().getBooleanExtra(EXTRA_NEW_PROFILE, false);
        SharedPreferences defaults = android.preference.PreferenceManager.getDefaultSharedPreferences(this);
        if (profileUuid != null || newProfile) {
            profileMode = true;
            baseline = defaults.getAll();
            if (profileUuid != null) {
                for (SettingsProfile candidate : ProfilesManager.getInstance().getProfiles()) {
                    if (candidate.getUuid().toString().equals(profileUuid)) {
                        profile = candidate;
                        break;
                    }
                }
                if (profile == null) {
                    Toast.makeText(this, R.string.profile_manager_profile_not_found, Toast.LENGTH_SHORT).show();
                    finish();
                    return;
                }
                prefs = new SettingsScreen.MemoryPreferences(profile.getOptions());
            }
            else {
                prefs = new SettingsScreen.MemoryPreferences(baseline);
            }
            findViewById(R.id.profileActions).setVisibility(View.VISIBLE);
            findViewById(R.id.saveProfileButton).setOnClickListener(v -> saveProfile());
            findViewById(R.id.renameProfileButton).setOnClickListener(v -> showRenameDialog());
        }
        else {
            prefs = defaults;
        }
        updateTitle();

        categoryAdapter = new CategoryAdapter();
        categoryList.setAdapter(categoryAdapter);
        categoryList.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                showCategory(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        categoryList.setOnItemClickListener((parent, view, position, id) -> {
            showCategory(position);
            itemList.requestFocus();
        });

        itemAdapter = new ItemAdapter();
        itemList.setAdapter(itemAdapter);
        itemList.setOnItemClickListener((parent, view, position, id) -> onRowClick(itemAdapter.getItem(position)));

        build();
        categoryList.requestFocus();
    }

    @Override
    public void onBackPressed() {
        // Back steps out of the rows to the categories first, like the TV's own settings
        if (itemList.hasFocus()) {
            categoryList.requestFocus();
        }
        else {
            super.onBackPressed();
        }
    }

    private void updateTitle() {
        if (!profileMode) {
            title.setText(R.string.main_settings);
        }
        else if (profile != null) {
            title.setText(getString(R.string.profile_manager_edit_profile_with, profile.getName()));
        }
        else if (pendingProfileName != null) {
            title.setText(getString(R.string.profile_manager_new_profile_with, pendingProfileName));
        }
        else {
            title.setText(R.string.profile_manager_new_profile);
        }
    }

    private void showCategory(int position) {
        if (position < 0 || position >= categories.size()) {
            return;
        }
        boolean changed = categoryIndex != position;
        categoryIndex = position;
        if (changed) {
            categoryAdapter.notifyDataSetChanged();
            // The rows of the new category fade and slide in while the old ones fade out
            float lift = 20 * getResources().getDisplayMetrics().density;
            itemList.animate().cancel();
            itemList.animate().alpha(0f).setDuration(110).withEndAction(() -> {
                itemAdapter.notifyDataSetChanged();
                itemList.setSelection(0);
                itemList.setTranslationY(lift);
                itemList.animate().alpha(1f).translationY(0f).setDuration(200).start();
            }).start();
        }
    }

    // ---- Building the rows ----

    private void build() {
        int selectedRow = itemList.getSelectedItemPosition();
        nativeResolutionStartIndex = Integer.MAX_VALUE;
        nativeFramerateShown = false;

        categories = SettingsScreen.build(this);
        for (SettingsScreen.Category category : categories) {
            for (SettingsScreen.Item item : category.items) {
                if (profileMode && !item.inProfiles) {
                    item.visible = false;
                }
            }
        }

        SettingsScreen.Item resolution = find("list_resolution");
        SettingsScreen.Item fps = find("list_fps");
        applyDisplayCapabilities(resolution, fps);

        resolution.onChange = (item, value) -> {
            String valueStr = (String) value;
            int index = item.values.indexOf(valueStr);
            if (index >= nativeResolutionStartIndex) {
                Dialog.displayDialog(this, getString(R.string.title_native_res_dialog), getString(R.string.text_native_res_dialog), false);
            }
            resetBitrateToDefault(valueStr, null);
            return true;
        };
        fps.onChange = (item, value) -> {
            String valueStr = (String) value;
            if (nativeFramerateShown && item.values.get(item.values.size() - 1).equals(valueStr)) {
                Dialog.displayDialog(this, getString(R.string.title_native_fps_dialog), getString(R.string.text_native_res_dialog), false);
            }
            resetBitrateToDefault(null, valueStr);
            return true;
        };

        find("checkbox_unlock_fps").onChange = (item, value) -> {
            // The frame rate list depends on it: rebuild once the value is stored
            new Handler().post(this::build);
            return true;
        };
        find("checkbox_enable_rumble").onChange = (item, value) -> {
            if (Boolean.TRUE.equals(value) && !profileMode && !BluetoothHidRumble.hasPermission(this)) {
                requestPermissions(new String[] { Manifest.permission.BLUETOOTH_CONNECT }, BT_PERMISSION_REQUEST_CODE);
            }
            return true;
        };



        SettingsScreen.Item debug = find("pref_debug_info");
        if (debug != null) {
            debug.action = () -> startActivity(new Intent(this, DebugInfoActivity.class));
        }

        categoryIndex = Math.min(categoryIndex, categories.size() - 1);
        categoryAdapter.notifyDataSetChanged();
        itemAdapter.notifyDataSetChanged();
        if (selectedRow >= 0) {
            itemList.setSelection(Math.min(selectedRow, Math.max(0, itemAdapter.getCount() - 1)));
        }
    }

    private SettingsScreen.Item find(String key) {
        for (SettingsScreen.Category category : categories) {
            SettingsScreen.Item item = category.find(key);
            if (item != null) {
                return item;
            }
        }
        return null;
    }

    /** Native resolutions and frame rates of this display, the limits of its decoders, HDR: as the Artemis screen did it */
    private void applyDisplayCapabilities(SettingsScreen.Item resolution, SettingsScreen.Item fps) {

        Display display = getWindowManager().getDefaultDisplay();
        float maxSupportedFps = display.getRefreshRate();
        int maxSupportedResW = 0;

        boolean hasInsets = false;
        DisplayCutout cutout = display.getCutout();
        if (cutout != null) {
            int widthInsets = cutout.getSafeInsetLeft() + cutout.getSafeInsetRight();
            int heightInsets = cutout.getSafeInsetBottom() + cutout.getSafeInsetTop();
            if (widthInsets != 0 || heightInsets != 0) {
                DisplayMetrics metrics = new DisplayMetrics();
                display.getRealMetrics(metrics);
                int width = Math.max(metrics.widthPixels - widthInsets, metrics.heightPixels - heightInsets);
                int height = Math.min(metrics.widthPixels - widthInsets, metrics.heightPixels - heightInsets);
                addNativeResolutionEntries(resolution, width, height, false, false);
                hasInsets = true;
            }
        }

        for (Display.Mode candidate : display.getSupportedModes()) {
            int width = Math.max(candidate.getPhysicalWidth(), candidate.getPhysicalHeight());
            int height = Math.min(candidate.getPhysicalWidth(), candidate.getPhysicalHeight());
            // TVs report strange values here, so only offer a native resolution above 4K
            if (width > 3840 || height > 2160) {
                addNativeResolutionEntries(resolution, width, height, hasInsets, false);
            }
            if ((width >= 3840 || height >= 2160) && maxSupportedResW < 3840) {
                maxSupportedResW = 3840;
            }
            else if ((width >= 2560 || height >= 1440) && maxSupportedResW < 2560) {
                maxSupportedResW = 2560;
            }
            else if ((width >= 1920 || height >= 1080) && maxSupportedResW < 1920) {
                maxSupportedResW = 1920;
            }
            if (candidate.getRefreshRate() > maxSupportedFps) {
                maxSupportedFps = candidate.getRefreshRate();
            }
        }

        MediaCodecHelper.initialize(this, GlPreferences.readPreferences(this).glRenderer);
        for (String mime : new String[] { "video/avc", "video/hevc" }) {
            MediaCodecInfo decoder = MediaCodecHelper.findProbableSafeDecoder(mime, -1);
            if (decoder == null) {
                continue;
            }
            Range<Integer> widths = decoder.getCapabilitiesForType(mime).getVideoCapabilities().getSupportedWidths();
            LimeLog.info(mime + " supported width range: " + widths.getLower() + " - " + widths.getUpper());
            // If 720p is not reported as supported, ignore all results from this API
            if (widths.contains(1280)) {
                if (widths.contains(3840) && maxSupportedResW < 3840) {
                    maxSupportedResW = 3840;
                }
                else if (widths.contains(1920) && maxSupportedResW < 1920) {
                    maxSupportedResW = 1920;
                }
                else if (maxSupportedResW < 1280) {
                    maxSupportedResW = 1280;
                }
            }
        }
        LimeLog.info("Maximum resolution slot: " + maxSupportedResW);

        if (maxSupportedResW != 0) {
            if (maxSupportedResW < 3840) {
                removeEntryAndSetValue(resolution, PreferenceConfiguration.RES_4K, PreferenceConfiguration.RES_1440P);
            }
            if (maxSupportedResW < 2560) {
                removeEntryAndSetValue(resolution, PreferenceConfiguration.RES_1440P, PreferenceConfiguration.RES_1080P);
            }
            if (maxSupportedResW < 1920) {
                removeEntryAndSetValue(resolution, PreferenceConfiguration.RES_1080P, PreferenceConfiguration.RES_720P);
            }
        }

        if (!prefs.getBoolean("checkbox_unlock_fps", false)) {
            // We give some extra room in case the FPS is rounded down
            if (maxSupportedFps < 118) {
                removeEntryAndSetValue(fps, "120", "90");
            }
            if (maxSupportedFps < 88) {
                removeEntryAndSetValue(fps, "90", "60");
            }
        }
        addNativeFrameRateEntry(fps, maxSupportedFps, false);

        boolean hdr10 = false;
        Display.HdrCapabilities hdr = display.getHdrCapabilities();
        if (hdr != null) {
            for (int type : hdr.getSupportedHdrTypes()) {
                if (type == Display.HdrCapabilities.HDR_TYPE_HDR10) {
                    hdr10 = true;
                    break;
                }
            }
        }
        if (!hdr10) {
            LimeLog.info("Excluding HDR toggle based on display capabilities");
            find("checkbox_enable_hdr").visible = false;
        }
    }

    private void addNativeResolutionEntries(SettingsScreen.Item item, int width, int height, boolean insetsRemoved, boolean custom) {
        if (PreferenceConfiguration.isSquarishScreen(width, height)) {
            addNativeResolutionEntry(item, height, width, insetsRemoved, true, custom);
        }
        addNativeResolutionEntry(item, width, height, insetsRemoved, false, custom);
    }

    private void addNativeResolutionEntry(SettingsScreen.Item item, int width, int height, boolean insetsRemoved, boolean portrait, boolean custom) {
        String name;
        if (insetsRemoved) {
            name = getString(R.string.resolution_prefix_native_fullscreen);
        }
        else {
            name = getString(custom ? R.string.resolution_prefix_custom : R.string.resolution_prefix_native);
        }
        if (PreferenceConfiguration.isSquarishScreen(width, height)) {
            name += " " + getString(portrait ? R.string.resolution_prefix_native_portrait : R.string.resolution_prefix_native_landscape);
        }
        name += " (" + width + "x" + height + ")";
        String value = width + "x" + height;
        if (item.values.contains(value)) {
            return;
        }
        if (item.values.size() < nativeResolutionStartIndex) {
            nativeResolutionStartIndex = item.values.size();
        }
        item.names.add(name);
        item.values.add(value);
    }

    private void addNativeFrameRateEntry(SettingsScreen.Item item, float framerate, boolean custom) {
        if (!custom) {
            framerate = Math.round(framerate);
            if (framerate == 0) {
                return;
            }
        }
        String value = custom ? Float.toString(framerate) : Integer.toString(Math.round(framerate));
        String name = getString(custom ? R.string.resolution_prefix_custom : R.string.resolution_prefix_native) +
                " (" + value + " " + getString(R.string.fps_suffix_fps) + ")";
        if (item.values.contains(value)) {
            nativeFramerateShown = false;
            return;
        }
        item.names.add(name);
        item.values.add(value);
        nativeFramerateShown = true;
    }

    private void removeEntryAndSetValue(SettingsScreen.Item item, String value, String nextDefault) {
        for (int i = item.values.size() - 1; i >= 0; i--) {
            if (item.values.get(i).equalsIgnoreCase(value)) {
                item.values.remove(i);
                item.names.remove(i);
            }
        }
        if (getChoice(item).equalsIgnoreCase(value)) {
            prefs.edit().putString(item.key, nextDefault).apply();
            resetBitrateToDefault(null, null);
        }
    }

    private void resetBitrateToDefault(String res, String fps) {
        if (res == null) {
            res = prefs.getString("list_resolution", PreferenceConfiguration.DEFAULT_RESOLUTION);
        }
        if (fps == null) {
            fps = prefs.getString("list_fps", PreferenceConfiguration.DEFAULT_FPS);
        }
        prefs.edit().putInt("seekbar_bitrate_kbps", PreferenceConfiguration.getDefaultBitrate(res, fps)).apply();
    }

    // ---- Values ----

    private boolean getToggle(SettingsScreen.Item item) {
        return prefs.getBoolean(item.key, item.defBool);
    }

    private String getChoice(SettingsScreen.Item item) {
        return prefs.getString(item.key, item.defValue);
    }

    private int getSlider(SettingsScreen.Item item) {
        return prefs.getInt(item.key, item.defInt);
    }

    private String getText(SettingsScreen.Item item) {
        return prefs.getString(item.key, item.defText);
    }

    private String formatSlider(SettingsScreen.Item item, int value) {
        String text = item.divisor != 1 ? String.format(Locale.ROOT, "%.1f", value / (float) item.divisor) : String.valueOf(value);
        if (item.suffix == null) {
            return text;
        }
        return item.suffix.length() > 1 ? text + " " + item.suffix : text + item.suffix;
    }

    private boolean isEnabled(SettingsScreen.Item item) {
        if ("checkbox_aaudio_renderer".equals(item.key)) {
            // AAudio cannot host the system equalizer session
            return !prefs.getBoolean("checkbox_enable_audiofx", false);
        }
        if (item.dependency == null) {
            return true;
        }
        SettingsScreen.Item parent = find(item.dependency);
        boolean parentOn = prefs.getBoolean(item.dependency, parent != null && parent.defBool);
        return parentOn && (parent == null || isEnabled(parent));
    }

    /** In a profile: does this row differ from the current settings of the app */
    private boolean isChanged(SettingsScreen.Item item) {
        if (!profileMode || item.key == null || baseline == null) {
            return false;
        }
        Object mine = prefs.getAll().get(item.key);
        Object base = baseline.get(item.key);
        if (mine == null) {
            return false;
        }
        return !mine.equals(base);
    }

    // ---- Interaction ----

    private void onRowClick(SettingsScreen.Item item) {
        if (!isEnabled(item)) {
            return;
        }
        switch (item.type) {
            case SettingsScreen.TYPE_TOGGLE:
                setToggle(item, !getToggle(item));
                break;
            case SettingsScreen.TYPE_CHOICE:
                showChoice(item);
                break;
            case SettingsScreen.TYPE_SLIDER:
                showSlider(item);
                break;
            case SettingsScreen.TYPE_TEXT:
                showTextInput(item);
                break;
            case SettingsScreen.TYPE_ACTION:
                if (item.action != null) {
                    item.action.run();
                }
                break;
            case SettingsScreen.TYPE_LINK:
                openLink(item);
                break;
            default:
                break;
        }
    }

    private void setToggle(SettingsScreen.Item item, boolean value) {
        if (item.onChange == null || item.onChange.onChange(item, value)) {
            prefs.edit().putBoolean(item.key, value).apply();
        }
        // The switch on screen slides right away; the rebind below finds the same value and leaves it alone
        refreshRow(item);
        // A dependent row may have changed its enabled state
        itemAdapter.notifyDataSetChanged();
    }

    /** Stores a chooser's value; on screen the old value slides out and the new one slides in from the side it lies on */
    private void setChoice(SettingsScreen.Item item, String value) {
        int from = item.values.indexOf(getChoice(item));
        if (item.onChange == null || item.onChange.onChange(item, value)) {
            prefs.edit().putString(item.key, value).apply();
        }
        int to = item.values.indexOf(getChoice(item));
        View row = rowFor(item);
        if (row == null || from < 0 || to < 0 || from == to) {
            itemAdapter.notifyDataSetChanged();
            return;
        }
        TextView text = row.findViewById(R.id.rowValue);
        float shift = 12 * getResources().getDisplayMetrics().density * (to > from ? 1 : -1);
        text.animate().cancel();
        text.animate().translationX(-shift).alpha(0f).setDuration(70).withEndAction(() -> {
            itemAdapter.bind(row, item);
            itemAdapter.notifyDataSetChanged();
            text.setTranslationX(shift);
            text.animate().translationX(0f).alpha(1f).setDuration(140).start();
        }).start();
    }

    /** The row on screen that shows this item, if any */
    private View rowFor(SettingsScreen.Item item) {
        for (int i = 0; i < itemList.getChildCount(); i++) {
            int position = itemList.getFirstVisiblePosition() + i;
            if (position < itemAdapter.getCount() && itemAdapter.getItem(position) == item) {
                return itemList.getChildAt(i);
            }
        }
        return null;
    }

    /** Re-binds only the row showing this item, so its control animates and the list does not flicker */
    private void refreshRow(SettingsScreen.Item item) {
        View row = rowFor(item);
        if (row != null) {
            itemAdapter.bind(row, item);
        }
        else {
            itemAdapter.notifyDataSetChanged();
        }
    }

    private void showChoice(SettingsScreen.Item item) {
        String current = getChoice(item);
        List<PanelMenu.Item> rows = new ArrayList<>();
        for (int i = 0; i < item.values.size(); i++) {
            final String value = item.values.get(i);
            rows.add(PanelMenu.Item.checked(item.names.get(i), value.equals(current), () -> {
                setChoice(item, value);
            }));
        }
        PanelMenu.show(this, getString(item.title), rows);
    }

    private void showSlider(SettingsScreen.Item item) {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * density);
        layout.setPadding(pad, Math.round(8 * density), pad, Math.round(8 * density));

        if (item.summary != 0) {
            TextView message = new TextView(this);
            message.setText(item.summary);
            message.setTextSize(14);
            message.setTextColor(0xFFB8B8BC);
            layout.addView(message);
        }

        TextView valueText = new TextView(this);
        valueText.setGravity(Gravity.CENTER_HORIZONTAL);
        valueText.setTextSize(32);
        valueText.setTextColor(0xFFFFFFFF);
        valueText.setPadding(0, Math.round(12 * density), 0, Math.round(4 * density));
        layout.addView(valueText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(item.max - item.min);
        if (item.keyStep != 0) {
            seekBar.setKeyProgressIncrement(item.keyStep);
        }
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                int value = progress + item.min;
                int rounded = Math.round((float) value / item.step) * item.step;
                if (rounded != value) {
                    bar.setProgress(rounded - item.min);
                    return;
                }
                valueText.setText(formatSlider(item, value));
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
        seekBar.setProgress(getSlider(item) - item.min);
        valueText.setText(formatSlider(item, getSlider(item)));
        layout.addView(seekBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(item.title)
                .setView(layout)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    int value = seekBar.getProgress() + item.min;
                    if (item.onChange == null || item.onChange.onChange(item, value)) {
                        prefs.edit().putInt(item.key, value).apply();
                    }
                    itemAdapter.notifyDataSetChanged();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> seekBar.requestFocus());
        dialog.show();
        Dialog.compact(dialog);
    }

    private void showTextInput(SettingsScreen.Item item) {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(20 * density);
        layout.setPadding(pad, Math.round(8 * density), pad, 0);

        if (item.dialogMessage != 0) {
            TextView message = new TextView(this);
            message.setText(item.dialogMessage);
            message.setTextSize(14);
            message.setTextColor(0xFFB8B8BC);
            layout.addView(message);
        }

        EditText input = new EditText(this);
        input.setInputType(item.inputType);
        input.setSingleLine(true);
        if (item.maxLength > 0) {
            input.setFilters(new InputFilter[] { new InputFilter.LengthFilter(item.maxLength) });
        }
        input.setText(getText(item));
        input.setSelection(input.getText().length());
        layout.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(item.title)
                .setView(layout)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    String value = input.getText().toString().trim();
                    if (item.onChange == null || item.onChange.onChange(item, value)) {
                        prefs.edit().putString(item.key, value).apply();
                    }
                    itemAdapter.notifyDataSetChanged();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(d -> input.requestFocus());
        dialog.show();
        Dialog.compact(dialog);
    }

    private void openLink(SettingsScreen.Item item) {
        if (HelpLauncher.isAppLink(this, item.url)) {
            if (!HelpLauncher.launchAppLink(this, item.url)) {
                if (item.fallbackUrl != null) {
                    HelpLauncher.launchUrl(this, item.fallbackUrl);
                }
                else {
                    toast(R.string.no_app_for_link);
                }
            }
            return;
        }
        HelpLauncher.launchUrl(this, item.url);
    }

    private void toast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == BT_PERMISSION_REQUEST_CODE &&
                (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            Toast.makeText(this, R.string.rumble_bluetooth_permission_denied, Toast.LENGTH_LONG).show();
        }
    }

    // ---- Profiles ----

    private void saveProfile() {
        Map<String, Object> options = new HashMap<>(prefs.getAll());
        String displayName;
        if (profile != null) {
            profile.setOptions(options);
            profile.setModifiedUtc(System.currentTimeMillis());
            displayName = profile.getName();
            ProfilesManager.getInstance().update(profile);
        }
        else {
            String name = pendingProfileName != null ? pendingProfileName.trim() : "";
            if (name.isEmpty()) {
                name = getString(R.string.profile_manager_profile) + (ProfilesManager.getInstance().getProfiles().size() + 1);
            }
            long now = System.currentTimeMillis();
            ProfilesManager.getInstance().add(new SettingsProfile(UUID.randomUUID(), name, now, now, options));
            displayName = name;
        }
        if (ProfilesManager.getInstance().save(this)) {
            Toast.makeText(this, getString(R.string.profile_manager_profile_saved, displayName), Toast.LENGTH_SHORT).show();
        }
        else {
            Toast.makeText(this, R.string.profile_manager_failed_to_save, Toast.LENGTH_LONG).show();
        }
        finish();
    }

    private void showRenameDialog() {
        EditText input = new EditText(this);
        String initial = profile != null ? profile.getName() : (pendingProfileName != null ? pendingProfileName : "");
        input.setText(initial);
        input.setSelection(initial.length());
        input.setSingleLine(true);
        float density = getResources().getDisplayMetrics().density;
        LinearLayout layout = new LinearLayout(this);
        int pad = Math.round(20 * density);
        layout.setPadding(pad, 0, pad, 0);
        layout.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.profile_manager_edit_profile_name)
                .setView(layout)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        toast(R.string.profile_manager_name_cannot_be_blank);
                        return;
                    }
                    if (profile != null) {
                        profile.setName(name);
                        profile.setModifiedUtc(System.currentTimeMillis());
                        ProfilesManager.getInstance().update(profile);
                    }
                    else {
                        pendingProfileName = name;
                    }
                    updateTitle();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.show();
        Dialog.compact(dialog);
    }

    // ---- Adapters ----

    private final class CategoryAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return categories.size();
        }

        @Override
        public SettingsScreen.Category getItem(int position) {
            return categories.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(SettingsActivity.this).inflate(R.layout.settings_category_row, parent, false);
            }
            String name = getString(getItem(position).title);
            ((TextView) convertView.findViewById(R.id.categoryName)).setText(categoryChanged(position) ? "\u2022 " + name : name);
            // The dark pill marks the category whose rows are on the right; the cursor is the list's selected state
            convertView.setActivated(position == categoryIndex);
            return convertView;
        }
    }

    private final class ItemAdapter extends BaseAdapter {
        private List<SettingsScreen.Item> rows() {
            List<SettingsScreen.Item> visible = new ArrayList<>();
            if (categoryIndex >= 0 && categoryIndex < categories.size()) {
                for (SettingsScreen.Item item : categories.get(categoryIndex).items) {
                    if (item.visible) {
                        visible.add(item);
                    }
                }
            }
            return visible;
        }

        @Override
        public int getCount() {
            return rows().size();
        }

        @Override
        public SettingsScreen.Item getItem(int position) {
            return rows().get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(SettingsActivity.this).inflate(R.layout.settings_row, parent, false);
            }
            bind(convertView, getItem(position));
            return convertView;
        }

        void bind(View row, SettingsScreen.Item item) {
            // The same row showing the same setting again: its control animates to the new value
            boolean sameItem = row.getTag() == item;
            row.setTag(item);

            TextView rowTitle = row.findViewById(R.id.rowTitle);
            TextView rowSummary = row.findViewById(R.id.rowSummary);
            TextView rowValue = row.findViewById(R.id.rowValue);
            ToggleView toggle = row.findViewById(R.id.rowSwitch);
            TextView chevron = row.findViewById(R.id.rowChevron);
            SliderTrackView track = row.findViewById(R.id.rowTrack);

            rowTitle.setText((isChanged(item) ? "\u2022 " : "") + getString(item.title));
            if (item.summary != 0) {
                rowSummary.setText(item.summary);
                rowSummary.setVisibility(View.VISIBLE);
            }
            else {
                rowSummary.setVisibility(View.GONE);
            }

            rowValue.setVisibility(View.GONE);
            toggle.setVisibility(View.GONE);
            chevron.setVisibility(View.GONE);
            track.setVisibility(View.GONE);

            switch (item.type) {
                case SettingsScreen.TYPE_TOGGLE:
                    toggle.setVisibility(View.VISIBLE);
                    toggle.setChecked(getToggle(item), sameItem);
                    break;
                case SettingsScreen.TYPE_CHOICE:
                    rowValue.setText(item.nameOf(getChoice(item)));
                    rowValue.setVisibility(View.VISIBLE);
                    break;
                case SettingsScreen.TYPE_SLIDER: {
                    int value = getSlider(item);
                    rowValue.setText(formatSlider(item, value));
                    rowValue.setVisibility(View.VISIBLE);
                    track.setFraction((value - item.min) / (float) (item.max - item.min), sameItem);
                    track.setVisibility(View.VISIBLE);
                    break;
                }
                case SettingsScreen.TYPE_TEXT:
                    rowValue.setText(getText(item));
                    rowValue.setVisibility(View.VISIBLE);
                    break;
                default:
                    chevron.setVisibility(View.VISIBLE);
                    break;
            }

            row.setAlpha(SettingsActivity.this.isEnabled(item) ? 1f : 0.38f);
        }
    }

    /** In a profile: does any row of this category differ from the current settings of the app */
    private boolean categoryChanged(int index) {
        if (!profileMode || index >= categories.size()) {
            return false;
        }
        for (SettingsScreen.Item item : categories.get(index).items) {
            if (isChanged(item)) {
                return true;
            }
        }
        return false;
    }
}
