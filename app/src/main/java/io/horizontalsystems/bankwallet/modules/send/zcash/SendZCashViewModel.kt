package io.horizontalsystems.bankwallet.modules.send.zcash

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.ext.collectWith
import cash.z.ecc.android.sdk.model.ZcashNetwork
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.core.App
import io.horizontalsystems.bankwallet.core.AppLogger
import io.horizontalsystems.bankwallet.core.HSCaution
import io.horizontalsystems.bankwallet.core.ISendZcashAdapter
import io.horizontalsystems.bankwallet.core.LocalizedException
import io.horizontalsystems.bankwallet.core.ViewModelUiState
import io.horizontalsystems.bankwallet.core.hexToByteArray
import io.horizontalsystems.bankwallet.core.managers.RecentAddressManager
import io.horizontalsystems.bankwallet.entities.AccountType
import io.horizontalsystems.bankwallet.entities.Address
import io.horizontalsystems.bankwallet.entities.Wallet
import io.horizontalsystems.bankwallet.modules.amount.SendAmountService
import io.horizontalsystems.bankwallet.modules.contacts.ContactsRepository
import io.horizontalsystems.bankwallet.modules.hardwarewallet.SendTransactionHardwareState
import io.horizontalsystems.bankwallet.modules.hardwarewallet.ble.HardwareWalletBleModule
import io.horizontalsystems.bankwallet.modules.hardwarewallet.ble.HardwareWalletSigningRequestStatus
import io.horizontalsystems.bankwallet.modules.send.SendConfirmationData
import io.horizontalsystems.bankwallet.modules.send.SendResult
import io.horizontalsystems.bankwallet.modules.xrate.XRateService
import io.horizontalsystems.bankwallet.ui.compose.TranslatableString
import io.horizontalsystems.marketkit.models.BlockchainType
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
import kotlin.math.min

private const val TESTNET_COIN_TYPE = 1
private const val ZCASH_MAINNET_COIN_TYPE = 133

class SendZCashViewModel(
    private val adapter: ISendZcashAdapter,
    val wallet: Wallet,
    private val xRateService: XRateService,
    private val amountService: SendAmountService,
    private val addressService: SendZCashAddressService,
    private val memoService: SendZCashMemoService,
    private val contactsRepo: ContactsRepository,
    private val showAddressInput: Boolean,
    private val address: Address,
    private val recentAddressManager: RecentAddressManager
) : ViewModelUiState<SendZCashUiState>() {
    val blockchainType = wallet.token.blockchainType
    val coinMaxAllowedDecimals = wallet.token.decimals
    val fiatMaxAllowedDecimals = App.appConfigProvider.fiatDecimal
    val memoMaxLength by memoService::memoMaxLength

    private val initialState = if (adapter.isHardwareAccount()) SendTransactionHardwareState.ReadyToLoad else null
    private val _unsignedTxState = MutableStateFlow<SendTransactionHardwareState?>(initialState)
    val unsignedTxState: StateFlow<SendTransactionHardwareState?> = _unsignedTxState.asStateFlow()

    private var scannedQr: String? = null
    private var bleRequestId: String? = null

    private val fee = adapter.fee
    private var amountState = amountService.stateFlow.value
    private var addressState = addressService.stateFlow.value
    private var memoState = memoService.stateFlow.value

    var coinRate by mutableStateOf(xRateService.getRate(wallet.coin.uid))
        private set
    var sendResult by mutableStateOf<SendResult?>(null)
        private set

    private val logger = AppLogger("Send-${wallet.coin.code}")

    init {
        xRateService.getRateFlow(wallet.coin.uid).collectWith(viewModelScope) {
            coinRate = it
        }
        amountService.stateFlow.collectWith(viewModelScope) {
            handleUpdatedAmountState(it)
        }
        addressService.stateFlow.collectWith(viewModelScope) {
            handleUpdatedAddressState(it)
        }
        memoService.stateFlow.collectWith(viewModelScope) {
            handleUpdatedMemoState(it)
        }

        viewModelScope.launch {
            addressService.setAddress(address)
        }
    }

    override fun createState() = SendZCashUiState(
        fee = fee,
        availableBalance = amountState.availableBalance,
        addressError = addressState.addressError,
        amountCaution = amountState.amountCaution,
        memoIsAllowed = memoState.memoIsAllowed,
        canBeSend = amountState.canBeSend && addressState.canBeSend,
        showAddressInput = showAddressInput,
        address = address
    )

    fun onEnterAmount(amount: BigDecimal?) {
        amountService.setAmount(amount)
    }

    fun onEnterAddress(address: Address?) {
        viewModelScope.launch {
            addressService.setAddress(address)
        }
    }

    fun onEnterMemo(memo: String) {
        memoService.setMemo(memo)
    }

    private fun handleUpdatedAmountState(amountState: SendAmountService.State) {
        this.amountState = amountState

        emitState()
    }

    private fun handleUpdatedAddressState(addressState: SendZCashAddressService.State) {
        this.addressState = addressState

        memoService.setAddressType(addressState.addressType)

        emitState()
    }

    private fun handleUpdatedMemoState(memoState: SendZCashMemoService.State) {
        this.memoState = memoState

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
            feeCoin = wallet.coin,
            memo = memoState.memo
        )
    }

    fun getUnsignedTransaction(amount: BigDecimal) {
        _unsignedTxState.value = SendTransactionHardwareState.Loading
        viewModelScope.launch {
            val address = addressState.address!!.hex
            try {
                val hex = adapter.getUnsignedTransaction(address = address, amount = amount, memo = memoState.memo, logger = logger)
                _unsignedTxState.value = SendTransactionHardwareState.NFCWritingStarted(hex)
            } catch (e: Exception) {
                _unsignedTxState.value = SendTransactionHardwareState.Error(createCaution(e))
            }
        }
    }

    fun createBleSigningRequest(amount: BigDecimal, onCreated: (String) -> Unit) {
        if (_unsignedTxState.value == SendTransactionHardwareState.Loading) return
        _unsignedTxState.value = SendTransactionHardwareState.Loading
        viewModelScope.launch {
            try {
                val unsignedHex = adapter.getUnsignedTransaction(
                    address = addressState.address!!.hex,
                    amount = amount,
                    memo = memoState.memo,
                    logger = logger,
                )

                val chunkSize = 3000
                val chunks = unsignedHex.chunked(chunkSize)
                Log.d("PCZT", "BEGIN length=${unsignedHex.length} chunks=${chunks.size}")
                chunks.forEachIndexed { index, chunk ->
                    Log.d("PCZT", "CHUNK[$index]=$chunk")
                }
                Log.d("PCZT", "END")

                val requestId = HardwareWalletBleModule.signingRequestRepository.create(
                    payload = unsignedHex.hexToByteArray(),
                    blockchainType = blockchainType,
                    networkCoinType = if (adapter.isTestNet()) {
                        TESTNET_COIN_TYPE
                    } else {
                        ZCASH_MAINNET_COIN_TYPE
                    },
                    accountIndex = (wallet.account.type as AccountType.ZcashHardware).accountIndex,
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
            HardwareWalletSigningRequestStatus.Completed -> submitBleSignedTransaction(requestId)
            null -> {
                bleRequestId = null
                adapter.clearHardwareSigningRequest()
                _unsignedTxState.value = initialState
            }
            else -> Unit
        }
    }

    private fun submitBleSignedTransaction(requestId: String) {
//        val signedPayload = HardwareWalletBleModule.signingRequestRepository.consumeResult(requestId)
//            ?: return
        bleRequestId = null
        _unsignedTxState.value = SendTransactionHardwareState.ScanToTransmit
//        viewModelScope.launch {
//            try {
//                sendResult = SendResult.Sending
//                withContext(Dispatchers.IO) {
//                    adapter.submitHardwareSignedTransaction(signedPayload)
//                }
//                sendResult = SendResult.Sent()
//                _unsignedTxState.value = SendTransactionHardwareState.Sent
//                recentAddressManager.setRecentAddress(addressState.address!!, BlockchainType.Zcash)
//            } catch (error: Throwable) {
//                signedPayload.fill(0)
//                sendResult = SendResult.Failed(createCaution(error))
//                _unsignedTxState.value = SendTransactionHardwareState.Error(createCaution(error))
//            }
//        }
    }

    fun onScannedQR(value: String?) {
        Log.d("SendZCashViewModel", "onScannedQR: $value")
        if (value == null) {
            _unsignedTxState.value = SendTransactionHardwareState.Error(createCaution(IOException("Scan Error")))
            return
        }
        if (value.startsWith("50435a54")) {
            scannedQr = value
            onClickSend()
        } else {
            _unsignedTxState.value = SendTransactionHardwareState.Error(createCaution(IOException("Scan Error")))
            return
        }
    }

    fun isHardwareAccount() : Boolean {
        return adapter.isHardwareAccount()
    }

    fun resetHardwareWalletState() {
        bleRequestId?.let(HardwareWalletBleModule.signingRequestRepository::remove)
        bleRequestId = null
        adapter.clearHardwareSigningRequest()
        _unsignedTxState.value = initialState
    }

    override fun onCleared() {
        bleRequestId?.let(HardwareWalletBleModule.signingRequestRepository::remove)
        bleRequestId = null
        adapter.clearHardwareSigningRequest()
        super.onCleared()
    }

    fun onNFCWritingSuccess() {
        _unsignedTxState.value = SendTransactionHardwareState.ScanToTransmit
    }

    fun onClickSend() {
        viewModelScope.launch {
            send()
        }
    }

    private suspend fun send() = withContext(Dispatchers.IO) {
        val logger = logger.getScopedUnique()
        logger.info("click")

        try {
            sendResult = SendResult.Sending

            if (adapter.isHardwareAccount()) {
                adapter.sendRawTransaction(scannedQr!!, logger)
            } else {
                adapter.send(
                    amountState.amount!!,
                    addressState.address!!.hex,
                    memoState.memo,
                    logger
                )
            }

            logger.info("success")
            sendResult = SendResult.Sent()

            recentAddressManager.setRecentAddress(addressState.address!!, BlockchainType.Zcash)
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

data class SendZCashUiState(
    val fee: BigDecimal,
    val availableBalance: BigDecimal,
    val addressError: Throwable?,
    val amountCaution: HSCaution?,
    val memoIsAllowed: Boolean,
    val canBeSend: Boolean,
    val showAddressInput: Boolean,
    val address: Address,
)
