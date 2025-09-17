package io.horizontalsystems.bankwallet.modules.hardwarewallet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.core.HSCaution
import io.horizontalsystems.bankwallet.core.LocalizedException
import io.horizontalsystems.bankwallet.core.stats.StatEvent
import io.horizontalsystems.bankwallet.core.stats.StatPage
import io.horizontalsystems.bankwallet.core.stats.stat
import io.horizontalsystems.bankwallet.modules.hardwarewallet.scanui.decodeRawTransactionSignature
import io.horizontalsystems.bankwallet.modules.hardwarewallet.scanui.toHex
import io.horizontalsystems.bankwallet.modules.multiswap.sendtransaction.SendTransactionServiceEvm
import io.horizontalsystems.bankwallet.ui.compose.TranslatableString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.UnknownHostException

class HardwareWalletEvmSignViewModel(
    private val sendTransactionServiceEvm: SendTransactionServiceEvm,
    private val isHardwareAccount: Boolean
) : ViewModel() {

    private val initialState = if (isHardwareAccount) {
        SendTransactionHardwareState.ReadyToLoad
    } else {
        null
    }
    private val _unsignedTxState = MutableStateFlow<SendTransactionHardwareState?>(initialState)
    val unsignedTxState = _unsignedTxState.asStateFlow()

    fun loadUnsigned() = viewModelScope.launch {
        _unsignedTxState.value = SendTransactionHardwareState.Loading
        runCatching { sendTransactionServiceEvm.getUnsignedTransactionHex() }
            .onSuccess { _unsignedTxState.value = SendTransactionHardwareState.NFCWritingStarted(it) }
            .onFailure { e -> _unsignedTxState.value = SendTransactionHardwareState.Error(createCaution(e)) }
    }

    fun resetHardwareWalletState() {
        _unsignedTxState.value = initialState
    }

    fun onScannedQR(scannedQr: String?) {
        if (scannedQr == null) {
            _unsignedTxState.value = SendTransactionHardwareState.Error(createCaution(IOException("Scan Error")))
            return
        }
        if (scannedQr.startsWith("https://app.hito.dev/eth/tx/#!")) {
            viewModelScope.launch {
                _unsignedTxState.value = SendTransactionHardwareState.Sending
                try {
                    val txHex = scannedQr.removePrefix("https://app.hito.dev/eth/tx/#!")
                    val signature = decodeRawTransactionSignature(txHex)
                    val signatureHex = signature?.toHex()
                    sendTransactionServiceEvm.sendTransaction(signatureHex)
                    stat(page = StatPage.SendConfirmation, event = StatEvent.Send)
                    _unsignedTxState.value = SendTransactionHardwareState.Sent
                } catch (t: Throwable) {
                    _unsignedTxState.value = SendTransactionHardwareState.Error(createCaution(t))
                }
            }
        } else if (scannedQr.startsWith("evm.sig:")) {
            val signatureHex = scannedQr.removePrefix("evm.sig:")
            //if (messageHex == null) {
                viewModelScope.launch {
                    _unsignedTxState.value = SendTransactionHardwareState.Sending
                    try {
                        sendTransactionServiceEvm.sendTransaction(signatureHex)
                        stat(page = StatPage.SendConfirmation, event = StatEvent.Send)
                        _unsignedTxState.value = SendTransactionHardwareState.Sent
                    }
                    catch(e: UnknownHostException) {
                        _unsignedTxState.value = SendTransactionHardwareState.Error(createCaution(e))
                    }
                    catch (t: Throwable) {
                        _unsignedTxState.value = SendTransactionHardwareState.Error(createCaution(t))
                    }
                }
//            } else {
//                val signature = signatureHex.toSignature()
//                val shex = signature?.toByteArray().toHexString()
//                // TODO: handle case when signature is null
////                    signMessageViewModel?.acceptWithSignature(shex!!)
//            }
        } else {
            _unsignedTxState.value = SendTransactionHardwareState.Error(createCaution(IOException("Scan Error")))
        }
    }

    fun onNFCWritingSuccess() {
        _unsignedTxState.value = SendTransactionHardwareState.ScanToTransmit
    }

    private fun createCaution(error: Throwable) = when (error) {
        is UnknownHostException -> HSCaution(TranslatableString.ResString(R.string.Hud_Text_NoInternet))
        is LocalizedException -> HSCaution(TranslatableString.ResString(error.errorTextRes))
        else -> HSCaution(TranslatableString.PlainString(error.cause?.message ?: error.message ?: ""))
    }

    class Factory(
        private val service: SendTransactionServiceEvm?,
        private val isHardwareAccount: Boolean
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return HardwareWalletEvmSignViewModel(service!!, isHardwareAccount) as T
        }
    }
}

sealed class SendTransactionHardwareState {
    data object Loading: SendTransactionHardwareState()
    data object ReadyToLoad: SendTransactionHardwareState()
    data object Sending: SendTransactionHardwareState()
    data object Sent: SendTransactionHardwareState()
    data class Error(val caution: HSCaution): SendTransactionHardwareState()
    data class NFCWritingStarted(val unsignedTxHex: String): SendTransactionHardwareState()
    data object ScanToTransmit: SendTransactionHardwareState()

    fun nfcPayloadOrNull(): String? {
        return when(this) {
            is NFCWritingStarted -> unsignedTxHex
            else -> null
        }
    }

    fun errorOrNull(): HSCaution? {
        return when(this) {
            is Error -> caution
            else -> null
        }
    }
}
