package com.limelight.profiles;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;

import androidx.annotation.NonNull;

import com.limelight.LimeLog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ProfilesManager {
    private static final String PROFILES_DIR = "profiles";
    private static final String PROFILES_FILE = "profiles.json";

    // JSON field names, the same ones Gson wrote for the ProfilesData/SettingsProfile fields before
    private static final String KEY_PROFILES = "profiles";
    private static final String KEY_ACTIVE_PROFILE_ID = "activeProfileId";
    private static final String KEY_UUID = "uuid";
    private static final String KEY_NAME = "name";
    private static final String KEY_CREATED_UTC = "createdUtc";
    private static final String KEY_MODIFIED_UTC = "modifiedUtc";
    private static final String KEY_OPTIONS = "options";

    static ProfilesManager instance;

    private final Map<UUID, SettingsProfile> profiles = new LinkedHashMap<>();
    private UUID activeProfileId;
    private final List<ProfileChangeListener> listeners = new ArrayList<>();
    private Context appContext; // Application context for auto-save

    private ProfilesManager() {}

    public static synchronized ProfilesManager getInstance() {
        if (instance == null) {
            instance = new ProfilesManager();
        }
        return instance;
    }

    public boolean load(Context context) {
        LimeLog.info("ArtemisProfile: Loading profile...");
        if (context == null) {
            return false;
        }

        try {
            this.appContext = context.getApplicationContext();
        } catch (Exception e) {
            // If getApplicationContext() fails (e.g., during app startup), use the context directly
            this.appContext = context;
        }

        // Additional safety check
        if (this.appContext == null) {
            return false;
        }

        try {
            File dir = new File(this.appContext.getFilesDir(), PROFILES_DIR);
            if (!dir.exists() && !dir.mkdirs()) {
                return false;
            }
            File file = new File(dir, PROFILES_FILE);
            if (!file.exists()) {
                // We don't want to warn user about profile not exist
                return true;
            }
            try {
                ProfilesData data = parseProfiles(readFile(file));
                if (data.profiles != null) {
                    profiles.clear();
                    for (SettingsProfile p : data.profiles) {
                        profiles.put(p.getUuid(), p);
                    }
                    activeProfileId = data.activeProfileId;
                }
            } catch (IOException | JSONException | IllegalArgumentException e) {
                LimeLog.warning("ArtemisProfile: Failed to load profiles from file:" + e);
                e.printStackTrace();
                return false;
            }
        } catch (Exception e) {
            LimeLog.warning("ArtemisProfile: Failed to load profiles:" + e);
            e.printStackTrace();
            return false;
        }

        return true;
    }

    public boolean save(Context context) {
        if (context == null) {
            return false;
        }

        try {
            File dir = new File(context.getFilesDir(), PROFILES_DIR);
            if (!dir.exists() && !dir.mkdirs()) {
                return false;
            }
            File file = new File(dir, PROFILES_FILE);
            try {
                ProfilesData data = new ProfilesData();
                data.profiles = new ArrayList<>(profiles.values());
                data.activeProfileId = activeProfileId;
                writeFile(file, serializeProfiles(data));
            } catch (IOException | JSONException e) {
                LimeLog.warning("ArtemisProfile: Failed to save profiles to file:" + e);
                e.printStackTrace();
                return false;
            }
        } catch (Exception e) {
            LimeLog.warning("ArtemisProfile: Failed to save profiles:" + e);
            e.printStackTrace();
            return false;
        }

        return true;
    }

    public List<SettingsProfile> getProfiles() {
        return new ArrayList<>(profiles.values());
    }

    public void add(SettingsProfile profile) {
        profiles.put(profile.getUuid(), profile);
        notifyListeners();
        saveIfPossible();
    }

    public void update(SettingsProfile profile) {
        profiles.put(profile.getUuid(), profile);
        notifyListeners();
        saveIfPossible();
    }

    public void delete(UUID uuid) {
        profiles.remove(uuid);
        if (uuid.equals(activeProfileId)) {
            activeProfileId = null;
        }
        notifyListeners();
        saveIfPossible();
    }

    public void setActive(UUID uuid) {
        activeProfileId = uuid;
        notifyListeners();
        saveIfPossible();
    }

    public SettingsProfile getActive() {
        return activeProfileId == null ? null : profiles.get(activeProfileId);
    }

    @NonNull
    public String getActiveName() {
        SettingsProfile active = getActive();
        return active == null ? "" : active.getName();
    }

    public void addListener(ProfileChangeListener listener) {
        listeners.add(listener);
    }

    public void removeListener(ProfileChangeListener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners() {
        for (ProfileChangeListener listener : listeners) {
            listener.onProfilesChanged();
        }
    }

    static class ProfilesData {
        List<SettingsProfile> profiles;
        UUID activeProfileId;
    }

    public interface ProfileChangeListener {
        void onProfilesChanged();
    }

    // profiles.json: {"profiles":[{"uuid":"..","name":"..","createdUtc":0,"modifiedUtc":0,"options":{..}}],"activeProfileId":".."}

    static ProfilesData parseProfiles(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        ProfilesData data = new ProfilesData();
        data.profiles = new ArrayList<>();
        JSONArray array = root.optJSONArray(KEY_PROFILES);
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject entry = array.getJSONObject(i);
                JSONObject optionsJson = entry.optJSONObject(KEY_OPTIONS);
                data.profiles.add(new SettingsProfile(
                        UUID.fromString(entry.getString(KEY_UUID)),
                        entry.optString(KEY_NAME, null),
                        entry.optLong(KEY_CREATED_UTC, 0),
                        entry.optLong(KEY_MODIFIED_UTC, 0),
                        optionsJson != null ? toOptions(optionsJson) : null));
            }
        }
        String active = root.optString(KEY_ACTIVE_PROFILE_ID, null);
        data.activeProfileId = active != null ? UUID.fromString(active) : null;
        return data;
    }

    static String serializeProfiles(ProfilesData data) throws JSONException {
        JSONObject root = new JSONObject();
        JSONArray array = new JSONArray();
        for (SettingsProfile profile : data.profiles) {
            JSONObject entry = new JSONObject();
            entry.put(KEY_UUID, profile.getUuid().toString());
            entry.put(KEY_NAME, profile.getName());
            entry.put(KEY_CREATED_UTC, profile.getCreatedUtc());
            entry.put(KEY_MODIFIED_UTC, profile.getModifiedUtc());
            if (profile.getOptions() != null) {
                entry.put(KEY_OPTIONS, fromOptions(profile.getOptions()));
            }
            array.put(entry);
        }
        root.put(KEY_PROFILES, array);
        if (data.activeProfileId != null) {
            root.put(KEY_ACTIVE_PROFILE_ID, data.activeProfileId.toString());
        }
        return root.toString();
    }

    /**
     * Option values are whatever SharedPreferences.getAll() holds: Boolean, String, numbers and
     * string sets. Arrays come back as string sets so the overlay's getStringSet() works.
     */
    private static Map<String, Object> toOptions(JSONObject json) throws JSONException {
        Map<String, Object> options = new LinkedHashMap<>();
        Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = json.get(key);
            if (value == JSONObject.NULL) {
                continue;
            }
            if (value instanceof JSONArray) {
                JSONArray array = (JSONArray) value;
                Set<String> set = new HashSet<>();
                for (int i = 0; i < array.length(); i++) {
                    set.add(array.getString(i));
                }
                options.put(key, set);
            } else if (!(value instanceof JSONObject)) {
                options.put(key, value);
            }
        }
        return options;
    }

    private static JSONObject fromOptions(Map<String, Object> options) throws JSONException {
        JSONObject json = new JSONObject();
        for (Map.Entry<String, Object> option : options.entrySet()) {
            Object value = option.getValue();
            if (value == null) {
                continue;
            }
            if (value instanceof Collection) {
                json.put(option.getKey(), new JSONArray((Collection<?>) value));
            } else {
                json.put(option.getKey(), value);
            }
        }
        return json;
    }

    private static String readFile(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int read = 0;
            while (read < bytes.length) {
                int n = in.read(bytes, read, bytes.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            return new String(bytes, 0, read, StandardCharsets.UTF_8);
        }
    }

    private static void writeFile(File file, String content) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Returns a SharedPreferences that overlays the active profile's options on top of the real prefs.
     */
    public SharedPreferences getOverlayingSharedPreferences(Context context) {
        SharedPreferences base = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context);
        SettingsProfile active = getActive();
        if (active == null || active.getOptions() == null) {
            return base;
        }
        return new OverlaySharedPreferences(base, active.getOptions());
    }

    /**
     * Wraps a SharedPreferences to override and shadow values from a profile's options map.
     */
    private static class OverlaySharedPreferences implements SharedPreferences {
        private final SharedPreferences base;
        private final Map<String, Object> patch;
        OverlaySharedPreferences(SharedPreferences base, Map<String, Object> patch) {
            this.base = base;
            this.patch = patch;
        }
        @Override public Map<String, ?> getAll() {
            Map<String, Object> combined = new LinkedHashMap<>(base.getAll());
            combined.putAll(patch);
            return combined;
        }
        @Override public String getString(String key, String defValue) {
            if (patch.containsKey(key)) return (String) patch.get(key);
            return base.getString(key, defValue);
        }
        @Override public int getInt(String key, int defValue) {
            if (patch.containsKey(key)) return ((Number) patch.get(key)).intValue();
            return base.getInt(key, defValue);
        }
        @Override public long getLong(String key, long defValue) {
            if (patch.containsKey(key)) return ((Number) patch.get(key)).longValue();
            return base.getLong(key, defValue);
        }
        @Override public float getFloat(String key, float defValue) {
            if (patch.containsKey(key)) return ((Number) patch.get(key)).floatValue();
            return base.getFloat(key, defValue);
        }
        @Override public boolean getBoolean(String key, boolean defValue) {
            if (patch.containsKey(key)) return (Boolean) patch.get(key);
            return base.getBoolean(key, defValue);
        }
        @Override public Set<String> getStringSet(String key, Set<String> defValues) {
            if (patch.containsKey(key)) return (Set<String>) patch.get(key);
            return base.getStringSet(key, defValues);
        }
        @Override public boolean contains(String key) {
            return patch.containsKey(key) || base.contains(key);
        }
        @Override public Editor edit() { return base.edit(); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
            base.registerOnSharedPreferenceChangeListener(listener);
        }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
            base.unregisterOnSharedPreferenceChangeListener(listener);
        }
    }

    private boolean saveIfPossible() {
        if (appContext != null) {
            return save(appContext);
        }
        return false;
    }
}
