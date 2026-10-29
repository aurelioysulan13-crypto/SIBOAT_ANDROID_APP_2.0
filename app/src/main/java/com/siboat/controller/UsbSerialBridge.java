package com.siboat.controller;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.annotation.Nullable;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Native Android bridge for the SIBOAT Monitor serial console. */
public final class UsbSerialBridge {
    private static final String ACTION_USB_PERMISSION = "com.siboat.controller.USB_PERMISSION";
    private final Context context;
    private final WebView webView;
    private final UsbManager usbManager;
    private UsbSerialPort port;
    private UsbDevice currentDevice;
    private Thread readThread;
    private volatile boolean connected = false;
    private volatile boolean destroyed = false;
    private int pendingBaud = 115200;

    public UsbSerialBridge(Context context, WebView webView) {
        this.context = context.getApplicationContext();
        this.webView = webView;
        this.usbManager = (UsbManager) this.context.getSystemService(Context.USB_SERVICE);
        IntentFilter permissionFilter = new IntentFilter(ACTION_USB_PERMISSION);
        IntentFilter deviceFilter = new IntentFilter();
        deviceFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        deviceFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            this.context.registerReceiver(permissionReceiver, permissionFilter, Context.RECEIVER_NOT_EXPORTED);
            this.context.registerReceiver(deviceReceiver, deviceFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            this.context.registerReceiver(permissionReceiver, permissionFilter);
            this.context.registerReceiver(deviceReceiver, deviceFilter);
        }
    }

    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context receiverContext, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction()) || destroyed) return;
            UsbDevice device;
            if (Build.VERSION.SDK_INT >= 33) {
                device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
            } else {
                //noinspection deprecation
                device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            }
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (granted && device != null) openDevice(device, pendingBaud);
            else sendState(false, "USB PERMISSION DENIED");
        }
    };

    private final BroadcastReceiver deviceReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context receiverContext, Intent intent) {
            if (destroyed) return;
            String action = intent.getAction();
            UsbDevice device;
            if (Build.VERSION.SDK_INT >= 33) {
                device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
            } else {
                //noinspection deprecation
                device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            }
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                if (device != null && currentDevice != null && currentDevice.equals(device)) {
                    disconnect();
                    sendState(false, "USB DETACHED");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                sendState(connected, connected ? "USB ONLINE" : "USB DEVICE ATTACHED");
                sendDeviceChange();
            }
        }
    };

    @JavascriptInterface
    public String getDevices() {
        StringBuilder json = new StringBuilder("[");
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        for (int i = 0; i < drivers.size(); i++) {
            UsbSerialDriver driver = drivers.get(i);
            UsbDevice device = driver.getDevice();
            if (i > 0) json.append(',');
            json.append("{\"index\":").append(i)
                    .append(",\"vendorId\":").append(device.getVendorId())
                    .append(",\"productId\":").append(device.getProductId())
                    .append(",\"deviceName\":\"").append(jsonEscape(device.getDeviceName()))
                    .append("\",\"label\":\"").append(jsonEscape(device.getProductName())).append("\"}");
        }
        return json.append(']').toString();
    }

    @JavascriptInterface
    public synchronized boolean connect(String indexOrEmpty, int baud) {
        if (destroyed) return false;
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        if (drivers.isEmpty()) {
            sendState(false, "NO USB SERIAL DEVICE");
            return false;
        }
        int index = 0;
        try {
            if (indexOrEmpty != null && !indexOrEmpty.trim().isEmpty()) index = Integer.parseInt(indexOrEmpty.trim());
        } catch (NumberFormatException ignored) { index = 0; }
        if (index < 0 || index >= drivers.size()) index = 0;
        UsbDevice device = drivers.get(index).getDevice();
        pendingBaud = baud > 0 ? baud : 115200;
        if (!usbManager.hasPermission(device)) {
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    context, 0,
                    new Intent(ACTION_USB_PERMISSION).setPackage(context.getPackageName()),
                    Build.VERSION.SDK_INT >= 31
                            ? PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                            : PendingIntent.FLAG_UPDATE_CURRENT
            );
            try {
                usbManager.requestPermission(device, pendingIntent);
            } catch (Exception exception) {
                sendState(false, "USB PERMISSION REQUEST FAILED");
                sendText("USB permission request failed: " + safeText(exception.getMessage()));
                return false;
            }
            sendState(false, "REQUESTING USB PERMISSION");
            return true;
        }
        return openDevice(device, pendingBaud);
    }

    private synchronized boolean openDevice(UsbDevice device, int baud) {
        if (destroyed || device == null) return false;
        disconnect();
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        UsbSerialDriver selected = null;
        for (UsbSerialDriver driver : drivers) {
            if (driver.getDevice().equals(device)) { selected = driver; break; }
        }
        if (selected == null || selected.getPorts().isEmpty()) {
            sendState(false, "USB SERIAL DRIVER NOT FOUND");
            return false;
        }
        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) {
            sendState(false, "USB DEVICE OPEN FAILED");
            return false;
        }
        try {
            port = selected.getPorts().get(0);
            port.open(connection);
            port.setParameters(baud > 0 ? baud : 115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            port.setDTR(true);
            port.setRTS(true);
            currentDevice = device;
            connected = true;
            sendState(true, "USB ONLINE · " + (baud > 0 ? baud : 115200));
            sendDeviceChange();
            readThread = new Thread(this::readLoop, "SIBOAT-USB-Serial");
            readThread.setDaemon(true);
            readThread.start();
            return true;
        } catch (Exception exception) {
            try { port.close(); } catch (Exception ignored) { }
            port = null;
            currentDevice = null;
            connected = false;
            sendState(false, "USB OPEN ERROR");
            sendText("USB open error: " + safeText(exception.getMessage()));
            return false;
        }
    }

    private void readLoop() {
        byte[] buffer = new byte[1024];
        while (connected && !destroyed && port != null) {
            try {
                int count = port.read(buffer, 1000);
                if (count > 0) sendText(new String(buffer, 0, count, StandardCharsets.UTF_8));
            } catch (Exception exception) {
                if (connected && !destroyed) sendText("USB read error: " + safeText(exception.getMessage()));
                break;
            }
        }
        if (connected && !destroyed) {
            connected = false;
            sendState(false, "USB OFFLINE");
        }
    }

    @JavascriptInterface
    public synchronized void disconnect() {
        connected = false;
        if (readThread != null && readThread != Thread.currentThread()) {
            try { readThread.interrupt(); } catch (Exception ignored) { }
        }
        readThread = null;
        if (port != null) {
            try { port.close(); } catch (Exception ignored) { }
        }
        port = null;
        currentDevice = null;
    }

    @JavascriptInterface public boolean isConnected() { return connected; }
    public boolean isConnectedInternal() { return connected; }

    public void destroy() {
        destroyed = true;
        disconnect();
        try { context.unregisterReceiver(permissionReceiver); } catch (Exception ignored) { }
        try { context.unregisterReceiver(deviceReceiver); } catch (Exception ignored) { }
    }

    private void sendText(@Nullable String value) {
        if (destroyed) return;
        final String js = "window.SIBOAT_ANDROID_SERIAL_RX && window.SIBOAT_ANDROID_SERIAL_RX(" + jsQuote(value) + ");";
        webView.post(() -> webView.evaluateJavascript(js, null));
    }

    private void sendState(boolean ok, String label) {
        if (destroyed) return;
        final String js = "window.SIBOAT_ANDROID_SERIAL_STATE && window.SIBOAT_ANDROID_SERIAL_STATE(" +
                (ok ? "true" : "false") + "," + jsQuote(label) + ");";
        webView.post(() -> webView.evaluateJavascript(js, null));
    }

    private void sendDeviceChange() {
        if (destroyed) return;
        webView.post(() -> webView.evaluateJavascript("window.dispatchEvent(new Event('SIBOAT_USB_DEVICE_CHANGE'));", null));
    }

    private String jsQuote(String value) {
        if (value == null) value = "";
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'")
                .replace("\n", "\\n").replace("\r", "\\r")
                .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029") + "'";
    }

    private String jsonEscape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n")
                .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
    }

    private String safeText(String value) { return value == null ? "unknown error" : value; }
}
