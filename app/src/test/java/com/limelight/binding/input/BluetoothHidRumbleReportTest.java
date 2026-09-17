package com.limelight.binding.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

/**
 * Byte layouts of the HID output reports BluetoothHidRumble writes to gamepads. Reference values come
 * from the Linux drivers (hid-microsoft, hid-sony/hid-playstation, hid-nintendo), SDL's hidapi drivers
 * and, for the Sony CRC32, an independent zlib computation over 0xA2 + report.
 */
@Config(sdk = {34})
@RunWith(RobolectricTestRunner.class)
public class BluetoothHidRumbleReportTest {
    private static final short FULL = (short) 0xFFFF;
    private static final short ZERO = 0;

    @Test
    public void xboxReportMatchesTheVerifiedAutotestBytes() {
        assertEquals("030F00006464FF00FF", BluetoothHidRumble.buildXbox03(0x0F, 0, 0, 100, 100));
        assertEquals("030C64640000FF00FF", BluetoothHidRumble.buildXbox03(0x0C, 100, 100, 0, 0));
        assertEquals("030F00006464FF00FF", BluetoothHidRumble.XBOX_03.build(FULL, FULL, ZERO, ZERO));
        assertEquals("030F00003232FF00FF", BluetoothHidRumble.XBOX_03.build((short) 32768, (short) 32768, ZERO, ZERO));
        assertTrue(BluetoothHidRumble.XBOX_03.hasTriggerMotors());
        assertEquals(0, BluetoothHidRumble.XBOX_03.refreshIntervalMs());
    }

    @Test
    public void dualShock4ReportIs78BytesWithHeaderFlagsMotorsAndCrc() {
        // right (weak) 0x40, left (strong) 0x80; CRC32 from zlib over A2 + first 74 bytes
        String report = BluetoothHidRumble.buildDualShock4_11(0x80, 0x40);
        assertEquals(78 * 2, report.length());
        assertEquals("11C4000100004080", report.substring(0, 16));
        assertEquals("8EA5E263", report.substring(74 * 2));
        assertTrue(report.substring(16, 74 * 2).matches("0+"));

        BluetoothHidRumble.ReportFormat format = new BluetoothHidRumble.DualShock4Format();
        assertEquals("dualshock4-11", format.name());
        assertEquals(report, format.build((short) 0x8000, (short) 0x4000, FULL, FULL)); // triggers ignored
        assertFalse(format.hasTriggerMotors());
        assertEquals(20, format.minIntervalMs());
    }

    @Test
    public void dualSenseReportCarriesSequenceTagAndCompatibleVibrationFlags() {
        String seq0 = BluetoothHidRumble.buildDualSense31(0, 0x80, 0x40);
        assertEquals(78 * 2, seq0.length());
        assertEquals("31001003004080", seq0.substring(0, 14));
        assertEquals("C8737924", seq0.substring(74 * 2));
        assertEquals("8453C773", BluetoothHidRumble.buildDualSense31(5, 0x80, 0x40).substring(74 * 2));
        assertEquals("50", BluetoothHidRumble.buildDualSense31(5, 0x80, 0x40).substring(2, 4));

        BluetoothHidRumble.DualSenseFormat format = new BluetoothHidRumble.DualSenseFormat();
        assertEquals("00", format.build(ZERO, ZERO, ZERO, ZERO).substring(2, 4));
        assertEquals("10", format.build(ZERO, ZERO, ZERO, ZERO).substring(2, 4));
        for (int i = 2; i < 16; i++) {
            format.build(ZERO, ZERO, ZERO, ZERO);
        }
        assertEquals("sequence wraps after 16 reports", "00", format.build(ZERO, ZERO, ZERO, ZERO).substring(2, 4));
    }

    @Test
    public void switchAmplitudeFollowsTheKernelTable() {
        assertEquals(0, BluetoothHidRumble.scaleSwitchAmp(ZERO));
        assertEquals(100, BluetoothHidRumble.scaleSwitchAmp(FULL));
        // 65535 * 1003 / 65535 = 1003 -> last step; 653 -> 1003*653/65535 = 9 -> first step >= 9 is 10 (index 1)
        assertEquals(1, BluetoothHidRumble.scaleSwitchAmp((short) 653));
        // half scale -> 501 -> index of 501 in the table
        assertEquals(68, BluetoothHidRumble.scaleSwitchAmp((short) 32768));
    }

    @Test
    public void switchReportEncodesBothMotorsWithNeutralAndFullValues() {
        // Neutral: 00 01 40 40 (dekuNukem / SDL standard neutral value)
        assertEquals("10070001404000014040", BluetoothHidRumble.buildSwitch10(7, 0, 0));
        // Full amplitude, kernel table entry { 0xc8, 0x0072, 1003 }: 00 C9 40 72
        assertEquals("100000C9407200C94072", BluetoothHidRumble.buildSwitch10(0, 100, 100));
        // Step 1 { 0x02, 0x8040, 10 }: 00 03 C0 40 on the left, neutral on the right
        assertEquals("10010003C04000014040", BluetoothHidRumble.buildSwitch10(1, 1, 0));
        // Enable-vibration subcommand: report 0x01, counter, neutral rumble, 0x48 0x01
        assertEquals("010300014040000140404801", BluetoothHidRumble.buildSwitchEnableVibration(3));
    }

    @Test
    public void switchFormatCountsPacketsPacesAndRefreshes() {
        BluetoothHidRumble.SwitchFormat pro = new BluetoothHidRumble.SwitchFormat(false);
        assertEquals("switch-10", pro.name());
        assertEquals(30, pro.minIntervalMs());
        assertEquals(50, pro.refreshIntervalMs());
        assertFalse(pro.hasTriggerMotors());
        List<String> prelude = pro.preludeReports();
        assertEquals(1, prelude.size());
        assertEquals("010000014040000140404801", prelude.get(0));
        // Counter continues after the prelude; strong on the left only
        assertEquals("100100C9407200014040", pro.build(FULL, ZERO, ZERO, ZERO));
        assertEquals("10020001404000C94072", pro.build(ZERO, FULL, ZERO, ZERO));

        // A lone Joy-Con has one motor and gets the louder level on both halves
        BluetoothHidRumble.SwitchFormat joyCon = new BluetoothHidRumble.SwitchFormat(true);
        assertEquals("joycon-10", joyCon.name());
        assertEquals("100000C9407200C94072", joyCon.build(ZERO, FULL, ZERO, ZERO));
    }

    @Test
    public void eightBitDoReportIsFiveBytes() {
        assertEquals("05FF800000", BluetoothHidRumble.build8BitDo05(0xFF, 0x80, 0, 0));
        BluetoothHidRumble.EightBitDoFormat enhanced = new BluetoothHidRumble.EightBitDoFormat(true);
        assertEquals("05FF80FF40", enhanced.build(FULL, (short) 0x8000, FULL, (short) 0x4000));
        assertTrue(enhanced.hasTriggerMotors());
        assertEquals(0x06, enhanced.preludeFeatureReportId());
        assertEquals(0, new BluetoothHidRumble.EightBitDoFormat(false).preludeFeatureReportId());
    }

    @Test
    public void lunaTakesTheXboxReportWithoutTriggerMotors() {
        assertEquals("030F00006464FF00FF", BluetoothHidRumble.LUNA_03.build(FULL, FULL, FULL, FULL));
        assertFalse(BluetoothHidRumble.LUNA_03.hasTriggerMotors());
        assertSame(BluetoothHidRumble.LUNA_03, BluetoothHidRumble.reportFormatFor(0x1949, 0x0419));
        assertSame(BluetoothHidRumble.LUNA_03, BluetoothHidRumble.reportFormatFor(0x1949, 0x041a));
        assertSame(BluetoothHidRumble.LUNA_03, BluetoothHidRumble.reportFormatFor(0x0171, 0x0419));
    }

    @Test
    public void stadiaReportCarriesSixteenBitLevelsLittleEndian() {
        assertEquals("053412CDAB", BluetoothHidRumble.buildStadia05(0x1234, 0xABCD));
        BluetoothHidRumble.ReportFormat format = new BluetoothHidRumble.StadiaFormat();
        assertEquals("05FFFF0000", format.build(FULL, ZERO, ZERO, ZERO));
        assertEquals("0500000080", format.build(ZERO, (short) 0x8000, ZERO, ZERO));
        assertEquals("stadia-05", format.name());
    }

    @Test
    public void shieldCommandReportIs33BytesWithSequenceAndAttenuatedLevels() {
        String report = BluetoothHidRumble.buildShieldRumble(5, 31, 16);
        assertEquals(33 * 2, report.length());
        assertEquals("043905011F10", report.substring(0, 12));
        assertTrue(report.substring(12).matches("0+"));
        assertEquals(31, BluetoothHidRumble.scaleShield(FULL));
        assertEquals(16, BluetoothHidRumble.scaleShield((short) 0x8000));
        BluetoothHidRumble.ShieldFormat format = new BluetoothHidRumble.ShieldFormat();
        assertEquals("00", format.build(FULL, FULL, ZERO, ZERO).substring(4, 6));
        assertEquals("01", format.build(FULL, FULL, ZERO, ZERO).substring(4, 6));
        assertEquals(500, format.refreshIntervalMs());
    }

    @Test
    public void gameSirReportIs64BytesWithCommandPrefix() {
        String report = BluetoothHidRumble.buildGameSirA2(0xFF, 0x80);
        assertEquals(64 * 2, report.length());
        assertEquals("A203FF80", report.substring(0, 8));
        assertTrue(report.substring(8).matches("0+"));
        assertEquals(report, new BluetoothHidRumble.GameSir8kFormat().build(FULL, (short) 0x8000, ZERO, ZERO));
    }

    @Test
    public void reportFormatTableCoversTheSupportedFamilies() {
        assertSame(BluetoothHidRumble.XBOX_03, BluetoothHidRumble.reportFormatFor(0x045e, 0x0b13));
        assertSame(BluetoothHidRumble.XBOX_03, BluetoothHidRumble.reportFormatFor(0x045e, 0x02e0));
        assertTrue(BluetoothHidRumble.reportFormatFor(0x054c, 0x05c4) instanceof BluetoothHidRumble.DualShock4Format);
        assertTrue(BluetoothHidRumble.reportFormatFor(0x054c, 0x09cc) instanceof BluetoothHidRumble.DualShock4Format);
        assertTrue(BluetoothHidRumble.reportFormatFor(0x054c, 0x0ce6) instanceof BluetoothHidRumble.DualSenseFormat);
        assertTrue(BluetoothHidRumble.reportFormatFor(0x054c, 0x0df2) instanceof BluetoothHidRumble.DualSenseFormat);
        assertEquals("switch-10", BluetoothHidRumble.reportFormatFor(0x057e, 0x2009).name());
        assertEquals("joycon-10", BluetoothHidRumble.reportFormatFor(0x057e, 0x2006).name());
        assertEquals("joycon-10", BluetoothHidRumble.reportFormatFor(0x057e, 0x2007).name());
        assertEquals("8bitdo-05", BluetoothHidRumble.reportFormatFor(0x2dc8, 0x6006).name());
        assertEquals(0, BluetoothHidRumble.reportFormatFor(0x2dc8, 0x6012).preludeFeatureReportId());
        assertTrue(BluetoothHidRumble.reportFormatFor(0x18d1, 0x9400) instanceof BluetoothHidRumble.StadiaFormat);
        assertTrue(BluetoothHidRumble.reportFormatFor(0x0955, 0x7214) instanceof BluetoothHidRumble.ShieldFormat);
        assertNull("2015 Shield controller is Wi-Fi Direct", BluetoothHidRumble.reportFormatFor(0x0955, 0x7210));
        assertTrue(BluetoothHidRumble.reportFormatFor(0x3537, 0x103c) instanceof BluetoothHidRumble.GameSir8kFormat);
        assertTrue(BluetoothHidRumble.reportFormatFor(0x3537, 0x10b8) instanceof BluetoothHidRumble.GameSir8kFormat);
        assertNull(BluetoothHidRumble.reportFormatFor(0x3537, 0x1000));
        // Stateful formats are fresh per gamepad
        assertNotSame(BluetoothHidRumble.reportFormatFor(0x054c, 0x0ce6), BluetoothHidRumble.reportFormatFor(0x054c, 0x0ce6));
        // Unknown pads, the TCL remote, DualShock 3, SNES controller (no motor)
        assertNull(BluetoothHidRumble.reportFormatFor(0x054c, 0x0268));
        assertNull(BluetoothHidRumble.reportFormatFor(0x057e, 0x2017));
        assertNull(BluetoothHidRumble.reportFormatFor(0x0000, 0x0000));
    }

    @Test
    public void xboxBatteryReportParsesXpadneoLayout() {
        // online, on battery, level 3, not charging -> discharging, 100 %
        BluetoothHidRumble.BatteryInfo b = BluetoothHidRumble.parseXboxBattery(new byte[] {(byte) 0x87});
        assertEquals(com.limelight.nvstream.jni.MoonBridge.LI_BATTERY_STATE_DISCHARGING, b.state);
        assertEquals(100, b.percentage);
        // the reply may carry the report ID first
        b = BluetoothHidRumble.parseXboxBattery(new byte[] {0x04, (byte) 0x87});
        assertEquals(100, b.percentage);
        // online, charging cable, charging, level 1 -> charging, 35 %
        b = BluetoothHidRumble.parseXboxBattery(new byte[] {(byte) 0x99});
        assertEquals(com.limelight.nvstream.jni.MoonBridge.LI_BATTERY_STATE_CHARGING, b.state);
        assertEquals(35, b.percentage);
        // charging and full
        b = BluetoothHidRumble.parseXboxBattery(new byte[] {(byte) 0x9B});
        assertEquals(com.limelight.nvstream.jni.MoonBridge.LI_BATTERY_STATE_FULL, b.state);
        // mode 0: USB power without a battery
        b = BluetoothHidRumble.parseXboxBattery(new byte[] {(byte) 0x80});
        assertEquals(com.limelight.nvstream.jni.MoonBridge.LI_BATTERY_STATE_NOT_PRESENT, b.state);
        assertEquals(com.limelight.nvstream.jni.MoonBridge.LI_BATTERY_PERCENTAGE_UNKNOWN, b.percentage);
        // anything that is not a one-byte status or an 0x04 report is ignored
        assertNull(BluetoothHidRumble.parseXboxBattery(new byte[] {0x03, 0x00, 0x00}));
        assertNull(BluetoothHidRumble.parseXboxBattery(new byte[0]));
    }

    @Test
    public void gattBatteryLevelIsOnePercentByte() {
        assertEquals(Integer.valueOf(87), BluetoothGattBattery.parseLevel(new byte[] {(byte) 87}));
        assertEquals(Integer.valueOf(100), BluetoothGattBattery.parseLevel(new byte[] {100, 0}));
        assertNull(BluetoothGattBattery.parseLevel(new byte[] {(byte) 0xFF}));
        assertNull(BluetoothGattBattery.parseLevel(new byte[0]));
        assertNull(BluetoothGattBattery.parseLevel(null));
    }
}
