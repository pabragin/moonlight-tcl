package com.limelight.binding.input;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.content.Context;
import android.hardware.input.InputManager;
import android.view.InputDevice;
import android.view.KeyEvent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ControllerBackNavigationTest {
    private InputDevice device(String name, int sources, boolean external) {
        InputDevice device = mock(InputDevice.class);
        when(device.getName()).thenReturn(name);
        when(device.getSources()).thenReturn(sources);
        when(device.isExternal()).thenReturn(external);
        when(device.hasKeys(KeyEvent.KEYCODE_BUTTON_SELECT)).thenReturn(new boolean[]{false});
        return device;
    }

    private boolean ignoresBack(InputDevice receiver) throws Exception {
        // Reproduce the TV's extra internal gamepad with no Select button. This used to cause
        // navigation Back from an unrelated internal IR receiver to be consumed as gamepad input.
        InputDevice search = device("virtual-search", InputDevice.SOURCE_GAMEPAD, false);
        Activity activity = mock(Activity.class);
        InputManager manager = mock(InputManager.class);
        when(activity.getSystemService(Context.INPUT_SERVICE)).thenReturn(manager);
        when(manager.getInputDeviceIds()).thenReturn(new int[]{3, 7});
        when(manager.getInputDevice(3)).thenReturn(receiver);
        when(manager.getInputDevice(7)).thenReturn(search);
        ControllerHandler handler = mock(ControllerHandler.class, CALLS_REAL_METHODS);
        Field context = ControllerHandler.class.getDeclaredField("activityContext");
        context.setAccessible(true);
        context.set(handler, activity);
        Method method = ControllerHandler.class.getDeclaredMethod("shouldIgnoreBack", InputDevice.class);
        method.setAccessible(true);
        return (boolean) method.invoke(handler, receiver);
    }

    @Test
    public void tclIrBackNavigatesDespiteInternalVirtualGamepad() throws Exception {
        assertTrue(ignoresBack(device("MTK Smart TV IR Receiver",
                InputDevice.SOURCE_KEYBOARD | InputDevice.SOURCE_DPAD, false)));
    }

    @Test
    public void ultimateGamepadRetainsBackButton() throws Exception {
        assertFalse(ignoresBack(device("8BitDo Ultimate wireless", InputDevice.SOURCE_GAMEPAD, true)));
    }

    @Test
    public void matchingNameDoesNotOverrideRealGamepadCapabilities() throws Exception {
        assertFalse(ignoresBack(device("MTK Smart TV IR Receiver", InputDevice.SOURCE_GAMEPAD, false)));
    }

    @Test
    public void unrelatedInternalButtonsKeepExistingBehavior() throws Exception {
        assertFalse(ignoresBack(device("Internal buttons", InputDevice.SOURCE_KEYBOARD, false)));
    }

    @Test
    public void externalRemoteStillNavigates() throws Exception {
        assertTrue(ignoresBack(device("TV Remote", InputDevice.SOURCE_DPAD, true)));
    }
}
