package jp.kshoji.blemidi.central;

import static android.bluetooth.BluetoothDevice.DEVICE_TYPE_DUAL;
import static android.bluetooth.BluetoothDevice.DEVICE_TYPE_LE;
import static android.bluetooth.le.ScanSettings.CALLBACK_TYPE_ALL_MATCHES;
import static android.bluetooth.le.ScanSettings.CALLBACK_TYPE_FIRST_MATCH;
import static android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY;
import static jp.kshoji.blemidi.util.BleMidiDeviceUtils.getBleMidiScanFilters;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import java.util.List;
import java.util.Set;

import jp.kshoji.blemidi.device.MidiInputDevice;
import jp.kshoji.blemidi.device.MidiOutputDevice;
import jp.kshoji.blemidi.listener.OnMidiDeviceAttachedListener;
import jp.kshoji.blemidi.listener.OnMidiDeviceDetachedListener;
import jp.kshoji.blemidi.listener.OnMidiScanStatusListener;

@SuppressLint("MissingPermission")
public final class BleMidiCentralProvider {
    private final BluetoothAdapter bluetoothAdapter;
    private final Context context;
    private final Handler handler;
    private final BleMidiCallback midiCallback;

    public BleMidiCentralProvider(@NonNull final Context context) throws UnsupportedOperationException, SecurityException {
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            throw new UnsupportedOperationException("Bluetooth LE not supported on this device.");
        }
        bluetoothAdapter = ((BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
        if (bluetoothAdapter == null) {
            throw new UnsupportedOperationException("Bluetooth is not available.");
        }
        if (!bluetoothAdapter.isEnabled()) {
            throw new UnsupportedOperationException("Bluetooth is disabled.");
        }
        this.context = context;
        this.midiCallback = new BleMidiCallback(context);
        this.handler = new Handler(context.getMainLooper());
    }

    public void connectGatt(BluetoothDevice bluetoothDevice) {
        bluetoothDevice.connectGatt(context, true, midiCallback);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) throws SecurityException {
            super.onScanResult(callbackType, result);
            if (callbackType == CALLBACK_TYPE_ALL_MATCHES) {
                final BluetoothDevice bluetoothDevice = result.getDevice();
                if ((bluetoothDevice.getType() != DEVICE_TYPE_LE) &&
                        (bluetoothDevice.getType() != DEVICE_TYPE_DUAL))
                    return;
                if (!midiCallback.isConnected(bluetoothDevice)) {
                    handler.post(() -> connectGatt(bluetoothDevice));
                    bluetoothAdapter.getBluetoothLeScanner().stopScan(scanCallback);
                }
            }
        }
    };

    private volatile boolean isScanning = false;

    public void setAutoStartInputDevice(boolean enable) {
        midiCallback.setAutoStartDevice(enable);
    }

    public void setRequestPairing(boolean needsPairing) {
        midiCallback.setNeedsBonding(needsPairing);
    }

    private Runnable stopScanRunnable = null;

    public void startScanDevice(int timeoutInMilliSeconds) throws SecurityException {
        BluetoothLeScanner bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        List<ScanFilter> scanFilters = getBleMidiScanFilters(context);
        ScanSettings scanSettings = new ScanSettings.Builder()
                .setScanMode(SCAN_MODE_LOW_LATENCY)
                .setCallbackType(CALLBACK_TYPE_FIRST_MATCH).build();
        bluetoothLeScanner.startScan(scanFilters, scanSettings, scanCallback);
        isScanning = true;
        if (onMidiScanStatusListener != null) {
            onMidiScanStatusListener.onMidiScanStatusChanged(isScanning);
        }
        if (stopScanRunnable != null) {
            handler.removeCallbacks(stopScanRunnable);
        }
        if (isScanning && timeoutInMilliSeconds > 0) {
            stopScanRunnable = () -> {
                stopScanDevice();
                isScanning = false;
                if (onMidiScanStatusListener != null) {
                    onMidiScanStatusListener.onMidiScanStatusChanged(isScanning);
                }
            };
            handler.postDelayed(stopScanRunnable, timeoutInMilliSeconds);
        }
    }

    public void stopScanDevice() throws SecurityException {
        try {
            final BluetoothLeScanner bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
            bluetoothLeScanner.flushPendingScanResults(scanCallback);
            bluetoothLeScanner.stopScan(scanCallback);
        } catch (Throwable ignored) {
            // NullPointerException on Bluetooth is OFF
        }
        if (stopScanRunnable != null) {
            handler.removeCallbacks(stopScanRunnable);
            stopScanRunnable = null;
        }
        isScanning = false;
        if (onMidiScanStatusListener != null) {
            onMidiScanStatusListener.onMidiScanStatusChanged(isScanning);
        }
    }

    public void disconnectDevice(@NonNull MidiInputDevice midiInputDevice) {
        midiCallback.disconnectDevice(midiInputDevice);
    }

    public void disconnectDevice(@NonNull MidiOutputDevice midiOutputDevice) {
        midiCallback.disconnectDevice(midiOutputDevice);
    }

    @NonNull
    public Set<MidiInputDevice> getMidiInputDevices() {
        return midiCallback.getMidiInputDevices();
    }

    @NonNull
    public Set<MidiOutputDevice> getMidiOutputDevices() {
        return midiCallback.getMidiOutputDevices();
    }

    private OnMidiScanStatusListener onMidiScanStatusListener;

    public void setOnMidiScanStatusListener(@Nullable OnMidiScanStatusListener onMidiScanStatusListener) {
        this.onMidiScanStatusListener = onMidiScanStatusListener;
    }

    public void setOnMidiDeviceAttachedListener(@Nullable OnMidiDeviceAttachedListener midiDeviceAttachedListener) {
        this.midiCallback.setOnMidiDeviceAttachedListener(midiDeviceAttachedListener);
    }

    public void setOnMidiDeviceDetachedListener(@Nullable OnMidiDeviceDetachedListener midiDeviceDetachedListener) {
        this.midiCallback.setOnMidiDeviceDetachedListener(midiDeviceDetachedListener);
    }

    public void terminate() {
        midiCallback.terminate();
        stopScanDevice();
    }
}
