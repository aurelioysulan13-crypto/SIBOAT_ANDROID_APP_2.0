package com.siboat.controller;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.Queue;
import java.util.UUID;

/** Native Bluetooth Low Energy transport used by the dashboard's SIBOAT_ANDROID_BLE bridge. */
public final class BleBridge {
    public static final int PERMISSION_REQUEST_CODE = 4103;

    private static final UUID SERVICE_UUID = UUID.fromString("e9b10000-9b5d-4c79-a012-5349424f4154");
    private static final UUID WRITE_UUID = UUID.fromString("e9b10001-9b5d-4c79-a012-5349424f4154");
    private static final UUID NOTIFY_UUID = UUID.fromString("e9b10002-9b5d-4c79-a012-5349424f4154");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final int SCAN_TIMEOUT_MS = 15000;
    private static final int CHUNK_SIZE = 20;
    private static final int MAX_QUEUED_CHUNKS = 100;

    private final Activity activity;
    private final WebView webView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Queue<byte[]> writeQueue = new ArrayDeque<>();

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic writeCharacteristic;
    private BluetoothGattCharacteristic notifyCharacteristic;
    private ScanCallback scanCallback;
    private String requestedNamePrefix = "SIBOAT MAIN";
    private boolean permissionPending;
    private boolean scanning;
    private boolean connected;
    private boolean writeBusy;
    private boolean destroyed;
    private boolean hasEverConnected;

    public BleBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        BluetoothManager manager = (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
    }

    @JavascriptInterface
    public boolean isSupported() {
        return !destroyed && activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
                && adapter != null;
    }

    @JavascriptInterface
    public boolean isConnected() {
        return connected && gatt != null;
    }

    @JavascriptInterface
    public boolean connect(String namePrefix) {
        if (!isSupported()) {
            sendState("error", "This Android device does not support Bluetooth Low Energy.");
            return false;
        }
        requestedNamePrefix = (namePrefix == null || namePrefix.trim().isEmpty())
                ? "SIBOAT MAIN" : namePrefix.trim();
        permissionPending = true;
        activity.runOnUiThread(() -> {
            if (destroyed) return;
            if (!hasRequiredPermissions()) {
                requestPermissions();
            } else {
                permissionPending = false;
                startScan();
            }
        });
        return true;
    }

    public void onPermissionResult(boolean granted) {
        if (destroyed || !permissionPending) return;
        if (!granted || !hasRequiredPermissions()) {
            permissionPending = false;
            sendState("error", "Nearby-device permission was denied. Allow it in Android Settings and try again.");
            return;
        }
        permissionPending = false;
        startScan();
    }

    @JavascriptInterface
    public synchronized boolean send(String text) {
        if (destroyed || !connected || gatt == null || writeCharacteristic == null || text == null) {
            return false;
        }
        byte[] all = text.getBytes(StandardCharsets.UTF_8);
        if (all.length == 0 || all.length > 360) return false;

        int chunks = (all.length + CHUNK_SIZE - 1) / CHUNK_SIZE;
        if (writeQueue.size() + chunks > MAX_QUEUED_CHUNKS) return false;
        for (int offset = 0; offset < all.length; offset += CHUNK_SIZE) {
            int length = Math.min(CHUNK_SIZE, all.length - offset);
            byte[] part = new byte[length];
            System.arraycopy(all, offset, part, 0, length);
            writeQueue.offer(part);
        }
        handler.post(this::drainWrites);
        return true;
    }

    @JavascriptInterface
    public void disconnect() {
        handler.post(() -> closeConnection(true));
    }

    private boolean hasRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_SCAN)
                    == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(activity, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(activity, new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            }, PERMISSION_REQUEST_CODE);
        } else {
            ActivityCompat.requestPermissions(activity, new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION
            }, PERMISSION_REQUEST_CODE);
        }
    }

    private void startScan() {
        if (destroyed) return;
        if (!hasRequiredPermissions()) {
            sendState("error", "Nearby-device permission is required to scan for SIBOAT MAIN.");
            return;
        }
        if (adapter == null || !adapter.isEnabled()) {
            sendState("error", "Turn on Bluetooth in Android Quick Settings, then try again.");
            return;
        }

        stopScan();
        closeConnection(false);
        hasEverConnected = false;
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            sendState("error", "Android could not start Bluetooth scanning. Check that Bluetooth is on.");
            return;
        }

        scanning = true;
        sendState("scanning", "Searching for " + requestedNamePrefix);
        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                BluetoothDevice device = result == null ? null : result.getDevice();
                if (device == null || destroyed || !scanning) return;
                String name = null;
                try {
                    if (result.getScanRecord() != null) name = result.getScanRecord().getDeviceName();
                    if (name == null || name.isEmpty()) name = device.getName();
                } catch (SecurityException ignored) {
                }
                if (name == null || !name.toLowerCase(Locale.ROOT)
                        .startsWith(requestedNamePrefix.toLowerCase(Locale.ROOT))) return;

                stopScan();
                sendState("connecting", name);
                try {
                    gatt = device.connectGatt(activity, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
                    if (gatt == null) sendState("error", "Android could not open a GATT connection to " + name + ".");
                } catch (SecurityException exception) {
                    sendState("error", "Android denied Bluetooth connection permission.");
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                scanning = false;
                sendState("error", "Bluetooth scan failed (code " + errorCode + "). Check Nearby devices permission and Bluetooth state.");
            }
        };

        try {
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            scanner.startScan(null, settings, scanCallback);
            handler.postDelayed(() -> {
                if (scanning) {
                    stopScan();
                    sendState("error", "No device named " + requestedNamePrefix + " was found. Power on SIBOAT MAIN and retry.");
                }
            }, SCAN_TIMEOUT_MS);
        } catch (SecurityException exception) {
            scanning = false;
            sendState("error", "Android denied Bluetooth scan permission.");
        } catch (Exception exception) {
            scanning = false;
            sendState("error", "Could not start Bluetooth scan: " + safeMessage(exception));
        }
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt callbackGatt, int status, int newState) {
            handler.post(() -> {
                if (destroyed || callbackGatt != gatt) return;
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    try {
                        boolean started = callbackGatt.discoverServices();
                        if (!started) failConnection("Bluetooth connected, but service discovery could not start.");
                    } catch (SecurityException exception) {
                        failConnection("Android denied permission to inspect the SIBOAT Bluetooth service.");
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    boolean previouslyConnected = connected || hasEverConnected;
                    connected = false;
                    writeBusy = false;
                    synchronized (BleBridge.this) { writeQueue.clear(); }
                    if (callbackGatt == gatt) {
                        gatt = null;
                        writeCharacteristic = null;
                        notifyCharacteristic = null;
                    }
                    try { callbackGatt.close(); } catch (Exception ignored) { }
                    if (previouslyConnected) sendState("disconnected", "SIBOAT Bluetooth disconnected.");
                    else if (!destroyed) sendState("error", "Bluetooth connection failed (GATT status " + status + ").");
                }
            });
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt callbackGatt, int status) {
            handler.post(() -> {
                if (destroyed || callbackGatt != gatt) return;
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    failConnection("SIBOAT Bluetooth service discovery failed.");
                    return;
                }
                BluetoothGattService service = callbackGatt.getService(SERVICE_UUID);
                if (service == null) {
                    failConnection("SIBOAT MAIN does not expose the expected control service UUID.");
                    return;
                }
                writeCharacteristic = service.getCharacteristic(WRITE_UUID);
                notifyCharacteristic = service.getCharacteristic(NOTIFY_UUID);
                if (writeCharacteristic == null || notifyCharacteristic == null) {
                    failConnection("SIBOAT MAIN is missing its expected BLE write or notification characteristic.");
                    return;
                }
                try {
                    if (!callbackGatt.setCharacteristicNotification(notifyCharacteristic, true)) {
                        failConnection("Could not enable SIBOAT Bluetooth notifications.");
                        return;
                    }
                    BluetoothGattDescriptor descriptor = notifyCharacteristic.getDescriptor(CCCD_UUID);
                    if (descriptor == null) {
                        failConnection("The SIBOAT BLE notification descriptor is missing.");
                        return;
                    }
                    boolean started;
                    if (Build.VERSION.SDK_INT >= 33) {
                        started = callbackGatt.writeDescriptor(descriptor,
                                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS;
                    } else {
                        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        started = callbackGatt.writeDescriptor(descriptor);
                    }
                    if (!started) failConnection("Android could not enable SIBOAT BLE notifications.");
                } catch (SecurityException exception) {
                    failConnection("Android denied Bluetooth GATT permission.");
                }
            });
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt callbackGatt, BluetoothGattDescriptor descriptor, int status) {
            handler.post(() -> {
                if (destroyed || callbackGatt != gatt) return;
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    connected = true;
                    hasEverConnected = true;
                    sendState("connected", "SIBOAT MAIN Bluetooth control link is connected.");
                } else {
                    failConnection("SIBOAT Bluetooth notification setup failed (GATT status " + status + ").");
                }
            });
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt callbackGatt,
                                            BluetoothGattCharacteristic characteristic) {
            byte[] value;
            try { value = characteristic.getValue(); } catch (Exception ignored) { return; }
            handleNotification(callbackGatt, characteristic, value);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt callbackGatt,
                                            BluetoothGattCharacteristic characteristic, byte[] value) {
            handleNotification(callbackGatt, characteristic, value);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt callbackGatt,
                                          BluetoothGattCharacteristic characteristic, int status) {
            handler.post(() -> {
                if (callbackGatt != gatt) return;
                synchronized (BleBridge.this) {
                    if (!writeQueue.isEmpty()) writeQueue.poll();
                    writeBusy = false;
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    synchronized (BleBridge.this) { writeQueue.clear(); }
                    sendState("error", "A SIBOAT Bluetooth command could not be written (GATT status " + status + ").");
                } else {
                    drainWrites();
                }
            });
        }
    };

    private void handleNotification(BluetoothGatt callbackGatt,
                                    BluetoothGattCharacteristic characteristic, byte[] value) {
        if (value == null || destroyed || characteristic == null || !NOTIFY_UUID.equals(characteristic.getUuid())) return;
        final String text = new String(value, StandardCharsets.UTF_8);
        handler.post(() -> {
            if (callbackGatt == gatt && connected) {
                String js = "window.SIBOAT_ANDROID_BLE_DATA && window.SIBOAT_ANDROID_BLE_DATA(" +
                        JSONObject.quote(text) + ");";
                webView.evaluateJavascript(js, null);
            }
        });
    }

    private synchronized void drainWrites() {
        if (destroyed || !connected || gatt == null || writeCharacteristic == null || writeBusy || writeQueue.isEmpty()) return;
        byte[] payload = writeQueue.peek();
        int properties = writeCharacteristic.getProperties();
        int writeType = (properties & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        boolean started = false;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                started = gatt.writeCharacteristic(writeCharacteristic, payload, writeType)
                        == BluetoothStatusCodes.SUCCESS;
            } else {
                writeCharacteristic.setWriteType(writeType);
                writeCharacteristic.setValue(payload);
                started = gatt.writeCharacteristic(writeCharacteristic);
            }
        } catch (SecurityException ignored) {
        }
        if (started) {
            writeBusy = true;
        } else {
            writeQueue.clear();
            writeBusy = false;
            sendState("error", "Android could not send a SIBOAT Bluetooth command.");
        }
    }

    private void stopScan() {
        if (!scanning) return;
        scanning = false;
        if (scanner != null && scanCallback != null) {
            try { scanner.stopScan(scanCallback); } catch (Exception ignored) { }
        }
        scanCallback = null;
    }

    private void failConnection(String message) {
        stopScan();
        sendState("error", message);
        closeConnection(false);
    }

    private void closeConnection(boolean notifyPage) {
        stopScan();
        connected = false;
        writeBusy = false;
        synchronized (this) { writeQueue.clear(); }
        BluetoothGatt oldGatt = gatt;
        gatt = null;
        writeCharacteristic = null;
        notifyCharacteristic = null;
        if (oldGatt != null) {
            try { oldGatt.disconnect(); } catch (Exception ignored) { }
            try { oldGatt.close(); } catch (Exception ignored) { }
        }
        if (notifyPage && !destroyed) sendState("disconnected", "SIBOAT Bluetooth disconnected.");
    }

    public void destroy() {
        destroyed = true;
        permissionPending = false;
        closeConnection(false);
        adapter = null;
    }

    private void sendState(String name, String detail) {
        if (destroyed || webView == null) return;
        String script = "window.SIBOAT_ANDROID_BLE_STATE && window.SIBOAT_ANDROID_BLE_STATE(" +
                JSONObject.quote(name == null ? "" : name) + "," +
                JSONObject.quote(detail == null ? "" : detail) + ");";
        webView.post(() -> webView.evaluateJavascript(script, null));
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null ? exception.getClass().getSimpleName() : message;
    }
}
