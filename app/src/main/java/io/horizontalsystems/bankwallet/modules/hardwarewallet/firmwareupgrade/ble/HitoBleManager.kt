package io.horizontalsystems.bankwallet.modules.hardwarewallet.firmwareupgrade.ble

import android.Manifest
import android.bluetooth.*
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.data.Data
import no.nordicsemi.android.ble.ktx.state.ConnectionState
import no.nordicsemi.android.ble.ktx.stateAsFlow
import no.nordicsemi.android.ble.ktx.suspend
import timber.log.Timber
import java.util.*
import kotlin.math.max
import kotlin.math.min

/**
 * Android port of your iOS BluetoothController tailored for Hito devices.
 * Uses Nordic Android BLE Library for reliability and automatic queueing.
 */

// ====== Public API models ======

val TAG = "hito-ble"

interface IHitoBle {

    enum class State {
        LOADING,
        READY,
        NOT_AVAILABLE
    }

    fun getDeviceName(): String?

    suspend fun connect()

    /**
     * Connects to the device.
     */
    suspend fun sendPayload(payload: ByteArray)

    /**
     * Requests the device version information.
     * Sends a request to the device and waits for a response containing the version details.
     * Returns a [DeviceVersionInfo] object if successful, or null if the request fails or times out.
     */
    suspend fun requestDeviceVersion(): DeviceVersionInfo?

    /**
     * Disconnects from the device.
     */
    fun release()

//    /**
//     * Installs the firmware on the connected device.
//     * This function handles the entire firmware installation process, including sending the firmware data
//     * and verifying the installation.
//     */
//    suspend fun installFirmware(bootloaderVersion: BootloaderVersion)

    /**
     * The current state of the hito.
     */
    val state: StateFlow<State>
    val installationProgress: StateFlow<Double>
}

class BleException(message: String) : Exception(message)

data class DeviceVersionInfo(
    val bootloaderVersion: BootloaderVersion,
    val deviceVersion: String
)

enum class BootloaderVersion {
    BETA,
    GENESIS,
    GENESIS_HOT,
    NONE;

    override fun toString(): String {
        return when (this) {
            BETA -> "Beta"
            GENESIS -> "Genesis"
            GENESIS_HOT -> "Genesis Hot"
            NONE -> "None"
        }
    }
}

object HitoSpec {
    val HITO_SERVICE_UUID: UUID = UUID.fromString("5cc44b16-070a-11ed-b939-0242ac120002")
    val HITO_TX_BETA_UUID: UUID = UUID.fromString("5cc44b17-070a-11ed-b939-0242ac120002")
    val HITO_TX_GENESIS_UUID: UUID = UUID.fromString("5cc44b18-070a-11ed-b939-0242ac120002")
}

class HitoBleManager (
    context: Context,
    device: BluetoothDevice
): IHitoBle by HitoBleManagerImpl(context, device)

private class HitoBleManagerImpl(
    context: Context,
    private val device: BluetoothDevice,
): BleManager(context), IHitoBle {
    private val scope = CoroutineScope(Dispatchers.IO)

    private var txChar: BluetoothGattCharacteristic? = null

    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val incoming: SharedFlow<ByteArray> = _incoming.asSharedFlow()

    private val _installationProgress = MutableStateFlow(-1.0)
    override val installationProgress: StateFlow<Double> = _installationProgress.asStateFlow()

    private var currentMtu = 23 // Default MTU
    private var maxWriteSize = 20 // Default write size (MTU - 3)

    override val state = stateAsFlow()
        .map {
            when (it) {
                is ConnectionState.Connecting,
                is ConnectionState.Initializing -> IHitoBle.State.LOADING
                is ConnectionState.Ready -> IHitoBle.State.READY
                is ConnectionState.Disconnecting,
                is ConnectionState.Disconnected -> IHitoBle.State.NOT_AVAILABLE
            }
        }
        .stateIn(scope, SharingStarted.Lazily, IHitoBle.State.NOT_AVAILABLE)

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun getDeviceName(): String? {
        return device.name
    }

    fun writeWithoutResponse(bytes: ByteArray) {
        val c = txChar ?: return
        Log.d("hito-ble", "Writing payload: ${bytes.decodeToString()}")
        writeCharacteristic(c, Data(bytes), BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            .fail { _, status -> Log.w("hito-ble", "write failed: $status") }
            .enqueue()
    }

    suspend fun writeAndAwaitAck(
        payload: ByteArray,
        ack: String = "ok",
        timeoutMs: Long = 10_000L
    ): Boolean {
        return try {
            writeWithoutResponse(payload)
            Log.d("hito-ble", "writeAndAwaitAck sent ${payload.size} bytes, waiting for ack='$ack'")
            withTimeout(timeoutMs) {
                incoming.first {
                    Log.d("hito-ble", "incoming: ${it.decodeToString()}")
                    it.decodeToString() == ack
                }
                true
            }
        } catch (e: Exception) {
            Log.e("hito-ble", "writeAndAwaitAck failed: ${e.message}", e)
            false
        }
    }

    override suspend fun connect() {
        try {
            Log.d(TAG, "Attempting to connect to device: ${device.address}")

            connect(device)
                .retry(3, 300)
                .useAutoConnect(false)
                .timeout(3000) // Increased timeout
                .suspend()

            Log.d(TAG, "Successfully connected to device")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to device: ${e.message}", e)
            throw e
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun initialize() {
        super.initialize()
        // Enable notifications
        if (device.bondState == BluetoothDevice.BOND_BONDING) {
            Log.d("hito-ble","Device is bonding, waiting...")
            return
        }
        requestMtu(512)
            .with { _, mtu ->
                currentMtu = mtu
                maxWriteSize = mtu - 3 // 3 bytes for ATT overhead
                Log.d("hito-ble","MTU negotiated: $mtu, max write size: $maxWriteSize")
            }
            .enqueue()
        val c = txChar ?: return
        setNotificationCallback(c).with { _, data ->
            val bytes = data.value ?: return@with
            Log.d("hito-ble", "Notification: ${bytes.decodeToString()}")
            _incoming.tryEmit(bytes)
        }
        enableNotifications(c).enqueue()
    }

    override suspend fun requestDeviceVersion(): DeviceVersionInfo? {
        val requestByte = ByteArray(1) { 0x76.toByte() }

        writeWithoutResponse(requestByte)
        Log.d("hito-ble", "Sent device version request")

        return withTimeoutOrNull(100_000L) { // 100 seconds timeout
            try {
                val response = incoming.first()
                val responseString = response.decodeToString()
                Log.d("hito-ble", "Device version response: $responseString")

                // Parse bootloader version
                val bootloaderVersion = when {
                    responseString.length > 2 -> {
                        val char0 = responseString[0].toString().toIntOrNull() ?: return@withTimeoutOrNull null
                        val char2 = responseString[2].toString().toIntOrNull() ?: return@withTimeoutOrNull null

                        if (char2 < 4 && char0 == 0) {
                            BootloaderVersion.BETA
                        } else {
                            BootloaderVersion.GENESIS
                        }
                    }
                    else -> {
                        Log.w("hito-ble", "Invalid response format")
                        return@withTimeoutOrNull null
                    }
                }

                // Parse device version
                val deviceVersion = responseString.indexOf(',').let { pointIndex ->
                    if (pointIndex != -1 && pointIndex < responseString.length - 1) {
                        responseString.substring(pointIndex + 1).replace("+", "'")
                    } else {
                        ""
                    }
                }

                Log.d("hito-ble", "Bootloader version: $bootloaderVersion, Device version: $deviceVersion")
                DeviceVersionInfo(bootloaderVersion, deviceVersion)

            } catch (e: Exception) {
                Log.e("hito-ble", "Error while requesting device version", e)
                null
            }
        } ?: run {
            Log.w("hito-ble", "Timeout while waiting for device version response")
            null
        }
    }

    override suspend fun sendPayload(payload: ByteArray) {
        _installationProgress.value = 0.0
        val info = ByteArray(5)
        val start = System.currentTimeMillis()
        val payloadSize = payload.size
        // big-endian 32-bit length
        info[0] = 0x69.toByte()
        info[1] = (payloadSize and 0xFF).toByte()
        info[2] = ((payloadSize shr 8) and 0xFF).toByte()
        info[3] = ((payloadSize shr 16) and 0xFF).toByte()
        info[4] = ((payloadSize shr 24) and 0xFF).toByte()

        // Send and await ack
        if (!writeAndAwaitAck(info, ack = "ok", timeoutMs = 60_000L)) throw BleException("Device is not responding")

        val packetSize = max(1, maxWriteSize - 1) // 1 byte for 'd'
        for (i in 0 until payloadSize step packetSize) {
            val rest = payloadSize - i

            var dataChunk = ByteArray(1 + min(packetSize, rest))
            dataChunk[0] = 0x64.toByte() // 'd'
            System.arraycopy(payload, i, dataChunk, 1, min(packetSize, rest))
            if (!writeAndAwaitAck(dataChunk, ack = "ok", timeoutMs = if (rest > packetSize) 60_000L else 8_000L)) throw BleException("Device is not responding")

            _installationProgress.value = (i + min(packetSize, rest)).toDouble() / payloadSize.toDouble()
            Log.d("hito-ble", "Progress: ${_installationProgress.value}")
        }
        val end = System.currentTimeMillis()
        Log.d("hito-ble", "Upload complete, total bytes sent: $payloadSize, time taken: ${end - start}ms")
    }

    override fun release() {
        // Cancel all coroutines.
        scope.cancel()

        val wasConnected = isReady
        // If the device wasn't connected, it means that ConnectRequest was still pending.
        // Cancelling queue will initiate disconnecting automatically.
        cancelQueue()

        // If the device was connected, we have to disconnect manually.
        if (wasConnected) {
            disconnect().enqueue()
        }
    }

    override fun log(priority: Int, message: String) {
        Timber.log(priority, message)
    }

    override fun getMinLogPriority(): Int {
        // By default, the library logs only INFO or
        // higher priority messages. You may change it here.
        return Log.VERBOSE
    }

    override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
        // Get the LBS Service from the gatt object.
        gatt.getService(HitoSpec.HITO_SERVICE_UUID)?.apply {
            // Get the TX Genesis characteristic.
            Log.d("hito-ble","Found Hito service")
            txChar = getCharacteristic(HitoSpec.HITO_TX_BETA_UUID)
                ?: getCharacteristic(HitoSpec.HITO_TX_GENESIS_UUID) ?: return false
            Log.d("hito-ble","txChar: $txChar")

            val props = txChar?.properties ?: 0
            val writeOk = (props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                    (props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            Log.d("hito-ble","props1: ${props and BluetoothGattCharacteristic.PROPERTY_WRITE}")
            Log.d("hito-ble","props2: ${props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE}")
            Log.d("hito-ble","txChar properties: $props, writeOk=$writeOk")

            // Return true if all required characteristics are supported.
            return txChar != null && writeOk
        }
        return false
    }

    override fun onServicesInvalidated() {
        txChar = null
    }
}
