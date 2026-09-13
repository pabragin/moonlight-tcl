package com.limelight.preferences;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;

import com.limelight.R;
import com.limelight.utils.HelpLauncher;

public class WebLauncherPreference extends Preference {
    private String url;
    // Optional web page to open when 'url' is an app link (obtainium://...) that no installed app handles
    private String fallbackUrl;

    public WebLauncherPreference(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        initialize(attrs);
    }

    public WebLauncherPreference(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initialize(attrs);
    }

    public WebLauncherPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        initialize(attrs);
    }

    private void initialize(AttributeSet attrs) {
        if (attrs == null) {
            throw new IllegalStateException("WebLauncherPreference must have attributes!");
        }

        url = attrs.getAttributeValue(null, "url");
        if (url == null) {
            throw new IllegalStateException("WebLauncherPreference must have 'url' attribute!");
        }
        fallbackUrl = attrs.getAttributeValue(null, "fallbackUrl");
    }

    @Override
    public void onClick() {
        Context context = getContext();
        if (HelpLauncher.isAppLink(context, url)) {
            if (!HelpLauncher.launchAppLink(context, url)) {
                if (fallbackUrl != null) {
                    HelpLauncher.launchUrl(context, fallbackUrl);
                }
                else {
                    Toast.makeText(context, R.string.no_app_for_link, Toast.LENGTH_LONG).show();
                }
            }
            return;
        }
        HelpLauncher.launchUrl(context, url);
    }
}
