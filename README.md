# Moonlight TCL

A build of [Artemis](https://github.com/ClassicOldSong/moonlight-android) (the Moonlight Android fork) for **TCL Google TVs on
Android 14 firmware** (C8K, C6K, QM6K, QM8K and similar), tuned for one thing: a gamepad-only 4K client with the lowest latency the
TV's MediaTek SoC can deliver, that does not freeze or reboot the TV. Everything below was measured on a TCL C8K over adb, not
guessed. Background: [moonlight-stream/moonlight-android#1533](https://github.com/moonlight-stream/moonlight-android/issues/1533).

The app is called **Moonlight TCL**, package id `com.limelight.tcl`, so it installs next to Artemis or Moonlight and is not replaced by
their updates. Android 14 only (`minSdk 34`), `armeabi-v7a` only (that is what these TVs run). Settings and PC pairings are per app.

## What is different from Artemis

**Video path**
- The proven pre-August-2025 renderer instead of the later experimental one (polling loop, codec-flushing watchdog, undocumented
  `vendor.mtk.vdec.*` keys, forced Balanced pacing), which made controller response noticeably slower on TCL.
- Asynchronous MediaCodec: frames go to the compositor from the codec's own callback thread; there is no renderer thread and no
  blocking `dequeueOutputBuffer` between the decoder and the screen.
- Single-copy submit: the receive thread of `moonlight-common-c` writes each frame straight into the MediaCodec input buffer
  (native `PLENTRY` chain → codec buffer), instead of native → Java `byte[]` → codec buffer.
- The decoder is always configured with every low-latency hint, the official `low-latency` key dropped last if the codec refuses
  the others (until tcl6 this was the "Ultra Low Latency" checkbox; the earlier build silently ended up with no low-latency option
  because a rejected `KEY_OPERATING_RATE` took the official key down with it).
- Decode-time statistics measured in the library's own clock (`CLOCK_MONOTONIC_RAW`); mixing it with `uptimeMillis()` had made
  the overlay show near-zero decode times.
- ADPF performance-hint session for the video threads, always on; video threads at display priority; `android:appCategory="game"`.
- The video is the only layer on screen (the MediaTek fast path presents a lone video layer in 2 to 12 ms; any second layer costs a
  full extra frame).

**Audio**
- Native AAudio low-latency output: PCM goes from the library's audio thread into a lock-free ring drained by the AAudio callback,
  no JNI and no blocking `AudioTrack.write()` on the hot path. Falls back to `AudioTrack` automatically (and when the system
  equalizer is on).

**Gamepad rumble that does not reboot the TV**
- TCL's Android 14 firmware has a race in `system_server`'s InputReader that crashes when an `InputDevice` vibrates: the TV
  reboots mid-game (confirmed from the crash log and AOSP source, fixed upstream in Android 15). Here rumble is safe by
  construction: a gamepad on a USB cable is driven by Moonlight's own USB driver and never touches the system input stack; a
  Bluetooth pad gets its rumble coalesced to at most 20 updates per second, sent right after the pad's own input event when the
  reader thread is idle. Days of play on a C8K without a reboot. One setting: `Settings → Gamepad → Enable rumble`.

**Stability**
- The whole-TV freezes of the first builds (volume bar, app switch, stream exit) no longer reproduce with the changes above; the
  keep-alive layer and the frame-pause workaround that fought them were removed in 20.2.9-tcl4. The "volume change after an hour
  freezes the screen and the app dies" symptom was the rumble crash all along.
- Fixed a Settings-screen crash on activity restore inherited from Artemis, and a crash at 150 Mbps HDR from an oversized decode
  unit (now a fresh IDR frame is requested instead).

**Client for a TV, nothing else**
- Defaults for a 4K TV: 3840×2160, 60 FPS, 100 Mbps, HEVC, HDR on, "Prefer lowest latency" pacing.
- Phone-only features are hidden (touch controls, on-screen keyboard, PiP, orientation), the 3D mode with OpenCV/LiteRT is
  gone (APK 37 → 11 MB), jmDNS replaced by `NsdManager`, `moonlight-common-c` tracks upstream master (NEON FEC, batched input).
- No adb debug knobs and few checkboxes: every switch left is a real setting (AAudio, rumble, USB driver, HDR, codec, latency
  test, performance overlay, post-stream toast). Logcat is quiet during a stream; the lines that matter for a bug report stay.
- **Built-in latency test** (`Settings → Advanced Settings → Latency test mode`): open [`tools/latency-test.html`](tools/latency-test.html)
  on the PC full screen, press A/B/X/Y in the stream, read button-to-frame latency split into input, host+network and
  decode+present. **Post-stream toast** (`Settings → UI`) reports decode time and, for the first seconds, the compositor's share.

## Measurements (TCL C8K, firmware V655, HEVC, 60 FPS, gigabit link)

**Where the latency is.** The client's own share of the per-frame "decode time" is under a millisecond; the MediaTek hardware
decoder is the rest, and what it costs depends on pixels and bits, not on the codec:

| Stream | Decode average | Note |
|---|---|---|
| HEVC 3840×2160 HDR, 100 Mbps | 13 ms | default |
| H.264 3840×2160, 100 Mbps | 14–15 ms | same as HEVC; AV1 only if the host encodes it |
| HEVC 2560×1440, 100 Mbps | 7 ms | half a frame interval less than 4K, the TV scales to the panel |
| HEVC 1920×1080, 100 Mbps | 6 ms | |

**Bitrate at 4K60 HDR**, camera spinning in a heavy scene, frames reaching the screen counted with `dumpsys SurfaceFlinger --latency`:

| Bitrate | Bits per pixel per frame | Decode average | Frames/s on screen |
|---|---|---|---|
| 60 Mbps | 0.12 | 12 ms | 60 |
| 100 Mbps | 0.20 | 13 ms | 58–60, single dropped frames |
| 120 Mbps | 0.24 | 14 ms | 60, with a one-second dip to about 50 around each IDR frame |
| 150 Mbps | 0.30 | 15 ms | 49–59 |

**Things that turned out not to matter on this TV.** The display exposes exactly one mode, 3840×2160 at 60 Hz; the panel's 120/144 Hz
exist only for HDMI inputs, so a 120 FPS stream is shown at 60 (every second frame dropped) and no setting changes that. Releasing
frames with timestamp 0 instead of `System.nanoTime()` (upstream PR #1577) changed neither smoothness nor latency. MediaTek's
`vendor.mtk-codec2.game-mode` keys are accepted by the decoder but cost a full extra frame on screen, so they are not requested.
In-app compositor timing only covers the first seconds of a stream: once the HWC moves the video layer to its fast path it stops
reporting present times (Codec2 logs `no present fence`), so steady-state numbers need `dumpsys SurfaceFlinger --latency`.

## Which bitrate to set

A real-time HEVC encoder is visually transparent at about 0.2 bits per pixel per frame; below that motion gets soft and dark HDR
scenes get blocky, above it only dark gradients improve a little.

| You want | 4K60 HDR | Why |
|---|---|---|
| Guaranteed smooth 60, softer picture | 60 Mbps | 12 ms decode, big headroom |
| Balance | 80 Mbps | headroom of 100 is already thin; 80 keeps most of its picture |
| Best picture that still holds 60 (**default**) | 100 Mbps | near-transparent, rare single-frame drops in heavy scenes |
| Maximum picture, accept rare dips | 120 Mbps | cleaner dark gradients; a one-second dip around IDR frames |
| Not this | 150 Mbps | no visible gain over 120, the decoder no longer holds 60 |

If latency matters more than sharpness, 2560×1440 at 60 Mbps decodes in about 7 ms and the TV scales it to the panel. Keep HEVC;
H.264 buys nothing at 4K on this decoder.

## If the picture stutters or "Slow connection to PC" appears

That message means the library lost 30 % of the frames in a 3 s window (or 15 % twice). It is about packets, not the decoder. On
the C8K it was the TV's own Ethernet port negotiating 2.5 Gbit over a marginal cable: `adb shell ping 192.168.8.1` from the TV lost
8 to 29 %, while a laptop reached the PC with 0 % loss; a reseated cable and a gigabit link fixed it. Check
`adb shell cat /sys/class/net/eth1/speed` and ping the router from the TV before changing any setting. Late packets also inflate
the decode figure in the toast.

## Download and install

APKs are on the [Releases](https://github.com/pabragin/moonlight-tcl/releases) page, one `armeabi-v7a` APK per release. Install with
a file manager, the Downloader app or `adb install`; pair with your PC again after installing (pairings are per app).
Updates: [add to Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.limelight.tcl%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2Fpabragin%2Fmoonlight-tcl%22%2C%22author%22%3A%22pabragin%22%2C%22name%22%3A%22Moonlight%20TCL%22%2C%22additionalSettings%22%3A%22%7B%5C%22apkFilterRegEx%5C%22%3A%5C%22armeabi-v7a%5C%22%2C%5C%22matchGroupToUse%5C%22%3A%5C%22%241%5C%22%2C%5C%22versionExtractionRegEx%5C%22%3A%5C%22v(.%2B)%5C%22%7D%22%7D)
or use the "Use Obtainium" entry in the app's settings.

If the TV freezes or reboots, open an issue with `adb logcat -v threadtime -b all` captured around the moment, or after a reboot
the output of `adb shell dumpsys dropbox --print system_server_native_crash`.

## Building

- JDK 17 and the Android SDK with NDK `27.3.13750724` (see `app/build.gradle`).
- `git submodule update --init --recursive` (`moonlight-common-c` comes from
  [pabragin/moonlight-common-c](https://github.com/pabragin/moonlight-common-c), branch `tcl`).
- Point Gradle at the SDK with `ANDROID_HOME` or `local.properties`.
- `./gradlew :app:assembleRelease` (not `-Pandroid.injected.build.abi`: it marks the APK `testOnly`, which the TV rejects), then
  `zipalign` and `apksigner sign` the APK from `app/build/outputs/apk/release/` with your own keystore.

## Credits and license

All streaming functionality is the work of the Moonlight and Artemis authors:
[Cameron Gutman](https://github.com/cgutman), [Diego Waxemberg](https://github.com/dwaxemberg), [Aaron Neyer](https://github.com/Aaronneyer),
[Andrew Hennessy](https://github.com/yetanothername), [ClassicOldSong](https://github.com/ClassicOldSong) and the contributors of both
projects. Moonlight started as a project of students at [Case Western](http://case.edu) at [MHacks](http://mhacks.org).

Licensed under the GNU GPL v3, see [LICENSE.txt](LICENSE.txt).

---

## Кратко по-русски

Сборка [Artemis](https://github.com/ClassicOldSong/moonlight-android) для телевизоров TCL на прошивке Android 14 (C8K и похожие):
клиент только под геймпад и 4K, с минимальной задержкой, которую способен дать чип MediaTek, и без зависаний и перезагрузок
телевизора. Всё ниже измерено на C8K через adb. Приложение называется Moonlight TCL, пакет `com.limelight.tcl`, ставится рядом с
обычным Artemis; только Android 14. APK на странице [Releases](https://github.com/pabragin/moonlight-tcl/releases), после установки
спарьтесь с ПК заново.

**Что сделано.** Возвращён проверенный видеоконвейер вместо экспериментального; декодер работает в асинхронном режиме без потока
рендера; кадр копируется из сети в буфер декодера один раз; всегда включены все подсказки низкой задержки декодеру и сессия ADPF;
звук через нативный AAudio; исправлена статистика времени декодирования. Вибрация геймпада не перезагружает телевизор: по USB
через собственный драйвер, по Bluetooth пакетами не чаще 20 раз в секунду сразу после ввода геймпада (гонка в InputReader
прошивки, исправленная в Android 15). Зависания телевизора из первых сборок больше не воспроизводятся, обход композитора удалён.
Настроек мало, отладочных ключей нет, лог тихий. Встроенный тест задержки и итог после стрима.

**Замеры.** Доля самого приложения во времени декодирования меньше миллисекунды, остальное аппаратный декодер: 4K HEVC 13 мс,
H.264 столько же, 1440p 7 мс, 1080p 6 мс. Экран у телевизора один режим 4K 60 Гц, 120 Гц только для HDMI. Битрейт для 4K60 HDR:
60 Мбит/с гарантированно ровные 60 кадров при более мягкой картинке; 80 баланс; **100 по умолчанию**, картинка почти без потерь,
редкие единичные пропуски кадров в тяжёлых сценах; 120 максимум качества с секундными просадками на опорных кадрах; 150 не нужно.
Если задержка важнее чёткости, 1440p при 60 Мбит/с даёт около 7 мс. Сообщение «Медленное подключение к ПК» это потери пакетов в
сети, а не декодер: сначала пинг роутера с телевизора и скорость линка Ethernet, потом настройки.
