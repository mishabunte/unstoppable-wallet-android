package io.horizontalsystems.bankwallet.modules.hardwarewallet.ble

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface HardwareWalletConnectionState {
    data object Disconnected : HardwareWalletConnectionState
    data object Connecting : HardwareWalletConnectionState
    data object Connected : HardwareWalletConnectionState
    data class Failed(val cause: Throwable) : HardwareWalletConnectionState
}

interface HardwareWalletBleSession {
    val connectionState: StateFlow<HardwareWalletConnectionState>
    val selectedDevice: StateFlow<HitoDevice?>
    val transferProgress: StateFlow<Double>

    fun selectDevice(device: HitoDevice)
    suspend fun connect()
    suspend fun send(payload: ByteArray)
    suspend fun receive(timeoutMillis: Long): ByteArray
    suspend fun exchange(payload: ByteArray, timeoutMillis: Long): ByteArray {
        send(payload)
        return receive(timeoutMillis)
    }
    suspend fun requestDeviceVersion(): DeviceVersionInfo?
    fun deviceName(): String?
    suspend fun disconnect()
    fun close()
}

class DefaultHardwareWalletBleSession(
    context: Context,
    private val managerFactory: (Context, HitoDevice) -> IHitoBle = { appContext, device ->
        HitoBleManager(appContext, device.bluetoothDevice)
    },
) : HardwareWalletBleSession {
    private val applicationContext = context.applicationContext
    private var scope = newScope()
    private val operationMutex = Mutex()

    private val _connectionState =
        MutableStateFlow<HardwareWalletConnectionState>(HardwareWalletConnectionState.Disconnected)
    override val connectionState = _connectionState.asStateFlow()

    private val _selectedDevice = MutableStateFlow<HitoDevice?>(null)
    override val selectedDevice = _selectedDevice.asStateFlow()

    private val _transferProgress = MutableStateFlow(-1.0)
    override val transferProgress = _transferProgress.asStateFlow()

    private var manager: IHitoBle? = null
    private var connectionObserver: Job? = null
    private var progressObserver: Job? = null

    override fun selectDevice(device: HitoDevice) {
        if (_selectedDevice.value == device && manager != null) return
        if (!scope.isActive) scope = newScope()
        releaseManager()
        _selectedDevice.value = device
        manager = managerFactory(applicationContext, device)
        observeConnection(manager!!)
        observeProgress(manager!!)
    }

    override suspend fun connect() = operationMutex.withLock {
        val activeManager = requireManager()
        _connectionState.value = HardwareWalletConnectionState.Connecting
        try {
            activeManager.connect()
            _connectionState.value = HardwareWalletConnectionState.Connected
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            _connectionState.value = HardwareWalletConnectionState.Failed(error)
            throw error
        }
    }

    override suspend fun send(payload: ByteArray) = operationMutex.withLock {
        check(_connectionState.value is HardwareWalletConnectionState.Connected) {
            "The hardware wallet is not connected"
        }
        requireManager().sendPayload(payload)
    }

    override suspend fun receive(timeoutMillis: Long): ByteArray = operationMutex.withLock {
        check(_connectionState.value is HardwareWalletConnectionState.Connected) {
            "The hardware wallet disconnected before returning a response"
        }
        requireManager().receivePayload(timeoutMillis)
    }

    override suspend fun exchange(payload: ByteArray, timeoutMillis: Long): ByteArray =
        operationMutex.withLock {
            check(_connectionState.value is HardwareWalletConnectionState.Connected) {
                "The hardware wallet is not connected"
            }
            val activeManager = requireManager()
            try {
                activeManager.sendPayload(payload)
                check(_connectionState.value is HardwareWalletConnectionState.Connected) {
                    "The hardware wallet disconnected before returning a response"
                }
                activeManager.receivePayload(timeoutMillis)
            } catch (error: Throwable) {
                // Dropping the GATT connection resets any partial length-prefixed upload on Hito.
                releaseManager()
                _connectionState.value = HardwareWalletConnectionState.Disconnected
                throw error
            }
        }

    override suspend fun requestDeviceVersion(): DeviceVersionInfo? = operationMutex.withLock {
        requireManager().requestDeviceVersion()
    }

    override fun deviceName(): String? = manager?.getDeviceName()

    override suspend fun disconnect() = operationMutex.withLock {
        releaseManager()
        _selectedDevice.value = null
        _connectionState.value = HardwareWalletConnectionState.Disconnected
    }

    override fun close() {
        releaseManager()
        _selectedDevice.value = null
        _connectionState.value = HardwareWalletConnectionState.Disconnected
        scope.cancel()
    }

    private fun requireManager(): IHitoBle = manager
        ?: throw IllegalStateException("No hardware wallet device has been selected")

    private fun observeConnection(activeManager: IHitoBle) {
        connectionObserver?.cancel()
        connectionObserver = scope.launch {
            activeManager.state.collect { state ->
                when (state) {
                    IHitoBle.State.LOADING ->
                        _connectionState.value = HardwareWalletConnectionState.Connecting
                    IHitoBle.State.READY ->
                        _connectionState.value = HardwareWalletConnectionState.Connected
                    IHitoBle.State.NOT_AVAILABLE -> {
                        if (_connectionState.value is HardwareWalletConnectionState.Connected) {
                            _connectionState.value = HardwareWalletConnectionState.Disconnected
                        }
                    }
                }
            }
        }
    }

    private fun observeProgress(activeManager: IHitoBle) {
        progressObserver?.cancel()
        progressObserver = scope.launch {
            activeManager.installationProgress.collect(_transferProgress::emit)
        }
    }

    private fun releaseManager() {
        connectionObserver?.cancel()
        connectionObserver = null
        progressObserver?.cancel()
        progressObserver = null
        manager?.release()
        manager = null
        _transferProgress.value = -1.0
    }

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
