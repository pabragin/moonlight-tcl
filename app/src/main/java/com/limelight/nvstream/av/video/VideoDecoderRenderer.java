package com.limelight.nvstream.av.video;

public abstract class VideoDecoderRenderer {
    public abstract int setup(int format, int width, int height, int redrawRate);

    public abstract void start();

    public abstract void stop();

    // This is called once for each frame-start NALU. This means it will be called several times
    // for an IDR frame which contains several parameter sets and the I-frame data.
    public abstract int submitDecodeUnit(byte[] decodeUnitData, int decodeUnitLength, int decodeUnitType,
                                         int frameNumber, int frameType, char frameHostProcessingLatency,
                                         long receiveTimeUs, long enqueueTimeUs);
    
    // Direct-copy submit path (optional): both run on the submit thread. Between them native writes
    // picDataLength bytes at the position prepare returned (see MoonBridge.bridgeDrPrepareDecodeUnit).
    public long prepareDecodeUnit(int picDataLength, int frameNumber, int frameType, char frameHostProcessingLatency,
                                  long receiveTimeUs, long enqueueTimeUs) {
        return com.limelight.nvstream.jni.MoonBridge.DR_PREPARE_FALLBACK;
    }

    public int commitDecodeUnit(byte[] fallbackData, int length) {
        return com.limelight.nvstream.jni.MoonBridge.DR_NEED_IDR;
    }

    public abstract void cleanup();

    public abstract int getCapabilities();

    public abstract void setHdrMode(boolean enabled, byte[] hdrMetadata);
}
