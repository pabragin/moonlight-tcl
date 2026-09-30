package com.limelight.binding.input;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.limelight.LimeLog;

import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Reads a Bluetooth LE gamepad's charge from the standard GATT Battery Service (0x180F, characteristic
 * Battery Level 0x2A19) with the public BluetoothGatt API, and on an Xbox Wireless Controller notices the
 * charging cable going in.
 *
 * Why not the input stack: this TV's kernel creates no power supply for HID devices, so
 * InputDevice.getBatteryState() never has anything. Why not the HID host: the Xbox Wireless Controller
 * connects as HID over GATT, and Android 14's LE HID host neither caches its battery input report (0x04,
 * GET_REPORT answers "no matching report") nor discovers the Battery Service. A second GATT client on the
 * same LE link is the documented way for an app to read a peripheral's battery; it does not disturb the
 * HID host's connection.
 *
 * The cable: the Battery Service is a bare level with no charging flag, and the pad's HID over GATT carries
 * no battery report. What the pad does have is a Microsoft vendor service whose first characteristic is a
 * table of (id16, value32 little-endian) statistics records; record 0x0010 counts the times a cable was
 * plugged in (measured 2026-09-30: +1 at every plug-in, nothing at unplug, nothing else moves with it).
 * The table is re-read every few seconds and a grown counter is reported as the charger.
 *
 * GATT allows one operation in flight per client, so everything goes through a queue: connect -> discover
 * -> subscribe to level notifications -> read the level -> larger MTU -> periodic table reads; a later
 * refresh() re-reads the level, or reconnects after the pad dropped the link.
 */
public final class BluetoothGattBattery {
    public interface Listener {
        /** percent 0..100; called on a Bluetooth binder thread */
        void onBatteryLevel(BluetoothDevice device, int percent);

        /** The pad's plug-in counter grew: a charging cable went in. Called on a Bluetooth binder thread. */
        void onChargerConnected(BluetoothDevice device);
    }

    private static final UUID BATTERY_SERVICE = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    private static final UUID BATTERY_LEVEL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    private static final UUID CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final String SIG_UUID_SUFFIX = "-0000-1000-8000-00805f9b34fb";

    // Xbox Wireless Controller vendor service: statistics table (read), status (read), command (write)
    private static final UUID XBOX_VENDOR_SERVICE = UUID.fromString("00000001-5f60-4c4f-9c83-a7953298d40d");
    private static final UUID XBOX_STATS_TABLE = UUID.fromString("00000002-5f60-4c4f-9c83-a7953298d40d");
    private static final int XBOX_STATS_PLUG_IN_COUNT = 0x0010;
    // The table is 512 bytes: with the default 23-byte MTU that is 24 round trips per read, so ask for a
    // bigger one first, and read it this often
    private static final int PREFERRED_MTU = 517;
    private static final long STATS_POLL_MS = 5000;
    // A queued operation that never answered blocks the queue; the next refresh() clears it after this long
    private static final long STALLED_OPERATION_MS = 10_000;

    private final Context appContext;
    private final BluetoothDevice device;
    private final Listener listener;

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic level;
    private BluetoothGattCharacteristic statsTable;
    private boolean closed;
    private boolean servicesLogged;
    private boolean noBatteryService;
    private long plugInCount = -1;

    private final ArrayDeque<BooleanSupplier> queue = new ArrayDeque<>();
    private boolean busy;
    private long busySinceMs;

    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            BluetoothGattCharacteristic table;
            synchronized (BluetoothGattBattery.this) {
                if (closed || gatt == null) {
                    return;
                }
                table = statsTable;
            }
            if (table != null) {
                enqueue(() -> startRead(table));
                pollHandler.postDelayed(this, STATS_POLL_MS);
            }
        }
    };

    public BluetoothGattBattery(Context context, BluetoothDevice device, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.device = device;
        this.listener = listener;
    }

    public BluetoothDevice getDevice() {
        return device;
    }

    /** null when the value is not a Battery Level (one byte, 0..100). */
    public static Integer parseLevel(byte[] value) {
        if (value == null || value.length == 0) {
            return null;
        }
        int percent = value[0] & 0xFF;
        return percent <= 100 ? percent : null;
    }

    /** The value of one record of the Xbox statistics table, or -1 when the table has no such record. */
    public static long parseStatsRecord(byte[] table, int id) {
        if (table == null) {
            return -1;
        }
        for (int i = 0; i + 6 <= table.length; i += 6) {
            if (((table[i] & 0xFF) | (table[i + 1] & 0xFF) << 8) == id) {
                return (table[i + 2] & 0xFFL) | (table[i + 3] & 0xFFL) << 8 | (table[i + 4] & 0xFFL) << 16 | (table[i + 5] & 0xFFL) << 24;
            }
        }
        return -1;
    }

    /**
     * Connects on first use, re-reads the level afterwards; the value arrives through the Listener.
     * Cheap when the pad has no Battery Service: nothing is sent again after service discovery said so.
     */
    public void refresh() {
        BluetoothGattCharacteristic c;
        synchronized (this) {
            if (closed || noBatteryService) {
                return;
            }
            if (gatt == null) {
                try {
                    gatt = device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE);
                    if (gatt == null) {
                        LimeLog.warning("GATT battery: connectGatt returned null for " + device.getAddress());
                    }
                } catch (Throwable t) {
                    LimeLog.warning("GATT battery: " + t);
                }
                return;
            }
            c = level;
            if (busy && SystemClock.uptimeMillis() - busySinceMs > STALLED_OPERATION_MS) {
                LimeLog.warning("GATT battery: operation never answered, dropping the queue");
                queue.clear();
                busy = false;
            }
        }
        if (c != null) {
            enqueue(() -> startRead(c));
        }
    }

    public void close() {
        BluetoothGatt g;
        pollHandler.removeCallbacks(pollRunnable);
        synchronized (this) {
            closed = true;
            g = gatt;
            gatt = null;
            level = null;
            statsTable = null;
            queue.clear();
            busy = false;
        }
        if (g != null) {
            try {
                g.disconnect();
                g.close();
            } catch (Throwable ignored) {
            }
        }
    }

    // ---- One GATT operation at a time ----

    private void enqueue(BooleanSupplier op) {
        synchronized (this) {
            queue.add(op);
            if (busy) {
                return;
            }
            busy = true;
            busySinceMs = SystemClock.uptimeMillis();
        }
        runNext();
    }

    // Starts queued operations until one is in flight (its callback calls this again) or none is left
    private void runNext() {
        while (true) {
            BooleanSupplier op;
            synchronized (this) {
                op = queue.poll();
                if (op == null) {
                    busy = false;
                    return;
                }
                busySinceMs = SystemClock.uptimeMillis();
            }
            boolean started;
            try {
                started = op.getAsBoolean();
            } catch (Throwable t) {
                LimeLog.warning("GATT battery: " + t);
                started = false;
            }
            if (started) {
                return;
            }
        }
    }

    private synchronized BluetoothGatt currentGatt() {
        return gatt;
    }

    private boolean startRead(BluetoothGattCharacteristic c) {
        BluetoothGatt g = currentGatt();
        return g != null && g.readCharacteristic(c);
    }

    private boolean startNotify(BluetoothGattCharacteristic c) {
        BluetoothGatt g = currentGatt();
        BluetoothGattDescriptor cccd = c.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG);
        if (g == null || cccd == null) {
            return false;
        }
        g.setCharacteristicNotification(c, true);
        byte[] value = (c.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
        return g.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS;
    }

    private boolean startMtuRequest() {
        BluetoothGatt g = currentGatt();
        return g != null && g.requestMtu(PREFERRED_MTU);
    }

    private void deliver(byte[] value) {
        Integer percent = parseLevel(value);
        if (percent != null) {
            listener.onBatteryLevel(device, percent);
        }
    }

    private void onStatsTable(byte[] value) {
        long count = parseStatsRecord(value, XBOX_STATS_PLUG_IN_COUNT);
        if (count < 0) {
            return;
        }
        long previous;
        synchronized (this) {
            previous = plugInCount;
            plugInCount = count;
        }
        if (previous >= 0 && count > previous) {
            LimeLog.info("GATT battery: " + device.getName() + " plug-in counter " + previous + " -> " + count);
            listener.onChargerConnected(device);
        }
    }

    private final BluetoothGattCallback callback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                try {
                    g.discoverServices();
                } catch (Throwable t) {
                    LimeLog.warning("GATT battery: discoverServices: " + t);
                }
                return;
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                // The pad went away (or the link dropped): forget this client, the next refresh() reconnects
                pollHandler.removeCallbacks(pollRunnable);
                synchronized (BluetoothGattBattery.this) {
                    if (g == gatt) {
                        gatt = null;
                        level = null;
                        statsTable = null;
                        plugInCount = -1;
                        queue.clear();
                        busy = false;
                    }
                }
                try {
                    g.close();
                } catch (Throwable ignored) {
                }
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattService service = status == BluetoothGatt.GATT_SUCCESS ? g.getService(BATTERY_SERVICE) : null;
            BluetoothGattCharacteristic c = service != null ? service.getCharacteristic(BATTERY_LEVEL) : null;
            BluetoothGattService vendor = status == BluetoothGatt.GATT_SUCCESS ? g.getService(XBOX_VENDOR_SERVICE) : null;
            BluetoothGattCharacteristic table = vendor != null ? vendor.getCharacteristic(XBOX_STATS_TABLE) : null;
            if (!servicesLogged) {
                servicesLogged = true;
                StringBuilder sb = new StringBuilder();
                List<BluetoothGattService> services = g.getServices();
                if (services != null) {
                    for (BluetoothGattService s : services) {
                        if (sb.length() > 0) {
                            sb.append(' ');
                        }
                        // Service with its characteristics, e.g. 180f(2a19): tells whether the pad has anything
                        // beyond the bare level, such as Battery Power State 2a1a or Battery Level Status 2bed
                        sb.append(shortUuid(s.getUuid())).append('(');
                        boolean first = true;
                        for (BluetoothGattCharacteristic ch : s.getCharacteristics()) {
                            if (!first) {
                                sb.append(',');
                            }
                            first = false;
                            sb.append(shortUuid(ch.getUuid()));
                        }
                        sb.append(')');
                    }
                }
                LimeLog.info("GATT battery: " + device.getName() + " status " + status + " services [" + sb + "]"
                        + (c != null ? ", Battery Level present" : ", no Battery Level")
                        + (table != null ? ", Xbox statistics table present" : ""));
            }
            if (c == null) {
                synchronized (BluetoothGattBattery.this) {
                    noBatteryService = status == BluetoothGatt.GATT_SUCCESS;
                }
                close();
                return;
            }
            synchronized (BluetoothGattBattery.this) {
                level = c;
                statsTable = table;
            }
            enqueue(() -> startNotify(c));
            enqueue(() -> startRead(c));
            if (table != null) {
                enqueue(BluetoothGattBattery.this::startMtuRequest);
                pollHandler.removeCallbacks(pollRunnable);
                pollHandler.post(pollRunnable);
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            LimeLog.info("GATT battery: " + device.getName() + " MTU " + mtu + " (status " + status + ")");
            runNext();
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            runNext();
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (BATTERY_LEVEL.equals(c.getUuid())) {
                    deliver(value);
                }
                else if (XBOX_STATS_TABLE.equals(c.getUuid())) {
                    onStatsTable(value);
                }
            }
            runNext();
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            if (BATTERY_LEVEL.equals(c.getUuid())) {
                deliver(value);
            }
        }
    };

    private static String shortUuid(UUID uuid) {
        String s = uuid.toString();
        return s.endsWith(SIG_UUID_SUFFIX) && s.startsWith("0000") ? s.substring(4, 8) : s;
    }
}
