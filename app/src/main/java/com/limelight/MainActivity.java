package com.limelight;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.text.InputFilter;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.binding.PlatformBinding;
import com.limelight.binding.crypto.AndroidCryptoProvider;
import com.limelight.binding.input.BluetoothHidRumble;
import com.limelight.binding.input.GamepadBatteryMonitor;
import com.limelight.computers.ComputerManagerListener;
import com.limelight.computers.ComputerManagerService;
import com.limelight.grid.AppTileAdapter;
import com.limelight.grid.assets.DiskAssetLoader;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.nvstream.http.PairingManager.PairState;
import com.limelight.nvstream.wol.WakeOnLanSender;
import com.limelight.preferences.AddComputerManually;
import com.limelight.preferences.GlPreferences;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.SettingsActivity;
import com.limelight.preferences.SettingsScreen;
import com.limelight.profiles.ProfilesManager;
import com.limelight.ui.GamepadStatusStrip;
import com.limelight.ui.PanelMenu;
import com.limelight.utils.CacheHelper;
import com.limelight.utils.Dialog;
import com.limelight.utils.HelpLauncher;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.ShortcutHelper;
import com.limelight.utils.SpinnerDialog;
import com.limelight.utils.UiHelper;

import org.xmlpull.v1.XmlPullParserException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.StringReader;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * The screen before a stream, in the Google TV style of the game menu and the on-screen keyboard: every PC
 * the app knows as a chip in the header (status dot or lock, name), the selected PC's apps as posters below,
 * attached gamepads with their charge in the top-right corner. A PC that is offline, unpaired or still being
 * checked shows a short message with the actions that make sense instead of the grid. Long press, Menu or
 * the gamepad's Y button opens the dark action panel for a chip or a poster.
 *
 * This replaced the two Artemis screens PcView and AppView; their intent extras are kept so launcher
 * shortcuts (ShortcutTrampoline) and manual additions keep working.
 */
public class MainActivity extends Activity {
    public static final String NAME_EXTRA = "Name";
    public static final String UUID_EXTRA = "UUID";
    public static final String NEW_PAIR_EXTRA = "NewPair";
    public static final String SHOW_HIDDEN_APPS_EXTRA = "ShowHiddenApps";
    public static final String HIDDEN_APPS_PREF_FILENAME = "HiddenApps";

    private static final String LAST_PC_PREF = "main_last_pc";
    private static final String BT_PERMISSION_ASKED_PREF = "bt_connect_permission_asked";
    private static final int BT_PERMISSION_REQUEST_CODE = 0x4254;

    private static final int DOT_ONLINE = 0xFF34C759;
    private static final int DOT_UNKNOWN = 0xFFFFB300;
    private static final int DOT_OFFLINE = 0xFF8E8E93;

    private LinearLayout pcChips;
    private HorizontalScrollView pcChipsScroll;
    private GridView appGrid;
    private View stateCard;
    private ProgressBar stateSpinner;
    private ImageView stateIcon;
    private TextView stateTitle, stateMessage;
    private LinearLayout stateActions;
    private TextView profilesButton;

    private final ArrayList<ComputerDetails> computers = new ArrayList<>();
    private String selectedUuid;
    private String pendingSelectUuid;
    private boolean pendingNewPair;

    private AppTileAdapter appAdapter;
    private String appsUuid;
    private String lastRawApplist;
    private int lastRunningAppId;
    private boolean appsLoaded;
    private boolean showHiddenApps;
    private final HashSet<Integer> hiddenAppIds = new HashSet<>();
    private ComputerManagerService.ApplistPoller poller;
    private boolean suspendGridUpdates;
    private View highlightedTile;
    private String shownStateKey;

    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private boolean freezeUpdates, runningPolling, inForeground, completeOnCreateCalled;
    private ShortcutHelper shortcutHelper;
    private PreferenceConfiguration prefConfig;
    private GamepadBatteryMonitor gamepadMonitor;
    private ComputerDetails.AddressTuple pendingPairingAddress;
    private String pendingPairingPin, pendingPairingPassphrase;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder binder) {
            final ComputerManagerService.ComputerManagerBinder localBinder =
                    ((ComputerManagerService.ComputerManagerBinder) binder);
            // Wait in a separate thread to avoid stalling the UI
            new Thread() {
                @Override
                public void run() {
                    localBinder.waitForReady();
                    managerBinder = localBinder;
                    startComputerUpdates();
                    // Force a keypair to be generated early to avoid discovery delays
                    new AndroidCryptoProvider(MainActivity.this).getClientCertificate();
                }
            }.start();
        }

        public void onServiceDisconnected(ComponentName className) {
            managerBinder = null;
        }
    };

    // ---- Lifecycle ----

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Assume we're in the foreground when created to avoid a race between binding to CMS and onResume()
        inForeground = true;

        maybeRequestBluetoothPermission();
        readLaunchIntent(getIntent());

        // Create a GLSurfaceView to fetch GLRenderer unless we have a cached result already
        final GlPreferences glPrefs = GlPreferences.readPreferences(this);
        if (!glPrefs.savedFingerprint.equals(Build.FINGERPRINT) || glPrefs.glRenderer.isEmpty()) {
            GLSurfaceView surfaceView = new GLSurfaceView(this);
            surfaceView.setRenderer(new GLSurfaceView.Renderer() {
                @Override
                public void onSurfaceCreated(GL10 gl10, EGLConfig eglConfig) {
                    glPrefs.glRenderer = gl10.glGetString(GL10.GL_RENDERER);
                    glPrefs.savedFingerprint = Build.FINGERPRINT;
                    glPrefs.writePreferences();
                    LimeLog.info("Fetched GL Renderer: " + glPrefs.glRenderer);
                    runOnUiThread(MainActivity.this::completeOnCreate);
                }

                @Override
                public void onSurfaceChanged(GL10 gl10, int i, int i1) {
                }

                @Override
                public void onDrawFrame(GL10 gl10) {
                }
            });
            setContentView(surfaceView);
        }
        else {
            LimeLog.info("Cached GL Renderer: " + glPrefs.glRenderer);
            completeOnCreate();
        }
    }

    private void readLaunchIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        String uuid = intent.getStringExtra(UUID_EXTRA);
        if (uuid != null) {
            pendingSelectUuid = uuid;
            pendingNewPair = intent.getBooleanExtra(NEW_PAIR_EXTRA, false);
        }
        if (intent.getBooleanExtra(SHOW_HIDDEN_APPS_EXTRA, false)) {
            showHiddenApps = true;
        }

        // Pairing requested from an art:// link (AddComputerManually) with the PIN already known
        String hostname = intent.getStringExtra("hostname");
        int port = intent.getIntExtra("port", NvHTTP.DEFAULT_HTTP_PORT);
        String pin = intent.getStringExtra("pin");
        String passphrase = intent.getStringExtra("passphrase");
        if (hostname != null && pin != null && passphrase != null) {
            pendingPairingAddress = new ComputerDetails.AddressTuple(hostname, port);
            pendingPairingPin = pin;
            pendingPairingPassphrase = passphrase;
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readLaunchIntent(intent);
        if (pendingSelectUuid != null && findComputer(pendingSelectUuid) != null) {
            selectPc(pendingSelectUuid);
            pendingSelectUuid = null;
        }
    }

    private void completeOnCreate() {
        completeOnCreateCalled = true;

        shortcutHelper = new ShortcutHelper(this);

        setContentView(R.layout.activity_main);
        UiHelper.notifyNewRootView(this);
        setShouldDockBigOverlays(false);

        // Set default preferences if we've never been run
        SettingsScreen.applyDefaults(this);
        prefConfig = PreferenceConfiguration.readPreferences(this);

        pcChips = findViewById(R.id.pcChips);
        pcChipsScroll = findViewById(R.id.pcChipsScroll);
        appGrid = findViewById(R.id.appGrid);
        stateCard = findViewById(R.id.stateCard);
        stateSpinner = findViewById(R.id.stateSpinner);
        stateIcon = findViewById(R.id.stateIcon);
        stateTitle = findViewById(R.id.stateTitle);
        stateMessage = findViewById(R.id.stateMessage);
        stateActions = findViewById(R.id.stateActions);
        profilesButton = findViewById(R.id.profilesButton);

        findViewById(R.id.settingsButton).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.addPcButton).setOnClickListener(v -> startActivity(new Intent(this, AddComputerManually.class)));
        findViewById(R.id.helpButton).setOnClickListener(v -> HelpLauncher.launchSetupGuide(this));
        profilesButton.setOnClickListener(v -> startActivity(new Intent(this, ProfilesActivity.class)));

        appGrid.setOnItemClickListener((parent, view, position, id) -> onAppClick(appAdapter.getItem(position)));
        appGrid.setOnItemLongClickListener((parent, view, position, id) -> {
            showAppMenu(appAdapter.getItem(position), view);
            return true;
        });
        appGrid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                highlightTile(appGrid.hasFocus() ? view : null);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                highlightTile(null);
            }
        });
        appGrid.setOnFocusChangeListener((v, hasFocus) -> highlightTile(hasFocus ? appGrid.getSelectedView() : null));

        if (selectedUuid == null) {
            selectedUuid = PreferenceManager.getDefaultSharedPreferences(this).getString(LAST_PC_PREF, null);
        }

        renderChips();
        renderSelected();

        // Bind to the computer manager service
        bindService(new Intent(this, ComputerManagerService.class), serviceConnection, Service.BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Display a decoder crash notification if we've returned after a crash
        UiHelper.showDecoderCrashDialog(this);

        if (completeOnCreateCalled) {
            prefConfig = PreferenceConfiguration.readPreferences(this);
            refreshProfileButton();
        }

        inForeground = true;
        startComputerUpdates();
        if (poller != null) {
            poller.start();
        }
        startGamepadStrip();
    }

    @Override
    protected void onPause() {
        super.onPause();

        inForeground = false;
        stopComputerUpdates(false);
        if (poller != null) {
            poller.stop();
        }
        if (appAdapter != null) {
            appAdapter.cancelQueuedOperations();
        }
        stopGamepadStrip();
    }

    @Override
    protected void onStop() {
        super.onStop();
        Dialog.closeDialogs();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        SpinnerDialog.closeDialogs(this);
        Dialog.closeDialogs();
        if (managerBinder != null) {
            unbindService(serviceConnection);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == ShortcutHelper.REQUEST_CODE_EXPORT_ART_FILE) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                ShortcutHelper.writeArtFileToUri(this, uri);
            }
            else {
                ShortcutHelper.artFileContentToExport = null;
                if (resultCode == Activity.RESULT_CANCELED) {
                    Toast.makeText(this, R.string.file_export_cancelled, Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Menu, Start or Y on a gamepad and the Menu key of a remote open the action panel of what is focused
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0 && completeOnCreateCalled) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_MENU:
                case KeyEvent.KEYCODE_BUTTON_START:
                case KeyEvent.KEYCODE_BUTTON_Y:
                    if (openMenuForFocus()) {
                        return true;
                    }
                    break;
                default:
                    break;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean openMenuForFocus() {
        View focus = getCurrentFocus();
        if (focus == appGrid && appAdapter != null) {
            int position = appGrid.getSelectedItemPosition();
            if (position >= 0 && position < appAdapter.getCount()) {
                showAppMenu(appAdapter.getItem(position), appGrid.getSelectedView());
                return true;
            }
        }
        if (focus != null && focus.getParent() == pcChips && focus.getTag() instanceof String) {
            ComputerDetails computer = findComputer((String) focus.getTag());
            if (computer != null) {
                showPcMenu(computer);
                return true;
            }
        }
        return false;
    }

    // ---- Permissions ----

    // Bluetooth pads rumble through the Bluetooth stack (BluetoothHidRumble), which needs the Nearby
    // devices permission. Ask once while rumble is on.
    private void maybeRequestBluetoothPermission() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (!PreferenceConfiguration.readPreferences(this).enableRumble
                || BluetoothHidRumble.hasPermission(this)
                || prefs.getBoolean(BT_PERMISSION_ASKED_PREF, false)) {
            return;
        }
        prefs.edit().putBoolean(BT_PERMISSION_ASKED_PREF, true).apply();
        requestPermissions(new String[] { Manifest.permission.BLUETOOTH_CONNECT }, BT_PERMISSION_REQUEST_CODE);
    }

    // ---- Header ----

    private void refreshProfileButton() {
        if (profilesButton == null) {
            return;
        }
        String name = ProfilesManager.getInstance().getActiveName();
        profilesButton.setText(name == null || name.isEmpty() ? getString(R.string.main_profile) : name);
    }

    // Attached gamepads and their charge in the strip at the top (GamepadStatusStrip); GATT clients live
    // only while this screen is in front, a stream opens its own
    private void startGamepadStrip() {
        GamepadStatusStrip strip = findViewById(R.id.gamepadStatusStrip);
        if (strip == null || gamepadMonitor != null) {
            return;
        }
        gamepadMonitor = new GamepadBatteryMonitor(this, strip::setGamepads);
        gamepadMonitor.start();
    }

    private void stopGamepadStrip() {
        if (gamepadMonitor != null) {
            gamepadMonitor.stop();
            gamepadMonitor = null;
        }
    }

    // ---- PC discovery ----

    private void startComputerUpdates() {
        if (managerBinder == null || runningPolling || !inForeground) {
            return;
        }
        freezeUpdates = false;
        managerBinder.startPolling(new ComputerManagerListener() {
            @Override
            public void notifyComputerUpdated(final ComputerDetails details) {
                if (freezeUpdates) {
                    return;
                }
                runOnUiThread(() -> updateComputer(details));

                // Add a launcher shortcut for this PC (off the main thread to prevent ANRs)
                if (details.pairState == PairState.PAIRED) {
                    shortcutHelper.createAppViewShortcutForOnlineHost(details);
                }

                if (pendingPairingAddress != null && details.state == ComputerDetails.State.ONLINE &&
                        details.activeAddress != null && details.activeAddress.equals(pendingPairingAddress)) {
                    runOnUiThread(() -> {
                        doPair(details, pendingPairingPin, pendingPairingPassphrase);
                        pendingPairingAddress = null;
                        pendingPairingPin = null;
                        pendingPairingPassphrase = null;
                    });
                }
            }
        });
        runningPolling = true;
    }

    private void stopComputerUpdates(boolean wait) {
        if (managerBinder == null || !runningPolling) {
            return;
        }
        freezeUpdates = true;
        managerBinder.stopPolling();
        if (wait) {
            managerBinder.waitForPollingStopped();
        }
        runningPolling = false;
    }

    private ComputerDetails findComputer(String uuid) {
        if (uuid == null) {
            return null;
        }
        for (ComputerDetails computer : computers) {
            if (uuid.equalsIgnoreCase(computer.uuid)) {
                return computer;
            }
        }
        return null;
    }

    private void updateComputer(ComputerDetails details) {
        if (!completeOnCreateCalled || isFinishing()) {
            return;
        }
        boolean known = false;
        for (int i = 0; i < computers.size(); i++) {
            if (computers.get(i).uuid.equalsIgnoreCase(details.uuid)) {
                computers.set(i, details);
                known = true;
                break;
            }
        }
        if (!known) {
            computers.add(details);
            Collections.sort(computers, (a, b) -> a.name.toLowerCase().compareTo(b.name.toLowerCase()));
        }

        if (pendingSelectUuid != null && pendingSelectUuid.equalsIgnoreCase(details.uuid)) {
            selectedUuid = details.uuid;
            pendingSelectUuid = null;
        }
        if (findComputer(selectedUuid) == null) {
            // Nothing chosen yet (or the remembered PC is gone): the first streamable PC, else the first one
            selectedUuid = details.uuid;
            for (ComputerDetails computer : computers) {
                if (computer.state == ComputerDetails.State.ONLINE && computer.pairState == PairState.PAIRED) {
                    selectedUuid = computer.uuid;
                    break;
                }
            }
        }

        renderChips();
        if (selectedUuid.equalsIgnoreCase(details.uuid)) {
            onSelectedComputerUpdated(details);
        }
    }

    private void removeComputer(ComputerDetails details) {
        managerBinder.removeComputer(details);
        new DiskAssetLoader(this).deleteAssetsForComputer(details.uuid);
        getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE).edit().remove(details.uuid).apply();
        shortcutHelper.disableComputerShortcut(details, getResources().getString(R.string.scut_deleted_pc));

        for (int i = 0; i < computers.size(); i++) {
            if (computers.get(i).uuid.equalsIgnoreCase(details.uuid)) {
                computers.remove(i);
                break;
            }
        }
        if (details.uuid.equalsIgnoreCase(selectedUuid)) {
            dropApps();
            selectedUuid = computers.isEmpty() ? null : computers.get(0).uuid;
        }
        renderChips();
        renderSelected();
    }

    private void selectPc(String uuid) {
        ComputerDetails computer = findComputer(uuid);
        if (computer == null) {
            return;
        }
        selectedUuid = computer.uuid;
        PreferenceManager.getDefaultSharedPreferences(this).edit().putString(LAST_PC_PREF, selectedUuid).apply();
        renderChips();
        renderSelected();
    }

    // ---- Header chips ----

    private void renderChips() {
        // Rebind the chips that are already in the row and only add, drop or move what changed: rebuilding the
        // row on every poll made the focused chip (and, for a frame, the profile pill) blink
        for (int i = pcChips.getChildCount() - 1; i >= 0; i--) {
            View chip = pcChips.getChildAt(i);
            if (findComputer((String) chip.getTag()) != null) {
                continue;
            }
            if (chip.hasFocus()) {
                View next = i + 1 < pcChips.getChildCount() ? pcChips.getChildAt(i + 1)
                        : i > 0 ? pcChips.getChildAt(i - 1) : appGrid;
                next.requestFocus();
            }
            pcChips.removeViewAt(i);
        }

        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < computers.size(); i++) {
            ComputerDetails computer = computers.get(i);
            View chip = findChip(computer.uuid);
            if (chip == null) {
                chip = inflater.inflate(R.layout.main_pc_chip, pcChips, false);
                chip.setTag(computer.uuid);
                chip.setOnFocusChangeListener((v, hasFocus) -> {
                    if (hasFocus) {
                        pcChipsScroll.requestChildRectangleOnScreen(pcChips,
                                new android.graphics.Rect(v.getLeft() - 48, v.getTop(), v.getRight() + 48, v.getBottom()), false);
                    }
                });
            }
            bindChip(chip, computer);
            if (pcChips.getChildAt(i) != chip) {
                boolean hadFocus = chip.hasFocus();
                if (chip.getParent() != null) {
                    pcChips.removeView(chip);
                }
                pcChips.addView(chip, Math.min(i, pcChips.getChildCount()));
                if (hadFocus) {
                    chip.requestFocus();
                }
            }
        }
    }

    private View findChip(String uuid) {
        for (int i = 0; i < pcChips.getChildCount(); i++) {
            View chip = pcChips.getChildAt(i);
            if (uuid.equalsIgnoreCase((String) chip.getTag())) {
                return chip;
            }
        }
        return null;
    }

    private void bindChip(View chip, ComputerDetails computer) {
        View dot = chip.findViewById(R.id.chipDot);
        ImageView icon = chip.findViewById(R.id.chipIcon);
        TextView name = chip.findViewById(R.id.chipName);
        String label = String.valueOf(computer.name);
        if (!label.contentEquals(name.getText())) {
            name.setText(label);
        }

        boolean locked = computer.state == ComputerDetails.State.ONLINE && computer.pairState != PairState.PAIRED;
        if (locked) {
            icon.setImageResource(R.drawable.ic_lock);
        }
        else {
            int color = computer.state == ComputerDetails.State.ONLINE ? DOT_ONLINE
                    : computer.state == ComputerDetails.State.UNKNOWN ? DOT_UNKNOWN : DOT_OFFLINE;
            if (!Integer.valueOf(color).equals(dot.getTag())) {
                Drawable background = dot.getBackground().mutate();
                background.setTint(color);
                dot.setBackground(background);
                dot.setTag(color);
            }
        }
        dot.setVisibility(locked ? View.GONE : View.VISIBLE);
        icon.setVisibility(locked ? View.VISIBLE : View.GONE);

        chip.setSelected(computer.uuid.equalsIgnoreCase(selectedUuid));
        chip.setOnClickListener(v -> {
            if (computer.uuid.equalsIgnoreCase(selectedUuid)) {
                showPcMenu(computer);
            }
            else {
                selectPc(computer.uuid);
            }
        });
        chip.setOnLongClickListener(v -> {
            showPcMenu(computer);
            return true;
        });
    }

    // ---- Body: state card or app grid ----

    private void renderSelected() {
        ComputerDetails computer = findComputer(selectedUuid);
        if (computer == null) {
            dropApps();
            if (computers.isEmpty()) {
                shownStateKey = null;
                showState(true, 0, getString(R.string.main_no_pc_title), getString(R.string.main_no_pc_message));
                addStateAction(getString(R.string.main_add_pc), () -> startActivity(new Intent(this, AddComputerManually.class)));
            }
            return;
        }
        onSelectedComputerUpdated(computer);
    }

    private void onSelectedComputerUpdated(ComputerDetails computer) {
        // What the state card would show for this PC; a poll that changes nothing leaves the card (and the
        // focus on one of its buttons) alone
        String stateKey = computer.uuid + "|" + computer.state + "|" + computer.pairState + "|" + computer.name
                + "|" + (computer.macAddress != null);
        switch (computer.state) {
            case UNKNOWN:
                if (appsUuid != null && appsUuid.equalsIgnoreCase(computer.uuid) && appsLoaded) {
                    // A short hiccup between two polls: keep the grid rather than flicker
                    return;
                }
                if (stateShown(stateKey)) {
                    return;
                }
                dropApps();
                showState(true, 0, computer.name, getString(R.string.main_pc_checking));
                return;

            case OFFLINE:
                if (stateShown(stateKey)) {
                    return;
                }
                dropApps();
                showState(false, R.drawable.ic_pc_offline, computer.name, getString(R.string.main_pc_offline_message));
                if (computer.macAddress != null) {
                    addStateAction(getString(R.string.main_wake), () -> doWakeOnLan(computer));
                }
                addStateAction(getString(R.string.pcview_menu_details), () -> showDetails(computer));
                addStateAction(getString(R.string.pcview_menu_delete_pc), () -> confirmDelete(computer));
                focusFirstStateAction();
                return;

            case ONLINE:
            default:
                break;
        }

        if (computer.pairState != PairState.PAIRED) {
            if (stateShown(stateKey)) {
                return;
            }
            dropApps();
            showState(false, R.drawable.ic_lock, computer.name, getString(R.string.main_pc_not_paired_message));
            addStateAction(getString(R.string.main_pair), () -> doPair(computer, null, null));
            addStateAction(getString(R.string.main_pair_passphrase), () -> doOtpPair(computer));
            addStateAction(getString(R.string.pcview_menu_delete_pc), () -> confirmDelete(computer));
            focusFirstStateAction();
            return;
        }

        shownStateKey = null;
        ensureAppsFor(computer);
        applyServerInfo(computer);
    }

    // True when the state card already shows exactly this; otherwise remembers the key for the card about to be built
    private boolean stateShown(String key) {
        if (stateCard.getVisibility() == View.VISIBLE && key.equals(shownStateKey)) {
            return true;
        }
        shownStateKey = key;
        return false;
    }

    private void showState(boolean spinner, int iconRes, String title, String message) {
        appGrid.setVisibility(View.GONE);
        if (stateCard.getVisibility() != View.VISIBLE) {
            stateCard.setAlpha(0f);
            stateCard.animate().alpha(1f).setDuration(220).start();
        }
        stateCard.setVisibility(View.VISIBLE);
        stateSpinner.setVisibility(spinner ? View.VISIBLE : View.GONE);
        if (iconRes != 0) {
            stateIcon.setImageResource(iconRes);
            stateIcon.setVisibility(View.VISIBLE);
        }
        else {
            stateIcon.setVisibility(View.GONE);
        }
        stateTitle.setText(title);
        stateMessage.setText(message);
        stateActions.removeAllViews();
    }

    private void addStateAction(String label, Runnable action) {
        TextView button = new TextView(this);
        button.setText(label);
        button.setTextSize(15);
        button.setTextColor(getColorStateList(R.color.main_chip_text));
        button.setBackgroundResource(R.drawable.main_pill_bg);
        button.setFocusable(true);
        button.setClickable(true);
        button.setGravity(android.view.Gravity.CENTER);
        float density = getResources().getDisplayMetrics().density;
        int padding = Math.round(20 * density);
        button.setPadding(padding, 0, padding, 0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(40 * density));
        params.setMarginEnd(Math.round(10 * density));
        button.setLayoutParams(params);
        button.setOnClickListener(v -> action.run());
        stateActions.addView(button);
    }

    private void focusFirstStateAction() {
        View focus = getCurrentFocus();
        if ((focus == null || focus == appGrid) && stateActions.getChildCount() > 0) {
            stateActions.getChildAt(0).requestFocus();
        }
    }

    // ---- Apps of the selected PC ----

    private void dropApps() {
        if (poller != null) {
            poller.stop();
            poller = null;
        }
        if (appAdapter != null) {
            appAdapter.cancelQueuedOperations();
            appAdapter = null;
        }
        appsUuid = null;
        appsLoaded = false;
        lastRawApplist = null;
        lastRunningAppId = 0;
        highlightedTile = null;
        appGrid.setAdapter(null);
    }

    private void ensureAppsFor(ComputerDetails computer) {
        if (appsUuid != null && appsUuid.equalsIgnoreCase(computer.uuid) && appAdapter != null) {
            return;
        }
        dropApps();
        if (managerBinder == null) {
            showState(true, 0, computer.name, getString(R.string.applist_refresh_msg));
            return;
        }
        appsUuid = computer.uuid;

        // Add a launcher shortcut for this PC (forced, since this is user interaction)
        boolean newPair = pendingNewPair;
        pendingNewPair = false;
        new Thread(() -> {
            shortcutHelper.createAppViewShortcut(computer, true, newPair);
            shortcutHelper.reportComputerShortcutUsed(computer);
        }).start();

        hiddenAppIds.clear();
        SharedPreferences hiddenAppsPrefs = getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE);
        for (String id : hiddenAppsPrefs.getStringSet(computer.uuid, new HashSet<>())) {
            hiddenAppIds.add(Integer.parseInt(id));
        }

        appAdapter = new AppTileAdapter(this, computer, managerBinder.getUniqueId(), showHiddenApps,
                position -> appGrid.hasFocus() && appGrid.getSelectedItemPosition() == position);
        appAdapter.updateHiddenApps(hiddenAppIds, true);
        appGrid.setAdapter(appAdapter);

        // Cached list first, so the grid is there before the network answers
        try {
            lastRawApplist = CacheHelper.readInputStreamToString(CacheHelper.openCacheFileForInput(getCacheDir(), "applist", computer.uuid));
            applyAppList(NvHTTP.getAppListByReader(new StringReader(lastRawApplist)));
            LimeLog.info("Loaded applist from cache");
        } catch (IOException | XmlPullParserException e) {
            if (lastRawApplist != null) {
                LimeLog.warning("Saved applist corrupted: " + lastRawApplist);
            }
            lastRawApplist = null;
            LimeLog.info("Loading applist from the network");
            showState(true, 0, computer.name, getString(R.string.applist_refresh_msg));
        }

        poller = managerBinder.createAppListPoller(computer);
        if (inForeground) {
            poller.start();
        }
    }

    /** The running app and the app list from a poll of the selected PC */
    private void applyServerInfo(ComputerDetails details) {
        if (suspendGridUpdates || appAdapter == null) {
            return;
        }
        if (details.rawAppList != null && !details.rawAppList.equals(lastRawApplist)) {
            lastRawApplist = details.rawAppList;
            try {
                applyAppList(NvHTTP.getAppListByReader(new StringReader(details.rawAppList)));
            } catch (XmlPullParserException | IOException e) {
                e.printStackTrace();
            }
        }
        if (details.runningGameId != lastRunningAppId || !appsLoaded) {
            lastRunningAppId = details.runningGameId;
            boolean changed = false;
            for (AppTileAdapter.Entry entry : appAdapter.getAll()) {
                boolean running = entry.app.getAppId() == details.runningGameId;
                if (entry.running != running) {
                    entry.running = running;
                    changed = true;
                }
            }
            if (changed) {
                appAdapter.notifyDataSetChanged();
            }
        }
    }

    private void applyAppList(List<NvApp> appList) {
        if (appAdapter == null) {
            return;
        }
        boolean updated = false;
        ComputerDetails computer = findComputer(appsUuid);

        for (NvApp app : appList) {
            AppTileAdapter.Entry existing = appAdapter.findByAppId(app.getAppId());
            if (existing != null) {
                if (!existing.app.getAppName().equals(app.getAppName())) {
                    existing.app.setAppName(app.getAppName());
                    updated = true;
                }
            }
            else {
                appAdapter.addApp(app);
                if (computer != null) {
                    shortcutHelper.enableAppShortcut(computer, app);
                }
                updated = true;
            }
        }

        List<AppTileAdapter.Entry> gone = new ArrayList<>();
        for (AppTileAdapter.Entry entry : appAdapter.getAll()) {
            boolean present = false;
            for (NvApp app : appList) {
                if (entry.app.getAppId() == app.getAppId()) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                gone.add(entry);
            }
        }
        for (AppTileAdapter.Entry entry : gone) {
            if (computer != null) {
                shortcutHelper.disableAppShortcut(computer, entry.app, getString(R.string.app_removed_from_pc));
            }
            appAdapter.removeApp(entry);
            updated = true;
        }

        if (updated || !appsLoaded) {
            appAdapter.notifyDataSetChanged();
        }
        if (!appsLoaded) {
            appsLoaded = true;
            stateCard.setVisibility(View.GONE);
            appGrid.setAlpha(0f);
            appGrid.setTranslationY(16 * getResources().getDisplayMetrics().density);
            appGrid.setVisibility(View.VISIBLE);
            appGrid.animate().alpha(1f).translationY(0f).setDuration(220).start();
            View focus = getCurrentFocus();
            if (focus == null || focus.getParent() != pcChips) {
                appGrid.requestFocus();
            }
        }
    }

    private void highlightTile(View tile) {
        for (int i = 0; i < appGrid.getChildCount(); i++) {
            View child = appGrid.getChildAt(i);
            boolean on = child == tile;
            if (on != child.findViewById(R.id.tileCard).isActivated()) {
                AppTileAdapter.setSelected(child, on, true);
            }
        }
        highlightedTile = tile;
    }

    private void saveHiddenApps() {
        HashSet<String> ids = new HashSet<>();
        for (Integer id : hiddenAppIds) {
            ids.add(id.toString());
        }
        getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE).edit().putStringSet(appsUuid, ids).apply();
        appAdapter.updateHiddenApps(hiddenAppIds, false);
    }

    // ---- App actions ----

    private void onAppClick(AppTileAdapter.Entry entry) {
        ComputerDetails computer = findComputer(appsUuid);
        if (computer == null || managerBinder == null) {
            return;
        }
        // Only open the action panel if something is running, otherwise start it
        if (lastRunningAppId != 0) {
            if (prefConfig.resumeWithoutConfirm && lastRunningAppId == entry.app.getAppId()) {
                ServerHelper.doStart(this, entry.app, computer, managerBinder, prefConfig.useVirtualDisplay);
            }
            else {
                showAppMenu(entry, appGrid.getSelectedView());
            }
        }
        else {
            startApp(computer, entry.app, prefConfig.useVirtualDisplay, false);
        }
    }

    private void startApp(ComputerDetails computer, NvApp app, boolean withVDisplay, boolean quitFirst) {
        Runnable start = () -> ServerHelper.doStart(this, app, computer, managerBinder, withVDisplay);
        Runnable confirmed = quitFirst ? () -> UiHelper.displayQuitConfirmationDialog(this, start, null) : start;
        if (withVDisplay && !(computer.vDisplaySupported && computer.vDisplayDriverReady)) {
            UiHelper.displayVdisplayConfirmationDialog(this, computer, confirmed, null);
        }
        else {
            confirmed.run();
        }
    }

    private void showAppMenu(AppTileAdapter.Entry entry, View tile) {
        ComputerDetails computer = findComputer(appsUuid);
        if (computer == null || managerBinder == null) {
            return;
        }
        List<PanelMenu.Item> items = new ArrayList<>();
        boolean thisRunning = lastRunningAppId == entry.app.getAppId();

        if (lastRunningAppId == 0) {
            items.add(new PanelMenu.Item(getString(R.string.main_start), () -> startApp(computer, entry.app, prefConfig.useVirtualDisplay, false)));
            items.add(new PanelMenu.Item(getString(prefConfig.useVirtualDisplay ? R.string.applist_menu_start_primarydisplay : R.string.applist_menu_start_vdisplay),
                    () -> startApp(computer, entry.app, !prefConfig.useVirtualDisplay, false)));
        }
        else if (thisRunning) {
            items.add(new PanelMenu.Item(getString(R.string.applist_menu_resume), () -> startApp(computer, entry.app, prefConfig.useVirtualDisplay, false)));
            items.add(new PanelMenu.Item(getString(R.string.applist_menu_quit), () -> UiHelper.displayQuitConfirmationDialog(this, () -> {
                suspendGridUpdates = true;
                ServerHelper.doQuit(this, computer, entry.app, managerBinder, () -> {
                    suspendGridUpdates = false;
                    if (poller != null) {
                        poller.pollNow();
                    }
                });
            }, null)));
        }
        else {
            items.add(new PanelMenu.Item(getString(R.string.applist_menu_quit_and_start), () -> startApp(computer, entry.app, prefConfig.useVirtualDisplay, true)));
            items.add(new PanelMenu.Item(getString(prefConfig.useVirtualDisplay ? R.string.applist_menu_quit_and_start_primarydisplay : R.string.applist_menu_quit_and_start_vdisplay),
                    () -> startApp(computer, entry.app, !prefConfig.useVirtualDisplay, true)));
        }

        if (!thisRunning || entry.hidden) {
            items.add(new PanelMenu.Item(getString(entry.hidden ? R.string.main_unhide_app : R.string.applist_menu_hide_app), () -> {
                if (entry.hidden) {
                    hiddenAppIds.remove(entry.app.getAppId());
                }
                else {
                    hiddenAppIds.add(entry.app.getAppId());
                }
                saveHiddenApps();
            }));
        }

        items.add(new PanelMenu.Item(getString(R.string.applist_menu_details),
                () -> Dialog.displayDialog(this, getResources().getString(R.string.title_details), entry.app.toString(), false)));

        // A pinned shortcut needs the box art, so only offer it once the poster is on screen
        Bitmap art = tile == null ? null : artOf(tile);
        if (art != null) {
            items.add(new PanelMenu.Item(getString(R.string.applist_menu_scut), () -> {
                if (!shortcutHelper.createPinnedGameShortcut(computer, entry.app, art)) {
                    Toast.makeText(this, getResources().getString(R.string.unable_to_pin_shortcut), Toast.LENGTH_LONG).show();
                }
            }));
        }

        items.add(new PanelMenu.Item(getString(R.string.applist_menu_export_launcher), () -> {
            if (entry.app.getAppUUID() == null || entry.app.getAppUUID().isEmpty()) {
                UiHelper.displayConfirmationDialog(this,
                        getResources().getString(R.string.title_export_sunshine_launcher_file),
                        getResources().getString(R.string.message_export_sunshine_launcher_file),
                        getResources().getString(R.string.proceed),
                        getResources().getString(R.string.cancel),
                        () -> shortcutHelper.exportLauncherFile(computer, entry.app),
                        null);
            }
            else {
                shortcutHelper.exportLauncherFile(computer, entry.app);
            }
        }));

        PanelMenu.show(this, entry.app.getAppName(), items);
    }

    private static Bitmap artOf(View tile) {
        ImageView image = tile.findViewById(R.id.grid_image);
        if (image == null || !(image.getDrawable() instanceof BitmapDrawable)) {
            return null;
        }
        return ((BitmapDrawable) image.getDrawable()).getBitmap();
    }

    // ---- PC actions ----

    private void showPcMenu(ComputerDetails computer) {
        List<PanelMenu.Item> items = new ArrayList<>();
        String status;
        switch (computer.state) {
            case ONLINE:
                status = getString(R.string.pcview_menu_header_online);
                break;
            case OFFLINE:
                status = getString(R.string.pcview_menu_header_offline);
                break;
            default:
                status = getString(R.string.pcview_menu_header_unknown);
                break;
        }

        if (computer.state != ComputerDetails.State.ONLINE) {
            items.add(new PanelMenu.Item(getString(R.string.main_wake), () -> doWakeOnLan(computer)));
        }
        else if (computer.pairState != PairState.PAIRED) {
            items.add(new PanelMenu.Item(getString(R.string.main_pair), () -> doPair(computer, null, null)));
            items.add(new PanelMenu.Item(getString(R.string.main_pair_passphrase), () -> doOtpPair(computer)));
        }
        else {
            if (computer.runningGameId != 0) {
                items.add(new PanelMenu.Item(getString(R.string.applist_menu_resume), () -> ServerHelper.doStart(this,
                        new NvApp("app", null, computer.runningGameId, false), computer, managerBinder, false)));
                items.add(new PanelMenu.Item(getString(R.string.applist_menu_quit), () -> UiHelper.displayQuitConfirmationDialog(this,
                        () -> ServerHelper.doQuit(this, computer, new NvApp("app", null, 0, false), managerBinder, null), null)));
            }
            if (computer.uuid.equalsIgnoreCase(appsUuid) && appAdapter != null && (showHiddenApps || !hiddenAppIds.isEmpty())) {
                items.add(new PanelMenu.Item(getString(showHiddenApps ? R.string.main_hide_hidden_apps : R.string.main_show_hidden_apps), () -> {
                    showHiddenApps = !showHiddenApps;
                    appAdapter.setShowHidden(showHiddenApps);
                }));
            }
            items.add(new PanelMenu.Item(getString(R.string.pcview_menu_unpair_pc), () -> doUnpair(computer)));
        }

        if (computer.state == ComputerDetails.State.ONLINE) {
            if (computer.nvidiaServer) {
                items.add(new PanelMenu.Item(getString(R.string.pcview_menu_eol), () -> HelpLauncher.launchGameStreamEolFaq(this)));
            }
            else {
                items.add(new PanelMenu.Item(getString(R.string.pcview_menu_open_management_page), () -> {
                    String url = managementUrl(computer);
                    if (url == null) {
                        Toast.makeText(this, getResources().getString(R.string.pcview_error_no_management_url), Toast.LENGTH_LONG).show();
                    }
                    else {
                        HelpLauncher.launchUrl(this, url);
                    }
                }));
            }
        }
        items.add(new PanelMenu.Item(getString(R.string.pcview_menu_test_network), () -> ServerHelper.doNetworkTest(this)));
        items.add(new PanelMenu.Item(getString(R.string.pcview_menu_details), () -> showDetails(computer)));
        items.add(new PanelMenu.Item(getString(R.string.pcview_menu_delete_pc), () -> confirmDelete(computer)));

        PanelMenu.show(this, computer.name + " · " + status, items);
    }

    private static String managementUrl(ComputerDetails computer) {
        if (computer.activeAddress == null) {
            return null;
        }
        return "https://" + computer.activeAddress.address + ":" + (computer.guessExternalPort() + 1);
    }

    private void showDetails(ComputerDetails computer) {
        Dialog.displayDialog(this, getResources().getString(R.string.title_details), computer.toString(), false);
    }

    private void confirmDelete(ComputerDetails computer) {
        if (ActivityManager.isUserAMonkey()) {
            LimeLog.info("Ignoring delete PC request from monkey");
            return;
        }
        UiHelper.displayDeletePcConfirmationDialog(this, computer, () -> {
            if (managerBinder == null) {
                toast(R.string.error_manager_not_running);
                return;
            }
            removeComputer(computer);
        }, null);
    }

    private void toast(int resId) {
        Toast.makeText(this, getResources().getString(resId), Toast.LENGTH_LONG).show();
    }

    private void doPair(final ComputerDetails computer, String otp, String passphrase) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            toast(R.string.pair_pc_offline);
            return;
        }
        if (managerBinder == null) {
            toast(R.string.error_manager_not_running);
            return;
        }

        Toast.makeText(this, getResources().getString(R.string.pairing), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            NvHTTP httpConn;
            String message;
            boolean success = false;
            try {
                // Stop updates and wait while pairing
                stopComputerUpdates(true);

                httpConn = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer),
                        computer.httpsPort, managerBinder.getUniqueId(), computer.serverCert,
                        PlatformBinding.getCryptoProvider(MainActivity.this));
                if (httpConn.getPairState() == PairState.PAIRED) {
                    message = null;
                    success = true;
                }
                else {
                    String pinStr = otp != null ? otp : PairingManager.generatePinString();

                    if (passphrase == null) {
                        Dialog.displayDialog(MainActivity.this, getResources().getString(R.string.pair_pairing_title),
                                getResources().getString(R.string.pair_pairing_msg) + " " + pinStr + "\n\n" +
                                        getResources().getString(R.string.pair_pairing_help), false);
                    }
                    else {
                        Dialog.displayDialog(MainActivity.this, getResources().getString(R.string.pair_pairing_title),
                                getResources().getString(R.string.pair_otp_pairing_msg) + "\n\n" +
                                        getResources().getString(R.string.pair_otp_pairing_help), false);
                    }

                    PairingManager pm = httpConn.getPairingManager();
                    PairState pairState = pm.pair(httpConn.getServerInfo(true), pinStr, passphrase);
                    if (pairState == PairState.PIN_WRONG) {
                        message = getResources().getString(R.string.pair_incorrect_pin);
                    }
                    else if (pairState == PairState.FAILED) {
                        message = getResources().getString(computer.runningGameId != 0 ? R.string.pair_pc_ingame : R.string.pair_fail);
                    }
                    else if (pairState == PairState.ALREADY_IN_PROGRESS) {
                        message = getResources().getString(R.string.pair_already_in_progress);
                    }
                    else if (pairState == PairState.PAIRED) {
                        message = null;
                        success = true;
                        // Pin this certificate for later HTTPS use
                        managerBinder.getComputer(computer.uuid).serverCert = pm.getPairedCert();
                        // Invalidate reachability information after pairing to force a refresh before reading pair state again
                        managerBinder.invalidateStateForComputer(computer.uuid);
                    }
                    else {
                        message = null;
                    }
                }
            } catch (UnknownHostException e) {
                message = getResources().getString(R.string.error_unknown_host);
            } catch (FileNotFoundException e) {
                message = getResources().getString(R.string.error_404);
            } catch (XmlPullParserException | IOException e) {
                e.printStackTrace();
                message = e.getMessage();
            }

            Dialog.closeDialogs();

            final String toastMessage = message;
            final boolean paired = success;
            runOnUiThread(() -> {
                if (toastMessage != null) {
                    Toast.makeText(MainActivity.this, toastMessage, Toast.LENGTH_LONG).show();
                }
                if (paired) {
                    pendingNewPair = true;
                    selectPc(computer.uuid);
                }
                startComputerUpdates();
            });
        }).start();
    }

    private void doOtpPair(final ComputerDetails computer) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 40);

        final EditText otpInput = new EditText(this);
        otpInput.setHint("PIN");
        otpInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        otpInput.setFilters(new InputFilter[] { new InputFilter.LengthFilter(4) });

        final EditText passphraseInput = new EditText(this);
        passphraseInput.setHint(getString(R.string.pair_passphrase_hint));
        passphraseInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        layout.addView(otpInput);
        layout.addView(passphraseInput);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.main_pair_passphrase)
                .setView(layout)
                .setPositiveButton(getString(R.string.proceed), null)
                .setNegativeButton(getString(R.string.cancel), (d, which) -> d.dismiss())
                .create();
        dialog.show();
        Dialog.compact(dialog);

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String pin = otpInput.getText().toString();
            String passphrase = passphraseInput.getText().toString();
            if (pin.length() != 4) {
                Toast.makeText(this, getString(R.string.pair_pin_length_msg), Toast.LENGTH_SHORT).show();
                return;
            }
            if (passphrase.length() < 4) {
                Toast.makeText(this, getString(R.string.pair_passphrase_length_msg), Toast.LENGTH_SHORT).show();
                return;
            }
            doPair(computer, pin, passphrase);
            dialog.dismiss();
        });
    }

    private void doWakeOnLan(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.ONLINE) {
            toast(R.string.wol_pc_online);
            return;
        }
        if (computer.macAddress == null) {
            toast(R.string.wol_no_mac);
            return;
        }
        new Thread(() -> {
            String message;
            try {
                WakeOnLanSender.sendWolPacket(computer);
                message = getResources().getString(R.string.wol_waking_msg);
            } catch (IOException e) {
                message = getResources().getString(R.string.wol_fail);
            }
            final String toastMessage = message;
            runOnUiThread(() -> Toast.makeText(MainActivity.this, toastMessage, Toast.LENGTH_LONG).show());
        }).start();
    }

    private void doUnpair(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            toast(R.string.error_pc_offline);
            return;
        }
        if (managerBinder == null) {
            toast(R.string.error_manager_not_running);
            return;
        }
        Toast.makeText(this, getResources().getString(R.string.unpairing), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String message;
            try {
                NvHTTP httpConn = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer),
                        computer.httpsPort, managerBinder.getUniqueId(), computer.serverCert,
                        PlatformBinding.getCryptoProvider(MainActivity.this));
                if (httpConn.getPairState() == PairState.PAIRED) {
                    httpConn.unpair();
                    message = getResources().getString(httpConn.getPairState() == PairState.NOT_PAIRED ? R.string.unpair_success : R.string.unpair_fail);
                }
                else {
                    message = getResources().getString(R.string.unpair_error);
                }
            } catch (UnknownHostException e) {
                message = getResources().getString(R.string.error_unknown_host);
            } catch (FileNotFoundException e) {
                message = getResources().getString(R.string.error_404);
            } catch (XmlPullParserException | IOException e) {
                message = e.getMessage();
                e.printStackTrace();
            }
            final String toastMessage = message;
            runOnUiThread(() -> {
                Toast.makeText(MainActivity.this, toastMessage, Toast.LENGTH_LONG).show();
                if (managerBinder != null) {
                    managerBinder.invalidateStateForComputer(computer.uuid);
                }
            });
        }).start();
    }
}
