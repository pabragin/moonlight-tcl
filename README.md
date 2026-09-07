# Artemis for TCL Android TV

A build of the [Artemis](https://github.com/ClassicOldSong/moonlight-android) game streaming client
(a fork of [Moonlight Android](https://github.com/moonlight-stream/moonlight-android)) with workarounds
for TCL Google TVs running **Android 14 firmware**.

**Android 14 only.** The APK declares `minSdk 34` and installs only on Android 14 (or newer) firmware. TVs still on the
Android 12 firmware do not have the bugs this project works around and can keep using stock Artemis or Moonlight.

## Where this comes from

- **Moonlight Android** by [moonlight-stream](https://github.com/moonlight-stream/moonlight-android): the original open source client for
  [Sunshine](https://github.com/LizardByte/Sunshine)/[Apollo](https://github.com/ClassicOldSong/Apollo) game streaming hosts.
- **Artemis** by [ClassicOldSong](https://github.com/ClassicOldSong/moonlight-android): a fork of Moonlight Android with many extra
  features (custom resolutions and bitrates, mouse modes, virtual gamepad, external display mode, Apollo integration, and more).
  This repository is a fork of Artemis and keeps its full commit history; everything not mentioned below is Artemis as-is.

## Goal of this project

Make streaming actually usable on TCL Google TVs (C8K, C6K, QM6K, QM8K and similar) after their Android 14 firmware update.
On that firmware the stock Moonlight/Artemis clients freeze the whole TV or reboot it; the same TVs work fine on Android 12
firmware. The bugs are in the TV firmware, so this project works around them on the client side. Background and logs:
[moonlight-stream/moonlight-android#1533](https://github.com/moonlight-stream/moonlight-android/issues/1533).

What is changed compared to Artemis:

1. **Compositor workaround, removed in 20.2.9-tcl4.** Builds tcl1 to tcl3 kept a 2×2 px layer above the video and later
   (tcl5 to tcl9) paused frame output before every overlay, because TCL firmware 604/622/624 hung the whole TV when the
   composition changed while the video layer received frames (measured over adb on a C8K: a lone video layer is presented in
   2 to 12 ms, any second layer costs 16 to 33 ms). Firmware V655 no longer hangs, a full day of play with the workaround off
   showed nothing, so the code, the two settings and the adb knobs are gone; the video is simply the only layer on screen.
2. **Gamepad rumble block** (`Settings → Gamepad → Block gamepad rumble on this TV`). The firmware has a race in `system_server`'s
   InputReader that crashes when an `InputDevice` vibrates, which shows up as the TV rebooting mid-game. Rumble through the Android
   input stack is blocked; USB gamepads driven by Moonlight's own USB driver still rumble. Confirmed on a C8K: with the block
   off the TV played for about an hour and then rebooted twice within minutes (it is a race, not a deterministic crash), so
   since tcl9 the block wins over every other rumble option and unchecking it on an affected TV asks for confirmation.
   Root cause (from the TV's crash log and AOSP source): Android 14's `InputReader::vibrate()` pushes into the input reader
   thread's event queue from the binder thread while the reader may be flushing it; Android 15 fixed this (`mPendingArgs`). So
   the only race-free rumble is Moonlight's own USB driver: since 20.2.8-tcl3 "Override native Xbox gamepad support" is on
   by default on affected TVs, so a gamepad on a USB cable rumbles through the app, not the system. For Bluetooth there is
   an **experimental** mode (`Rumble over Bluetooth anyway`, on by default on affected TVs since 20.2.8-tcl7): rumble is
   coalesced to at most 20 updates per second and sent only right after the pad's own input event, when the system's input
   thread is idle. That makes the crash rare, not impossible; if the TV still reboots during play, turn it off.

The rumble block turns itself on for TCL TVs on Android 14 or newer and can be toggled by hand. The old "volume change after an
hour freezes the screen and the app dies" symptom turned out to be the rumble crash (a remote key press is an input-reader event,
exactly the moment the rumble race hits), not a compositor problem.

3. **Video pipeline back to the proven one.** Artemis after August 2025 (commit `4de0227f`) gained an experimental renderer: a
   "latest-frame" polling loop with adaptive frame dropping, a decoder watchdog that flushes the codec, a set of undocumented
   `vendor.mtk.vdec.*` decoder keys, reference frame invalidation for MediaTek, three competing `setFrameRate()` calls, and code that
   silently forced the *Balanced* frame pacing mode regardless of the setting. On TCL this showed up as a noticeably slower controller
   response. This build restores the previous renderer, honours the frame pacing setting (default: lowest latency) and picks HEVC
   automatically; AV1 remains available via "Force AV1".
4. **Defaults for a 4K TV.** First start uses 3840x2160, 60 FPS, 100 Mbps, HEVC and "Prefer lowest latency" frame pacing
   (Artemis defaults to 1280x720 and 80 Mbps at 4K). Everything is still adjustable in Settings.
5. **Decoder tuning that can be checked.** "Ultra Low Latency" is on by default on MediaTek TVs and the video renderer thread runs
   at display priority. Until 20.2.8-tcl5 the MediaTek build also asked for `KEY_OPERATING_RATE = 32767`, which
   `c2.mtk.hevc.decoder` rejects at `start()`; that key sat in every fallback attempt, so the decoder silently ended up
   configured with *no* low-latency option at all (C8K log: tries 0–2 fail, try 3 succeeds with a bare format). 20.2.8-tcl6
   drops the key and reorders the attempts so the official `low-latency` key is the last one given up. The performance
   overlay's "Low-latency mode" line shows what was requested and what the decoder echoed, so this can be checked without adb.
6. **Declared as a game.** The manifest carries `android:appCategory="game"` (plus the legacy `isGame` flag). Without the game
   category Android ignores the app's `GameManager` state updates and game-mode config, and TV vendors' automatic game picture
   mode keys off the category too. Together with `preferMinimalPostProcessing` this is everything an app can do to request the
   TV's game mode; whether the TV honours it is up to the firmware.
7. **No 3D mode, leaner APK.** Artemis' "AI 3D" stereo renderer with OpenCV, LiteRT and the MiDaS depth model is removed
   (a TV stream only needs the plain SurfaceView path). Gamepad/keyboard input is requested unbuffered on the window's decor view,
   so the request cannot be lost when another view takes focus.


8. **Built-in latency test** (`Settings → Advanced Settings → Latency test mode`). Open [`tools/latency-test.html`](tools/latency-test.html)
   full screen on the PC (press **F**; a small square keeps the host capturing at full rate), start the stream and press A/B/X/Y.
   The overlay shows the button-to-frame latency (last/avg/min/max) and the averages of input→app, host+net and decode+present;
   the TV panel's own delay is excluded, so app versions can be compared on the same TV. Each sample is logged to logcat.
9. **TV-only build.** The rooted flavor with its evdev mouse reader, the NVIDIA SHIELD controller extensions and the legacy
   mouse-capture fallbacks are removed (Android 14 always has native pointer capture). Settings that only make sense on a
   phone or tablet (on-screen controls and keyboard, touch/trackpad, screen orientation, external display, PiP, zoom/pan) are
   hidden on TV. Gamepad battery polling is off by default on TV. The thread that feeds video NALUs to the decoder runs at
   display priority like the renderer.
10. **Leaner, newer core (tcl5).** The on-screen gamepad and virtual keyboard are gone (a TV has no touchscreen), together with
   jmDNS (NsdManager does discovery on Android 14), the SHIELD/ChromeOS/Samsung manifest leftovers, pre-Android-14 code paths and
   a few orphaned classes and drawables; the release build strips unused resources. Input fixes from moonlight-stream are ported
   (rumble through `VibratorManager`, controller LED requests off the main thread, Xbox Series X|S / Elite 2 / 8BitDo ids in the USB
   driver). `moonlight-common-c` now tracks moonlight-stream master (NEON Reed-Solomon FEC, batched gamepad input, RTT queries
   without the ENet lock, RTSP hardening) plus the two Apollo protocol patches, from
   [pabragin/moonlight-common-c](https://github.com/pabragin/moonlight-common-c) branch `tcl`. Small things: no `setFrameRate()`
   request when the display already runs at the stream rate, launcher-shortcut bookkeeping off the connect path, native library
   exports only its JNI symbols. tcl6 sweeps the last leftovers (unused frame-render-time path, dead fields, the ENet Win32 source
   in the native build) and, like upstream on Oreo+, stops rewriting H.264 SPS constraint flags and level_idc.
11. **Compositor latency in the overlay (tcl7).** With the performance overlay or the latency test enabled, the decoder
   registers a frame-rendered listener and shows "Present (compositor)": the average and maximum time between
   `releaseOutputBuffer()` and the moment the display actually showed the frame. The latency test gets the same value as a
   separate "compositor" component. A visible overlay is itself a second compositor layer, so the clean measurement is the
   **post-stream toast** (`Settings → UI → Show post-stream latency toast`, tcl8): nothing is drawn over the video during the
   stream and the toast at the end reports the compositor average/maximum and the frame count. Nothing is tracked when
   overlay, test and toast are all off. Caveat found in tcl9: on the C8K the
   compositor attaches present times to the video layer only until it moves it to the MediaTek video fast path, a few seconds
   into the stream (Codec2 then logs `no present fence for frame N` and stops the callbacks), so these figures describe the
   first seconds of a session, not its steady state; the toast says so. Steady-state numbers still need
   `adb shell dumpsys SurfaceFlinger --latency 'SurfaceView[com.limelight.tcl/com.limelight.Game](BLAST)#N'`.
12. **Settings screen no longer crashes on restore (20.2.8-tcl2).** Artemis' settings fragment had only a constructor with an
   argument, so whenever Android re-created the Settings activity from saved state (process killed in the background, a
   configuration change) the app crashed with `Fragment$InstantiationException`. The TV's crash log showed this on Artemis
   20.2.6 and on every TCL build; a no-arg constructor that re-reads the preferences fixes it.
13. **Audio, CPU hints and decoder keys checked on the TV (20.2.8-tcl9).**
   - **AAudio output** (`Settings → Audio → AAudio low-latency output`, on by default). Decoded PCM goes straight from
     `moonlight-common-c`'s audio thread into a lock-free ring that an AAudio low-latency stream drains in its callback, so no
     JNI call and no blocking `AudioTrack.write()` sit between the decoder and the audio HAL. The system equalizer needs an
     `AudioTrack` session, so with audio effects on the classic renderer is used; any AAudio failure also falls back to it.
     Measured on a C8K over HDMI: 2 to 4 underruns at stream
     start, none later, no dropped packets; `AAudio stats` in logcat every 10 s. The Artemis bug that handed the *audio
     effects* renderer flag the value of "play audio on PC" is fixed on the way.
   - **ADPF performance hints** (`Settings → Advanced Settings → Performance hints (ADPF)`, on by default). A
     `PerformanceHintManager` session covers the video renderer thread and the thread that feeds the decoder, with a one-frame
     target and the real per-frame duration reported. The C8K's power HAL accepts sessions (`dumpsys performance_hint`), but
     its CPUs already run at their maximum during a stream, so expect less jitter rather than a lower average. Inert where
     the HAL declines.
   - **MediaTek Codec2 vendor keys** `vendor.mtk-codec2.game-mode` and `low-latency-mode` (upstream issue #1406). The C8K's
     `c2.mtk.hevc.decoder` lists and echoes them, and the "Low-latency mode" overlay line now shows every `vendor.*` key, but
     `dumpsys SurfaceFlinger --latency` showed them costing a full extra frame on screen (present minus desired 17 ms instead of
     about 0), so since 20.2.9-tcl4 they are not requested at all.
   - **Decode-time statistics were wrong since tcl5** and are fixed: the overlay, toast and latency test compared frame
     timestamps from `moonlight-common-c` (`CLOCK_MONOTONIC_RAW`) with `SystemClock.uptimeMillis()`, and the two clocks drift
     apart, which showed decode times near 0 ms. The statistics now use the library's own clock (`MoonBridge.getMicroseconds()`).
   - **Quiet logcat (20.2.9-tcl2/tcl3).** USB device dumps, PC polling, mDNS address filtering, poster cache hits, the
     per-event rumble line and the periodic audio counters only appear in debug builds; the audio counters still log a line
     whenever underruns or dropped packets actually change.
   - The timestamp-0 release mode (upstream PR #1577) is the checkbox `Settings → Advanced Settings → Render frames with
     timestamp 0`, off by default. Since 20.2.9-tcl4 there are no adb debug knobs: every switch is a real setting.
14. **One copy less and no renderer thread on the video path (20.2.9-tcl4).** Two code explorations of the per-frame hot path
   found that the client's share of the "decode time" statistic is small (the MediaTek hardware decoder dominates the 10 to
   19 ms at 4K60 HEVC) and removed what there was:
   - **Single-copy submit.** The frame used to travel native packet buffers → a Java `byte[]` → the MediaCodec input buffer,
     two full-frame memcpys inside the measured number (about 0.1 to 0.2 ms per 4K P-frame, milliseconds on IDR frames).
     Now Java hands the codec's input buffer to native once per buffer and `moonlight-common-c`'s receive thread writes the
     picture data straight into it (`callbacks.c`, `prepareDecodeUnit`/`commitDecodeUnit`). Parameter sets keep the old path,
     a non-direct buffer falls back to it automatically.
   - **Asynchronous MediaCodec.** Frames are released to the compositor from the codec's own callback thread
     (`onOutputBufferAvailable`) and free input buffers arrive through `onInputBufferAvailable`, so there is no renderer
     thread and no blocking `dequeueOutputBuffer`/`dequeueInputBuffer` between the decoder and the screen. Codec recovery and
     the HDR restart mute the callbacks and drain the callback thread before every codec state change.
   - One clock read per frame feeds both the statistic and the ADPF report.
   - **HDR is on by default** (the checkbox stays).
   Verified on the C8K after publishing: 14441 frames copied directly, 0 through the byte[] path, no input-buffer waits, one
   HDR decoder restart as before, no warnings; decode time 15 ms (14 ms hardware) at 4K60 HEVC, the same as tcl9.
15. **What the MediaTek decoder costs per codec and resolution (measured on the C8K, 60 FPS, 100 Mbps, HDR, post-stream
   toast figures, one minute of the same scene each).** This is where latency is actually won on this TV:

   | Stream | Decode average | Hardware share | Note |
   |---|---|---|---|
   | HEVC 3840×2160, HDR | 15–16 ms | 14–15 ms | default |
   | HEVC 3840×2160, SDR | 14 ms | 13 ms | |
   | HEVC 3840×2160, SDR, 60 Mbps | 11 ms | 10 ms | bitrate is the second lever |
   | HEVC 3840×2160, HDR, 40 Mbps | 11 ms | 11 ms | 4 to 5 ms less than HDR at 100 Mbps |
   | H.264 3840×2160 | 14–15 ms | 13–14 ms | one run out of three showed 58 ms and 23 frames/s; not reproduced |
   | HEVC 2560×1440 | 7 ms | 7 ms | half a frame interval less than 4K |
   | HEVC 1920×1080 | 6 ms | 6 ms | |

   AV1 could not be measured: the client offers `c2.mtk.av1.decoder`, but the host answered with HEVC (no AV1 encoder there).
   So the codec hardly matters at 4K (HEVC and H.264 decode in the same 14 to 16 ms, HDR on or off); resolution and bitrate
   do: 1440p HEVC removes about 8 ms per frame (the TV scales it to the panel itself), and 40 to 60 instead of 100 Mbps at 4K
   removes 3 to 5 ms (the two lower bitrates decode alike).

## Download and install

APKs are published on the [Releases](https://github.com/pabragin/moonlight-tcl/releases) page. Each release has one `armeabi-v7a` APK:
TCL/MediaTek TVs run 32-bit apps, and 64-bit Android TVs install 32-bit APKs as well.

- The app is called **Moonlight TCL** and uses its own package id `com.limelight.tcl`, so it installs next to official Artemis or
  Moonlight and is not replaced by their updates. Install it with a file manager, the Downloader app, or `adb install`.
- Settings and PC pairings are per app, so pair with your PC again after installing.
- The performance overlay no longer needs to be enabled.
- Updates: [add to Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.limelight.tcl%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fpabragin%2Fmoonlight-tcl%22%2C%22author%22%3A%22pabragin%22%2C%22name%22%3A%22Moonlight%20TCL%22%2C%22additionalSettings%22%3A%22%7B%5C%22apkFilterRegEx%5C%22%3A%5C%22armeabi-v7a%5C%22%2C%5C%22matchGroupToUse%5C%22%3A%5C%22%241%5C%22%2C%5C%22versionExtractionRegEx%5C%22%3A%5C%22v(.%2B)%5C%22%7D%22%7D)
  or use the "Use Obtainium" entry in the app's settings.

If the TV still freezes or reboots, open an issue with `adb logcat -v threadtime -b all` captured around the moment it happens, or, after a
reboot, the output of `adb shell dumpsys dropbox --print system_server_native_crash`.

## Building

- Install a JDK 17 and the Android SDK with NDK `27.3.13750724` (see `app/build.gradle`).
- Run `git submodule update --init --recursive` (the `moonlight-common-c` submodule comes from
  [pabragin/moonlight-common-c](https://github.com/pabragin/moonlight-common-c), branch `tcl`).
- Point Gradle at the SDK with `ANDROID_HOME` or a `local.properties` file containing `sdk.dir=`.
- Build with `./gradlew :app:assembleRelease` (do not use `-Pandroid.injected.build.abi`: it marks the APK `testOnly`, which the
  TV's installer rejects as invalid), then `zipalign` and `apksigner sign` the APK from
  `app/build/outputs/apk/release/` with your own keystore.

## Credits and license

All streaming functionality is the work of the Moonlight and Artemis authors:
[Cameron Gutman](https://github.com/cgutman), [Diego Waxemberg](https://github.com/dwaxemberg), [Aaron Neyer](https://github.com/Aaronneyer),
[Andrew Hennessy](https://github.com/yetanothername), [ClassicOldSong](https://github.com/ClassicOldSong) and the contributors of both
projects. Moonlight started as a project of students at [Case Western](http://case.edu) at [MHacks](http://mhacks.org).

Licensed under the GNU GPL v3, see [LICENSE.txt](LICENSE.txt).

---

## Кратко по-русски

Это сборка [Artemis](https://github.com/ClassicOldSong/moonlight-android) (форк Moonlight Android) с обходами ошибок прошивки
Android 14 на телевизорах TCL (C8K и похожие): зависание всего ТВ при регулировке громкости, смене приложения и выходе из
стрима, а также перезагрузки из-за вибрации геймпада. Цель проекта — доработать клиент так, чтобы он стабильно работал на TCL.
Сборка только для Android 14 (`minSdk 34`): на прошивку Android 12 она не установится, там этих ошибок нет и подходит обычный Artemis.
Приложение называется Moonlight TCL и имеет свой идентификатор пакета `com.limelight.tcl`, поэтому ставится рядом с обычным Artemis
и не затирается его обновлениями. APK на странице [Releases](https://github.com/pabragin/moonlight-tcl/releases); после установки
спарьтесь с ПК заново.

В 20.2.9-tcl4: убран обход композитора целиком (на прошивке V655 он больше не нужен), кадр копируется из сети в буфер
декодера один раз вместо двух, декодер работает в асинхронном режиме без отдельного потока рендера, HDR включён по умолчанию.
Проверено на C8K: все кадры идут прямым копированием, ожиданий буфера нет, ошибок нет, время декодирования прежнее. Замеры
декодера MediaTek при 60 к/с и 100 Мбит/с: HEVC 4K с HDR 15–16 мс, без HDR 14 мс, при 60 Мбит/с 11 мс, с HDR при 40 Мбит/с 11 мс, H.264 4K 14–15 мс (один прогон из трёх дал 58 мс и 23 к/с, не повторился), HEVC 1440p 7 мс,
HEVC 1080p 6 мс; AV1 хост не кодирует. Кодек при 4K почти не влияет, влияют разрешение и битрейт: 1440p HEVC экономит около 8 мс на кадре,
40–60 Мбит/с вместо 100 при 4K от 3 до 5 мс (40 и 60 декодируются одинаково).

В 20.2.8-tcl9: звук идёт через нативный AAudio с малой задержкой (при сбое или включённом эквалайзере автоматически
используется прежний AudioTrack), добавлены подсказки производительности ADPF для потоков видео, исправлена статистика времени
декодирования (с tcl5 она показывала около нуля из-за разных часов). Ключи MediaTek `game-mode`/`low-latency-mode` декодер
принимает, но по замерам они добавляют целый кадр задержки на экране, поэтому с 20.2.9-tcl4 не запрашиваются.

Ключи adb удалены в 20.2.9-tcl4: все переключатели теперь только в настройках приложения (нулевая метка времени кадра,
AAudio, подсказки ADPF, вибрация).
