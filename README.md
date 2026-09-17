<p align="center"><img src="store-assets/moonlight-tcl-icon.svg" width="128" alt="Moonlight TCL"></p>

# Moonlight TCL

A build of [Artemis](https://github.com/ClassicOldSong/moonlight-android) (the Moonlight Android fork) for **TCL Google TVs on
Android 14 firmware** (C8K, C6K, QM6K, QM8K and similar): a gamepad-only 4K client with the lowest latency the TV's MediaTek chip
can deliver, and no TV freezes or reboots. The numbers here were measured on a TCL C8K (the one game figure that was not is
marked as such).
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

**Rumble: through the Bluetooth stack, not the input service that restarts the TV**
- TCL's Android 14 firmware has a race in `system_server`'s input service: a gamepad vibration arrives on a binder thread and is
  pushed into the event queue that the input reader thread is draining without the lock. Any rumble sent through the Android
  input stack can hit it; when it does, the system service aborts, the TV shows the boot animation and every app restarts, which
  looks exactly like a reboot (confirmed from the crash dumps and AOSP source; fixed upstream in Android 15, so only TCL can fix
  it here). Moonlight TCL therefore sends rumble to Bluetooth gamepads through the Bluetooth stack's own HID host instead: the
  output report reaches the pad by the same Bluetooth path the kernel's force feedback would take, but `system_server` and its
  input reader are never involved. It needs the Nearby devices (Bluetooth) permission, which the app asks for once. Xbox pads are
  verified on the TV; DualShock 4, DualSense, Switch Pro and Joy-Con, 8BitDo, Amazon Luna, Google Stadia, NVIDIA Shield 2017 and
  GameSir 8K pads are written from the Linux and SDL driver sources and still wait for someone with the hardware (an issue saying
  whether yours rumbles would settle it). Rumble is **on** by default. Other Bluetooth pads stay silent on this TV: there is no
  switch back to the input service, because that path is what restarts the TV. A gamepad on a USB cable is driven by Moonlight's
  own USB driver and never touches the input stack either.

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
smoothness or latency; and of the MediaTek decoder's ~113 vendor keys, the latency-named ones were tried one at a time and none
helped (some are ignored, one blanks the screen, `game-mode` leaves the decode time unchanged and visibly smears the picture),
so none are used.

One more thing the numbers depend on: after about an hour of continuous 4K HDR the decoder on this TV started returning 45–50
frames per second with either client while still receiving 60, and fifteen minutes of idle brought it back to 60. All figures
here are from a rested TV.

What else runs on the TV matters as much as the client. In one session the frames on screen came in waves, about 40 per
second for 20 seconds every 90 seconds, while 60 kept arriving from the network and the socket dropped nothing: Google
Assistant's hands-free "Hey Google" detection was using a quarter of a core the whole time, a browser and the TV settings
sat in memory, the kernel was stalling on memory reclaim 250 times a second and the SoC ran at 78–80 °C. With hands-free
detection switched off and the background apps closed, the same stream held 60 in 86 % of samples instead of 48 %, and the
SoC ran two degrees cooler. Hands-free detection is turned off in the Assistant settings or with the TV's microphone switch.

## Compared with the official Artemis 20.2.6

Same TV, same PC, same settings (4K60 HEVC HDR, 100 Mbps, lowest-latency pacing). Artemis 20.2.6 is the release this fork
started from. For this run its source was built as the same package as Moonlight TCL, so both clients shared one pairing and
one settings file; they were installed over each other and measured back to back, twice each, on the same host session. The
source is [`tools/framerate-test.html`](tools/framerate-test.html) in heavy mode on the PC, a page that is guaranteed to
deliver 60 frames per second (its own counter read 60.0 throughout). The TV's compositor was asked every two seconds how many
frames it had actually put on the screen, and each answer was scored as either "all 60 per second" or "fewer". The
performance overlay was off in both clients: drawing it costs the compositor about 25 ms per frame and ruins the measurement.

| Test page, heavy mode, 100 Mbps | Artemis 20.2.6 | Moonlight TCL 20.2.10-tcl3 |
|---|---|---|
| Samples with all 60 frames per second on screen | 0 of 48; every sample 50–56 | 44 of 48; the rest 57–59 |
| Frames per second arriving from the network | 60 | 60 |
| Decoder time per frame | 14–15 ms | 14–15 ms |
| App CPU load (4 cores) | 54–62 % | 38–41 % |

Both clients receive the same 60 frames a second and decode them in the same time; the difference is in the path between the
decoder and the compositor. An earlier run at 200 Mbps, where the decoder itself is the limit for both, gave 5 of 22 samples
at 60 for Artemis against 10 of 22 here.

In a game at 100 Mbps the gap was larger, 40–51 frames per second on screen for Artemis against a steady 60 here while spinning
the camera in a heavy scene, but the PC's own frame rate was not recorded in those runs, so take that pair as indicative only.
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
or use `Settings → Follow Update` in the app. Moonlight TCL is also listed in the
[Obtainium app catalog](https://apps.obtainium.imranr.dev/), so it can be found from Obtainium's own "Add app" search.

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
странице [Releases](https://github.com/pabragin/moonlight-tcl/releases), обновления через Obtainium (приложение есть в его
каталоге); после установки спарьтесь с ПК заново.

**Что сделано.** Возвращён проверенный видеоконвейер; кадры уходят на экран прямо из декодера, без промежуточного потока; кадр
копируется один раз; декодеру всегда выставляются все опции низкой задержки; звук через нативный AAudio. Вибрация геймпада включена по
умолчанию и идёт в обход системной службы ввода: в прошивке Android 14 у неё есть гонка, которую запускает вибрация через системный
стек, служба падает, телевизор показывает анимацию загрузки, и все приложения перезапускаются (исправлено только в Android 15).
Bluetooth-геймпадам вибрация отправляется через собственный HID-хост Bluetooth-стека, для этого нужно разрешение «Устройства
поблизости»; Xbox проверен на телевизоре, DualShock 4, DualSense, Switch Pro, Joy-Con, 8BitDo, Amazon Luna, Google Stadia, NVIDIA
Shield 2017 и GameSir 8K сделаны по исходникам драйверов Linux и SDL и ждут проверки на живых геймпадах; остальные Bluetooth-геймпады
на этом телевизоре не вибрируют, обратного переключения на системную службу нет. По USB-кабелю вибрация идёт через собственный
драйвер Moonlight. Зависания
телевизора из первых сборок больше не воспроизводятся. Настроек мало, есть встроенный тест задержки и
сообщение с временем декодирования после стрима.

**Сравнение с официальным Artemis 20.2.6** на том же телевизоре при одинаковых настройках 4K60 HDR, 100 Мбит/с. Исходники
Artemis собраны как тот же пакет, поэтому у обоих клиентов одна пара с ПК и один файл настроек; они ставились друг поверх друга
и мерялись подряд, по два раза каждый, в одной сессии хоста. На тестовой странице `tools/framerate-test.html` в тяжёлом режиме,
которая гарантированно выдаёт 60 кадров в секунду, композитор телевизора каждые две секунды опрашивался, сколько кадров он
реально вывел; оверлей статистики у обоих выключен, его отрисовка стоит композитору около 25 мс на кадр. Из сети оба получают
60 кадров в секунду и декодируют за 14–15 мс, но у Artemis все 60 не дошли до экрана ни в одном из 48 замеров (везде 50–56),
у этой сборки дошли в 44 из 48 (остальные 57–59); загрузка процессора 54–62 % против 38–41 %. При 200 Мбит/с, когда оба
упираются в декодер, в более раннем прогоне 5 из 22 против 10 из 22. В игре разрыв был больше, 40–51 кадр против ровных 60, но
частота хоста там не записывалась. Вибрация у Artemis идёт через системный стек, который перезагружает этот телевизор. Ещё
одно наблюдение: примерно через час непрерывного 4K HDR декодер этого телевизора начал выдавать 45–50 кадров в секунду с любым
клиентом, а после пятнадцати минут простоя вернулся к 60; все цифры сняты на отдохнувшем телевизоре. Фоновая нагрузка телевизора влияет не меньше клиента: в одной
сессии кадры на экране шли волнами, около 40 в секунду по 20 секунд каждые полторы минуты, хотя из сети приходили все 60 и
сокет ничего не терял. Причина: Google Assistant с распознаванием «Окей, Google» без пульта постоянно занимал четверть ядра,
в памяти сидели браузер и настройки телевизора, ядро 250 раз в секунду останавливалось на освобождение памяти, SoC 78–80 °C.
После отключения голосового управления без пульта и закрытия фоновых приложений тот же стрим держал 60 кадров в 86 % замеров
вместо 48 %, а SoC стал на два градуса холоднее. Голосовое управление без пульта выключается в настройках Assistant или
переключателем микрофона на телевизоре.

**Какой битрейт ставить для 4K60 HDR.** 100 Мбит/с по умолчанию: картинка практически без потерь, 60 кадров держатся почти во
всех сценах. 60 Мбит/с, если нужна гарантированная плавность в любой сцене ценой чуть более мягкой картинки. 120 Мбит/с, если
важны детали в тёмных HDR-сценах и не пугают просадки до 40 кадров в самых тяжёлых моментах. Если задержка важнее чёткости,
1440p декодируется за 7 мс вместо 13, а до панели телевизор масштабирует сам.
