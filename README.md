# Artemis for TCL Android TV

A build of the [Artemis](https://github.com/ClassicOldSong/moonlight-android) game streaming client
(a fork of [Moonlight Android](https://github.com/moonlight-stream/moonlight-android)) with workarounds
for TCL Google TVs running **Android 14 firmware**.

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

1. **Compositor workaround** (`Settings → Advanced Settings → Android TV compositor workaround`). The TV's compositor hangs when it has to
   reconfigure while the stream video is the only layer on screen and is still receiving frames (volume OSD, app switch, leaving the
   stream). The app now keeps a tiny invisible UI layer above the video at all times, the same effect people got by enabling the
   performance overlay, and on exit it stops the decoder and removes the video layer *before* the activity transition starts.
2. **Gamepad rumble block** (`Settings → Gamepad → Block gamepad rumble on this TV`). The firmware has a race in `system_server`'s
   InputReader that crashes when an `InputDevice` vibrates, which shows up as the TV rebooting mid-game. Rumble through the Android
   input stack is blocked; USB gamepads driven by Moonlight's own USB driver still rumble.

Both options turn themselves on for TCL/MediaTek TVs on Android 14 or newer and stay off on other devices. They can be toggled by hand.

3. **Video pipeline back to the proven one.** Artemis after August 2025 (commit `4de0227f`) gained an experimental renderer: a
   "latest-frame" polling loop with adaptive frame dropping, a decoder watchdog that flushes the codec, a set of undocumented
   `vendor.mtk.vdec.*` decoder keys, reference frame invalidation for MediaTek, three competing `setFrameRate()` calls, and code that
   silently forced the *Balanced* frame pacing mode regardless of the setting. On TCL this showed up as a noticeably slower controller
   response. This build restores the previous renderer, honours the frame pacing setting (default: lowest latency) and picks HEVC
   automatically; AV1 remains available via "Force AV1".
4. **Defaults for a 4K TV.** First start uses 3840x2160, 60 FPS, 100 Mbps, HEVC and "Prefer lowest latency" frame pacing
   (Artemis defaults to 1280x720 and 80 Mbps at 4K). Everything is still adjustable in Settings.
5. **Decoder tuning that can be checked.** "Ultra Low Latency" is on by default on MediaTek TVs and now also requests the maximum
   operating rate from the MediaTek decoder; the video renderer thread runs at display priority. The performance overlay shows a
   "Low-latency mode" line with the options the decoder actually kept, so the effect of a setting can be verified without adb.
6. **Declared as a game.** The manifest carries `android:appCategory="game"` (plus the legacy `isGame` flag). Without the game
   category Android ignores the app's `GameManager` state updates and game-mode config, and TV vendors' automatic game picture
   mode keys off the category too. Together with `preferMinimalPostProcessing` this is everything an app can do to request the
   TV's game mode; whether the TV honours it is up to the firmware.


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

- Install a JDK 17 and the Android SDK with NDK `27.0.12077973` (see `app/build.gradle`).
- Run `git submodule update --init --recursive`.
- Point Gradle at the SDK with `ANDROID_HOME` or a `local.properties` file containing `sdk.dir=`.
- Build with `./gradlew :app:assembleNonRoot_gameRelease` (do not use `-Pandroid.injected.build.abi`: it marks the APK `testOnly`, which the
  TV's installer rejects as invalid), then `zipalign` and `apksigner sign` the APK from
  `app/build/outputs/apk/nonRoot_game/release/` with your own keystore.

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
Приложение называется Moonlight TCL и имеет свой идентификатор пакета `com.limelight.tcl`, поэтому ставится рядом с обычным Artemis
и не затирается его обновлениями. APK на странице [Releases](https://github.com/pabragin/moonlight-tcl/releases); после установки
спарьтесь с ПК заново.
