package io.horizontalsystems.bankwallet.modules.receive

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.core.App
import io.horizontalsystems.bankwallet.core.HSCaution
import io.horizontalsystems.bankwallet.core.IAdapterManager
import io.horizontalsystems.bankwallet.core.LocalizedException
import io.horizontalsystems.bankwallet.core.ViewModelUiState
import io.horizontalsystems.bankwallet.core.adapters.StellarAssetAdapter
import io.horizontalsystems.bankwallet.entities.CoinValue
import io.horizontalsystems.bankwallet.entities.Currency
import io.horizontalsystems.bankwallet.entities.CurrencyValue
import io.horizontalsystems.bankwallet.entities.Wallet
import io.horizontalsystems.bankwallet.modules.hardwarewallet.SendTransactionHardwareState
import io.horizontalsystems.bankwallet.modules.send.SendResult
import io.horizontalsystems.bankwallet.modules.xrate.XRateService
import io.horizontalsystems.bankwallet.ui.compose.TranslatableString
import io.horizontalsystems.marketkit.models.BlockchainType
import io.horizontalsystems.marketkit.models.Token
import io.horizontalsystems.marketkit.models.TokenQuery
import io.horizontalsystems.marketkit.models.TokenType
import io.horizontalsystems.stellarkit.EnablingAssetError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.math.BigDecimal
import java.net.UnknownHostException

class ActivateTokenViewModel(
    wallet: Wallet,
    feeToken: Token,
    adapterManager: IAdapterManager,
    xRateService: XRateService,
) : ViewModelUiState<ActivateTokenUiState>() {
    private val token = wallet.token
    private val adapter = adapterManager.getAdapterForWallet<StellarAssetAdapter>(wallet)
    private var activateEnabled = false
    private var error: ActivateTokenError? = null
    private val feeAmount = adapter?.activationFee
    private var feeCoinValue: CoinValue? = null
    private var feeFiatValue: CurrencyValue? = null

    private val initialState = if (isHardwareAccount()) SendTransactionHardwareState.ReadyToLoad else null
    private val _unsignedTxState = MutableStateFlow<SendTransactionHardwareState?>(initialState)
    val unsignedTxState: StateFlow<SendTransactionHardwareState?> = _unsignedTxState.asStateFlow()

    private var scannedQr: String? = null


    fun resetHardwareWalletState() {
        _unsignedTxState.value = initialState
    }

    fun onNFCWritingSuccess() {
        _unsignedTxState.value = SendTransactionHardwareState.ScanToTransmit
    }

    fun getUnsignedTransaction() {
        _unsignedTxState.value = SendTransactionHardwareState.Loading
        val assetId = adapter?.getStellarAssetId()
        if (assetId == null) {
            _unsignedTxState.value = SendTransactionHardwareState.Error(createCaution(IOException("Asset not found")))
            return
        }
        val networkId = adapter?.getNetworkPassphrase()
        if (networkId == null) {
            _unsignedTxState.value = SendTransactionHardwareState.Error(createCaution(IOException("Network not found")))
            return
        }
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val hex = "$networkId:" + adapter!!.getChangeTrustAssetTransaction(
                    assetId,
                    null
                )
                Log.d("AAA", "SendStellarViewModel.getUnsignedTransaction: $hex")
                _unsignedTxState.value = SendTransactionHardwareState.NFCWritingStarted(hex)
            } catch (e: Exception) {
                _unsignedTxState.value = SendTransactionHardwareState.Error(createCaution(e))
            }
        }
    }

    fun onScannedQR(value: String?) {
        if (value == null) {
            _unsignedTxState.value = SendTransactionHardwareState.Error(createCaution(IOException("Scan Error")))
            return
        }
        if (value.startsWith("https://app.hito.dev/eth/tx/#!")) {
            scannedQr = value.removePrefix("https://app.hito.dev/eth/tx/#!")
            onClickSend()
        } else {
            _unsignedTxState.value = SendTransactionHardwareState.Error(createCaution(IOException("Scan Error")))
            return
        }
    }

    fun onClickSend() {
        viewModelScope.launch {
            send()
        }
    }

    private suspend fun send() = withContext(Dispatchers.IO) {
        if (!isHardwareAccount()) {
            return@withContext
        }
        try {
            Log.d("AAA", "SendStellarViewModel.send: scannedQr=$scannedQr")
            _unsignedTxState.value = SendTransactionHardwareState.Sending
            adapter?.sendRawTransaction(scannedQr!!)
            _unsignedTxState.value = SendTransactionHardwareState.Sent
        } catch (e: Throwable) {
            _unsignedTxState.value = SendTransactionHardwareState.Error(createCaution(e))
        }
    }

    init {
        viewModelScope.launch(Dispatchers.Default) {
            val tmpAdapter = adapter

            if (tmpAdapter == null) {
                activateEnabled = false
                error = ActivateTokenError.NullAdapter()
            } else if (tmpAdapter.isTrustlineEstablished()) {
                activateEnabled = false
                error = ActivateTokenError.AlreadyActive()
            } else try {
                tmpAdapter.validateActivation()

                activateEnabled = true
                error = null
            } catch (e: EnablingAssetError.InsufficientBalance) {
                activateEnabled = false
                error = ActivateTokenError.InsufficientBalance()
            }

            feeAmount?.let { feeAmount ->
                feeCoinValue = CoinValue(feeToken, feeAmount)
                feeFiatValue = xRateService.getRate(feeToken.coin.uid)?.let { rate ->
                    rate.copy(value = rate.value * feeAmount)
                }
            }

            emitState()
        }
    }

    fun isHardwareAccount(): Boolean {
        return adapter?.isHardwareAccount() == true
    }

    override fun createState() = ActivateTokenUiState(
        token = token,
        currency = App.currencyManager.baseCurrency,
        activateEnabled = activateEnabled,
        error = error,
        feeCoinValue = feeCoinValue,
        feeFiatValue = feeFiatValue
    )

    suspend fun activate() = withContext(Dispatchers.Default) {
        adapter?.activate()
    }

    private fun createCaution(error: Throwable) = when (error) {
        is UnknownHostException -> HSCaution(TranslatableString.ResString(R.string.Hud_Text_NoInternet))
        is LocalizedException -> HSCaution(TranslatableString.ResString(error.errorTextRes))
        else -> HSCaution(TranslatableString.PlainString(error.message ?: ""))
    }

    class Factory(private val wallet: Wallet) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val feeToken =
                App.coinManager.getToken(TokenQuery(wallet.token.blockchainType, TokenType.Native)) ?: throw IllegalArgumentException()

            val xRateService = XRateService(App.marketKit, App.currencyManager.baseCurrency)

            return ActivateTokenViewModel(
                wallet,
                feeToken,
                App.adapterManager,
                xRateService
            ) as T
        }
    }
}

sealed class ActivateTokenError : Throwable() {
    class NullAdapter : ActivateTokenError()
    class AlreadyActive : ActivateTokenError()
    class InsufficientBalance : ActivateTokenError()
}

data class ActivateTokenUiState(
    val token: Token,
    val currency: Currency,
    val activateEnabled: Boolean,
    val error: ActivateTokenError?,
    val feeCoinValue: CoinValue?,
    val feeFiatValue: CurrencyValue?
)
