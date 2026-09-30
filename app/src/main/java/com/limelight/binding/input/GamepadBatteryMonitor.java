package com.limelight.binding.input;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.InputDevice;

import com.limelight.LimeLog;
import com.limelight.ui.BatteryDrawable;

import java.util.ArrayList;
import java.util.List;

/**
 * The gamepads attached while the app's own screens are up (before a stream), with their charge, for the
 * status strip at the top of those screens. Same sources as ControllerHandler in a stream: the pad's GATT
 * Battery Service for the level, the Xbox plug-in counter for the cable, the first seconds after a pad
 * appears treated as provisional (the Xbox pad answers 50 % until it has measured).
 *
 * Main thread only; start() in onResume, stop() in onPause so the GATT clients are gone before a stream
 * opens its own.
 */
public final class GamepadBatteryMonitor implements InputManager.InputDeviceListener {
    public static final class Entry {
        public final int deviceId;
        public int percent = BatteryDrawable.LEVEL_UNKNOWN;
        public boolean charging;
        BluetoothDevice btDevice;
        BluetoothGattBattery battery;
        long attachedAtMs;
        int provisionalPercent = -1;
        int chargeCeilingPercent = -1;

        Entry(int deviceId) {
            this.deviceId = deviceId;
        }
    }

    public interface Listener {
        /** Main thread; the list is in order of appearance. */
        void onGamepadsChanged(List<Entry> gamepads);
    }

    private static final long BATTERY_SETTLE_MS = 6000;
    private static final long REFRESH_MS = 120_000;
    private static final int CHARGE_DROP_PERCENT = 2;

    private final Context context;
    private final Listener listener;
    private final InputManager inputManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SparseArray<Entry> entries = new SparseArray<>();
    private final List<Entry> order = new ArrayList<>();
    private BluetoothHidRumble bluetooth;
    private boolean running;

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            for (Entry entry : order) {
                if (entry.battery != null) {
                    entry.battery.refresh();
                }
            }
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    private final BluetoothGattBattery.Listener batteryListener = new BluetoothGattBattery.Listener() {
        @Override
        public void onBatteryLevel(BluetoothDevice device, int percent) {
            handler.post(() -> onLevel(device, percent));
        }

        @Override
        public void onChargerConnected(BluetoothDevice device) {
            handler.post(() -> onCharger(device));
        }
    };

    public GamepadBatteryMonitor(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.inputManager = (InputManager) context.getSystemService(Context.INPUT_SERVICE);
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        bluetooth = BluetoothHidRumble.openIfAvailable(context);
        for (int id : inputManager.getInputDeviceIds()) {
            InputDevice dev = inputManager.getInputDevice(id);
            if (dev != null && ControllerHandler.isAnnouncedGamepad(dev)) {
                add(dev, false);
            }
        }
        inputManager.registerInputDeviceListener(this, handler);
        handler.postDelayed(refreshRunnable, REFRESH_MS);
        publish();
    }

    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        inputManager.unregisterInputDeviceListener(this);
        handler.removeCallbacksAndMessages(null);
        for (Entry entry : order) {
            if (entry.battery != null) {
                entry.battery.close();
            }
        }
        entries.clear();
        order.clear();
        if (bluetooth != null) {
            bluetooth.close();
            bluetooth = null;
        }
    }

    private void add(InputDevice dev, boolean justAttached) {
        Entry entry = new Entry(dev.getId());
        entry.attachedAtMs = justAttached ? SystemClock.uptimeMillis() : 0;
        entry.btDevice = bluetooth != null ? bluetooth.resolve(dev) : null;
        if (entry.btDevice != null && entry.btDevice.getType() != BluetoothDevice.DEVICE_TYPE_CLASSIC) {
            entry.battery = new BluetoothGattBattery(context, entry.btDevice, batteryListener);
            entry.battery.refresh();
            if (justAttached) {
                // The real level replaces the pad's placeholder a few seconds after it connects
                handler.postDelayed(entry.battery::refresh, BATTERY_SETTLE_MS);
            }
        }
        entries.put(dev.getId(), entry);
        order.add(entry);
        LimeLog.info("Gamepad strip: " + dev.getName() + " (" + dev.getId() + ")"
                + (entry.battery != null ? ", battery over GATT" : ", no battery source"));
    }

    private Entry entryFor(BluetoothDevice device) {
        for (Entry entry : order) {
            if (entry.btDevice != null && device.getAddress().equals(entry.btDevice.getAddress())) {
                return entry;
            }
        }
        return null;
    }

    private void onLevel(BluetoothDevice device, int percent) {
        Entry entry = running ? entryFor(device) : null;
        if (entry == null) {
            return;
        }
        if (entry.attachedAtMs != 0) {
            boolean settled = SystemClock.uptimeMillis() - entry.attachedAtMs >= BATTERY_SETTLE_MS
                    || (entry.provisionalPercent >= 0 && percent != entry.provisionalPercent);
            if (!settled) {
                entry.provisionalPercent = percent;
                return;
            }
            entry.attachedAtMs = 0;
        }
        if (entry.charging) {
            if (percent > entry.chargeCeilingPercent) {
                entry.chargeCeilingPercent = percent;
            }
            else if (percent <= entry.chargeCeilingPercent - CHARGE_DROP_PERCENT) {
                // Draining again: the cable is gone (the pad never says so itself)
                entry.charging = false;
            }
        }
        if (entry.percent != percent) {
            entry.percent = percent;
            publish();
        }
    }

    private void onCharger(BluetoothDevice device) {
        Entry entry = running ? entryFor(device) : null;
        if (entry == null) {
            return;
        }
        entry.charging = true;
        entry.chargeCeilingPercent = Math.max(entry.chargeCeilingPercent, entry.percent);
        publish();
    }

    private void publish() {
        listener.onGamepadsChanged(new ArrayList<>(order));
    }

    @Override
    public void onInputDeviceAdded(int deviceId) {
        InputDevice dev = running ? inputManager.getInputDevice(deviceId) : null;
        if (dev == null || entries.get(deviceId) != null || !ControllerHandler.isAnnouncedGamepad(dev)) {
            return;
        }
        add(dev, true);
        publish();
    }

    @Override
    public void onInputDeviceRemoved(int deviceId) {
        Entry entry = entries.get(deviceId);
        if (entry == null) {
            return;
        }
        if (entry.battery != null) {
            entry.battery.close();
        }
        entries.remove(deviceId);
        order.remove(entry);
        publish();
    }

    @Override
    public void onInputDeviceChanged(int deviceId) {
        // Same pad, same battery client: nothing to redo
    }
}
