package com.limelight.binding.input.capture;

import android.app.Activity;

import com.limelight.LimeLog;
import com.limelight.R;

public class InputCaptureManager {
    // Android 14 only build: native pointer capture (Android O+) is always available, so the
    // legacy providers (NVIDIA SHIELD extensions, rooted evdev reader, pointer-icon hiding) are gone.
    public static InputCaptureProvider getInputCaptureProvider(Activity activity) {
        LimeLog.info("Using Android native mouse capture");
        return new AndroidNativePointerCaptureProvider(activity, activity.findViewById(R.id.streamContainer));
    }
}
