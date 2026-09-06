package com.limelight;

import android.app.Application;
import android.widget.Toast;

import com.limelight.profiles.ProfilesManager;

public class ArtemisApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // adb shell settings put global moonlight_tcl_verbose_log 1 -> chatty diagnostics reach logcat
        LimeLog.setVerbose("1".equals(android.provider.Settings.Global.getString(getContentResolver(), "moonlight_tcl_verbose_log")));
        ProfilesManager profilesManager = ProfilesManager.getInstance();
        if (!profilesManager.load(this)) {
            Toast.makeText(this, R.string.profile_manager_failed_to_load, Toast.LENGTH_LONG).show();
        }
    }
}