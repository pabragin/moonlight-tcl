package com.limelight.profiles;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.TestLogSuppressor;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.*;

@Config(sdk = {34}, shadows = {com.limelight.shadows.ShadowMoonBridge.class, com.limelight.shadows.ShadowGameManager.class})
@RunWith(RobolectricTestRunner.class)
public class OverlayPreferencesTest {
    private Context ctx;

    @BeforeClass
    public static void suppressInvalidIdLogs() {
        TestLogSuppressor.install();
    }

    @Before
    public void setup() {
        ctx = ApplicationProvider.getApplicationContext();
        // Reset singleton to ensure clean slate for each run
        ProfilesManager.instance = null;
    }

    /**
     * Profiles written by the Gson-based versions hold plain JSON numbers; whatever numeric type
     * the parser hands back must coerce to int via getInt().
     */
    @Test
    public void overlayPref_CoercesDoubleToInt() {
        ProfilesManager pm = ProfilesManager.getInstance();
        pm.load(ctx);

        Map<String, Object> opts = new HashMap<>();
        opts.put("video_bitrate_kbps", 15000.0);

        SettingsProfile profile = new SettingsProfile(UUID.randomUUID(), "Test", 0, 0, opts);
        pm.add(profile);
        pm.setActive(profile.getUuid());

        SharedPreferences sp = pm.getOverlayingSharedPreferences(ctx);
        assertEquals(15000, sp.getInt("video_bitrate_kbps", -1));
    }

    /**
     * Ensure that a Double value is coerced correctly by getLong().
     */
    @Test
    public void overlayPref_CoercesDoubleToLong() {
        ProfilesManager pm = ProfilesManager.getInstance();
        pm.load(ctx);

        Map<String, Object> opts = new HashMap<>();
        opts.put("decoder_flush_delay_ms", 250.0);

        SettingsProfile profile = new SettingsProfile(UUID.randomUUID(), "TestLong", 0, 0, opts);
        pm.add(profile);
        pm.setActive(profile.getUuid());

        SharedPreferences sp = pm.getOverlayingSharedPreferences(ctx);
        assertEquals(250L, sp.getLong("decoder_flush_delay_ms", -1));
    }

    /**
     * profiles.json as Gson wrote it (20.2.10-tcl3 and earlier) must load through the org.json
     * parser with the same values, and survive a save/load round trip.
     */
    @Test
    public void profilesJson_GsonFormatRoundTrips() throws Exception {
        String uuid = "5f2c9a3e-1b7d-4e0a-9c11-0d5a7b8e2f31";
        String json = "{\"profiles\":[{\"uuid\":\"" + uuid + "\",\"name\":\"4K HDR\",\"createdUtc\":1700000000000,"
                + "\"modifiedUtc\":1700000001000,\"options\":{\"video_bitrate_kbps\":15000,\"checkbox_enable_hdr\":true,"
                + "\"list_fps\":\"60\",\"some_set\":[\"a\",\"b\"],\"seekbar_scale\":1.5}}],\"activeProfileId\":\"" + uuid + "\"}";

        ProfilesManager.ProfilesData data = ProfilesManager.parseProfiles(json);
        assertEquals(1, data.profiles.size());
        assertEquals(UUID.fromString(uuid), data.activeProfileId);
        SettingsProfile profile = data.profiles.get(0);
        assertEquals(UUID.fromString(uuid), profile.getUuid());
        assertEquals("4K HDR", profile.getName());
        assertEquals(1700000000000L, profile.getCreatedUtc());
        assertEquals(1700000001000L, profile.getModifiedUtc());
        Map<String, Object> options = profile.getOptions();
        assertEquals(15000, ((Number) options.get("video_bitrate_kbps")).intValue());
        assertEquals(Boolean.TRUE, options.get("checkbox_enable_hdr"));
        assertEquals("60", options.get("list_fps"));
        assertEquals(1.5f, ((Number) options.get("seekbar_scale")).floatValue(), 0.0001f);
        Set<String> set = new HashSet<>(Arrays.asList("a", "b"));
        assertEquals(set, options.get("some_set"));

        ProfilesManager.ProfilesData again = ProfilesManager.parseProfiles(ProfilesManager.serializeProfiles(data));
        assertEquals(data.activeProfileId, again.activeProfileId);
        assertEquals(profile.getName(), again.profiles.get(0).getName());
        assertEquals(options, again.profiles.get(0).getOptions());
    }

    /**
     * No active profile: Gson left the field out, the writer must too and the reader must accept it.
     */
    @Test
    public void profilesJson_NoActiveProfile() throws Exception {
        ProfilesManager.ProfilesData data = ProfilesManager.parseProfiles("{\"profiles\":[]}");
        assertTrue(data.profiles.isEmpty());
        assertNull(data.activeProfileId);
        String out = ProfilesManager.serializeProfiles(data);
        assertFalse(out.contains("activeProfileId"));
    }
}
