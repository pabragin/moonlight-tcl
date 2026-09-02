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

## Download and install

APKs are published on the [Releases](https://github.com/pabragin/moonlight-tcl/releases) page. Use the `armeabi-v7a` build: TCL/MediaTek TVs run
32-bit apps.

- The package id is `com.limelight.noir`, the same as official Artemis, but the APK is signed with a different key.
  **Uninstall the official Artemis first**, then install this one (file manager, Downloader app, or `adb install`).
- You will have to pair with your PC again.
- The performance overlay no longer needs to be enabled.

If the TV still freezes or reboots, open an issue with `adb logcat -v threadtime -b all` captured around the moment it happens, or, after a
reboot, the output of `adb shell dumpsys dropbox --print system_server_native_crash`.

## Building

- Install a JDK 17 and the Android SDK with NDK `27.0.12077973` (see `app/build.gradle`).
- Run `git submodule update --init --recursive`.
- Point Gradle at the SDK with `ANDROID_HOME` or a `local.properties` file containing `sdk.dir=`.
- Build one ABI: `./gradlew :app:assembleNonRoot_gameRelease -Pandroid.injected.build.abi=armeabi-v7a`, then `zipalign` and `apksigner sign`
  the unsigned APK from `app/build/intermediates/apk/nonRoot_game/release/` with your own keystore.

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
APK на странице [Releases](https://github.com/pabragin/moonlight-tcl/releases); перед установкой удалите официальный Artemis (другая
подпись), после установки спарьтесь с ПК заново.
