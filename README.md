# Moonlight TCL

A build of [Artemis](https://github.com/ClassicOldSong/moonlight-android) (the Moonlight Android fork) for **TCL Google TVs on
Android 14 firmware** (C8K, C6K, QM6K, QM8K and similar): a gamepad-only 4K client with the lowest latency the TV's MediaTek chip
can deliver, and no TV freezes or reboots. Everything in this file was measured on a TCL C8K.
Background: [moonlight-stream/moonlight-android#1533](https://github.com/moonlight-stream/moonlight-android/issues/1533).

The app is called **Moonlight TCL**, package `com.limelight.tcl`, so it installs next to Artemis or Moonlight and is not replaced by
their updates. Android 14 only, `armeabi-v7a` only (that is what these TVs run). Settings and PC pairings are per app.

## What is different from Artemis

**Video**
- The proven renderer from before Artemis' August 2025 experiments (polling loop, codec-flushing watchdog, undocumented MediaTek
  keys, forced Balanced pacing), which had made controller response noticeably slower on TCL.
- Decoded frames go to the screen straight from the decoder's callback: no renderer thread in between and no blocking waits.
- Each frame is copied once, from the network buffers directly into the decoder's input buffer, instead of twice through Java.
- The decoder is always asked for every low-latency option it accepts; the video threads run at display priority and are
  registered with Android's performance hints (ADPF); the app is declared a game.
- The video is the only layer on screen, which keeps the TV's compositor on its fast path.

**Audio**
- Native AAudio low-latency output, no Java between the decoder and the audio driver. Falls back to `AudioTrack` on its own,
  and when the system equalizer is on.

**Rumble that does not reboot the TV**
- TCL's Android 14 firmware has a race in `system_server` that crashes when a gamepad vibrates through the Android input stack:
  the TV reboots mid-game (confirmed from the crash log and AOSP source; fixed upstream in Android 15). Here a USB gamepad is
  driven by Moonlight's own USB driver and never touches that stack, and a Bluetooth pad gets its rumble coalesced to at most
  20 updates per second, sent right after the pad's own input when the firmware's input thread is idle. One switch:
  `Settings → Gamepad → Enable rumble`.

**Stability and housekeeping**
- The whole-TV freezes of the first builds (volume bar, app switch, stream exit) no longer reproduce; the workarounds that fought
  them are gone. The "volume change after an hour freezes the screen and the app dies" symptom was the rumble crash all along.
- Fixed a Settings-screen crash inherited from Artemis and a crash on oversized frames at high bitrates.
- Defaults for a 4K TV: 3840×2160, 60 FPS, 100 Mbps, HEVC, HDR on, lowest-latency pacing. Phone-only features and the 3D mode are
  gone (APK 37 → 11 MB), settings are down to the ones that matter, logcat is quiet during a stream.
- **Built-in latency test** (`Settings → Advanced Settings → Latency test mode`): open [`tools/latency-test.html`](tools/latency-test.html)
  full screen on the PC, press A/B/X/Y in the stream, read button-to-frame latency split into input, host+network and
  decode+present. **Post-stream toast** (`Settings → UI`) shows the decode time of the session.
  [`tools/framerate-test.html`](tools/framerate-test.html) is a local 60 fps test pattern for the PC (ladder, moving bar, frame
  counter, heavy mode): every dropped or duplicated frame in the stream is visible on the TV without any overlay.

## Measurements

TCL C8K, firmware V655, HEVC, 60 FPS, wired gigabit network. The client's own share of the per-frame "decode time" is under a
millisecond; the rest is the MediaTek hardware decoder, and what it costs depends on pixels and bitrate, not on the codec:

| Stream | Decode time per frame |
|---|---|
| 3840×2160 HDR, 100 Mbps | 13 ms |
| 3840×2160 H.264, 100 Mbps | 14–15 ms (no better than HEVC) |
| 2560×1440, 100 Mbps | 7 ms |
| 1920×1080, 100 Mbps | 6 ms |

Frames that actually reach the screen at 4K60 HDR while spinning the camera in a heavy scene (counted at the compositor):

| Bitrate | Decode time | Frames/s on screen |
|---|---|---|
| 60 Mbps | 12 ms | 60 |
| 100 Mbps | 13 ms | 58–60, an occasional single dropped frame |
| 120 Mbps | 14 ms | 60 in some scenes, about 40 in the heaviest |

Things that turned out not to matter on this TV: the display has exactly one mode, 3840×2160 at 60 Hz (the panel's 120/144 Hz
exist only for HDMI inputs), so a 120 FPS stream is shown at 60; releasing frames with a zero timestamp made no difference to
smoothness or latency; MediaTek's `game-mode` decoder keys cost a full extra frame on screen and are not used.

## Compared with the official Artemis 20.2.6

Same TV, same PC, same settings (4K60 HEVC HDR, lowest-latency pacing), Artemis 20.2.6 being the release this fork started from, so
the decoder and its options are identical and both report the same decode time, 14 ms. The controlled comparison uses
[`tools/framerate-test.html`](tools/framerate-test.html) in heavy mode on the PC, a source that is guaranteed to deliver 60 frames per
second. The TV's compositor was asked every two seconds how many frames it had actually put on the screen, and each answer was
scored as either "all 60 per second" or "fewer". Over about 45 seconds per app:

| Test page, heavy mode | Artemis 20.2.6 | Moonlight TCL |
|---|---|---|
| 100 Mbps: samples with all 60 frames per second on screen | 8 of 24 (33 %); the rest 54–59 | 13 of 14 (93 %); the one miss lost a single frame |
| 200 Mbps, where the decoder itself is the limit for both | 5 of 22 (23 %); the rest 54–59 | 10 of 22 (45 %); the rest 56–59 |
| App CPU load | 35–54 % | 37–51 % |

In a game at 100 Mbps the gap was larger, 40–51 frames per second on screen for Artemis against a steady 60 here while spinning the
camera in a heavy scene, but the PC's own frame rate was not recorded in those runs, so take that pair as indicative only.
Two more differences that need no measurement: Artemis sends rumble through the system input stack, the path that reboots this
TV, and its Settings screen crashed on open during the test.

## Which bitrate to set (4K60 HDR)

- **100 Mbps**, the default: the picture is essentially transparent for a real-time encoder, and 60 frames per second hold in
  nearly every scene.
- **60 Mbps** if you want smoothness guaranteed in every scene and accept a slightly softer picture; anything in between behaves
  accordingly.
- **120 Mbps** if you want the last bit of detail in dark HDR scenes and accept dips to about 40 frames per second in the heaviest
  moments; the decoder has no headroom left there.
- If latency matters more than sharpness, 2560×1440 decodes in about 7 ms instead of 13 and the TV scales it to the panel. Stay on
  HEVC.

## Download and install

APKs are on the [Releases](https://github.com/pabragin/moonlight-tcl/releases) page, one `armeabi-v7a` APK per release. Install with
a file manager, the Downloader app or `adb install`; pair with your PC again after installing.
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
клиент под геймпад и 4K с минимальной задержкой, которую может дать чип MediaTek, без зависаний и перезагрузок телевизора.
Приложение называется Moonlight TCL, пакет `com.limelight.tcl`, ставится рядом с обычным Artemis, только Android 14. APK на
странице [Releases](https://github.com/pabragin/moonlight-tcl/releases); после установки спарьтесь с ПК заново.

**Что сделано.** Возвращён проверенный видеоконвейер; кадры уходят на экран прямо из декодера, без промежуточного потока; кадр
копируется один раз; декодеру всегда выставляются все опции низкой задержки; звук через нативный AAudio. Вибрация геймпада не
перезагружает телевизор: по USB через собственный драйвер, по Bluetooth короткими пакетами сразу после ввода геймпада, в обход
гонки в прошивке. Зависания телевизора из первых сборок больше не воспроизводятся. Настроек мало, есть встроенный тест задержки и
сообщение с временем декодирования после стрима.

**Сравнение с официальным Artemis 20.2.6** на том же телевизоре при одинаковых настройках 4K60 HDR: декодер и время
декодирования одинаковые, 14 мс. На тестовой странице `tools/framerate-test.html`, которая гарантированно выдаёт 60 кадров в
секунду, композитор телевизора каждые две секунды опрашивался, сколько кадров он реально вывел. При 100 Мбит/с у Artemis все 60
кадров в секунду были лишь в 8 замерах из 24, у этой сборки в 13 из 14; при 200 Мбит/с, когда оба упираются в декодер, 5 из 22
против 10 из 22. В игре разрыв был больше, 40–51 кадр против ровных 60, но частота хоста там не
записывалась. Вибрация у Artemis идёт через системный стек, который перезагружает этот телевизор.

**Какой битрейт ставить для 4K60 HDR.** 100 Мбит/с по умолчанию: картинка практически без потерь, 60 кадров держатся почти во
всех сценах. 60 Мбит/с, если нужна гарантированная плавность в любой сцене ценой чуть более мягкой картинки. 120 Мбит/с, если
важны детали в тёмных HDR-сценах и не пугают просадки до 40 кадров в самых тяжёлых моментах. Если задержка важнее чёткости,
1440p декодируется за 7 мс вместо 13, а до панели телевизор масштабирует сам.
