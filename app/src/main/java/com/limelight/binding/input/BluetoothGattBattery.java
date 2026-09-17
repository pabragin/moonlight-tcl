package com.limelight.binding.input;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;

import com.limelight.LimeLog;

import java.util.List;
import java.util.UUID;

/**
 * Reads a Bluetooth LE gamepad's charge from the standard GATT Battery Service (0x180F, characteristic
 * Battery Level 0x2A19) with the public BluetoothGatt API.
 *
 * Why not the input stack: this TV's kernel creates no power supply for HID devices, so
 * InputDevice.getBatteryState() never has anything. Why not the HID host: the Xbox Wireless Controller
 * connects as HID over GATT, and Android 14's LE HID host neither caches its battery input report (0x04,
 * GET_REPORT answers "no matching report") nor discovers the Battery Service. A second GATT client on the
 * same LE link is the documented way for an app to read a peripheral's battery; it does not disturb the
 * HID host's connection.
 *
 * All GATT calls are one at a time: connect -> discover -> subscribe to notifications -> read; a later
 * refresh() re-reads the level (or reconnects after the pad dropped the link).
 */
public final class BluetoothGattBattery {
    public interface Listener {
        /** percent 0..100; called on a Bluetooth binder thread */
        void onBatteryLevel(BluetoothDevice device, int percent);
    }

    private static final UUID BATTERY_SERVICE = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    private static final UUID BATTERY_LEVEL = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");
    private static final UUID CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Context appContext;
    private final BluetoothDevice device;
    private final Listener listener;

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic level;
    private boolean closed;
    private boolean servicesLogged;
    private boolean noBatteryService;

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

    /**
     * Connects on first use, re-reads the level afterwards; the value arrives through the Listener.
     * Cheap when the pad has no Battery Service: nothing is sent again after service discovery said so.
     */
    public synchronized void refresh() {
        if (closed || noBatteryService) {
            return;
        }
        try {
            if (gatt == null) {
                gatt = device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE);
                if (gatt == null) {
                    LimeLog.warning("GATT battery: connectGatt returned null for " + device.getAddress());
                }
            } else if (level != null) {
                gatt.readCharacteristic(level);
            }
        } catch (Throwable t) {
            LimeLog.warning("GATT battery: " + t);
        }
    }

    public synchronized void close() {
        closed = true;
        BluetoothGatt g = gatt;
        gatt = null;
        level = null;
        if (g != null) {
            try {
                g.disconnect();
                g.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private void deliver(byte[] value) {
        Integer percent = parseLevel(value);
        if (percent != null) {
            listener.onBatteryLevel(device, percent);
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
                synchronized (BluetoothGattBattery.this) {
                    if (g == gatt) {
                        gatt = null;
                        level = null;
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
            if (!servicesLogged) {
                servicesLogged = true;
                StringBuilder sb = new StringBuilder();
                List<BluetoothGattService> services = g.getServices();
                if (services != null) {
                    for (BluetoothGattService s : services) {
                        if (sb.length() > 0) {
                            sb.append(' ');
                        }
                        sb.append(shortUuid(s.getUuid()));
                    }
                }
                LimeLog.info("GATT battery: " + device.getName() + " status " + status + " services [" + sb + "]"
                        + (c != null ? ", Battery Level present" : ", no Battery Level"));
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
            }
            // Ask for notifications first (one GATT operation at a time), then read the current value
            BluetoothGattDescriptor cccd = c.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG);
            boolean pending = false;
            try {
                g.setCharacteristicNotification(c, true);
                if (cccd != null) {
                    pending = g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS;
                }
            } catch (Throwable t) {
                LimeLog.warning("GATT battery: notifications: " + t);
            }
            if (!pending) {
                readLevel(g, c);
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            BluetoothGattCharacteristic c;
            synchronized (BluetoothGattBattery.this) {
                c = level;
            }
            if (c != null) {
                readLevel(g, c);
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS && BATTERY_LEVEL.equals(c.getUuid())) {
                deliver(value);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            if (BATTERY_LEVEL.equals(c.getUuid())) {
                deliver(value);
            }
        }
    };

    private static void readLevel(BluetoothGatt g, BluetoothGattCharacteristic c) {
        try {
            g.readCharacteristic(c);
        } catch (Throwable t) {
            LimeLog.warning("GATT battery: readCharacteristic: " + t);
        }
    }

    private static String shortUuid(UUID uuid) {
        String s = uuid.toString();
        return s.endsWith("-0000-1000-8000-00805f9b34fb") && s.startsWith("0000") ? s.substring(4, 8) : s;
    }
}
