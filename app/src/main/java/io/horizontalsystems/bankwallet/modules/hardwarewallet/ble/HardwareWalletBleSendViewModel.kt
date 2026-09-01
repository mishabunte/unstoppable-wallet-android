package io.horizontalsystems.bankwallet.modules.hardwarewallet.ble

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.horizontalsystems.marketkit.models.BlockchainType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val CONNECT_TIMEOUT_MILLIS = 15_000L
private const val SEND_TIMEOUT_MILLIS = 180_000L
private const val RECEIVE_TIMEOUT_MILLIS = 120_000L

enum class HardwareWalletTransactionError {
    InvalidRequest,
    Disconnected,
    Rejected,
    Timeout,
    InvalidResponse,
    TransferFailed,
}

sealed interface HardwareWalletTransactionState {
    data object Idle : HardwareWalletTransactionState
    data object Connecting : HardwareWalletTransactionState
    data class Sending(val progress: Float) : HardwareWalletTransactionState
    data object WaitingForDevice : HardwareWalletTransactionState
    data object Receiving : HardwareWalletTransactionState
    data object Completed : HardwareWalletTransactionState
    data class Error(val type: HardwareWalletTransactionError, val cause: Throwable? = null) :
        HardwareWalletTransactionState
}

class HardwareWalletBleSendViewModel(
    private val requestId: String?,
    private val repository: HardwareWalletSigningRequestRepository?,
    private val session: HardwareWalletBleSession,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val operation: HardwareWalletBleOperation,
) : ViewModel() {
    private val _state = MutableStateFlow<HardwareWalletTransactionState>(
        HardwareWalletTransactionState.Idle,
    )
    val state: StateFlow<HardwareWalletTransactionState> = _state.asStateFlow()

    private var transferJob: Job? = null

    init {
        viewModelScope.launch {
            session.connectionState.collect { connectionState ->
                val activeState = _state.value
                if (connectionState is HardwareWalletConnectionState.Disconnected &&
                    transferJob?.isActive == true &&
                    (activeState is HardwareWalletTransactionState.Sending ||
                        activeState == HardwareWalletTransactionState.WaitingForDevice ||
                        activeState == HardwareWalletTransactionState.Receiving)
                ) {
                    transferJob?.cancel()
                    repository?.releaseForRetry(requestId)
                    _state.value = HardwareWalletTransactionState.Error(
                        HardwareWalletTransactionError.Disconnected,
                    )
                }
            }
        }
        viewModelScope.launch {
            session.transferProgress.collect { progress ->
                if (_state.value is HardwareWalletTransactionState.Sending && progress >= 0) {
                    _state.value = HardwareWalletTransactionState.Sending(progress.toFloat())
                }
            }
        }
    }

    private fun buildPcztFrame(pczt: ByteArray): ByteArray {
        require(pczt.size <= 0xFFFF) {
            "PCZT payload is too large for u16 length: ${pczt.size}"
        }

        val headerSize = 8
        val frame = ByteArray(headerSize + pczt.size)

        "pczt".toByteArray(Charsets.US_ASCII).copyInto(
            destination = frame,
            destinationOffset = 0
        )

        frame[4] = 0x1F.toByte()
        frame[5] = 0x01.toByte()
        frame[6] = (pczt.size ushr 8).toByte()
        frame[7] = pczt.size.toByte()

        pczt.copyInto(
            destination = frame,
            destinationOffset = headerSize
        )

        return frame
    }

    private fun buildPairingFrame(
        networkCoinType: Int,
        accountIndex: Long,
    ): ByteArray =
        "pair $networkCoinType $accountIndex\n".toByteArray(Charsets.UTF_8)

    private fun buildPayload(request: HardwareWalletSigningRequest): ByteArray =
        when (request.blockchainType) {
            BlockchainType.Zcash -> when (operation) {
                HardwareWalletBleOperation.SignTransaction ->
                    buildPcztFrame(request.payload)
                HardwareWalletBleOperation.Pairing ->
                    buildPairingFrame(request.networkCoinType, request.accountIndex)
                else -> request.payload
            }
            else -> request.payload
        }

    fun start() {
        if (transferJob?.isActive == true) return
        if (_state.value == HardwareWalletTransactionState.Completed) return

        val request = repository?.acquire(requestId)
        if (request == null) {
            _state.value = if (
                repository?.status(requestId) == HardwareWalletSigningRequestStatus.Completed
            ) {
                HardwareWalletTransactionState.Completed
            } else {
                HardwareWalletTransactionState.Error(HardwareWalletTransactionError.InvalidRequest)
            }
            return
        }

        transferJob = viewModelScope.launch {
            try {
                _state.value = HardwareWalletTransactionState.Connecting
                withContext(ioDispatcher) {
                    withTimeout(CONNECT_TIMEOUT_MILLIS) { session.connect() }
                }
                val payload = buildPayload(request)

                _state.value = HardwareWalletTransactionState.Sending(0f)
                withContext(ioDispatcher) {
                    withTimeout(SEND_TIMEOUT_MILLIS) { session.send(payload) }
                }

                request.payload.fill(0)
//                if (operation == HardwareWalletBleOperation.Pairing) {
//                    check(repository.complete(requestId, byteArrayOf())) {
//                        "The pairing request was already completed"
//                    }
//                    _state.value = HardwareWalletTransactionState.Completed
//                    withContext(ioDispatcher) { session.disconnect() }
//                    return@launch
//                }

                _state.value = HardwareWalletTransactionState.WaitingForDevice
                val response = withContext(ioDispatcher) {
                    session.receive(RECEIVE_TIMEOUT_MILLIS)
                }

                _state.value = HardwareWalletTransactionState.Receiving
                try {
                    validateResponse(response)
                    check(repository.complete(requestId, response)) {
                        "The signing request was already completed"
                    }
                } finally {
                    response.fill(0)
                }
                _state.value = HardwareWalletTransactionState.Completed
                withContext(ioDispatcher) { session.disconnect() }
            } catch (error: CancellationException) {
                request.payload.fill(0)
                if (error is TimeoutCancellationException) {
                    repository.releaseForRetry(requestId)
                    _state.value = HardwareWalletTransactionState.Error(
                        HardwareWalletTransactionError.Timeout,
                        error,
                    )
                } else {
                    throw error
                }
            } catch (error: HardwareWalletUserRejectedException) {
                repository.releaseForRetry(requestId)
                _state.value = HardwareWalletTransactionState.Error(
                    HardwareWalletTransactionError.Rejected,
                    error,
                )
            } catch (error: HardwareWalletInvalidResponseException) {
                repository.releaseForRetry(requestId)
                _state.value = HardwareWalletTransactionState.Error(
                    HardwareWalletTransactionError.InvalidResponse,
                    error,
                )
            } catch (error: Throwable) {
                repository.releaseForRetry(requestId)
                val type = if (session.connectionState.value is HardwareWalletConnectionState.Disconnected) {
                    HardwareWalletTransactionError.Disconnected
                } else {
                    HardwareWalletTransactionError.TransferFailed
                }
                _state.value = HardwareWalletTransactionState.Error(type, error)
            }
        }
    }

    fun cancel() {
        transferJob?.cancel()
        transferJob = null
        repository?.remove(requestId)
        viewModelScope.launch(ioDispatcher) { session.disconnect() }
    }

    override fun onCleared() {
        transferJob?.cancel()
        if (_state.value != HardwareWalletTransactionState.Completed) {
            repository?.remove(requestId)
        }
        session.close()
        super.onCleared()
    }

    private fun validateResponse(response: ByteArray) {
        if (response.isEmpty()) throw HardwareWalletInvalidResponseException()
        when (response.decodeToString().trim().lowercase()) {
            "reject", "rejected", "cancel", "cancelled", "canceled" ->
                throw HardwareWalletUserRejectedException()
        }
    }

    class Factory(
        private val requestId: String?,
        private val repository: HardwareWalletSigningRequestRepository?,
        private val session: HardwareWalletBleSession,
        private val operation: HardwareWalletBleOperation,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HardwareWalletBleSendViewModel(
                requestId = requestId,
                repository = repository,
                session = session,
                operation = operation,
            ) as T
    }
}

class HardwareWalletUserRejectedException : Exception("Rejected by user")
class HardwareWalletInvalidResponseException : Exception("Invalid hardware wallet response")
