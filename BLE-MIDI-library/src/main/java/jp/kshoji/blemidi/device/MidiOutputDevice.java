package jp.kshoji.blemidi.device;

import android.support.annotation.NonNull;

import java.io.ByteArrayOutputStream;

/**
 * Represents BLE MIDI Output Device
 *
 * @author K.Shoji
 */
public abstract class MidiOutputDevice {

    public static final int MAX_TIMESTAMP = 8192;

    final ByteArrayOutputStream transferDataStream = new ByteArrayOutputStream();

    /** Scratch space for one MIDI message, only ever touched while holding the
     * {@link #transferDataStream} monitor. */
    private final byte[] messageBuffer = new byte[3];

    /**
     * Transfer data
     *
     * @param writeBuffer byte array to write
     * @return true if transfer succeed
     */
    protected abstract boolean transferData(@NonNull byte[] writeBuffer);

    /**
     * Obtains the device name
     *
     * @return device name
     */
    @NonNull
    public abstract String getDeviceName();

    /**
     * Obtains the manufacturer name
     *
     * @return manufacturer name
     */
    @NonNull
    public abstract String getManufacturer();

    /**
     * Obtains the model name
     *
     * @return model name
     */
    @NonNull
    public abstract String getModel();

    /**
     * Obtains the device address
     *
     * @return device address
     */
    @NonNull
    public abstract String getDeviceAddress();

    /**
     * Obtains buffer size
     * @return buffer size
     */
    public abstract int getBufferSize();

    @NonNull
    @Override
    public final String toString() {
        return getDeviceName();
    }

    volatile boolean transferDataThreadAlive;
    volatile boolean isRunning;
    final Thread transferDataThread = new Thread(new Runnable() {
        @Override
        public void run() {
            transferDataThreadAlive = true;

            while (true) {
                // running
                while (transferDataThreadAlive && isRunning) {
                    synchronized (transferDataStream) {
                        if (writtenDataCount > 0) {
                            if (transferData(transferDataStream.toByteArray())) {
                                // reset the stream if transfer succeed
                                transferDataStream.reset();
                                writtenDataCount = 0;
                            }
                        }
                    }

                    try {
                        Thread.sleep(10); // BluetoothGatt.WRITE_CHARACTERISTIC_TIME_TO_WAIT
                    } catch (InterruptedException ignored) {
                    }
                }

                if (!transferDataThreadAlive) {
                    break;
                }

                // stopping
                while (!transferDataThreadAlive && !isRunning) {
                    // sleep until interrupt
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException ignored) {
                    }
                }

                if (!transferDataThreadAlive) {
                    break;
                }
            }
        }
    });

    protected MidiOutputDevice() {
        transferDataThread.start();
    }

    /**
     * Starts using the device
     */
    public final void start() {
        if (!transferDataThreadAlive) {
            return;
        }
        isRunning = true;
        transferDataThread.interrupt();
    }

    /**
     * Stops using the device
     */
    public final void stop() {
        if (!transferDataThreadAlive) {
            return;
        }
        isRunning = false;
        transferDataThread.interrupt();
    }

    /**
     * Terminates the device instance
     */
    public final void terminate() {
        transferDataThreadAlive = false;
        isRunning = false;
        transferDataThread.interrupt();
    }

    transient int writtenDataCount;

    /**
     * Queues one MIDI message. Writes the bytes straight into the transfer stream so a
     * real time caller allocates nothing per message.
     *
     * @param timestamp 0-8191, the BLE MIDI millisecond timestamp for this message
     * @param byte1 the first byte
     * @param byte2 the second byte, ignored when count is below 2
     * @param byte3 the third byte, ignored when count is below 3
     * @param count how many of the three bytes are part of the message, 1-3
     */
    private void storeTransferData(long timestamp, int byte1, int byte2, int byte3, int count) {
        if (!transferDataThreadAlive || !isRunning) {
            return;
        }

        synchronized (transferDataStream) {
            if (writtenDataCount == 0) {
                // Store timestamp high
                transferDataStream.write((byte) (0x80 | ((timestamp >> 7) & 0x3f)));
                writtenDataCount++;
            }
            // timestamp low
            transferDataStream.write((byte) (0x80 | (timestamp & 0x7f)));
            writtenDataCount++;

            messageBuffer[0] = (byte) byte1;
            messageBuffer[1] = (byte) byte2;
            messageBuffer[2] = (byte) byte3;
            transferDataStream.write(messageBuffer, 0, count);
            writtenDataCount += count;

            transferDataThread.interrupt();
        }
    }

    /**
     * Converts a {@link System#nanoTime()} stamp into this device's 13 bit millisecond
     * timestamp, keeping how far ahead of now the stamp is so scheduled messages carry
     * their intended timing instead of their enqueue time.
     *
     * @param timestampNanos a {@link System#nanoTime()} value, or 0 for now
     * @return 0-8191
     */
    private static long timestampOf(long timestampNanos) {
        long millis = System.currentTimeMillis();
        if (timestampNanos != 0L) {
            millis += (timestampNanos - System.nanoTime()) / 1000000L;
        }
        return ((millis % MAX_TIMESTAMP) + MAX_TIMESTAMP) % MAX_TIMESTAMP;
    }

    /**
     * Sends MIDI message to output device.
     *
     * @param byte1 the first byte
     */
    private void sendMidiMessage(int byte1) {
        storeTransferData(timestampOf(0L), byte1, 0, 0, 1);
    }

    /**
     * Sends MIDI message to output device.
     *
     * @param byte1 the first byte
     * @param byte2 the second byte
     */
    private void sendMidiMessage(int byte1, int byte2) {
        storeTransferData(timestampOf(0L), byte1, byte2, 0, 2);
    }

    /**
     * Sends MIDI message to output device.
     *
     * @param byte1 the first byte
     * @param byte2 the second byte
     * @param byte3 the third byte
     */
    private void sendMidiMessage(int byte1, int byte2, int byte3) {
        storeTransferData(timestampOf(0L), byte1, byte2, byte3, 3);
    }

    /**
     * SysEx
     *
     * @param systemExclusive : start with 'F0', and end with 'F7'
     */
    public final void sendMidiSystemExclusive(@NonNull byte[] systemExclusive) {
        byte[] timestampAddedSystemExclusive = new byte[systemExclusive.length + 2];
        System.arraycopy(systemExclusive, 0, timestampAddedSystemExclusive, 1, systemExclusive.length);

        long timestamp = System.currentTimeMillis() % MAX_TIMESTAMP;

        // extend a byte for timestamp LSB, before the last byte('F7')
        timestampAddedSystemExclusive[systemExclusive.length + 1] = systemExclusive[systemExclusive.length - 1];
        // set first byte to timestamp LSB
        timestampAddedSystemExclusive[0] = (byte) (0x80 | (timestamp & 0x7f));

        // split into bufferSize bytes. BLE can't send more than (bufferSize: MTU - 3) bytes.
        int bufferSize = getBufferSize();
        byte[] writeBuffer = new byte[bufferSize];
        for (int i = 0; i < timestampAddedSystemExclusive.length; i += (bufferSize - 1)) {
            // Don't send 0xF7 timestamp LSB inside of SysEx(MIDI parser will fail) 0x7f -> 0x7e
            timestampAddedSystemExclusive[systemExclusive.length] = (byte) (0x80 | (timestamp & 0x7e));

            if (i + (bufferSize - 1) <= timestampAddedSystemExclusive.length) {
                System.arraycopy(timestampAddedSystemExclusive, i, writeBuffer, 1, (bufferSize - 1));
            } else {
                // last message
                writeBuffer = new byte[timestampAddedSystemExclusive.length - i + 1];

                System.arraycopy(timestampAddedSystemExclusive, i, writeBuffer, 1, timestampAddedSystemExclusive.length - i);
            }

            // timestamp MSB
            writeBuffer[0] = (byte) (0x80 | ((timestamp >> 7) & 0x3f));

            // immediately transfer data
            while (true) {
                if (transferData(writeBuffer)) {
                    break;
                }

                try {
                    Thread.sleep(10); // BluetoothGatt.WRITE_CHARACTERISTIC_TIME_TO_WAIT
                } catch (InterruptedException ignored) {
                }
            }

            timestamp = System.currentTimeMillis() % MAX_TIMESTAMP;
        }
    }

    /**
     * Note-off
     *
     * @param channel 0-15
     * @param note 0-127
     * @param velocity 0-127
     */
    public final void sendMidiNoteOff(int channel, int note, int velocity) {
        sendMidiMessage(0x80 | (channel & 0xf), note, velocity);
    }

    /**
     * Note-on
     *
     * @param channel 0-15
     * @param note 0-127
     * @param velocity 0-127
     */
    public final void sendMidiNoteOn(int channel, int note, int velocity) {
        sendMidiMessage(0x90 | (channel & 0xf), note, velocity);
    }

    /**
     * Note-off at a scheduled time
     *
     * @param channel 0-15
     * @param note 0-127
     * @param velocity 0-127
     * @param timestampNanos a {@link System#nanoTime()} value, or 0 for now
     */
    public final void sendMidiNoteOff(int channel, int note, int velocity, long timestampNanos) {
        storeTransferData(timestampOf(timestampNanos),
                0x80 | (channel & 0xf), note, velocity, 3);
    }

    /**
     * Note-on at a scheduled time
     *
     * @param channel 0-15
     * @param note 0-127
     * @param velocity 0-127
     * @param timestampNanos a {@link System#nanoTime()} value, or 0 for now
     */
    public final void sendMidiNoteOn(int channel, int note, int velocity, long timestampNanos) {
        storeTransferData(timestampOf(timestampNanos),
                0x90 | (channel & 0xf), note, velocity, 3);
    }

    /**
     * Control Change at a scheduled time
     *
     * @param channel 0-15
     * @param function 0-127
     * @param value 0-127
     * @param timestampNanos a {@link System#nanoTime()} value, or 0 for now
     */
    public final void sendMidiControlChange(int channel, int function, int value,
                                            long timestampNanos) {
        storeTransferData(timestampOf(timestampNanos),
                0xb0 | (channel & 0xf), function, value, 3);
    }

    /**
     * Start Playing at a scheduled time
     *
     * @param timestampNanos a {@link System#nanoTime()} value, or 0 for now
     */
    public final void sendMidiStart(long timestampNanos) {
        storeTransferData(timestampOf(timestampNanos), 0xfa, 0, 0, 1);
    }

    /**
     * Poly-KeyPress
     *
     * @param channel 0-15
     * @param note 0-127
     * @param pressure 0-127
     */
    public final void sendMidiPolyphonicAftertouch(int channel, int note, int pressure) {
        sendMidiMessage(0xa0 | (channel & 0xf), note, pressure);
    }

    /**
     * Control Change
     *
     * @param channel 0-15
     * @param function 0-127
     * @param value 0-127
     */
    public final void sendMidiControlChange(int channel, int function, int value) {
        sendMidiMessage(0xb0 | (channel & 0xf), function, value);
    }

    /**
     * Program Change
     *
     * @param channel 0-15
     * @param program 0-127
     */
    public final void sendMidiProgramChange(int channel, int program) {
        sendMidiMessage(0xc0 | (channel & 0xf), program);
    }

    /**
     * Channel Pressure
     *
     * @param channel 0-15
     * @param pressure 0-127
     */
    public final void sendMidiChannelAftertouch(int channel, int pressure) {
        sendMidiMessage(0xd0 | (channel & 0xf), pressure);
    }

    /**
     * PitchBend Change
     *
     * @param channel 0-15
     * @param amount 0(low)-8192(center)-16383(high)
     */
    public final void sendMidiPitchWheel(int channel, int amount) {
        sendMidiMessage(0xe0 | (channel & 0xf), amount & 0x7f, (amount >> 7) & 0x7f);
    }

    /**
     * MIDI Time Code(MTC) Quarter Frame
     *
     * @param timing 0-127
     */
    public final void sendMidiTimeCodeQuarterFrame(int timing) {
        sendMidiMessage(0xf1, timing & 0x7f);
    }

    /**
     * Song Select
     *
     * @param song 0-127
     */
    public final void sendMidiSongSelect(int song) {
        sendMidiMessage(0xf3, song & 0x7f);
    }

    /**
     * Song Position Pointer
     *
     * @param position 0-16383
     */
    public final void sendMidiSongPositionPointer(int position) {
        sendMidiMessage(0xf2, position & 0x7f, (position >> 7) & 0x7f);
    }

    /**
     * Tune Request
     */
    public final void sendMidiTuneRequest() {
        sendMidiMessage(0xf6);
    }

    /**
     * Timing Clock
     */
    public final void sendMidiTimingClock() {
        sendMidiMessage(0xf8);
    }

    /**
     * Start Playing
     */
    public final void sendMidiStart() {
        sendMidiMessage(0xfa);
    }

    /**
     * Continue Playing
     */
    public final void sendMidiContinue() {
        sendMidiMessage(0xfb);
    }

    /**
     * Stop Playing
     */
    public final void sendMidiStop() {
        sendMidiMessage(0xfc);
    }

    /**
     * Active Sensing
     */
    public final void sendMidiActiveSensing() {
        sendMidiMessage(0xfe);
    }

    /**
     * Reset Device
     */
    public final void sendMidiReset() {
        sendMidiMessage(0xff);
    }

    /**
     * RPN message
     *
     * @param channel 0-15
     * @param function 14bits
     * @param value 7bits or 14bits
     */
    public final void sendRPNMessage(int channel, int function, int value) {
        sendRPNMessage(channel, (function >> 7) & 0x7f, function & 0x7f, value);
    }

    /**
     * RPN message
     *
     * @param channel 0-15
     * @param functionMSB higher 7bits
     * @param functionLSB lower 7bits
     * @param value 7bits or 14bits
     */
    public final void sendRPNMessage(int channel, int functionMSB, int functionLSB, int value) {
        // send the function
        sendMidiControlChange(channel, 101, functionMSB & 0x7f);
        sendMidiControlChange(channel, 100, functionLSB & 0x7f);

        // send the value
        if ((value >> 7) > 0) {
            sendMidiControlChange(channel, 6, (value >> 7) & 0x7f);
            sendMidiControlChange(channel, 38, value & 0x7f);
        } else {
            sendMidiControlChange(channel, 6, value & 0x7f);
        }

        // send the NULL function
        sendMidiControlChange(channel, 101, 0x7f);
        sendMidiControlChange(channel, 100, 0x7f);
    }

    /**
     * NRPN message
     *
     * @param channel 0-15
     * @param function 14bits
     * @param value 7bits or 14bits
     */
    public final void sendNRPNMessage(int channel, int function, int value) {
        sendNRPNMessage(channel, (function >> 7) & 0x7f, function & 0x7f, value);
    }

    /**
     * NRPN message
     *
     * @param channel 0-15
     * @param functionMSB higher 7bits
     * @param functionLSB lower 7bits
     * @param value 7bits or 14bits
     */
    public final void sendNRPNMessage(int channel, int functionMSB, int functionLSB, int value) {
        // send the function
        sendMidiControlChange(channel, 99, functionMSB & 0x7f);
        sendMidiControlChange(channel, 98, functionLSB & 0x7f);

        // send the value
        if ((value >> 7) > 0) {
            sendMidiControlChange(channel, 6, (value >> 7) & 0x7f);
            sendMidiControlChange(channel, 38, value & 0x7f);
        } else {
            sendMidiControlChange(channel, 6, value & 0x7f);
        }

        // send the NULL function
        sendMidiControlChange(channel, 101, 0x7f);
        sendMidiControlChange(channel, 100, 0x7f);
    }
}
