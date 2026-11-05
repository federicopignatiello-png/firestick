package com.firepad.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phone-side Fire TV trackpad.
 *
 * <p>Fire TV discovers accessories; it usually does not advertise itself while the user is
 * browsing. This activity still runs an unfiltered BLE scan (then a classic inquiry) so a Fire
 * Stick that is actually advertising can be selected, and it registers a HID mouse and asks
 * Android to become discoverable so the stick can connect inbound. Inbound HID connections are
 * accepted even when the user never tapped a row: that is the normal Fire TV pairing direction.
 */
public class MainActivity extends Activity {
    // Standard relative mouse: Report ID 1, 3 buttons, X/Y delta and wheel (-127..127).
    private static final byte[] MOUSE_DESCRIPTOR = new byte[] {
        0x05, 0x01, 0x09, 0x02, (byte) 0xA1, 0x01, (byte) 0x85, 0x01,
        0x09, 0x01, (byte) 0xA1, 0x00, 0x05, 0x09, 0x19, 0x01, 0x29, 0x03,
        0x15, 0x00, 0x25, 0x01, (byte) 0x95, 0x03, 0x75, 0x01, (byte) 0x81, 0x02,
        (byte) 0x95, 0x01, 0x75, 0x05, (byte) 0x81, 0x03,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38,
        0x15, (byte) 0x81, 0x25, 0x7F, 0x75, 0x08, (byte) 0x95, 0x03,
        (byte) 0x81, 0x06, (byte) 0xC0, (byte) 0xC0
    };
    private static final int REQUEST_PERMISSIONS = 10;
    private static final int REQUEST_DISCOVERABLE = 21;
    private static final long BLE_SCAN_MS = 12000L;

    private enum Phase { IDLE, BLE, CLASSIC }

    private final int ink = Color.rgb(24, 27, 32);
    private final int sage = Color.rgb(40, 125, 105);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, DeviceEntry> found = new LinkedHashMap<>();
    private final AxisAccumulator wheelAxis = new AxisAccumulator();

    private BluetoothAdapter adapter;
    private BluetoothHidDevice hid;
    private BluetoothDevice target;
    private boolean registered;
    private boolean linkConnected;
    private boolean profileRequestInFlight;
    private boolean registerInFlight;
    private boolean permissionRequestLaunched;
    private boolean discoverablePromptShown;
    private boolean pendingDiscoverable;
    private boolean resumed;
    private boolean receiverRegistered;
    private boolean scanning;
    private boolean awaitingHost;
    private boolean renderPosted;
    private int scanGeneration;
    private Phase scanPhase = Phase.IDLE;
    private Runnable bleFinishedTask;

    private TextView status;
    private TextView scanStatus;
    private TextView phoneName;
    private TextView selected;
    private LinearLayout devices;
    private LinearLayout contentRoot;
    private float prevX, prevY, remainderX, remainderY;
    private long downAt;
    private float downX, downY;
    private boolean moved;
    private boolean multiTouch;
    private int touchSlop;

    private final BluetoothProfile.ServiceListener hidListener = new BluetoothProfile.ServiceListener() {
        @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
            handler.post(() -> onHidServiceConnected(profile, proxy));
        }
        @Override public void onServiceDisconnected(int profile) {
            handler.post(() -> onHidServiceDisconnected(profile));
        }
    };

    private final BluetoothHidDevice.Callback hidCallback = new BluetoothHidDevice.Callback() {
        @Override public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean isRegistered) {
            if (isDestroyed()) return;
            registerInFlight = false;
            registered = isRegistered;
            if (isRegistered) {
                status.setText("Mouse HID pronto. Sulla Fire Stick: Impostazioni > Controller e dispositivi Bluetooth > Altri dispositivi Bluetooth > Aggiungi, poi scegli \"" + phoneBluetoothName() + "\".");
                adoptConnectedHosts();
                addBondedDevices();
                if (!scanning && !linkConnected) maybeBecomeDiscoverable();
            } else {
                linkConnected = false;
                status.setText("Registrazione HID non disponibile su questo telefono.");
            }
        }

        @Override public void onConnectionStateChanged(BluetoothDevice device, int state) {
            if (device == null || isDestroyed()) return;
            if (state == BluetoothProfile.STATE_CONNECTED) {
                awaitingHost = true;
                stopScans();
                target = device;
                linkConnected = true;
                selected.setText("Selezionato: " + labelOf(device));
                status.setText("Connesso a " + labelOf(device) + ". Puoi usare il trackpad.");
                remember(device, safeName(device), false, false, true);
                return;
            }
            if (target != null && !sameDevice(target, device)) return;
            target = device;
            linkConnected = false;
            if (state == BluetoothProfile.STATE_CONNECTING) {
                stopScans();
                status.setText("Connessione a " + labelOf(device) + "...");
            } else if (state == BluetoothProfile.STATE_DISCONNECTING) {
                status.setText("Disconnessione da " + labelOf(device) + "...");
            } else {
                status.setText("Non connesso. Seleziona un dispositivo o aggiungi il telefono dalla Fire Stick.");
                maybeBecomeDiscoverable();
            }
        }

        @Override public void onGetReport(BluetoothDevice device, byte type, byte id, int bufferSize) {
            if (hid == null || device == null) return;
            try {
                if (type == BluetoothHidDevice.REPORT_TYPE_INPUT && (id == 0 || id == 1)) {
                    byte replyId = id == 0 ? (byte) 1 : id;
                    hid.replyReport(device, type, replyId, new byte[] {0, 0, 0, 0});
                } else {
                    hid.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ);
                }
            } catch (SecurityException ignored) {
                // Permission was revoked while the host was querying the report.
            }
        }
    };

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override public void onScanResult(int callbackType, ScanResult result) {
            if (result == null || result.getDevice() == null) return;
            ScanRecord record = result.getScanRecord();
            String advertised = record == null ? null : record.getDeviceName();
            remember(result.getDevice(), advertised, true, hasAmazonManufacturer(record), false);
        }

        @Override public void onBatchScanResults(List<ScanResult> results) {
            if (results == null) return;
            for (ScanResult result : results) onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result);
        }

        @Override public void onScanFailed(int errorCode) {
            runOnUiThread(() -> {
                if (isDestroyed() || !resumed) return;
                if (bleFinishedTask != null) handler.removeCallbacks(bleFinishedTask);
                stopBleOnly();
                scanStatus.setText(scanFailure(errorCode));
                startClassic(scanGeneration);
            });
        }
    };

    private final BroadcastReceiver btReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            String action = intent.getAction();
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                if (state == BluetoothAdapter.STATE_ON) {
                    setupBluetooth();
                    startScans(false);
                } else if (state == BluetoothAdapter.STATE_OFF) {
                    stopScans();
                    linkConnected = false;
                    status.setText("Il Bluetooth è spento. Attivalo per cercare la Fire Stick.");
                    scanStatus.setText("Ricerca interrotta.");
                }
                return;
            }
            if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                if (scanPhase == Phase.CLASSIC) finishScan(scanGeneration, null);
                return;
            }
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = deviceExtra(intent);
                if (device == null) return;
                boolean bonded = false;
                try {
                    bonded = device.getBondState() == BluetoothDevice.BOND_BONDED;
                } catch (SecurityException ignored) {
                    bonded = false;
                }
                remember(device, null, false, false, bonded);
                return;
            }
            if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                BluetoothDevice device = deviceExtra(intent);
                if (device == null || target == null || !sameDevice(target, device)) return;
                int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                int previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR);
                if (state == BluetoothDevice.BOND_BONDED) {
                    remember(device, null, false, false, true);
                    hidConnect(device);
                } else if (state == BluetoothDevice.BOND_NONE && previous == BluetoothDevice.BOND_BONDING) {
                    status.setText("Associazione annullata o rifiutata.");
                }
            }
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        registerBtReceiver();
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter();
        if (!hasAllPermissions()) requestNeededPermissions();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        showPhoneName();
        if (!readyForRadio()) return;
        setupBluetooth();
        if (registered) adoptConnectedHosts();
        // An active inquiry makes this phone a poor target for the Fire Stick, so after a
        // search finishes we stay discoverable instead of scanning again on every resume.
        if (!linkConnected && !awaitingHost) startScans(false);
        else if (pendingDiscoverable) requestDiscoverable();
    }

    @Override protected void onPause() {
        resumed = false;
        stopScans();
        super.onPause();
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (contentRoot != null) contentRoot.requestApplyInsets();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_PERMISSIONS) return;
        if (hasAllPermissions()) {
            status.setText("Permessi concessi. Ricerca della Fire Stick...");
            setupBluetooth();
            startScans(true);
            return;
        }
        if (permissionRequestLaunched && permanentlyDenied()) {
            status.setText("Permesso negato. Apri le impostazioni dell'app e abilita Dispositivi nelle vicinanze, oppure la Posizione su Android 9–11.");
        } else {
            status.setText("Permessi Bluetooth mancanti. Servono per trovare la Fire Stick e per il mouse.");
        }
    }

    @Override @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_DISCOVERABLE) return;
        if (resultCode == 0) {
            status.setText("Il telefono non è visibile: la Fire Stick non può trovarlo finché non accetti la richiesta di visibilità.");
        } else {
            scanStatus.setText("Telefono visibile per alcuni minuti. Aggiungilo dalla Fire Stick.");
        }
    }

    @Override protected void onDestroy() {
        if (bleFinishedTask != null) handler.removeCallbacks(bleFinishedTask);
        stopHardwareScans();
        if (receiverRegistered) {
            try {
                unregisterReceiver(btReceiver);
            } catch (IllegalArgumentException ignored) {
                // Receiver was already unregistered.
            }
            receiverRegistered = false;
        }
        shutdownHid();
        super.onDestroy();
    }

    private void buildUi() {
        touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(247, 248, 246));
        contentRoot = new LinearLayout(this);
        contentRoot.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(contentRoot);
        setContentView(scroll);
        installInsets(contentRoot);

        contentRoot.addView(text("FirePad / 01", 29, true));
        contentRoot.addView(text("Trackpad Bluetooth per Fire TV. Il cursore bloccato si muove da qui.", 14, false));
        status = text("In attesa del Bluetooth...", 14, true);
        status.setTextColor(sage);
        contentRoot.addView(status);
        scanStatus = text("Ricerca non avviata.", 13, false);
        contentRoot.addView(scanStatus);
        phoneName = text("Nome Bluetooth di questo telefono: …", 13, false);
        contentRoot.addView(phoneName);
        selected = text("Nessuna Fire Stick selezionata", 13, false);
        contentRoot.addView(selected);

        Button settings = button("Apri impostazioni Bluetooth");
        settings.setOnClickListener(v -> openBluetoothSettings());
        contentRoot.addView(settings);
        Button refresh = button("Cerca di nuovo");
        refresh.setOnClickListener(v -> onSearchAgain());
        contentRoot.addView(refresh);

        contentRoot.addView(text("DISPOSITIVI", 12, true));
        devices = new LinearLayout(this);
        devices.setOrientation(LinearLayout.VERTICAL);
        contentRoot.addView(devices);
        addHint(devices, "In attesa dei permessi Bluetooth.");

        contentRoot.addView(text("TRACKPAD", 12, true));
        View pad = new View(this);
        pad.setContentDescription("Trackpad");
        pad.setClickable(true);
        pad.setBackground(bg(Color.rgb(225, 233, 230), dp(16)));
        contentRoot.addView(pad, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(240)));
        pad.setOnTouchListener((v, event) -> onPadTouch(v, event));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        contentRoot.addView(actions);
        Button left = button("Clic sinistro");
        left.setOnClickListener(v -> click(1));
        actions.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button right = button("Clic destro");
        right.setOnClickListener(v -> click(2));
        actions.addView(right, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        LinearLayout scrollBtns = new LinearLayout(this);
        scrollBtns.setOrientation(LinearLayout.HORIZONTAL);
        contentRoot.addView(scrollBtns);
        Button up = button("Scorri ↑");
        up.setOnClickListener(v -> wheel(5));
        scrollBtns.addView(up, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button down = button("Scorri ↓");
        down.setOnClickListener(v -> wheel(-5));
        scrollBtns.addView(down, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        addHint(contentRoot, "Tieni l'app in primo piano. Molte Fire Stick non compaiono nella ricerca del telefono: aggiungi questo telefono dalle impostazioni Bluetooth della Fire Stick, usando il nome mostrato sopra.");
    }

    private boolean onPadTouch(View pad, MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            ViewParent parent = pad.getParent();
            if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
            prevX = downX = event.getX();
            prevY = downY = event.getY();
            downAt = System.currentTimeMillis();
            moved = false;
            multiTouch = false;
            remainderX = 0f;
            remainderY = 0f;
            wheelAxis.reset();
            return true;
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            multiTouch = true;
            prevX = event.getX();
            prevY = event.getY();
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            float x = event.getX();
            float y = event.getY();
            if (Math.hypot(x - downX, y - downY) > touchSlop) moved = true;
            if (event.getPointerCount() >= 2) {
                multiTouch = true;
                for (int tick : wheelAxis.consume((prevY - y) / 9f, 12)) wheel(tick);
            } else if (!multiTouch) {
                motion(x - prevX, y - prevY);
            }
            prevX = x;
            prevY = y;
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            if (!moved && !multiTouch && System.currentTimeMillis() - downAt < 450) click(1);
            return true;
        }
        return true;
    }

    private void installInsets(View root) {
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int left;
            int top;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                left = bars.left;
                top = bars.top;
                right = bars.right;
                bottom = bars.bottom;
            } else {
                left = insets.getSystemWindowInsetLeft();
                top = insets.getSystemWindowInsetTop();
                right = insets.getSystemWindowInsetRight();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(dp(20) + left, dp(24) + top, dp(20) + right, dp(20) + bottom);
            return insets;
        });
        root.requestApplyInsets();
    }

    private void onSearchAgain() {
        if (!hasAllPermissions()) {
            if (permissionRequestLaunched && permanentlyDenied()) openAppSettings();
            else requestNeededPermissions();
            return;
        }
        if (adapter != null && !bluetoothEnabled()) {
            try {
                startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            } catch (Exception e) {
                openBluetoothSettings();
            }
            return;
        }
        if (needsLocationServices() && !locationEnabled()) {
            try {
                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            } catch (Exception e) {
                status.setText("Attiva la posizione dalle impostazioni di sistema.");
            }
            return;
        }
        registerInFlight = false;
        awaitingHost = false;
        discoverablePromptShown = false;
        setupBluetooth();
        startScans(true);
    }

    private boolean readyForRadio() {
        if (adapter == null) {
            status.setText("Questo telefono non ha il Bluetooth.");
            scanStatus.setText("Ricerca non disponibile.");
            return false;
        }
        if (!hasAllPermissions()) {
            status.setText("Permessi Bluetooth mancanti. Servono per trovare la Fire Stick e per il mouse.");
            scanStatus.setText("Concedi i permessi per avviare la ricerca BLE.");
            return false;
        }
        if (!bluetoothEnabled()) {
            status.setText("Il Bluetooth è spento. Attivalo per cercare la Fire Stick.");
            scanStatus.setText("Ricerca in attesa del Bluetooth.");
            return false;
        }
        if (needsLocationServices() && !locationEnabled()) {
            status.setText("Su Android 9–11 la ricerca BLE non mostra nulla se la posizione è spenta. Attivala e premi Cerca di nuovo.");
            scanStatus.setText("Posizione disattivata.");
            return false;
        }
        return true;
    }

    @SuppressLint("MissingPermission")
    private void setupBluetooth() {
        if (!canConnect() || adapter == null || !bluetoothEnabled()) return;
        if (hid != null) {
            if (!registered) registerHidApp();
            else addBondedDevices();
            return;
        }
        if (profileRequestInFlight) return;
        profileRequestInFlight = true;
        status.setText("Inizializzazione profilo mouse HID...");
        boolean requested;
        try {
            requested = adapter.getProfileProxy(this, hidListener, BluetoothProfile.HID_DEVICE);
        } catch (SecurityException e) {
            profileRequestInFlight = false;
            status.setText("Permesso Bluetooth necessario per il mouse HID.");
            return;
        }
        if (!requested) {
            profileRequestInFlight = false;
            status.setText("Questo telefono non espone il profilo Bluetooth HID.");
        }
    }

    @SuppressLint("MissingPermission")
    private void onHidServiceConnected(int profile, BluetoothProfile proxy) {
        profileRequestInFlight = false;
        if (profile != BluetoothProfile.HID_DEVICE || !(proxy instanceof BluetoothHidDevice)) return;
        if (isDestroyed()) {
            closeProfile(proxy);
            return;
        }
        hid = (BluetoothHidDevice) proxy;
        registerHidApp();
    }

    private void onHidServiceDisconnected(int profile) {
        if (profile != BluetoothProfile.HID_DEVICE) return;
        hid = null;
        registered = false;
        registerInFlight = false;
        linkConnected = false;
        profileRequestInFlight = false;
        if (!isDestroyed()) status.setText("Servizio Bluetooth scollegato.");
    }

    @SuppressLint("MissingPermission")
    private void registerHidApp() {
        if (hid == null || !canConnect() || registerInFlight || registered) return;
        registerInFlight = true;
        try {
            BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                "FirePad Mouse",
                "Trackpad Android per Fire TV",
                "FirePad",
                BluetoothHidDevice.SUBCLASS1_MOUSE,
                MOUSE_DESCRIPTOR);
            boolean ok = hid.registerApp(sdp, null, null, command -> handler.post(command), hidCallback);
            if (!ok) {
                registerInFlight = false;
                status.setText("Impossibile registrare il profilo HID su questo telefono.");
            }
        } catch (SecurityException e) {
            registerInFlight = false;
            status.setText("Permesso Bluetooth necessario per il mouse HID.");
        }
    }

    @SuppressLint("MissingPermission")
    private void adoptConnectedHosts() {
        if (hid == null || !canConnect()) return;
        try {
            List<BluetoothDevice> hosts = hid.getConnectedDevices();
            if (hosts == null || hosts.isEmpty()) return;
            BluetoothDevice chosen = hosts.get(0);
            for (BluetoothDevice device : hosts) {
                if (FireDeviceNames.looksLikeFireTv(safeName(device))) {
                    chosen = device;
                    break;
                }
            }
            target = chosen;
            linkConnected = true;
            awaitingHost = true;
            selected.setText("Selezionato: " + labelOf(chosen));
            status.setText("Connesso a " + labelOf(chosen) + ". Puoi usare il trackpad.");
            remember(chosen, safeName(chosen), false, false, true);
        } catch (SecurityException ignored) {
            status.setText("Permesso Bluetooth necessario per leggere la connessione.");
        }
    }

    private void maybeBecomeDiscoverable() {
        if (!registered || scanning || linkConnected || isDestroyed()) return;
        awaitingHost = true;
        pendingDiscoverable = true;
        requestDiscoverable();
    }

    @SuppressWarnings("deprecation")
    @SuppressLint("MissingPermission")
    private void requestDiscoverable() {
        if (!resumed || !pendingDiscoverable || adapter == null || !canAdvertise()) return;
        if (discoverablePromptShown) {
            pendingDiscoverable = false;
            return;
        }
        try {
            if (adapter.getScanMode() == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
                discoverablePromptShown = true;
                pendingDiscoverable = false;
                scanStatus.setText("Telefono già visibile via Bluetooth.");
                return;
            }
            Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
            startActivityForResult(intent, REQUEST_DISCOVERABLE);
            discoverablePromptShown = true;
            pendingDiscoverable = false;
        } catch (Exception e) {
            pendingDiscoverable = false;
            status.setText("Non riesco a rendere visibile il telefono. Abilita la visibilità dalle impostazioni Bluetooth.");
        }
    }

    private void startScans(boolean clear) {
        if (scanning && !clear) return;
        if (!readyForRadio()) return;
        stopHardwareScans();
        if (bleFinishedTask != null) handler.removeCallbacks(bleFinishedTask);
        final int generation = ++scanGeneration;
        if (clear) found.clear();
        addBondedDevices();
        scanning = true;
        scanPhase = Phase.BLE;
        scanStatus.setText("Ricerca BLE in corso...");
        renderDevices();
        if (!startBle()) {
            startClassic(generation);
            return;
        }
        bleFinishedTask = () -> {
            if (generation != scanGeneration || isDestroyed()) return;
            stopBleOnly();
            startClassic(generation);
        };
        handler.postDelayed(bleFinishedTask, BLE_SCAN_MS);
    }

    private void stopScans() {
        scanGeneration++;
        scanning = false;
        scanPhase = Phase.IDLE;
        if (bleFinishedTask != null) handler.removeCallbacks(bleFinishedTask);
        stopHardwareScans();
    }

    @SuppressLint("MissingPermission")
    private boolean startBle() {
        if (!canScan() || adapter == null) return false;
        BluetoothLeScanner scanner;
        try {
            scanner = adapter.getBluetoothLeScanner();
        } catch (SecurityException e) {
            scanStatus.setText("Permesso mancante per la ricerca BLE.");
            return false;
        }
        if (scanner == null) {
            scanStatus.setText("Ricerca BLE non disponibile su questo telefono.");
            return false;
        }
        try {
            ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
            scanner.startScan(Collections.emptyList(), settings, scanCallback);
            return true;
        } catch (SecurityException e) {
            scanStatus.setText("Permesso mancante per la ricerca BLE.");
            return false;
        } catch (IllegalStateException e) {
            scanStatus.setText("Il Bluetooth è spento. Attivalo per cercare la Fire Stick.");
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    private void startClassic(int generation) {
        if (generation != scanGeneration || isDestroyed() || adapter == null || !canScan()) return;
        scanPhase = Phase.CLASSIC;
        scanning = true;
        scanStatus.setText("Ricerca Bluetooth classica in corso...");
        try {
            if (adapter.isDiscovering()) adapter.cancelDiscovery();
            if (!adapter.startDiscovery()) finishScan(generation, "Ricerca Bluetooth classica non avviata.");
        } catch (SecurityException e) {
            finishScan(generation, "Permesso mancante per la ricerca Bluetooth.");
        }
    }

    private void finishScan(int generation, String error) {
        if (generation != scanGeneration || isDestroyed()) return;
        scanGeneration++;
        scanning = false;
        scanPhase = Phase.IDLE;
        stopHardwareScans();
        if (error != null) scanStatus.setText(error);
        else scanStatus.setText("Ricerca terminata: " + found.size() + (found.size() == 1 ? " dispositivo." : " dispositivi."));
        renderDevices();
        if (!linkConnected) maybeBecomeDiscoverable();
    }

    private void stopHardwareScans() {
        stopBleOnly();
        if (adapter == null) return;
        try {
            if (adapter.isDiscovering()) adapter.cancelDiscovery();
        } catch (SecurityException ignored) {
            // Discovery cannot be cancelled without scan permission.
        }
    }

    private void stopBleOnly() {
        if (adapter == null) return;
        BluetoothLeScanner scanner;
        try {
            scanner = adapter.getBluetoothLeScanner();
        } catch (SecurityException e) {
            return;
        }
        if (scanner == null) return;
        try {
            scanner.stopScan(scanCallback);
        } catch (SecurityException | IllegalStateException ignored) {
            // Scan was already stopped or Bluetooth is off.
        }
    }

    @SuppressLint("MissingPermission")
    private void addBondedDevices() {
        if (adapter == null || !canConnect()) return;
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            if (bonded == null) return;
            for (BluetoothDevice device : bonded) remember(device, null, false, false, true);
        } catch (SecurityException e) {
            status.setText("Permesso Bluetooth necessario per leggere i dispositivi associati.");
        }
    }

    private void remember(BluetoothDevice device, String advertisedName, boolean ble, boolean amazonMaker, boolean bonded) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(() -> remember(device, advertisedName, ble, amazonMaker, bonded));
            return;
        }
        if (isDestroyed() || device == null) return;
        String address;
        try {
            address = device.getAddress();
        } catch (SecurityException e) {
            return;
        }
        if (address == null || address.isEmpty()) return;
        DeviceEntry previous = found.get(address);
        String candidate = firstText(advertisedName, safeName(device));
        String label = candidate;
        if ((label == null || label.isEmpty()) && previous != null) label = previous.label;
        if (label == null || label.isEmpty()) label = "Dispositivo senza nome";
        boolean fire = amazonMaker || FireDeviceNames.looksLikeFireTv(label) || (previous != null && previous.fireLike);
        boolean isBle = ble || (previous != null && previous.ble);
        boolean isBonded = bonded || (previous != null && previous.bonded);
        found.put(address, new DeviceEntry(device, label, fire, isBle, isBonded));
        scheduleRender();
    }

    private void scheduleRender() {
        if (renderPosted) return;
        renderPosted = true;
        handler.post(() -> {
            renderPosted = false;
            if (!isDestroyed()) renderDevices();
        });
    }

    private void renderDevices() {
        devices.removeAllViews();
        if (found.isEmpty()) {
            addHint(devices, scanning
                ? "Ricerca in corso..."
                : "Nessun dispositivo trovato. La Fire Stick di solito non compare qui, perché è lei a cercare il telecomando. Con questa schermata aperta, sulla Fire Stick vai in Impostazioni > Controller e dispositivi Bluetooth > Altri dispositivi Bluetooth > Aggiungi, poi scegli il nome del telefono mostrato sopra.");
            return;
        }
        List<DeviceEntry> list = new ArrayList<>(found.values());
        list.sort((a, b) -> {
            if (a.fireLike != b.fireLike) return a.fireLike ? -1 : 1;
            if (a.bonded != b.bonded) return a.bonded ? -1 : 1;
            return a.label.compareToIgnoreCase(b.label);
        });
        int limit = Math.min(list.size(), 40);
        for (int i = 0; i < limit; i++) {
            DeviceEntry entry = list.get(i);
            Button row = button(entryLabel(entry));
            row.setOnClickListener(v -> {
                stopScans();
                connect(entry.device);
            });
            devices.addView(row);
        }
    }

    @SuppressLint("MissingPermission")
    private void connect(BluetoothDevice device) {
        if (!canConnect()) {
            requestNeededPermissions();
            return;
        }
        if (hid == null || !registered) {
            status.setText("Il profilo HID non è ancora pronto. Se il messaggio resta così, questo telefono non può fare da mouse Bluetooth.");
            return;
        }
        if (target != null && !sameDevice(target, device)) {
            try {
                hid.disconnect(target);
            } catch (SecurityException ignored) {
                // The previous host could not be disconnected.
            }
            linkConnected = false;
        }
        target = device;
        selected.setText("Selezionato: " + labelOf(device));
        int bond;
        try {
            bond = device.getBondState();
        } catch (SecurityException e) {
            status.setText("Permesso Bluetooth necessario per associare.");
            return;
        }
        if (bond != BluetoothDevice.BOND_BONDED) {
            status.setText("Associazione con " + labelOf(device) + "... Conferma la richiesta su telefono e Fire Stick.");
            try {
                if (!device.createBond()) {
                    status.setText("Associazione non avviata. Aggiungi il telefono dalla Fire Stick.");
                }
            } catch (SecurityException e) {
                status.setText("Permesso Bluetooth necessario per associare.");
            }
            return;
        }
        hidConnect(device);
    }

    @SuppressLint("MissingPermission")
    private void hidConnect(BluetoothDevice device) {
        if (hid == null || !registered || device == null) return;
        target = device;
        linkConnected = false;
        selected.setText("Selezionato: " + labelOf(device));
        status.setText("Connessione a " + labelOf(device) + "...");
        try {
            if (adapter != null && adapter.isDiscovering()) adapter.cancelDiscovery();
            if (!hid.connect(device)) {
                status.setText("Connessione rifiutata. Controlla l'associazione sulla Fire Stick.");
            }
        } catch (SecurityException e) {
            status.setText("Permesso Bluetooth necessario per connettersi.");
        }
    }

    @SuppressLint("MissingPermission")
    private void shutdownHid() {
        BluetoothHidDevice proxy = hid;
        hid = null;
        registered = false;
        registerInFlight = false;
        linkConnected = false;
        profileRequestInFlight = false;
        if (proxy == null) return;
        try {
            if (target != null) proxy.disconnect(target);
        } catch (Exception ignored) {
            // Already disconnected.
        }
        try {
            proxy.unregisterApp();
        } catch (Exception ignored) {
            // Profile already gone.
        }
        closeProfile(proxy);
    }

    private void closeProfile(BluetoothProfile proxy) {
        try {
            if (adapter != null) adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy);
        } catch (Exception ignored) {
            // Proxy was already closed.
        }
    }

    @SuppressLint("MissingPermission")
    private void report(int buttons, int dx, int dy, int wheelAmount) {
        if (!linkConnected || hid == null || target == null || !canConnect()) return;
        try {
            hid.sendReport(target, 1, new byte[] {(byte) buttons, (byte) dx, (byte) dy, (byte) wheelAmount});
        } catch (SecurityException ignored) {
            linkConnected = false;
            status.setText("Permesso Bluetooth revocato. Il trackpad è fermo.");
        }
    }

    private void motion(float dx, float dy) {
        remainderX += dx * 1.5f;
        remainderY += dy * 1.5f;
        int ix = (int) remainderX;
        int iy = (int) remainderY;
        remainderX -= ix;
        remainderY -= iy;
        while (ix != 0 || iy != 0) {
            int x = Math.max(-127, Math.min(127, ix));
            int y = Math.max(-127, Math.min(127, iy));
            report(0, x, y, 0);
            ix -= x;
            iy -= y;
        }
    }

    private void click(int buttonBits) {
        report(buttonBits, 0, 0, 0);
        report(0, 0, 0, 0);
    }

    private void wheel(int amount) {
        if (amount != 0) report(0, 0, 0, amount);
    }

    private void registerBtReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(btReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(btReceiver, filter);
        }
        receiverRegistered = true;
    }

    private void requestNeededPermissions() {
        permissionRequestLaunched = true;
        requestPermissions(requiredPermissions(), REQUEST_PERMISSIONS);
    }

    private String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[] {
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            };
        }
        return new String[] {Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean hasAllPermissions() {
        for (String permission : requiredPermissions()) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    private boolean permanentlyDenied() {
        for (String permission : requiredPermissions()) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
                && !shouldShowRequestPermissionRationale(permission)) {
                return true;
            }
        }
        return false;
    }

    private boolean canConnect() {
        return Build.VERSION.SDK_INT < 31
            || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean canScan() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean canAdvertise() {
        return Build.VERSION.SDK_INT < 31
            || checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean needsLocationServices() {
        return Build.VERSION.SDK_INT <= 30;
    }

    private boolean locationEnabled() {
        LocationManager manager = getSystemService(LocationManager.class);
        return manager != null && manager.isLocationEnabled();
    }

    private boolean bluetoothEnabled() {
        if (adapter == null) return false;
        try {
            return adapter.isEnabled();
        } catch (SecurityException e) {
            return false;
        }
    }

    private void showPhoneName() {
        phoneName.setText("Nome Bluetooth di questo telefono: " + phoneBluetoothName());
    }

    private String phoneBluetoothName() {
        if (adapter == null || !canConnect()) return "questo telefono";
        try {
            String name = adapter.getName();
            if (name != null && !name.trim().isEmpty()) return name.trim();
        } catch (SecurityException ignored) {
            return "questo telefono";
        }
        return "questo telefono";
    }

    private String labelOf(BluetoothDevice device) {
        String name = safeName(device);
        if (name != null) return name;
        try {
            String address = device.getAddress();
            if (address != null && !address.isEmpty()) return address;
        } catch (SecurityException ignored) {
            return "dispositivo";
        }
        return "dispositivo";
    }

    private String safeName(BluetoothDevice device) {
        if (device == null || !canConnect()) return null;
        try {
            String name = device.getName();
            if (name == null) return null;
            name = name.trim();
            return name.isEmpty() ? null : name;
        } catch (SecurityException e) {
            return null;
        }
    }

    private String entryLabel(DeviceEntry entry) {
        String kind = entry.fireLike ? "Fire/Amazon" : (entry.ble ? "BLE" : "Bluetooth");
        String bonded = entry.bonded ? " · associato" : "";
        String address = "";
        try {
            if (entry.device.getAddress() != null) address = "\n" + entry.device.getAddress();
        } catch (SecurityException ignored) {
            address = "";
        }
        return kind + " · " + entry.label + bonded + address;
    }

    private static boolean hasAmazonManufacturer(ScanRecord record) {
        if (record == null) return false;
        SparseArray<byte[]> data = record.getManufacturerSpecificData();
        return data != null && data.indexOfKey(FireDeviceNames.AMAZON_MANUFACTURER_ID) >= 0;
    }

    private static boolean sameDevice(BluetoothDevice left, BluetoothDevice right) {
        if (left == null || right == null) return false;
        try {
            String a = left.getAddress();
            String b = right.getAddress();
            return a != null && a.equals(b);
        } catch (SecurityException e) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private static BluetoothDevice deviceExtra(Intent intent) {
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
        }
        return intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
    }

    private static String firstText(String primary, String secondary) {
        if (primary != null && !primary.trim().isEmpty()) return primary.trim();
        if (secondary != null && !secondary.trim().isEmpty()) return secondary.trim();
        return null;
    }

    private String scanFailure(int code) {
        String reason;
        if (code == ScanCallback.SCAN_FAILED_ALREADY_STARTED) reason = "già avviata";
        else if (code == ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED) reason = "registrazione fallita";
        else if (code == ScanCallback.SCAN_FAILED_INTERNAL_ERROR) reason = "errore interno";
        else if (code == ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED) reason = "BLE non supportato";
        else if (code == ScanCallback.SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES) reason = "Bluetooth occupato";
        else if (code == 6) reason = "troppe ricerche, riprova tra poco";
        else reason = "codice " + code;
        return "Scansione BLE non riuscita: " + reason + ".";
    }

    private void openBluetoothSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        } catch (Exception e) {
            status.setText("Apri le impostazioni Bluetooth del telefono.");
        }
    }

    private void openAppSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            status.setText("Apri le impostazioni dell'app e abilita i permessi Bluetooth.");
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable bg(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private TextView text(String content, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextColor(ink);
        view.setTextSize(size);
        if (bold) view.setTypeface(null, Typeface.BOLD);
        view.setPadding(0, dp(6), 0, dp(6));
        return view;
    }

    private Button button(String label) {
        Button view = new Button(this);
        view.setText(label);
        view.setAllCaps(false);
        view.setTextColor(ink);
        return view;
    }

    private void addHint(LinearLayout layout, String message) {
        layout.addView(text(message, 13, false));
    }

    private static final class DeviceEntry {
        final BluetoothDevice device;
        final String label;
        final boolean fireLike;
        final boolean ble;
        final boolean bonded;

        DeviceEntry(BluetoothDevice device, String label, boolean fireLike, boolean ble, boolean bonded) {
            this.device = device;
            this.label = label;
            this.fireLike = fireLike;
            this.ble = ble;
            this.bonded = bonded;
        }
    }
}
