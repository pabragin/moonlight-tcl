# Moonlight TCL (minimal)

A build of the [Artemis](https://github.com/ClassicOldSong/moonlight-android) game streaming client (a fork of
[Moonlight Android](https://github.com/moonlight-stream/moonlight-android)) for TCL Google TVs running **Android 14 firmware**
(C8K, C6K, QM6K, QM8K and similar).

## What this branch is

This branch (`tcl-minimal`) is Artemis **20.2.6 exactly as released in August 2025** (commit `4de0227f`) plus the smallest
possible TCL layer. Nothing that landed in Artemis after that commit is included: on TCL the later renderer changes made
controller response noticeably worse, so the video, decoder and input code here is the untouched 20.2.6 code.

Added on top of 20.2.6:

1. **Separate app**: package `com.limelight.tcl`, name "Moonlight TCL", own TV banner. Installs next to official Artemis and is
   not replaced by its updates. Only `armeabi-v7a` (TCL/MediaTek TVs run 32-bit apps; 64-bit TVs install it too).
2. **Compositor workaround** (`Settings → Advanced Settings → Android TV compositor workaround`). The TV's compositor hangs when it
   has to reconfigure while the stream video is the only layer on screen and is still receiving frames (volume OSD, app switch,
   leaving the stream). The app keeps a tiny invisible UI layer above the video and, on exit, stops the decoder and removes the
   video layer *before* the activity transition starts.
3. **Gamepad rumble block** (`Settings → Gamepad → Block gamepad rumble on this TV`). The firmware has a race in `system_server`'s
   InputReader that crashes when an `InputDevice` vibrates, which shows up as the TV rebooting mid-game. Rumble through the Android
   input stack is blocked; USB gamepads driven by Moonlight's own USB driver still rumble.
4. **4K defaults** for a fresh install: 3840x2160, 60 FPS, 100 Mbps. Declared as a game (`appCategory="game"`) so the TV may apply
   its game picture mode.

Both workarounds turn themselves on for TCL/MediaTek TVs on Android 14 or newer and can be toggled by hand.
Background: [moonlight-stream/moonlight-android#1533](https://github.com/moonlight-stream/moonlight-android/issues/1533).

## Measuring latency (built in)

Since tcl9 the app can measure button-to-frame latency itself, so builds can be compared without filming the screen:

1. On the PC open [`tools/latency-test.html`](tools/latency-test.html) in a browser and press **F** for full screen. The page is black
   and turns white while any gamepad button (or mouse button / key) is held.
2. On the TV enable *Settings → Advanced Settings → Latency test mode*, start the stream so the test page fills the picture,
   and press A/B/X/Y a few times. An overlay shows the last, average, min and max latency and how long the button event took
   to reach the app from the Android input stack. Every sample is also written to logcat (`Latency test: ...`).

The value is input stack + network + host + decode, i.e. up to the frame being available on the stream surface. The TV panel's
own processing delay is not included, which is exactly what makes two app versions comparable on the same TV.

## Download

APKs are on the [Releases](https://github.com/pabragin/moonlight-tcl/releases) page. Install with a file manager, the Downloader
app, or `adb install`; pair with your PC again on first start (settings are per app).

## Building

JDK 17, Android SDK with NDK `27.0.12077973`, `git submodule update --init --recursive`, then
`./gradlew :app:assembleNonRoot_gameRelease` and `zipalign` + `apksigner sign` the APK from
`app/build/outputs/apk/nonRoot_game/release/` with your own keystore. Do not build with `-Pandroid.injected.build.abi`: it marks the
APK `testOnly` and the TV's installer rejects it.

## Credits and license

All streaming functionality is the work of the Moonlight and Artemis authors: [Cameron Gutman](https://github.com/cgutman),
[Diego Waxemberg](https://github.com/dwaxemberg), [Aaron Neyer](https://github.com/Aaronneyer), [Andrew Hennessy](https://github.com/yetanothername),
[ClassicOldSong](https://github.com/ClassicOldSong) and the contributors of both projects. Licensed under the GNU GPL v3, see [LICENSE.txt](LICENSE.txt).

---

## Кратко по-русски

Ветка `tcl-minimal` — это Artemis 20.2.6 ровно в том виде, как он вышел в августе 2025 (`4de0227f`), плюс минимум для TCL:
отдельный пакет `com.limelight.tcl`, обходы зависания композитора и вибрации на прошивке Android 14, только armeabi-v7a и
настройки 4K по умолчанию. Всё, что Artemis добавил после этого коммита, сюда не вошло: на TCL это ухудшало отклик геймпада.
