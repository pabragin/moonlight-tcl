package com.limelight.utils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.ArrayList;

public class KeyConfigHelper {
    public static class ShortcutFile {
        public List<Shortcut> data;

        public ShortcutFile() {
            this.data = new ArrayList<>();
        }
        public ShortcutFile(List<Shortcut> data) {
            this.data = data;
        }
    }

    public static class Shortcut {
        public String id;
        public String name;
        public boolean sticky = false;  // Default to false
        public List<String> keys;

        public Shortcut() {
            this.keys = new ArrayList<>();
        }

        public Shortcut(String id, String name, boolean sticky, List<String> keys) {
            this.id = id;
            this.name = name;
            this.sticky = sticky;
            this.keys = keys;
        }
    }

    /**
     * Parses {"data":[{"id":..,"name":..,"sticky":..,"keys":[..]}]}. Missing fields keep their
     * defaults, like the Gson mapping this replaces; malformed JSON is an IllegalArgumentException.
     */
    public static ShortcutFile parseShortcutFile(String json) {
        try {
            JSONObject root = new JSONObject(json);
            List<Shortcut> shortcuts = new ArrayList<>();
            JSONArray data = root.optJSONArray("data");
            if (data != null) {
                for (int i = 0; i < data.length(); i++) {
                    JSONObject entry = data.getJSONObject(i);
                    List<String> keys = new ArrayList<>();
                    JSONArray keyArray = entry.optJSONArray("keys");
                    if (keyArray != null) {
                        for (int k = 0; k < keyArray.length(); k++) {
                            keys.add(keyArray.getString(k));
                        }
                    }
                    shortcuts.add(new Shortcut(
                            entry.optString("id", null),
                            entry.optString("name", null),
                            entry.optBoolean("sticky", false),
                            keys));
                }
            }
            return new ShortcutFile(shortcuts);
        } catch (JSONException e) {
            throw new IllegalArgumentException("Invalid shortcut file", e);
        }
    }
}
