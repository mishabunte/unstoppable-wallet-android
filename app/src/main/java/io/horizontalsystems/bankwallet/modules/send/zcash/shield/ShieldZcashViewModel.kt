package io.horizontalsystems.bankwallet.modules.send.zcash.shield

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.ext.collectWith
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.core.AppLogger
import io.horizontalsystems.bankwallet.core.HSCaution
import io.horizontalsystems.bankwallet.core.LocalizedException
import io.horizontalsystems.bankwallet.core.adapters.zcash.ZcashAdapter
import io.horizontalsystems.bankwallet.core.hexToByteArray
import io.horizontalsystems.bankwallet.entities.AccountType
import io.horizontalsystems.bankwallet.entities.Wallet
import io.horizontalsystems.bankwallet.modules.hardwarewallet.SendTransactionHardwareState
import io.horizontalsystems.bankwallet.modules.hardwarewallet.ble.HardwareWalletBleModule
import io.horizontalsystems.bankwallet.modules.hardwarewallet.ble.HardwareWalletSigningRequestStatus
import io.horizontalsystems.bankwallet.modules.send.SendConfirmationData
import io.horizontalsystems.bankwallet.modules.send.SendResult
import io.horizontalsystems.bankwallet.modules.xrate.XRateService
import io.horizontalsystems.bankwallet.ui.compose.TranslatableString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.math.BigDecimal
import java.net.UnknownHostException

private const val TESTNET_COIN_TYPE = 1
private const val ZCASH_MAINNET_COIN_TYPE = 133

class ShieldZcashViewModel(
    private val adapter: ZcashAdapter,
    private val wallet: Wallet,
    private val xRateService: XRateService,
) : ViewModel() {
    private val logger = AppLogger("Shield-Zcash")

    val blockchainType = wallet.token.blockchainType
    val coinMaxAllowedDecimals = wallet.token.decimals

    private val initialHardwareState = if (adapter.isHardwareAccount()) {
        SendTransactionHardwareState.ReadyToLoad
    } else {
        null
    }
    private val _unsignedTxState = MutableStateFlow<SendTransactionHardwareState?>(initialHardwareState)
    val unsignedTxState: StateFlow<SendTransactionHardwareState?> = _unsignedTxState.asStateFlow()

    private var scannedQr: String? = null
    private var bleRequestId: String? = null

    var coinRate by mutableStateOf(xRateService.getRate(wallet.coin.uid))
        private set

    var sendResult by mutableStateOf<SendResult?>(null)
        private set

    var fee by mutableStateOf<BigDecimal?>(null)
        private set

    init {
        xRateService.getRateFlow(wallet.coin.uid).collectWith(viewModelScope) {
            coinRate = it
        }

        viewModelScope.launch {
            fee = adapter.shieldTransactionFee()
        }
    }

    fun getConfirmationData(): SendConfirmationData {
        return SendConfirmationData(
            amount = adapter.balanceData.unshielded,
            fee = null,
            address = null,
            contact = null,
            coin = wallet.coin,
            feeCoin = wallet.coin,
            memo = null
        )
    }

    fun onClickSend() {
        viewModelScope.launch {
            send()
        }
    }

    fun getUnsignedTransaction() {
        Log.d("ShieldZcashViewModel", "getUnsignedTransaction")
        if (_unsignedTxState.value == SendTransactionHardwareState.Loading) return
        _unsignedTxState.value = SendTransactionHardwareState.Loading
        viewModelScope.launch {
            try {
                val unsignedHex = adapter.getUnsignedShieldTransaction()
                val chunkSize = 3000
                val chunks = unsignedHex.chunked(chunkSize)
                Log.d("PCZT", "BEGIN length=${unsignedHex.length} chunks=${chunks.size}")
                chunks.forEachIndexed { index, chunk ->
                    Log.d("PCZT", "CHUNK[$index]=$chunk")
                }
                Log.d("PCZT", "END")
                _unsignedTxState.value = SendTransactionHardwareState.NFCWritingStarted(unsignedHex)
            } catch (error: Exception) {
                _unsignedTxState.value = SendTransactionHardwareState.Error(createCaution(error))
            }
        }
    }

    fun createBleSigningRequest(onCreated: (String) -> Unit) {
        if (_unsignedTxState.value == SendTransactionHardwareState.Loading) return
        _unsignedTxState.value = SendTransactionHardwareState.Loading
        viewModelScope.launch {
            try {
                val unsignedHex = adapter.getUnsignedShieldTransaction()
                val hardwareAccount = wallet.account.type as AccountType.ZcashHardware
                val requestId = HardwareWalletBleModule.signingRequestRepository.create(
                    payload = unsignedHex.hexToByteArray(),
                    blockchainType = blockchainType,
                    networkCoinType = if (adapter.isTestNet()) {
                        TESTNET_COIN_TYPE
                    } else {
                        ZCASH_MAINNET_COIN_TYPE
                    },
                    accountIndex = hardwareAccount.accountIndex,
                )
                bleRequestId = requestId
                onCreated(requestId)
            } catch (error: Exception) {
                _unsignedTxState.value = SendTransactionHardwareState.Error(createCaution(error))
            }
        }
    }

    fun onBleFlowResumed() {
        val requestId = bleRequestId ?: return
        when (HardwareWalletBleModule.signingRequestRepository.status(requestId)) {
            HardwareWalletSigningRequestStatus.Completed -> {
                bleRequestId = null
                _unsignedTxState.value = SendTransactionHardwareState.ScanToTransmit
            }
            null -> resetHardwareWalletState()
            else -> Unit
        }
    }

    fun onNFCWritingSuccess() {
        _unsignedTxState.value = SendTransactionHardwareState.ScanToTransmit
    }

    fun onScannedQR(value: String?) {
        Log.d("ShieldZcashViewModel", "onScannedQR: $value")
        if (value?.startsWith("50435a54") == true) {
            scannedQr = value
            onClickSend()
        } else {
            _unsignedTxState.value = SendTransactionHardwareState.Error(
                createCaution(IOException("Scan Error"))
            )
        }
    }

    fun resetHardwareWalletState() {
        bleRequestId?.let(HardwareWalletBleModule.signingRequestRepository::remove)
        bleRequestId = null
        scannedQr = null
        adapter.clearHardwareSigningRequest()
        _unsignedTxState.value = initialHardwareState
    }

    override fun onCleared() {
        bleRequestId?.let(HardwareWalletBleModule.signingRequestRepository::remove)
        adapter.clearHardwareSigningRequest()
        super.onCleared()
    }

    private suspend fun send() = withContext(Dispatchers.IO) {
        val logger = logger.getScopedUnique()
        logger.info("click")

        try {
            sendResult = SendResult.Sending

            if (adapter.isHardwareAccount()) {
                adapter.sendRawTransaction(
                    pcztHex = checkNotNull(scannedQr) { "Signed PCZT missing" },
                    logger = logger,
                )
            } else {
                adapter.sendShieldProposal()
            }

            logger.info("success")
            sendResult = SendResult.Sent()

        } catch (e: Throwable) {
            logger.warning("failed", e)
            sendResult = SendResult.Failed(createCaution(e))
        }
    }

    private fun createCaution(error: Throwable) = when (error) {
        is UnknownHostException -> HSCaution(TranslatableString.ResString(R.string.Hud_Text_NoInternet))
        is LocalizedException -> HSCaution(TranslatableString.ResString(error.errorTextRes))
        else -> HSCaution(TranslatableString.PlainString(error.message ?: ""))
    }
}
