#pragma once

#include <stdint.h>
#include <stdbool.h>

// Native AAudio output path (Android 8+, we build for 14). Decoded PCM from moonlight-common-c's
// AudioDec thread goes into a lock-free ring that the AAudio data callback drains, so no JNI and no
// AudioTrack.write() blocking sit between the decoder and the HAL.

// Returns 0 on success, a negative AAudio result or -100 when disabled by debug.moonlight.aaudio=0.
int AAudioRenderer_Setup(int channelCount, int sampleRate, int samplesPerFrame, int maxPendingMs);
int AAudioRenderer_Start(void);
void AAudioRenderer_Stop(void);
void AAudioRenderer_Cleanup(void);
bool AAudioRenderer_IsActive(void);
int AAudioRenderer_GetMaxPendingMs(void);

// Producer side (AudioDec thread): interleaved int16 frames.
void AAudioRenderer_Write(const int16_t* frames, int frameCount);
void AAudioRenderer_CountDroppedPacket(void);

// {latencyMs, underruns, dropped, reopens}
void AAudioRenderer_GetStats(int stats[4]);
