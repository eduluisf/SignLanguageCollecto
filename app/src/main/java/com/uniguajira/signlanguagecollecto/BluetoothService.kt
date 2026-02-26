package com.uniguajira.signlanguagecollecto

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.UUID

/**
 * Manages a BLE connection to an ESP32-C3 glove using the Nordic UART Service (NUS).
 *
 * Data flows over the NUS TX characteristic (notify direction: peripheral → central).
 * The ESP32 streams comma-separated sensor lines terminated by '\n':
 *   flex1,flex2,flex3,flex4,flex5,accelX,accelY,accelZ,gyroX,gyroY,gyroZ,roll,pitch,yaw
 *
 * Because BLE packets can split or merge across '\n' boundaries, incoming bytes are
 * accumulated in [lineBuffer] and flushed line-by-line.
 *
 * All [Listener] callbacks are invoked on the BluetoothGattCallback binder thread.
 * The caller must switch to the main thread (e.g. runOnUiThread) before touching Views.
 *
 * The [listener] must also be a [Context] (an Activity satisfies this).
 */
class BluetoothService(
    private val device: BluetoothDevice,
    private val listener: Listener
) {

    // ── Public API ────────────────────────────────────────────────────────────

    interface Listener {
        fun onConnected(deviceName: String)
        fun onSensorData(data: SensorData)
        fun onDisconnected()
        fun onError(message: String)
    }

    data class SensorData(
        val flex1: Int,  val flex2: Int,  val flex3: Int,  val flex4: Int,  val flex5: Int,
        val accelX: Float, val accelY: Float, val accelZ: Float,
        val gyroX: Float,  val gyroY: Float,  val gyroZ: Float,
        val roll: Float,   val pitch: Float,  val yaw: Float
    )

    // ── UUIDs & constants ─────────────────────────────────────────────────────

    companion object {
        private const val TAG = "BLE"

        /** Nordic UART Service */
        private val NUS_SERVICE_UUID = UUID.fromString("6E400001-B5B4-F393-E0A9-E50E24DCCA9E")
        /** NUS TX characteristic — peripheral notifies central with sensor data */
        private val NUS_TX_UUID      = UUID.fromString("6E400003-B5B4-F393-E0A9-E50E24DCCA9E")
        /** Client Characteristic Configuration Descriptor — enables remote notifications */
        private val CCCD_UUID        = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }

    // ── Internal state ────────────────────────────────────────────────────────

    // The listener is always an Activity in this app, so casting to Context is safe.
    private val context: Context = listener as? Context
        ?: throw IllegalArgumentException("BluetoothService: listener must also be a Context (pass the Activity)")

    private var gatt: BluetoothGatt? = null

    /** Set to true before calling gatt.disconnect() so the STATE_DISCONNECTED callback
     *  knows the disconnect was intentional and suppresses the onDisconnected() event. */
    @Volatile private var intentionalDisconnect = false

    /** Accumulates raw BLE bytes until a full '\n'-terminated line is available. */
    private val lineBuffer = StringBuilder()

    // ── Public methods ────────────────────────────────────────────────────────

    fun connect() {
        intentionalDisconnect = false
        val name = try { device.name ?: device.address } catch (ignored: SecurityException) { device.address }
        Log.i(TAG, "connect() → device=$name  addr=${device.address}")
        try {
            // TRANSPORT_LE forces BLE (not Classic) — requires minSdk 23, satisfied by our minSdk 26
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            Log.d(TAG, "connectGatt() returned: $gatt")
        } catch (e: SecurityException) {
            Log.e(TAG, "connect() SecurityException: ${e.message}")
            listener.onError("BLUETOOTH_CONNECT permission missing: ${e.message}")
        }
    }

    /**
     * Gracefully disconnects.
     * Sets [intentionalDisconnect] before calling disconnect() so the GATT callback
     * does not fire onDisconnected() (the caller already knows the session ended).
     * gatt.close() is deferred to the STATE_DISCONNECTED callback per Android best-practice.
     */
    fun disconnect() {
        Log.i(TAG, "disconnect() called")
        intentionalDisconnect = true
        gatt?.disconnect()
    }

    // ── GATT callback ─────────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val stateStr = when (newState) {
                BluetoothProfile.STATE_CONNECTED    -> "CONNECTED"
                BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                BluetoothProfile.STATE_CONNECTING   -> "CONNECTING"
                else -> "UNKNOWN($newState)"
            }
            Log.i(TAG, "onConnectionStateChange: status=$status  newState=$stateStr")

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "onConnectionStateChange: non-SUCCESS status=$status — closing GATT")
                val wasIntentional = intentionalDisconnect
                intentionalDisconnect = false
                closeGatt()
                if (!wasIntentional) listener.onError("GATT error, status=$status")
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // Request a 512-byte MTU before service discovery.
                    // The default BLE MTU is only 23 bytes (20-byte payload), which is
                    // too small for our ~74-byte sensor line — data would be silently
                    // truncated and the lineBuffer would never receive a '\n'.
                    // onMtuChanged will call discoverServices() once negotiation completes.
                    Log.i(TAG, "STATE_CONNECTED — requesting MTU 512 before service discovery")
                    gatt.requestMtu(512)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "STATE_DISCONNECTED — intentional=$intentionalDisconnect")
                    val wasIntentional = intentionalDisconnect
                    intentionalDisconnect = false
                    closeGatt()
                    if (!wasIntentional) listener.onDisconnected()
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "onMtuChanged: negotiated mtu=$mtu  status=$status  (0=SUCCESS)")
            // Proceed with service discovery regardless of whether the requested MTU
            // was granted — even at 23 bytes we at least attempt to connect.
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            // Single-line summary first — easy to spot when filtering Logcat by "BLE"
            Log.d(TAG, "Services found: ${gatt.services.map { it.uuid }}")
            Log.i(TAG, "onServicesDiscovered: status=$status  serviceCount=${gatt.services.size}")

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "onServicesDiscovered: failed status=$status")
                listener.onError("Service discovery failed (status=$status)")
                return
            }

            // ── Full service/characteristic/descriptor dump ───────────────────
            Log.d(TAG, "── Discovered services (${gatt.services.size}) ──────────────────")
            for (svc in gatt.services) {
                Log.d(TAG, "  SERVICE  ${svc.uuid}")
                for (ch in svc.characteristics) {
                    val props = buildString {
                        if (ch.properties and BluetoothGattCharacteristic.PROPERTY_READ             != 0) append("READ ")
                        if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE            != 0) append("WRITE ")
                        if (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY           != 0) append("NOTIFY ")
                        if (ch.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE         != 0) append("INDICATE ")
                        if (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) append("WRITE_NO_RSP ")
                    }.trim()
                    Log.d(TAG, "    CHAR   ${ch.uuid}  props=[$props]")
                    for (desc in ch.descriptors) {
                        Log.d(TAG, "      DESC ${desc.uuid}")
                    }
                }
            }
            Log.d(TAG, "────────────────────────────────────────────────────────────")

            // ── Locate NUS service ────────────────────────────────────────────
            val service = gatt.getService(NUS_SERVICE_UUID)
            if (service == null) {
                Log.e(TAG, "NUS service NOT found — UUID we look for: $NUS_SERVICE_UUID")
                Log.e(TAG, "Compare the UUID in the dump above with the one expected.")
                listener.onError("NUS service (6E400001…) not found — is this the right device?")
                return
            }
            Log.i(TAG, "NUS service found ✓")

            // ── Locate TX characteristic ──────────────────────────────────────
            val txChar = service.getCharacteristic(NUS_TX_UUID)
            if (txChar == null) {
                Log.e(TAG, "NUS TX characteristic NOT found — UUID we look for: $NUS_TX_UUID")
                listener.onError("NUS TX characteristic (6E400003…) not found")
                return
            }
            Log.i(TAG, "NUS TX characteristic found ✓  properties=${txChar.properties}")
            val hasNotify   = txChar.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY   != 0
            val hasIndicate = txChar.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
            Log.i(TAG, "TX char: NOTIFY=$hasNotify  INDICATE=$hasIndicate")
            if (!hasNotify && !hasIndicate) {
                Log.e(TAG, "TX char has neither NOTIFY nor INDICATE — data cannot be received!")
            }

            // ── Step 1: register locally so Android routes notifications here ──
            val notifOk = gatt.setCharacteristicNotification(txChar, true)
            Log.i(TAG, "setCharacteristicNotification(txChar, true) returned $notifOk")
            if (!notifOk) {
                Log.e(TAG, "setCharacteristicNotification returned false — data will NOT arrive!")
            }

            // Signal the UI as connected NOW, before the CCCD write.
            // Some ESP32 NUS builds start sending notifications as soon as local routing
            // is enabled, without waiting for the CCCD write to complete.
            // This ensures onCharacteristicChanged results are visible in the UI
            // even if onDescriptorWrite is slow or never fires.
            val name = try { device.name ?: device.address } catch (ignored: SecurityException) { device.address }
            Log.i(TAG, "Signalling onConnected('$name') before CCCD write")
            listener.onConnected(name)

            // ── Step 2: write CCCD after a short delay ────────────────────────
            // A 500 ms pause lets the peripheral finish its own connection setup
            // before receiving the descriptor write. Some ESP32 stacks drop the
            // write if it arrives too quickly after service discovery completes.
            val cccd = txChar.getDescriptor(CCCD_UUID)
            if (cccd == null) {
                Log.w(TAG, "CCCD descriptor not found — skipping descriptor write.")
                Log.w(TAG, "Data may still flow if the ESP32 sends regardless of CCCD state.")
                return
            }
            Log.i(TAG, "CCCD descriptor found ✓ — scheduling write in 500 ms")
            Handler(Looper.getMainLooper()).postDelayed({
                Log.i(TAG, "500 ms elapsed — writing ENABLE_NOTIFICATION_VALUE to CCCD")
                writeDescriptorCompat(gatt, cccd)
            }, 500)
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            // Diagnostic only — onConnected() was already fired after setCharacteristicNotification
            Log.i(TAG, "onDescriptorWrite: uuid=${descriptor.uuid}  status=$status  (0=SUCCESS)")
            if (descriptor.uuid != CCCD_UUID) {
                Log.d(TAG, "onDescriptorWrite: not the CCCD — ignoring")
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "onDescriptorWrite: CCCD write FAILED status=$status — notifications may not flow from peripheral")
            } else {
                Log.i(TAG, "onDescriptorWrite: CCCD write SUCCESS — peripheral will now send notifications ✓")
            }
        }

        // Called on API < 33
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val bytes = characteristic.value ?: run {
                Log.w(TAG, "onCharacteristicChanged (API<33): characteristic.value is null")
                return
            }
            Log.v(TAG, "onCharacteristicChanged (API<33): uuid=${characteristic.uuid}  bytes=${bytes.size}  hex=${bytes.toHex()}  utf8=\"${String(bytes, Charsets.UTF_8).trim()}\"")
            if (characteristic.uuid == NUS_TX_UUID) {
                handleChunk(bytes)
            } else {
                Log.w(TAG, "onCharacteristicChanged: unexpected characteristic ${characteristic.uuid}")
            }
        }

        // Called on API 33+ (takes precedence over the deprecated overload above)
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            Log.v(TAG, "onCharacteristicChanged (API33+): uuid=${characteristic.uuid}  bytes=${value.size}  hex=${value.toHex()}  utf8=\"${String(value, Charsets.UTF_8).trim()}\"")
            if (characteristic.uuid == NUS_TX_UUID) {
                handleChunk(value)
            } else {
                Log.w(TAG, "onCharacteristicChanged: unexpected characteristic ${characteristic.uuid}")
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Writes ENABLE_NOTIFICATION_VALUE to the CCCD using the API-appropriate method. */
    private fun writeDescriptorCompat(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            Log.d(TAG, "writeDescriptorCompat: using API 33+ gatt.writeDescriptor(descriptor, value)")
            val result = gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            Log.d(TAG, "writeDescriptor() returned $result  (0=SUCCESS)")
        } else {
            Log.d(TAG, "writeDescriptorCompat: using legacy descriptor.value + gatt.writeDescriptor()")
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            val result = gatt.writeDescriptor(descriptor)
            Log.d(TAG, "gatt.writeDescriptor() returned $result")
        }
    }

    /**
     * Appends a raw BLE packet to the line buffer, then flushes all complete lines.
     * A single BLE notification may contain zero, one, or multiple '\n' characters.
     */
    private fun handleChunk(bytes: ByteArray) {
        val chunk = String(bytes, Charsets.UTF_8)
        Log.v(TAG, "handleChunk: +${bytes.size} bytes  bufferBefore=${lineBuffer.length}  chunk=\"$chunk\"")
        lineBuffer.append(chunk)
        var newlineAt: Int
        while (lineBuffer.indexOf("\n").also { newlineAt = it } >= 0) {
            val line = lineBuffer.substring(0, newlineAt).trim()
            lineBuffer.delete(0, newlineAt + 1)
            Log.v(TAG, "handleChunk: complete line → \"$line\"")
            if (line.isNotEmpty()) parseLine(line)
        }
    }

    private fun parseLine(line: String) {
        val parts = line.split(",")
        if (parts.size != 14) {
            Log.w(TAG, "parseLine: expected 14 values, got ${parts.size} → \"$line\"")
            return
        }
        try {
            val data = SensorData(
                flex1  = parts[0].trim().toInt(),
                flex2  = parts[1].trim().toInt(),
                flex3  = parts[2].trim().toInt(),
                flex4  = parts[3].trim().toInt(),
                flex5  = parts[4].trim().toInt(),
                accelX = parts[5].trim().toFloat(),
                accelY = parts[6].trim().toFloat(),
                accelZ = parts[7].trim().toFloat(),
                gyroX  = parts[8].trim().toFloat(),
                gyroY  = parts[9].trim().toFloat(),
                gyroZ  = parts[10].trim().toFloat(),
                roll   = parts[11].trim().toFloat(),
                pitch  = parts[12].trim().toFloat(),
                yaw    = parts[13].trim().toFloat()
            )
            Log.v(TAG, "parseLine: OK → flex=[${data.flex1},${data.flex2},${data.flex3},${data.flex4},${data.flex5}]  accel=[${data.accelX},${data.accelY},${data.accelZ}]")
            listener.onSensorData(data)
        } catch (e: NumberFormatException) {
            Log.w(TAG, "parseLine: NumberFormatException on \"$line\" — ${e.message}")
        }
    }

    private fun closeGatt() {
        Log.d(TAG, "closeGatt()")
        lineBuffer.clear()
        try { gatt?.close() } catch (ignored: Exception) {}
        gatt = null
    }

    // ── Extension ──────────────────────────────────────────────────────────────

    /** Converts a ByteArray to a hex string for log output, e.g. "22 2C 31 39 0A" */
    private fun ByteArray.toHex(): String =
        joinToString(" ") { "%02X".format(it) }
}
