package io.horizontalsystems.bankwallet.modules.send.stellar

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.core.App
import io.horizontalsystems.bankwallet.core.AppLogger
import io.horizontalsystems.bankwallet.core.HSCaution
import io.horizontalsystems.bankwallet.core.ISendStellarAdapter
import io.horizontalsystems.bankwallet.core.LocalizedException
import io.horizontalsystems.bankwallet.core.ViewModelUiState
import io.horizontalsystems.bankwallet.core.managers.RecentAddressManager
import io.horizontalsystems.bankwallet.entities.Address
import io.horizontalsystems.bankwallet.entities.Wallet
import io.horizontalsystems.bankwallet.modules.amount.SendAmountService
import io.horizontalsystems.bankwallet.modules.contacts.ContactsRepository
import io.horizontalsystems.bankwallet.modules.hardwarewallet.SendTransactionHardwareState
import io.horizontalsystems.bankwallet.modules.send.SendConfirmationData
import io.horizontalsystems.bankwallet.modules.send.SendResult
import io.horizontalsystems.bankwallet.modules.xrate.XRateService
import io.horizontalsystems.bankwallet.ui.compose.TranslatableString
import io.horizontalsystems.marketkit.models.BlockchainType
import io.horizontalsystems.marketkit.models.Token
import io.horizontalsystems.marketkit.models.TokenType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.math.BigDecimal
import java.net.UnknownHostException

class SendStellarViewModel(
    val wallet: Wallet,
    private val sendToken: Token,
    val feeToken: Token,
    private val adapter: ISendStellarAdapter,
    val coinMaxAllowedDecimals: Int,
    private val xRateService: XRateService,
    private val address: Address,
    private val showAddressInput: Boolean,
    private val amountService: SendAmountService,
    private val addressService: SendStellarAddressService,
    private val contactsRepo: ContactsRepository,
    private val recentAddressManager: RecentAddressManager,
    private val minimumAmountService: SendStellarMinimumAmountService
) : ViewModelUiState<SendStellarUiState>() {
    private val fee = adapter.fee

    val blockchainType = wallet.token.blockchainType
    val feeTokenMaxAllowedDecimals = feeToken.decimals
    val fiatMaxAllowedDecimals = App.appConfigProvider.fiatDecimal

    private var amountState = amountService.stateFlow.value
    private var addressState = addressService.stateFlow.value
    private var minimumAmountState = minimumAmountService.stateFlow.value
    private var memo: String? = null

    private val initialState = if (adapter.isHardwareAccount()) SendTransactionHardwareState.ReadyToLoad else null
    private val _unsignedTxState = MutableStateFlow<SendTransactionHardwareState?>(initialState)
    val unsignedTxState: StateFlow<SendTransactionHardwareState?> = _unsignedTxState.asStateFlow()

    private var scannedQr: String? = null

    var coinRate by mutableStateOf(xRateService.getRate(sendToken.coin.uid))
        private set
    var feeCoinRate by mutableStateOf(xRateService.getRate(feeToken.coin.uid))
        private set
    var sendResult by mutableStateOf<SendResult?>(null)
        private set

    private val logger: AppLogger = AppLogger("send-stellar")

    init {
//        addCloseable(feeService)

        viewModelScope.launch(Dispatchers.Default) {
            amountService.stateFlow.collect {
                handleUpdatedAmountState(it)
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            addressService.stateFlow.collect {
                handleUpdatedAddressState(it)
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            minimumAmountService.stateFlow.collect {
                handleUpdatedMinimumAmountState(it)
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            xRateService.getRateFlow(sendToken.coin.uid).collect {
                coinRate = it
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            xRateService.getRateFlow(feeToken.coin.uid).collect {
                feeCoinRate = it
            }
        }

        addressService.setAddress(address)
    }

    private fun handleUpdatedMinimumAmountState(state: SendStellarMinimumAmountService.State) {
        minimumAmountState = state

        amountService.setMinimumSendAmount(minimumAmountState.minimumAmount)

        emitState()
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

    override fun createState() = SendStellarUiState(
        availableBalance = amountState.availableBalance,
        amountCaution = amountState.amountCaution,
        addressError = addressState.addressError,
        minimumAmountError = minimumAmountState.error,
        canBeSend = amountState.canBeSend && addressState.canBeSend && minimumAmountState.canBeSend,
        showAddressInput = showAddressInput,
        fee = fee,
        address = address
    )

    fun onEnterAmount(amount: BigDecimal?) {
        amountService.setAmount(amount)
    }

    fun onEnterMemo(memo: String) {
        this.memo = memo.ifBlank { null }
    }

    private fun handleUpdatedAmountState(amountState: SendAmountService.State) {
        this.amountState = amountState

        emitState()
    }

    fun getUnsignedTransaction(amount: BigDecimal) {
        _unsignedTxState.value = SendTransactionHardwareState.Loading
        val from = wallet.account.type.stellarAddress() ?: throw IllegalStateException("Stellar address is not set")
        val to = addressState.address?.hex ?: throw IllegalStateException("Destination address is not set")
        val networkId = adapter.getNetworkPassphrase()
        val assetId = if (wallet.token.type is TokenType.Asset) {
            (wallet.token.type as TokenType.Asset).id
        } else {
            null
        }
        Log.d("AAA", "SendStellarViewModel.getUnsignedTransaction: assetId=$assetId, from=$from, to=$to, amount=$amount, memo=$memo")
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val hex = networkId + ":" + adapter.getUnsignedTransaction(assetId = assetId, destination = to, amount = amountState.amount!!, memo = memo)
                Log.d("AAA", "SendStellarViewModel.getUnsignedTransaction: $hex")
                _unsignedTxState.value = SendTransactionHardwareState.NFCWritingStarted(hex)
            } catch (e: Exception) {
                _unsignedTxState.value = SendTransactionHardwareState.Error(createCaution(e))
            }
        }
    }



    private suspend fun handleUpdatedAddressState(addressState: SendStellarAddressService.State) {
        this.addressState = addressState

        minimumAmountService.setValidAddress(addressState.validAddress)

        emitState()
    }

    fun getConfirmationData(): SendConfirmationData {
        val address = addressState.address!!
        val contact = contactsRepo.getContactsFiltered(
            blockchainType,
            addressQuery = address.hex
        ).firstOrNull()
        return SendConfirmationData(
            amount = amountState.amount!!,
            fee = fee,
            address = address,
            contact = contact,
            coin = wallet.coin,
            feeCoin = feeToken.coin,
            memo = memo,
        )
    }

    fun resetHardwareWalletState() {
        _unsignedTxState.value = initialState
    }

    fun onNFCWritingSuccess() {
        _unsignedTxState.value = SendTransactionHardwareState.ScanToTransmit
    }


    fun onClickSend() {
        logger.info("click send button")

        viewModelScope.launch {
            send()
        }
    }

    private suspend fun send() = withContext(Dispatchers.IO) {
        try {
            sendResult = SendResult.Sending
            logger.info("sending tx")

            if (adapter.isHardwareAccount()) {
                Log.d("AAA", "SendStellarViewModel.send: scannedQr=$scannedQr")
                _unsignedTxState.value = SendTransactionHardwareState.Sending
                adapter.sendRawTransaction(scannedQr!!)
                _unsignedTxState.value = SendTransactionHardwareState.Sent
            } else {
                adapter.send(amountState.amount!!, addressState.address?.hex!!, memo)
            }

            sendResult = SendResult.Sent()
            logger.info("success")

            recentAddressManager.setRecentAddress(addressState.address!!, BlockchainType.Stellar)
        } catch (e: Throwable) {
            sendResult = SendResult.Failed(createCaution(e))
            if (adapter.isHardwareAccount()) {
                _unsignedTxState.value = SendTransactionHardwareState.Error(createCaution(e))
            }
            logger.warning("failed", e)
        }
    }

    private fun createCaution(error: Throwable) = when (error) {
        is UnknownHostException -> HSCaution(TranslatableString.ResString(R.string.Hud_Text_NoInternet))
        is LocalizedException -> HSCaution(TranslatableString.ResString(error.errorTextRes))
        else -> HSCaution(TranslatableString.PlainString(error.message ?: ""))
    }
}

data class SendStellarUiState(
    val availableBalance: BigDecimal?,
    val amountCaution: HSCaution?,
    val addressError: Throwable?,
    val minimumAmountError: Throwable?,
    val canBeSend: Boolean,
    val showAddressInput: Boolean,
    val fee: BigDecimal?,
    val address: Address,
)

