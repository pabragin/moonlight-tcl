package com.limelight.nvstream.av.audio;

import com.limelight.nvstream.jni.MoonBridge;

public interface AudioRenderer {
    default int getSupportedDolbyCodecs(MoonBridge.AudioConfiguration audioConfiguration) {
        return 0;
    }

    int setup(MoonBridge.AudioConfiguration audioConfiguration, int sampleRate, int samplesPerFrame);

    default int setupEncoded(MoonBridge.AudioConfiguration audioConfiguration, int sampleRate, int encoding) {
        return -1;
    }

    void start();

    void stop();
    
    void playDecodedAudio(short[] audioData);

    default void playEncodedAudio(byte[] audioData, int length, int encoding) {}

    void cleanup();
}
