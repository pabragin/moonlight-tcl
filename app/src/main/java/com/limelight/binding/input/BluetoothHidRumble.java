package com.limelight.binding.input;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.view.InputDevice;

import com.limelight.LimeLog;
import com.limelight.nvstream.jni.MoonBridge;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * Gamepad rumble sent through the Bluetooth stack's HID host instead of the Android input stack.
 *
 * Vibrating an InputDevice goes through system_server's InputReader thread, which on Android 14 has a
 * data race between vibrate() and the reader's queue flush (fixed in Android 15). On TCL's firmware that
 * race restarts system_server. The Bluetooth stack (com.android.bluetooth) can write an HID output report
 * to a connected pad itself: BluetoothHidHost.sendData() ends in the same GATT/L2CAP write the kernel's
 * force-feedback path uses, but never touches system_server. The class and its methods are hidden system
 * APIs, so they are reached through reflection after a hidden-API exemption; the server side only asks
 * for BLUETOOTH_CONNECT.
 *
 * Every failure collapses to "no rumble": callers fall back to whatever policy they have.
 */
public final class BluetoothHidRumble {
    /** BluetoothProfile.HID_HOST; the constant itself is a hidden system API. */
    private static final int PROFILE_HID_HOST = 4;
    /** BluetoothHidHost.REPORT_TYPE_INPUT / REPORT_TYPE_OUTPUT / REPORT_TYPE_FEATURE */
    private static final byte REPORT_TYPE_INPUT = 1;
    private static final byte REPORT_TYPE_OUTPUT = 2;
    private static final byte REPORT_TYPE_FEATURE = 3;
    /** Pause after a prelude so the pad acts on it before the first rumble report. */
    private static final long PRELUDE_SETTLE_MS = 60;
    /** How long after open the profile proxy may still be binding before it counts as unavailable. */
    private static final long PROXY_GRACE_MS = 5000;

    private static final boolean HIDDEN_API_EXEMPTED;
    static {
        boolean ok = false;
        try {
            ok = HiddenApiBypass.addHiddenApiExemptions(
                    "Landroid/bluetooth/BluetoothHidHost;",
                    "Landroid/view/InputDevice;");
        } catch (Throwable t) {
            LimeLog.warning("Bluetooth HID rumble: hidden API exemption failed: " + t);
        }
        HIDDEN_API_EXEMPTED = ok;
    }

    /**
     * Builds the HID output reports (as the ASCII hex strings the stack expects) for one gamepad
     * family. Formats with a sequence counter get one instance per gamepad from reportFormatFor().
     * Motor levels are unsigned 16-bit (0..65535); low frequency = strong/left, high = weak/right.
     */
    public abstract static class ReportFormat {
        public abstract String name();

        public abstract String build(short lowFreqMotor, short highFreqMotor, short leftTriggerMotor, short rightTriggerMotor);

        /** Never send reports closer together than this: the radio and the pad's firmware set the pace. */
        public int minIntervalMs() {
            return 20;
        }

        /** Whether the pad has trigger motors this report can drive. */
        public boolean hasTriggerMotors() {
            return false;
        }

        /** Re-send a running level after this long without a change; 0 = the pad keeps rumbling on its own. */
        public int refreshIntervalMs() {
            return 0;
        }

        /** Feature report to GET once before the first rumble (0 = none); the reply is ignored. */
        public int preludeFeatureReportId() {
            return 0;
        }

        /** Output reports to send once before the first rumble, e.g. "enable vibration". */
        public List<String> preludeReports() {
            return Collections.emptyList();
        }
    }

    /**
     * Xbox Wireless Controller (Bluetooth, report protocol). Output report 0x03:
     * enable bits (BIT0 weak/right, BIT1 strong/left, BIT2 right trigger, BIT3 left trigger), then
     * left trigger, right trigger, strong, weak magnitudes 0..100, duration and loop count in 10 ms units.
     * Same body as the GIP rumble packet of the USB driver (XboxOneController), same values as the
     * Linux hid-microsoft driver the TV kernel uses for this pad today. Verified on the TCL C8K.
     */
    public static final ReportFormat XBOX_03 = new ReportFormat() {
        @Override
        public String name() {
            return "xbox-03";
        }

        @Override
        public String build(short lowFreqMotor, short highFreqMotor, short leftTriggerMotor, short rightTriggerMotor) {
            return buildXbox03(0x0F, scalePercent(leftTriggerMotor), scalePercent(rightTriggerMotor),
                    scalePercent(lowFreqMotor), scalePercent(highFreqMotor));
        }

        @Override
        public boolean hasTriggerMotors() {
            return true;
        }
    };

    public static String buildXbox03(int enable, int leftTrigger, int rightTrigger, int strong, int weak) {
        byte[] report = {
                0x03, (byte) enable,
                (byte) leftTrigger, (byte) rightTrigger, (byte) strong, (byte) weak,
                (byte) 0xFF, 0x00, (byte) 0xFF
        };
        return toHex(report);
    }

    /**
     * DualShock 4 over Bluetooth (Linux hid-sony / hid-playstation, SDL_hidapi_ps4). Output report 0x11,
     * 78 bytes: hw_control 0xC0 = HID data + CRC present, low bits = 4 ms poll interval (the kernel
     * driver's default), valid_flag0 0x01 = motors only so the light bar keeps whatever the kernel
     * driver set, motor_right = weak, motor_left = strong (0..255), CRC32 over the 0xA2 HIDP header plus
     * the first 74 bytes, little-endian in the last four. UNTESTED: written from the drivers' sources.
     */
    public static final class DualShock4Format extends ReportFormat {
        @Override
        public String name() {
            return "dualshock4-11";
        }

        @Override
        public String build(short lowFreqMotor, short highFreqMotor, short leftTriggerMotor, short rightTriggerMotor) {
            return buildDualShock4_11(scaleByte(lowFreqMotor), scaleByte(highFreqMotor));
        }
    }

    public static String buildDualShock4_11(int strong, int weak) {
        byte[] report = new byte[78];
        report[0] = 0x11;
        report[1] = (byte) 0xC4;
        report[3] = 0x01;
        report[6] = (byte) weak;
        report[7] = (byte) strong;
        appendSonyCrc(report);
        return toHex(report);
    }

    /**
     * DualSense and DualSense Edge over Bluetooth (Linux hid-playstation, SDL_hidapi_ps5). Output report
     * 0x31, 78 bytes: seq_tag (sequence number in the high nibble, +1 per report), tag 0x10, then the
     * common block: valid_flag0 0x03 = "compatible vibration" (the DualShock-style rumble emulation every
     * firmware supports; the newer v2 flag needs the firmware version we cannot read here) + haptics
     * select, motor_right = weak, motor_left = strong (0..255), CRC32 as on the DualShock 4.
     * UNTESTED: written from the drivers' sources.
     */
    public static final class DualSenseFormat extends ReportFormat {
        private int seq;

        @Override
        public String name() {
            return "dualsense-31";
        }

        @Override
        public String build(short lowFreqMotor, short highFreqMotor, short leftTriggerMotor, short rightTriggerMotor) {
            String report = buildDualSense31(seq, scaleByte(lowFreqMotor), scaleByte(highFreqMotor));
            seq = (seq + 1) & 0xF;
            return report;
        }
    }

    public static String buildDualSense31(int seq, int strong, int weak) {
        byte[] report = new byte[78];
        report[0] = 0x31;
        report[1] = (byte) ((seq & 0xF) << 4);
        report[2] = 0x10;
        report[3] = 0x03;
        report[5] = (byte) weak;
        report[6] = (byte) strong;
        appendSonyCrc(report);
        return toHex(report);
    }

    /**
     * Nintendo Switch Pro Controller and Joy-Cons over Bluetooth (Linux hid-nintendo, SDL_hidapi_switch,
     * dekuNukem's reverse engineering). Output report 0x10: packet counter 0..15, then 4 bytes of
     * "HD rumble" data per motor (left, right): a 320 Hz high band and a 160 Hz low band at the same
     * amplitude, encoded through the kernel driver's amplitude table (0..1003). The pad vibrates only
     * after subcommand 0x48 "enable vibration" in output report 0x01; the TV kernel's 2019 hid-nintendo
     * never sends it (no force feedback at all there), so it goes out as the prelude. Reports must be
     * 30 ms apart or more (faster can switch the pad off over Bluetooth) and a running level has to be
     * repeated every 50 ms or the motors stop. A lone Joy-Con has one motor: it gets the louder level.
     * UNTESTED: written from the drivers' sources.
     */
    public static final class SwitchFormat extends ReportFormat {
        private final boolean singleJoyCon;
        private int counter;

        public SwitchFormat(boolean singleJoyCon) {
            this.singleJoyCon = singleJoyCon;
        }

        @Override
        public String name() {
            return singleJoyCon ? "joycon-10" : "switch-10";
        }

        @Override
        public String build(short lowFreqMotor, short highFreqMotor, short leftTriggerMotor, short rightTriggerMotor) {
            int left = scaleSwitchAmp(lowFreqMotor), right = scaleSwitchAmp(highFreqMotor);
            if (singleJoyCon) {
                left = right = Math.max(left, right);
            }
            String report = buildSwitch10(counter, left, right);
            counter = (counter + 1) & 0xF;
            return report;
        }

        @Override
        public int minIntervalMs() {
            return 30;
        }

        @Override
        public int refreshIntervalMs() {
            return 50;
        }

        @Override
        public List<String> preludeReports() {
            String report = buildSwitchEnableVibration(counter);
            counter = (counter + 1) & 0xF;
            return Collections.singletonList(report);
        }
    }

    /** Amplitude steps of the kernel driver's table; index i encodes as high byte 2i, low word 0x40+i/2 (bit 15 for odd i). */
    private static final int[] SWITCH_AMP_STEPS = {
            0, 10, 12, 14, 17, 20, 24, 28, 33, 40, 47, 56, 67, 80, 95, 112, 117, 123, 128, 134, 140, 146, 152, 159,
            166, 173, 181, 189, 198, 206, 215, 225, 230, 235, 240, 245, 251, 256, 262, 268, 273, 279, 286, 292, 298,
            305, 311, 318, 325, 332, 340, 347, 355, 362, 370, 378, 387, 395, 404, 413, 422, 431, 440, 450, 460, 470,
            480, 491, 501, 512, 524, 535, 547, 559, 571, 584, 596, 609, 623, 636, 650, 665, 679, 694, 709, 725, 741,
            757, 773, 790, 808, 825, 843, 862, 881, 900, 920, 940, 960, 981, 1003
    };
    private static final int SWITCH_MAX_AMP = 1003;

    /** 0..65535 -> index into SWITCH_AMP_STEPS, the way hid-nintendo picks it (first step >= amplitude). */
    static int scaleSwitchAmp(short value) {
        int amp = (int) (((value & 0xFFFFL) * SWITCH_MAX_AMP) / 65535);
        if (amp <= 0) {
            return 0;
        }
        for (int i = 1; i < SWITCH_AMP_STEPS.length - 1; i++) {
            if (amp <= SWITCH_AMP_STEPS[i]) {
                return i;
            }
        }
        return SWITCH_AMP_STEPS.length - 1;
    }

    /** Four rumble bytes for one motor: 320 Hz high band (0x0001) and 160 Hz low band (0x40) at amplitude step i. */
    private static void encodeSwitchMotor(byte[] out, int offset, int ampIndex) {
        int ampHigh = ampIndex * 2;
        int ampLow = 0x40 + (ampIndex >> 1) + ((ampIndex & 1) != 0 ? 0x8000 : 0);
        out[offset] = 0x00;
        out[offset + 1] = (byte) (0x01 + ampHigh);
        out[offset + 2] = (byte) (0x40 + ((ampLow >> 8) & 0xFF));
        out[offset + 3] = (byte) (ampLow & 0xFF);
    }

    public static String buildSwitch10(int counter, int leftAmpIndex, int rightAmpIndex) {
        byte[] report = new byte[10];
        report[0] = 0x10;
        report[1] = (byte) (counter & 0xF);
        encodeSwitchMotor(report, 2, leftAmpIndex);
        encodeSwitchMotor(report, 6, rightAmpIndex);
        return toHex(report);
    }

    /** Output report 0x01 carrying subcommand 0x48 (enable vibration) with neutral rumble data. */
    public static String buildSwitchEnableVibration(int counter) {
        byte[] report = new byte[12];
        report[0] = 0x01;
        report[1] = (byte) (counter & 0xF);
        encodeSwitchMotor(report, 2, 0);
        encodeSwitchMotor(report, 6, 0);
        report[10] = 0x48;
        report[11] = 0x01;
        return toHex(report);
    }

    /**
     * 8BitDo pads in their own Bluetooth mode (SDL_hidapi_8bitdo). Output report 0x05: strong, weak,
     * left trigger, right trigger, 0..255 each. SDL reads feature report 0x06 before anything else on
     * the SF30/SN30 Pro, Pro 2 and Pro 3, which switches the pad into its enhanced mode; sent as the
     * prelude. In X-input mode these pads pose as an Xbox controller (045e:02e0) and take the Xbox
     * report; in Switch mode as a Pro Controller. UNTESTED: written from SDL's source.
     */
    public static final class EightBitDoFormat extends ReportFormat {
        private final boolean enhancedModeFeature;

        public EightBitDoFormat(boolean enhancedModeFeature) {
            this.enhancedModeFeature = enhancedModeFeature;
        }

        @Override
        public String name() {
            return "8bitdo-05";
        }

        @Override
        public String build(short lowFreqMotor, short highFreqMotor, short leftTriggerMotor, short rightTriggerMotor) {
            return build8BitDo05(scaleByte(lowFreqMotor), scaleByte(highFreqMotor),
                    scaleByte(leftTriggerMotor), scaleByte(rightTriggerMotor));
        }

        @Override
        public boolean hasTriggerMotors() {
            return true;
        }

        @Override
        public int preludeFeatureReportId() {
            return enhancedModeFeature ? 0x06 : 0;
        }
    }

    public static String build8BitDo05(int strong, int weak, int leftTrigger, int rightTrigger) {
        byte[] report = {0x05, (byte) strong, (byte) weak, (byte) leftTrigger, (byte) rightTrigger};
        return toHex(report);
    }

    /**
     * Amazon Luna Controller over Bluetooth (SDL_hidapi_luna): takes the Xbox output report 0x03, but has
     * no trigger motors. UNTESTED: written from SDL's source.
     */
    public static final ReportFormat LUNA_03 = new ReportFormat() {
        @Override
        public String name() {
            return "luna-03";
        }

        @Override
        public String build(short lowFreqMotor, short highFreqMotor, short leftTriggerMotor, short rightTriggerMotor) {
            return buildXbox03(0x0F, 0, 0, scalePercent(lowFreqMotor), scalePercent(highFreqMotor));
        }
    };

    /**
     * Google Stadia Controller after the 2023 Bluetooth firmware (SDL_hidapi_stadia). Output report 0x05:
     * strong and weak levels as 16-bit little-endian values. UNTESTED: written from SDL's source.
     */
    public static final class StadiaFormat extends ReportFormat {
        @Override
        public String name() {
            return "stadia-05";
        }

        @Override
        public String build(short lowFreqMotor, short highFreqMotor, short leftTriggerMotor, short rightTriggerMotor) {
            return buildStadia05(lowFreqMotor & 0xFFFF, highFreqMotor & 0xFFFF);
        }
    }

    public static String buildStadia05(int strong, int weak) {
        byte[] report = {0x05, (byte) strong, (byte) (strong >> 8), (byte) weak, (byte) (weak >> 8)};
        return toHex(report);
    }

    /**
     * NVIDIA Shield Controller 2017 (SDL_hidapi_shield). Command report 0x04, 33 bytes (shorter reports
     * are dropped over Bluetooth): command 0x39 = rumble, sequence number, then enable 0x01, left and
     * right amplitude 0..31 (the official driver tones the strong motors down: level >> 11). A running
     * level is refreshed every 500 ms. UNTESTED: written from SDL's source.
     */
    public static final class ShieldFormat extends ReportFormat {
        private int seq;

        @Override
        public String name() {
            return "shield-39";
        }

        @Override
        public String build(short lowFreqMotor, short highFreqMotor, short leftTriggerMotor, short rightTriggerMotor) {
            String report = buildShieldRumble(seq, scaleShield(lowFreqMotor), scaleShield(highFreqMotor));
            seq = (seq + 1) & 0xFF;
            return report;
        }

        @Override
        public int refreshIntervalMs() {
            return 500;
        }
    }

    /** 0..65535 -> 0..31, the Shield driver's own attenuation */
    static int scaleShield(short value) {
        return (value & 0xFFFF) >> 11;
    }

    public static String buildShieldRumble(int seq, int left, int right) {
        byte[] report = new byte[33];
        report[0] = 0x04;
        report[1] = 0x39;
        report[2] = (byte) seq;
        report[3] = 0x01;
        report[4] = (byte) left;
        report[5] = (byte) right;
        return toHex(report);
    }

    /**
     * GameSir Tarantula 8K and G7 Pro 8K in Bluetooth mode (SDL_hidapi_gamesir). Report 0xA2, 64 bytes:
     * command 0x03, strong, weak (0..255), zero padding. UNTESTED: written from SDL's source.
     */
    public static final class GameSir8kFormat extends ReportFormat {
        @Override
        public String name() {
            return "gamesir-a2";
        }

        @Override
        public String build(short lowFreqMotor, short highFreqMotor, short leftTriggerMotor, short rightTriggerMotor) {
            return buildGameSirA2(scaleByte(lowFreqMotor), scaleByte(highFreqMotor));
        }
    }

    public static String buildGameSirA2(int strong, int weak) {
        byte[] report = new byte[64];
        report[0] = (byte) 0xA2;
        report[1] = 0x03;
        report[2] = (byte) strong;
        report[3] = (byte) weak;
        return toHex(report);
    }

    /** Which report format a pad speaks, by USB/Bluetooth vendor and product ID; null = unknown pad. */
    public static ReportFormat reportFormatFor(int vendorId, int productId) {
        switch (vendorId) {
            case 0x045e: // Microsoft
                switch (productId) {
                    case 0x0b13: // Xbox Series X|S controller (1914), BLE (verified)
                    case 0x0b20: // Xbox One S controller (1708) with BLE firmware
                    case 0x0b22: // Xbox Elite Series 2 with BLE firmware
                    case 0x0b05: // Xbox Elite Series 2, classic Bluetooth (untested)
                    case 0x02fd: // Xbox One S controller (1708), classic Bluetooth (untested)
                    case 0x02e0: // 8BitDo SN30 Pro+ and friends in X-input mode (untested)
                        return XBOX_03;
                    default:
                        return null;
                }
            case 0x054c: // Sony
                switch (productId) {
                    case 0x05c4: // DualShock 4 (CUH-ZCT1)
                    case 0x09cc: // DualShock 4 v2 (CUH-ZCT2)
                        return new DualShock4Format();
                    case 0x0ce6: // DualSense
                    case 0x0df2: // DualSense Edge
                        return new DualSenseFormat();
                    default:
                        return null;
                }
            case 0x057e: // Nintendo
                switch (productId) {
                    case 0x2009: // Pro Controller (also 8BitDo and other pads in Switch mode)
                        return new SwitchFormat(false);
                    case 0x2006: // Joy-Con (L)
                    case 0x2007: // Joy-Con (R)
                        return new SwitchFormat(true);
                    default:
                        return null;
                }
            case 0x2dc8: // 8BitDo
                switch (productId) {
                    case 0x6000: // SF30 Pro
                    case 0x6100: // SF30 Pro, Bluetooth
                    case 0x6001: // SN30 Pro
                    case 0x6101: // SN30 Pro, Bluetooth
                    case 0x6003: // Pro 2
                    case 0x6006: // Pro 2, Bluetooth
                    case 0x6009: // Pro 3
                        return new EightBitDoFormat(true);
                    case 0x6012: // Ultimate 2 Wireless
                    case 0x202f: // Ultimate 3
                        return new EightBitDoFormat(false);
                    default:
                        return null;
                }
            case 0x1949: // Amazon, USB vendor ID (also seen over Bluetooth)
                switch (productId) {
                    case 0x0419: // Luna Controller
                    case 0x041a: // Luna Controller, later revision
                        return LUNA_03;
                    default:
                        return null;
                }
            case 0x0171: // Amazon, Bluetooth vendor ID
                return productId == 0x0419 ? LUNA_03 : null;
            case 0x18d1: // Google
                return productId == 0x9400 ? new StadiaFormat() : null; // Stadia Controller
            case 0x0955: // NVIDIA
                // Shield Controller 2017; the 2015 model (0x7210) talks Wi-Fi Direct, not Bluetooth
                return productId == 0x7214 ? new ShieldFormat() : null;
            case 0x3537: // GameSir
                switch (productId) {
                    case 0x103c: // Tarantula 8K
                    case 0x10b8: // G7 Pro 8K
                        return new GameSir8kFormat();
                    default:
                        return null;
                }
            default:
                return null;
        }
    }

    /** 0..65535 (unsigned short) -> 0..100 */
    static int scalePercent(short value) {
        return ((value & 0xFFFF) * 100 + 32767) / 65535;
    }

    /** 0..65535 (unsigned short) -> 0..255 */
    static int scaleByte(short value) {
        return (value & 0xFFFF) >> 8;
    }

    /** Sony Bluetooth output reports end in a CRC32 over the 0xA2 HIDP header and the rest of the report. */
    private static void appendSonyCrc(byte[] report) {
        CRC32 crc = new CRC32();
        crc.update(0xA2);
        crc.update(report, 0, report.length - 4);
        long value = crc.getValue();
        int n = report.length - 4;
        report[n] = (byte) value;
        report[n + 1] = (byte) (value >> 8);
        report[n + 2] = (byte) (value >> 16);
        report[n + 3] = (byte) (value >> 24);
    }

    private static String toHex(byte[] data) {
        char[] digits = "0123456789ABCDEF".toCharArray();
        char[] out = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            out[i * 2] = digits[(data[i] >> 4) & 0xF];
            out[i * 2 + 1] = digits[data[i] & 0xF];
        }
        return new String(out);
    }

    /** BluetoothHidHost.ACTION_REPORT and its extras (hidden @SystemApi): the reply to a getReport() request. */
    private static final String ACTION_REPORT = "android.bluetooth.input.profile.action.REPORT";
    private static final String EXTRA_REPORT = "android.bluetooth.BluetoothHidHost.extra.REPORT";
    private static final String EXTRA_REPORT_BUFFER_SIZE = "android.bluetooth.BluetoothHidHost.extra.REPORT_BUFFER_SIZE";

    /** Receives the payload of every report the HID host hands back after a getReport() request. */
    public interface ReportListener {
        void onReport(BluetoothDevice device, byte[] report);
    }

    /**
     * Xbox controllers on Bluetooth carry their battery in input report 0x04, a single status byte. This
     * TV's kernel creates no power supply for HID devices, so InputDevice.getBatteryState() never has
     * anything; asking the pad over the same HID host that carries rumble does. Layout as read by the
     * Linux xpadneo driver: bit 7 online, bit 4 charging, bits 3-2 mode (0 none/USB power, 1 battery,
     * 2 charging cable), bits 1-0 level (0 critical .. 3 full).
     */
    public static final int XBOX_BATTERY_REPORT_ID = 0x04;
    private static final byte[] XBOX_LEVEL_PERCENT = {10, 35, 65, 100};

    public static final class BatteryInfo {
        public final byte state;
        public final byte percentage;
        public final int rawStatus;

        BatteryInfo(byte state, byte percentage, int rawStatus) {
            this.state = state;
            this.percentage = percentage;
            this.rawStatus = rawStatus;
        }

        @Override
        public String toString() {
            return String.format("state=%d percentage=%d raw=0x%02X", state, percentage, rawStatus);
        }
    }

    /** null when the report is not an Xbox battery report. */
    public static BatteryInfo parseXboxBattery(byte[] report) {
        if (report == null || report.length == 0) {
            return null;
        }
        int status;
        if (report.length >= 2 && (report[0] & 0xFF) == XBOX_BATTERY_REPORT_ID) {
            // The reply carries the report ID first
            status = report[1] & 0xFF;
        } else if (report.length == 1) {
            status = report[0] & 0xFF;
        } else {
            return null;
        }
        boolean online = (status & 0x80) != 0;
        boolean charging = (status & 0x10) != 0;
        int mode = (status & 0x0C) >> 2;
        int level = status & 0x03;
        byte state;
        byte percentage;
        if (!online || mode == 0) {
            state = MoonBridge.LI_BATTERY_STATE_NOT_PRESENT;
            percentage = MoonBridge.LI_BATTERY_PERCENTAGE_UNKNOWN;
        } else {
            percentage = XBOX_LEVEL_PERCENT[level];
            if (charging) {
                state = level == 3 ? MoonBridge.LI_BATTERY_STATE_FULL : MoonBridge.LI_BATTERY_STATE_CHARGING;
            } else {
                state = MoonBridge.LI_BATTERY_STATE_DISCHARGING;
            }
        }
        return new BatteryInfo(state, percentage, status);
    }

    public static boolean hasPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Starts binding the HID host profile proxy. Returns null when the path cannot work at all (no
     * permission, Bluetooth off). Binding is asynchronous: check isReady() before sending.
     */
    public static BluetoothHidRumble openIfAvailable(Context context) {
        if (!hasPermission(context)) {
            return null;
        }
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager != null ? manager.getAdapter() : null;
        if (adapter == null || !adapter.isEnabled()) {
            return null;
        }
        BluetoothHidRumble rumble = new BluetoothHidRumble(adapter);
        try {
            if (!adapter.getProfileProxy(context.getApplicationContext(), rumble.listener, PROFILE_HID_HOST)) {
                return null;
            }
        } catch (Throwable t) {
            LimeLog.warning("Bluetooth HID rumble: getProfileProxy(HID_HOST) failed: " + t);
            return null;
        }
        rumble.registerReportReceiver(context.getApplicationContext());
        return rumble;
    }

    private final BluetoothAdapter adapter;
    private final long openedAtMs = SystemClock.uptimeMillis();
    private volatile BluetoothProfile proxy;
    private volatile Method sendDataMethod;
    private volatile Method setReportMethod;
    private volatile Method getReportMethod;
    private volatile String lastResolveMethod = "none";
    private volatile boolean closed;
    private volatile Context receiverContext;
    private volatile ReportListener reportListener;

    private final BroadcastReceiver reportReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_REPORT.equals(intent.getAction())) {
                return;
            }
            ReportListener listener = reportListener;
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
            byte[] report = intent.getByteArrayExtra(EXTRA_REPORT);
            if (listener == null || device == null || report == null) {
                return;
            }
            int size = intent.getIntExtra(EXTRA_REPORT_BUFFER_SIZE, report.length);
            if (size > 0 && size < report.length) {
                report = Arrays.copyOf(report, size);
            }
            listener.onReport(device, report);
        }
    };

    private void registerReportReceiver(Context appContext) {
        try {
            // Sent by the Bluetooth stack (a protected broadcast); the sender must hold BLUETOOTH_CONNECT
            appContext.registerReceiver(reportReceiver, new IntentFilter(ACTION_REPORT),
                    Manifest.permission.BLUETOOTH_CONNECT, null, Context.RECEIVER_EXPORTED);
            receiverContext = appContext;
        } catch (Throwable t) {
            LimeLog.warning("Bluetooth HID rumble: cannot listen for HID reports: " + t);
        }
    }

    public void setReportListener(ReportListener listener) {
        reportListener = listener;
    }

    private final BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile connectedProxy) {
            if (closed) {
                try {
                    adapter.closeProfileProxy(PROFILE_HID_HOST, connectedProxy);
                } catch (Throwable ignored) {
                }
                return;
            }
            Class<?> cls = connectedProxy.getClass();
            sendDataMethod = findMethod(cls, "sendData", BluetoothDevice.class, String.class);
            setReportMethod = findMethod(cls, "setReport", BluetoothDevice.class, byte.class, String.class);
            getReportMethod = findMethod(cls, "getReport", BluetoothDevice.class, byte.class, byte.class, int.class);
            proxy = connectedProxy;
            if (sendDataMethod == null) {
                LimeLog.warning("Bluetooth HID rumble: " + cls.getName() + " has no reachable sendData()");
            }
        }

        @Override
        public void onServiceDisconnected(int profile) {
            proxy = null;
            sendDataMethod = null;
            setReportMethod = null;
            getReportMethod = null;
        }
    };

    private BluetoothHidRumble(BluetoothAdapter adapter) {
        this.adapter = adapter;
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>... params) {
        try {
            return cls.getMethod(name, params);
        } catch (Throwable ignored) {
        }
        try {
            // Exemption did not take (or the method is not public): ask the bypass directly
            return HiddenApiBypass.getDeclaredMethod(cls, name, params);
        } catch (Throwable t) {
            LimeLog.debug("Bluetooth HID rumble: " + cls.getName() + "." + name + " not found: " + t);
            return null;
        }
    }

    /** Proxy bound and sendData() resolved. */
    public boolean isReady() {
        return proxy != null && sendDataMethod != null;
    }

    /** Not ready and past the binding grace period: the path is not going to work in this session. */
    public boolean isUnavailable() {
        return !isReady() && (closed || SystemClock.uptimeMillis() - openedAtMs > PROXY_GRACE_MS);
    }

    public boolean hiddenApiExempted() {
        return HIDDEN_API_EXEMPTED;
    }

    /** How the last resolve() found (or failed to find) the Bluetooth device; for diagnostics. */
    public String lastResolveMethod() {
        return lastResolveMethod;
    }

    /**
     * The Bluetooth device behind an InputDevice, or null for USB/internal devices and for pads that
     * cannot be told apart (two bonded pads with the same name and no address).
     */
    public BluetoothDevice resolve(InputDevice dev) {
        if (dev == null) {
            return null;
        }
        String address = null;
        try {
            // Hidden getter. On Android 14 it is a binder call into InputManagerService that enforces the
            // legacy normal permission android.permission.BLUETOOTH (declared in the manifest).
            Method getter = findMethod(InputDevice.class, "getBluetoothAddress");
            if (getter != null) {
                address = (String) getter.invoke(dev);
            }
        } catch (Throwable t) {
            Throwable cause = t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
            LimeLog.debug("Bluetooth HID rumble: getBluetoothAddress failed: " + cause);
        }
        if (address != null) {
            try {
                lastResolveMethod = "InputDevice.getBluetoothAddress";
                return adapter.getRemoteDevice(address);
            } catch (IllegalArgumentException e) {
                lastResolveMethod = "bad address from InputDevice: " + address;
                return null;
            }
        }
        // Fallback: the one bonded peripheral with this name; with several bonded pads of the same
        // model, the one the HID host currently reports as connected
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            List<BluetoothDevice> connected = connectedHidDevices();
            BluetoothDevice match = null;
            BluetoothDevice connectedMatch = null;
            int matches = 0;
            int connectedMatches = 0;
            if (bonded != null) {
                for (BluetoothDevice candidate : bonded) {
                    if (!dev.getName().equals(candidate.getName())) {
                        continue;
                    }
                    BluetoothClass cls = candidate.getBluetoothClass();
                    if (cls != null && cls.getMajorDeviceClass() != BluetoothClass.Device.Major.PERIPHERAL) {
                        continue;
                    }
                    match = candidate;
                    matches++;
                    if (connected != null && connected.contains(candidate)) {
                        connectedMatch = candidate;
                        connectedMatches++;
                    }
                }
            }
            if (matches == 1) {
                lastResolveMethod = "bonded device with the same name";
                return match;
            }
            if (connectedMatches == 1) {
                lastResolveMethod = "the only connected pad with the same name (" + matches + " bonded)";
                return connectedMatch;
            }
            lastResolveMethod = matches == 0 ? "no bonded device with this name"
                    : "ambiguous name (" + matches + " bonded, " + connectedMatches + " connected)";
        } catch (Throwable t) {
            lastResolveMethod = "getBondedDevices failed: " + t;
        }
        return null;
    }

    /** Devices the HID host profile reports as connected, or null while the proxy is not bound. */
    private List<BluetoothDevice> connectedHidDevices() {
        BluetoothProfile p = proxy;
        if (p == null) {
            return null;
        }
        try {
            // Public BluetoothProfile method; HidHostService only checks BLUETOOTH_CONNECT for it
            return p.getConnectedDevices();
        } catch (Throwable t) {
            LimeLog.debug("Bluetooth HID rumble: getConnectedDevices failed: " + t);
            return null;
        }
    }

    /** A bonded device by MAC address, for diagnostics; null if the address is malformed. */
    public BluetoothDevice deviceForAddress(String address) {
        try {
            return adapter.getRemoteDevice(address.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** DATA write of an output report (the path the kernel's force feedback takes). Blocking binder call. */
    public boolean send(BluetoothDevice device, String hexReport) {
        return invoke(sendDataMethod, device, hexReport);
    }

    /** SET_REPORT of an output report; alternative for pads that ignore DATA writes. Blocking binder call. */
    public boolean sendSetReport(BluetoothDevice device, String hexReport) {
        return invoke(setReportMethod, device, REPORT_TYPE_OUTPUT, hexReport);
    }

    /**
     * GET_REPORT of a feature report. The reply goes out as a broadcast nobody here listens to; some pads
     * (8BitDo) only care that the request happened. Blocking binder call.
     */
    public boolean requestFeatureReport(BluetoothDevice device, int reportId) {
        return invoke(getReportMethod, device, REPORT_TYPE_FEATURE, (byte) reportId, 64);
    }

    /** GET_REPORT for an input report; the payload arrives through the ReportListener. */
    public boolean requestInputReport(BluetoothDevice device, int reportId) {
        return invoke(getReportMethod, device, REPORT_TYPE_INPUT, (byte) reportId, 64);
    }

    /** One-time setup a pad family needs before its first rumble report. Blocking; call on the rumble thread. */
    public void runPrelude(BluetoothDevice device, ReportFormat format) {
        boolean sent = false;
        int feature = format.preludeFeatureReportId();
        if (feature != 0) {
            sent |= requestFeatureReport(device, feature);
        }
        for (String hex : format.preludeReports()) {
            sent |= send(device, hex);
        }
        if (sent) {
            LimeLog.info("Bluetooth HID rumble: prelude sent for " + format.name() + " to " + device.getAddress());
            SystemClock.sleep(PRELUDE_SETTLE_MS);
        }
    }

    private boolean invoke(Method method, BluetoothDevice device, Object... args) {
        BluetoothProfile p = proxy;
        if (method == null || p == null || device == null) {
            return false;
        }
        Object[] fullArgs = new Object[args.length + 1];
        fullArgs[0] = device;
        System.arraycopy(args, 0, fullArgs, 1, args.length);
        try {
            return Boolean.TRUE.equals(method.invoke(p, fullArgs));
        } catch (Throwable t) {
            LimeLog.debug("Bluetooth HID rumble: " + method.getName() + " failed: " + t);
            return false;
        }
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        Context c = receiverContext;
        receiverContext = null;
        if (c != null) {
            try {
                c.unregisterReceiver(reportReceiver);
            } catch (Throwable ignored) {
            }
        }
        BluetoothProfile p = proxy;
        proxy = null;
        sendDataMethod = null;
        setReportMethod = null;
        getReportMethod = null;
        if (p != null) {
            try {
                adapter.closeProfileProxy(PROFILE_HID_HOST, p);
            } catch (Throwable ignored) {
            }
        }
    }
}
