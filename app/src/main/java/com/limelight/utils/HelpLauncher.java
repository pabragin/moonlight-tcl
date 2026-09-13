package com.limelight.utils;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
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
        // Try to launch the default browser
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setData(Uri.parse(url));

            // Several Android TV devices will lie and say they do have a browser even though the OS
            // just shows an error dialog if we try to use it. We used to try to be clever and check
            // the package name of the resolved intent, but it's not worth it anymore with Android 11's
            // package visibility changes. We'll just always use the WebView on Android TV.
            if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
                context.startActivity(i);
                return;
            }
        } catch (Exception e) {
            // This is only supposed to throw ActivityNotFoundException but
            // it can (at least) also throw SecurityException if a user's default
            // browser is not exported. We'll catch everything to workaround this.

            // Fall through
        }

        // This platform has no browser (possibly a leanback device)
        // We'll launch our WebView activity
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
