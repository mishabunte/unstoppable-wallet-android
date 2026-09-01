package io.horizontalsystems.bankwallet.modules.hardwarewallet.importmnemonic

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.horizontalsystems.bankwallet.modules.hardwarewallet.ble.HardwareWalletBleSession
import io.horizontalsystems.bankwallet.modules.hardwarewallet.ble.HardwareWalletConnectionState
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
private const val IMPORT_TIMEOUT_MILLIS = 180_000L

enum class MnemonicInputError {
    Empty,
    UnsupportedWordCount,
    InvalidWord,
    InvalidChecksum,
}

enum class MnemonicImportError {
    NotConnected,
    Disconnected,
    Timeout,
    FirmwareRejected,
    MalformedResponse,
    TransferFailed,
}

sealed interface MnemonicImportPhase {
    data object Connecting : MnemonicImportPhase
    data object EnterPhrase : MnemonicImportPhase
    data class Confirm(val wordCount: Int) : MnemonicImportPhase
    data class Sending(val progress: Float) : MnemonicImportPhase
    data object Processing : MnemonicImportPhase
    data object Success : MnemonicImportPhase
    data class Error(val type: MnemonicImportError) : MnemonicImportPhase
}

data class MnemonicImportUiState(
    val phase: MnemonicImportPhase = MnemonicImportPhase.Connecting,
    val phrase: String = "",
    val inputError: MnemonicInputError? = null,
)

class HardwareWalletMnemonicImportViewModel(
    private val session: HardwareWalletBleSession,
    private val validator: HardwareWalletMnemonicValidator,
    private val importer: HardwareWalletMnemonicImporter,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val releaseSession: (HardwareWalletBleSession) -> Unit = {},
) : ViewModel() {
    private val _uiState = MutableStateFlow(MnemonicImportUiState())
    val uiState: StateFlow<MnemonicImportUiState> = _uiState.asStateFlow()

    private var pendingMnemonic: CharArray? = null
    private var connectJob: Job? = null
    private var importJob: Job? = null
    private var started = false

    init {
        viewModelScope.launch {
            session.transferProgress.collect { progress ->
                if (_uiState.value.phase is MnemonicImportPhase.Sending && progress >= 0.0) {
                    _uiState.value = _uiState.value.copy(
                        phase = if (progress >= 1.0) {
                            MnemonicImportPhase.Processing
                        } else {
                            MnemonicImportPhase.Sending(progress.toFloat())
                        },
                    )
                }
            }
        }
        viewModelScope.launch {
            session.connectionState.collect { state ->
                if (started && state is HardwareWalletConnectionState.Disconnected &&
                    _uiState.value.phase !is MnemonicImportPhase.Connecting &&
                    _uiState.value.phase !is MnemonicImportPhase.Success
                ) {
                    clearSensitiveState()
                    _uiState.value = MnemonicImportUiState(
                        phase = MnemonicImportPhase.Error(MnemonicImportError.Disconnected),
                    )
                }
            }
        }
    }

    fun start() {
        if (started || connectJob?.isActive == true) return
        started = true
        if (session.selectedDevice.value == null) {
            _uiState.value = MnemonicImportUiState(
                phase = MnemonicImportPhase.Error(MnemonicImportError.NotConnected),
            )
            return
        }

        if (session.connectionState.value is HardwareWalletConnectionState.Connected) {
            _uiState.value = MnemonicImportUiState(phase = MnemonicImportPhase.EnterPhrase)
            return
        }

        _uiState.value = MnemonicImportUiState(phase = MnemonicImportPhase.Connecting)
        connectJob = viewModelScope.launch {
            try {
                withContext(ioDispatcher) {
                    withTimeout(CONNECT_TIMEOUT_MILLIS) { session.connect() }
                }
                _uiState.value = MnemonicImportUiState(phase = MnemonicImportPhase.EnterPhrase)
            } catch (error: CancellationException) {
                if (error is TimeoutCancellationException) {
                    _uiState.value = MnemonicImportUiState(
                        phase = MnemonicImportPhase.Error(MnemonicImportError.Timeout),
                    )
                } else {
                    throw error
                }
            } catch (_: Throwable) {
                _uiState.value = MnemonicImportUiState(
                    phase = MnemonicImportPhase.Error(MnemonicImportError.NotConnected),
                )
            }
        }
    }

    fun updatePhrase(phrase: String) {
        if (_uiState.value.phase !is MnemonicImportPhase.EnterPhrase) return
        _uiState.value = _uiState.value.copy(phrase = phrase, inputError = null)
    }

    fun validateAndConfirm() {
        val state = _uiState.value
        if (state.phase !is MnemonicImportPhase.EnterPhrase) return

        val input = state.phrase.toCharArray()
        val validation = try {
            validator.validate(input)
        } finally {
            input.fill('\u0000')
        }

        when (validation) {
            is MnemonicValidationResult.Valid -> {
                clearPendingMnemonic()
                pendingMnemonic = validation.mnemonic
                _uiState.value = MnemonicImportUiState(
                    phase = MnemonicImportPhase.Confirm(validation.wordCount),
                )
            }
            MnemonicValidationResult.Empty -> setInputError(MnemonicInputError.Empty)
            is MnemonicValidationResult.UnsupportedWordCount ->
                setInputError(MnemonicInputError.UnsupportedWordCount)
            MnemonicValidationResult.InvalidWord -> setInputError(MnemonicInputError.InvalidWord)
            MnemonicValidationResult.InvalidChecksum ->
                setInputError(MnemonicInputError.InvalidChecksum)
        }
    }

    fun editPhrase() {
        clearPendingMnemonic()
        _uiState.value = MnemonicImportUiState(phase = MnemonicImportPhase.EnterPhrase)
    }

    fun confirmImport() {
        if (importJob?.isActive == true) return
        val mnemonic = pendingMnemonic ?: return
        pendingMnemonic = null
        _uiState.value = MnemonicImportUiState(phase = MnemonicImportPhase.Sending(0f))

        importJob = viewModelScope.launch {
            try {
                val result = withContext(ioDispatcher) {
                    withTimeout(IMPORT_TIMEOUT_MILLIS) { importer.importMnemonic(mnemonic) }
                }
                _uiState.value = MnemonicImportUiState(
                    phase = when (result) {
                        ImportMnemonicResult.Success -> MnemonicImportPhase.Success
                        ImportMnemonicResult.FirmwareRejected ->
                            MnemonicImportPhase.Error(MnemonicImportError.FirmwareRejected)
                        ImportMnemonicResult.MalformedResponse ->
                            MnemonicImportPhase.Error(MnemonicImportError.MalformedResponse)
                    },
                )
            } catch (error: CancellationException) {
                if (error is TimeoutCancellationException) {
                    _uiState.value = MnemonicImportUiState(
                        phase = MnemonicImportPhase.Error(MnemonicImportError.Timeout),
                    )
                } else {
                    throw error
                }
            } catch (_: Throwable) {
                val error = if (
                    session.connectionState.value is HardwareWalletConnectionState.Disconnected
                ) {
                    MnemonicImportError.Disconnected
                } else {
                    MnemonicImportError.TransferFailed
                }
                _uiState.value = MnemonicImportUiState(phase = MnemonicImportPhase.Error(error))
            } finally {
                mnemonic.fill('\u0000')
            }
        }
    }

    fun clearSensitiveState() {
        clearPendingMnemonic()
        _uiState.value = _uiState.value.copy(phrase = "", inputError = null)
    }

    fun leave() {
        connectJob?.cancel()
        importJob?.cancel()
        clearSensitiveState()
        viewModelScope.launch(ioDispatcher) { session.disconnect() }
    }

    private fun setInputError(error: MnemonicInputError) {
        _uiState.value = _uiState.value.copy(inputError = error)
    }

    private fun clearPendingMnemonic() {
        pendingMnemonic?.fill('\u0000')
        pendingMnemonic = null
    }

    override fun onCleared() {
        connectJob?.cancel()
        importJob?.cancel()
        clearSensitiveState()
        releaseSession(session)
        super.onCleared()
    }

    class Factory(
        private val session: HardwareWalletBleSession,
        private val releaseSession: (HardwareWalletBleSession) -> Unit,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HardwareWalletMnemonicImportViewModel(
                session = session,
                validator = Bip39HardwareWalletMnemonicValidator(),
                importer = DefaultHardwareWalletMnemonicImporter(session),
                releaseSession = releaseSession,
            ) as T
    }
}
