package com.limelight.utils;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.limelight.HelpActivity;

public class HelpLauncher {
    // "@<resId>" is how a string resource reference arrives from a raw XML attribute
    private static String resolveStringRef(Context context, String url) {
        if (url.startsWith("@")) {
            try {
                int resId = Integer.parseInt(url.substring(1));
                return context.getString(resId);
            } catch (Exception ignored) {

            }
        }
        return url;
    }

    /** True for links with a custom scheme (obtainium://...) that only the owning app can open. */
    public static boolean isAppLink(Context context, String url) {
        String scheme = Uri.parse(resolveStringRef(context, url)).getScheme();
        return scheme != null && !scheme.equals("http") && !scheme.equals("https");
    }

    /**
     * Opens an app link with the app that owns its scheme. Never falls back to the WebView, which
     * cannot forward such links. Returns false when no installed app handles it.
     */
    public static boolean launchAppLink(Context context, String url) {
        try {
            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(resolveStringRef(context, url))));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void launchUrl(Context context, String url) {
        url = resolveStringRef(context, url);
        // Android TV: the system browser is missing or just shows an error dialog, so always use our WebView
        Intent i = new Intent(context, HelpActivity.class);
        i.setData(Uri.parse(url));
        context.startActivity(i);
    }

    public static void launchSetupGuide(Context context) {
        launchUrl(context, "https://github.com/moonlight-stream/moonlight-docs/wiki/Setup-Guide");
    }

    public static void launchTroubleshooting(Context context) {
        launchUrl(context, "https://github.com/moonlight-stream/moonlight-docs/wiki/Troubleshooting");
    }

    public static void launchGameStreamEolFaq(Context context) {
        launchUrl(context, "https://github.com/moonlight-stream/moonlight-docs/wiki/NVIDIA-GameStream-End-Of-Service-Announcement-FAQ");
    }
}
