package com.limelight;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.limelight.binding.input.BluetoothHidRumble;
import com.limelight.utils.DeviceUtils;

import java.util.ArrayList;
import java.util.List;

public class DebugInfoActivity extends AppCompatActivity implements View.OnClickListener {

    private TextView tx_gamepad_info;
    private List<InputDevice> ids = new ArrayList<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_axitest);

        tx_gamepad_info = findViewById(R.id.tx_game_pad_info);
        TextView tx_content = findViewById(R.id.tx_content);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        String kernelVersion = System.getProperty("os.version");
        StringBuffer sb = new StringBuffer();
        sb.append(getString(R.string.debug_info_android_version) + DeviceUtils.getSDKVersionName());
        sb.append("\t" + getString(R.string.debug_info_api_version) + Build.VERSION.SDK_INT);
        sb.append("\n" + getString(R.string.debug_info_kernel_version) + kernelVersion);
        sb.append("\n" + getString(R.string.debug_info_brand_model) + DeviceUtils.getManufacturer() + "\t-\t" + DeviceUtils.getModel());
        tx_content.setText(sb.toString());

        // adb shell am start -n <pkg>/com.limelight.DebugInfoActivity --ez bt_hid_autotest true [--es pad Xbox]
        // runs the Bluetooth HID rumble test on the first matching gamepad and logs the result (LimeLog)
        if (getIntent().getBooleanExtra("bt_hid_autotest", false)) {
            updateGamePad();
            String wanted = getIntent().getStringExtra("pad");
            InputDevice target = null;
            for (InputDevice dev : ids) {
                if (wanted == null || dev.getName().toLowerCase().contains(wanted.toLowerCase())) {
                    target = dev;
                    break;
                }
            }
            // With no gamepad the test still reports whether the proxy binds and the hidden API is reachable;
            // --es mac XX:XX:XX:XX:XX:XX sends one report to that bonded HID device instead (call-path check)
            startBtHidTest(target, getIntent().getStringExtra("mac"));
        }
    }

    @Override
    public void onClick(View v) {
        // Gamepad rumble through the Bluetooth stack (the only rumble path on this TV)
        if (v.getId() == R.id.bt_vibrator_gamepad) {
            if (ids.isEmpty()) {
                Toast.makeText(DebugInfoActivity.this, getString(R.string.debug_info_no_gamepad_detected), Toast.LENGTH_LONG).show();
                return;
            }
            String[] strings = new String[ids.size()];
            for (int i = 0; i < ids.size(); i++) {
                strings[i] = ids.get(i).getName();
            }
            new AlertDialog.Builder(this).setItems(strings, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    dialog.dismiss();
                    startBtHidTest(ids.get(which), null);
                }
            }).setTitle(getString(R.string.debug_info_please_choose)).create().show();
            return;
        }

        // Refresh Gamepad Info
        if (v.getId() == R.id.bt_update_gamepad) {
            updateGamePad();
            return;
        }

    }

    // ---- Bluetooth HID output report test: rumble through the Bluetooth stack, not the input stack ----

    private static final int REQUEST_BT_CONNECT = 0x4254;
    private BluetoothHidRumble btHidRumble;

    private void startBtHidTest(final InputDevice dev, final String mac) {
        if (!BluetoothHidRumble.hasPermission(this)) {
            Toast.makeText(this, R.string.debug_info_bt_hid_permission, Toast.LENGTH_LONG).show();
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BT_CONNECT);
            return;
        }
        if (btHidRumble == null) {
            btHidRumble = BluetoothHidRumble.openIfAvailable(this);
            if (btHidRumble == null) {
                showBtHidResult("BluetoothHidRumble.openIfAvailable() returned null: Bluetooth off or no adapter");
                return;
            }
        }
        Toast.makeText(this, R.string.debug_info_bt_hid_running, Toast.LENGTH_SHORT).show();
        // The worker keeps its own reference: onDestroy() may close and drop the field meanwhile
        final BluetoothHidRumble rumble = btHidRumble;
        new Thread(new Runnable() {
            @Override
            public void run() {
                runBtHidTest(rumble, dev, mac);
            }
        }, "BtHidTest").start();
    }

    // Worker thread: sendData() is a blocking binder call into the Bluetooth process
    private void runBtHidTest(BluetoothHidRumble btHidRumble, InputDevice dev, String mac) {
        StringBuilder sb = new StringBuilder();
        long start = SystemClock.uptimeMillis();
        while (!btHidRumble.isReady() && !btHidRumble.isUnavailable() && SystemClock.uptimeMillis() - start < 5000) {
            SystemClock.sleep(50);
        }
        sb.append("hidden API exempted: ").append(btHidRumble.hiddenApiExempted()).append('\n');
        sb.append("proxy ready: ").append(btHidRumble.isReady()).append(" after ")
                .append(SystemClock.uptimeMillis() - start).append(" ms\n");
        BluetoothDevice device;
        BluetoothHidRumble.ReportFormat format;
        if (mac != null) {
            device = btHidRumble.deviceForAddress(mac);
            format = BluetoothHidRumble.XBOX_03;
            sb.append("target by address: ").append(mac).append(" -> ").append(device != null ? "ok" : "invalid").append('\n');
        } else {
            if (dev == null) {
                sb.append("no gamepad connected (").append(ids.size()).append(" candidates)\n");
                showBtHidResult(sb.toString());
                return;
            }
            sb.append("pad: ").append(dev.getName())
                    .append(String.format(" [%04x:%04x]", dev.getVendorId(), dev.getProductId())).append('\n');
            device = btHidRumble.resolve(dev);
            sb.append("bluetooth device: ").append(device != null ? device.getAddress() : "null")
                    .append(" (").append(btHidRumble.lastResolveMethod()).append(")\n");
            format = BluetoothHidRumble.reportFormatFor(dev.getVendorId(), dev.getProductId());
            sb.append("report format: ").append(format != null ? format.name() : "none").append('\n');
        }
        if (device == null || !btHidRumble.isReady()) {
            showBtHidResult(sb.toString());
            return;
        }
        final short full = (short) 0xFFFF, half = (short) 0x8000, zero = 0;
        String stop;
        String[][] steps;
        if (format == null || format == BluetoothHidRumble.XBOX_03) {
            // Xbox: also probe the enable-bit variants and the trigger motors
            stop = BluetoothHidRumble.buildXbox03(0x0F, 0, 0, 0, 0);
            steps = new String[][]{
                    {"strong + weak 100%, enable 0F", BluetoothHidRumble.buildXbox03(0x0F, 0, 0, 100, 100)},
                    {"triggers 100%, enable 0C", BluetoothHidRumble.buildXbox03(0x0C, 100, 100, 0, 0)},
                    {"strong only (byte 5)", BluetoothHidRumble.buildXbox03(0x0F, 0, 0, 100, 0)},
                    {"weak only (byte 6)", BluetoothHidRumble.buildXbox03(0x0F, 0, 0, 0, 100)},
                    {"strong + weak 100%, enable 03", BluetoothHidRumble.buildXbox03(0x03, 0, 0, 100, 100)},
                    {"format.build(low 50%, high 50%)", format != null ? format.build(half, half, zero, zero) : stop},
            };
        } else {
            // Other families: the prelude first (Switch enable-vibration, 8BitDo enhanced mode), then levels
            long t = SystemClock.uptimeMillis();
            btHidRumble.runPrelude(device, format);
            sb.append("prelude for ").append(format.name()).append(": feature report ")
                    .append(format.preludeFeatureReportId()).append(", ").append(format.preludeReports().size())
                    .append(" report(s), ").append(SystemClock.uptimeMillis() - t).append(" ms\n");
            stop = format.build(zero, zero, zero, zero);
            steps = new String[][]{
                    {"strong + weak 100%", format.build(full, full, zero, zero)},
                    {"strong only", format.build(full, zero, zero, zero)},
                    {"weak only", format.build(zero, full, zero, zero)},
                    {"strong + weak 50%", format.build(half, half, zero, zero)},
                    {"triggers 100%", format.hasTriggerMotors() ? format.build(zero, zero, full, full) : null},
            };
        }
        boolean anyFailure = false;
        if (mac != null) {
            // Call-path check against an arbitrary HID device: one report, one stop
            steps = new String[][]{steps[0]};
        }
        int refreshMs = format != null ? format.refreshIntervalMs() : 0;
        for (String[] step : steps) {
            if (step[1] == null) {
                continue;
            }
            long t = SystemClock.uptimeMillis();
            boolean ok = btHidRumble.send(device, step[1]);
            sb.append(ok ? "OK   " : "FAIL ").append(step[0]).append(' ').append(step[1])
                    .append(' ').append(SystemClock.uptimeMillis() - t).append(" ms\n");
            anyFailure |= !ok;
            // Hold the level for 1.2 s, repeating it for pads that stop without a refresh (Switch)
            long holdUntil = SystemClock.uptimeMillis() + 1200;
            while (SystemClock.uptimeMillis() < holdUntil) {
                long remaining = holdUntil - SystemClock.uptimeMillis();
                SystemClock.sleep(refreshMs > 0 ? Math.min(refreshMs, remaining) : remaining);
                if (refreshMs > 0 && SystemClock.uptimeMillis() < holdUntil) {
                    btHidRumble.send(device, step[1]);
                }
            }
            // Stateful formats need a fresh stop report each time (sequence counters)
            stop = format != null ? format.build(zero, zero, zero, zero) : stop;
            ok = btHidRumble.send(device, stop);
            sb.append(ok ? "OK   " : "FAIL ").append("stop\n");
            SystemClock.sleep(600);
        }
        if (anyFailure) {
            long t = SystemClock.uptimeMillis();
            boolean ok = btHidRumble.sendSetReport(device, steps[0][1]);
            sb.append(ok ? "OK   " : "FAIL ").append("setReport(OUTPUT) variant ")
                    .append(SystemClock.uptimeMillis() - t).append(" ms\n");
            SystemClock.sleep(1200);
            btHidRumble.sendSetReport(device, stop);
        }
        showBtHidResult(sb.toString());
    }

    private void showBtHidResult(final String text) {
        LimeLog.info("Bluetooth HID rumble test:\n" + text);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                new AlertDialog.Builder(DebugInfoActivity.this)
                        .setTitle(R.string.debug_info_bt_hid_result)
                        .setMessage(text)
                        .setPositiveButton(android.R.string.ok, null)
                        .create().show();
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BT_CONNECT) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            Toast.makeText(this, granted ? R.string.debug_info_bt_hid_granted : R.string.debug_info_bt_hid_permission, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (btHidRumble != null) {
            btHidRumble.close();
            btHidRumble = null;
        }
    }

    private void updateGamePad() {
        ids.clear();
        StringBuffer sb = new StringBuffer();
        sb.append("\n");
        int[] deviceIds = InputDevice.getDeviceIds();
        for (int deviceId : deviceIds) {
            InputDevice dev = InputDevice.getDevice(deviceId);
            int sources = dev.getSources();
            if (((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD)
                    || ((sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)) {
                if (getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_X) != null &&
                        getMotionRangeForJoystickAxis(dev, MotionEvent.AXIS_Y) != null) {
                    // This is a gamepad
                    ids.add(dev);
                    sb.append(getString(R.string.debug_info_name) + dev.getName());
                    sb.append("\n");
                    sb.append(getString(R.string.debug_info_sensors));
                    String sensor = "";
                    if (dev.getSensorManager().getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null) {
                        sensor += getString(R.string.debug_info_accelerometer);
                    }
                    if (dev.getSensorManager().getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null) {
                        sensor += getString(R.string.debug_info_gyroscope);
                    }
                    if (sensor.length() == 0) {
                        sb.append(getString(R.string.debug_info_no_relevant_driver));
                    } else {
                        sb.append(sensor);
                    }
                    sb.append("\n");
                    sb.append(getString(R.string.debug_info_vid_pid) + dev.getVendorId() + "_" + dev.getProductId()
                            + "\t    [" + String.format("%04x", dev.getVendorId()) + "_" + String.format("%04x", dev.getProductId()) + "]");
                    sb.append("\n");
                    sb.append(getString(R.string.debug_info_vibration) + (dev.getVibrator().hasVibrator() ? getString(R.string.debug_info_supported) : getString(R.string.debug_info_not_supported)));
                    sb.append("\n");
                    sb.append(getString(R.string.debug_info_details) + "\n");
                    sb.append(dev.toString());
                    sb.append("\n");
                }
            }
        }
        tx_gamepad_info.setText(getString(R.string.debug_info_number_of_gamepads) + ids.size() + "\n" + sb.toString());
    }

    private static InputDevice.MotionRange getMotionRangeForJoystickAxis(InputDevice dev, int axis) {
        InputDevice.MotionRange range;

        // First get the axis for SOURCE_JOYSTICK
        range = dev.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK);
        if (range == null) {
            // Now try the axis for SOURCE_GAMEPAD
            range = dev.getMotionRange(axis, InputDevice.SOURCE_GAMEPAD);
        }

        return range;
    }
}
