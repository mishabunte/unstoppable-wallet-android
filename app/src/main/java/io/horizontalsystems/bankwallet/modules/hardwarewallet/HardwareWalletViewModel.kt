package io.horizontalsystems.bankwallet.modules.hardwarewallet

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.entities.AccountType
import io.horizontalsystems.bankwallet.entities.Address
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.tool.DerivationTool
import io.horizontalsystems.bankwallet.modules.hardwarewallet.ble.HardwareWalletSigningRequestRepository
import io.horizontalsystems.bankwallet.modules.hardwarewallet.ble.HardwareWalletSigningRequestStatus
import io.horizontalsystems.marketkit.models.BlockchainType
import io.horizontalsystems.hdwalletkit.HDExtendedKey
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val TESTNET_COIN_TYPE = 1
private const val ZCASH_MAINNET_COIN_TYPE = 133
private const val MAX_ACCOUNT_INDEX = 0x7FFF_FFFFL

fun interface ZcashAddressDeriver {
    suspend fun derive(viewingKey: String, network: ZcashNetwork): String
}

class HardwareWalletViewModel(
    private val hardwareWalletService: HardwareWalletService,
    private val pairingRequestRepository: HardwareWalletSigningRequestRepository,
    private val zcashAddressDeriver: ZcashAddressDeriver = ZcashAddressDeriver { viewingKey, network ->
        DerivationTool
            .getInstance()
            .deriveUnifiedAddress(
                viewingKey = viewingKey,
                network = network,
            )
    },
) : ViewModel() {

    private var accountCreated = false
    private var submitButtonType: SubmitButtonType = SubmitButtonType.Next(false)
    private var type = Type.EvmAddressHardware
    private var zcashNetworkType: ZcashNetworkType? = null
    private var address: Address? = null
    private var xPubKey: String? = null
    private var invalidXPubKey = false
    private var zcashUfvkInput = ""
    private var zcashUfvk: String? = null
    private var zcashSeedFingerprint: String? = null
    private var zcashAccountIndex: Long? = null
    private var zcashExternalNsk: String? = null
    private var zcashInternalNsk: String? = null
    private var zcashAccountIndexInput = "0"
    private var zcashPairingRequestId: String? = null
    private var zcashPairingNavigationRequestId: String? = null
    private var zcashAwaitingQr = false

    private val seedFingerprintRegex = Regex("^[0-9a-fA-F]{64}$")
    private var zcashUnifiedAddress: String? = null
    private var invalidZcashUfvk = false
    private var zcashValidationJob: Job? = null

    private var accountType: AccountType? = null
    private var accountNameEdited = false
    val defaultAccountName = hardwareWalletService.nextHardwareAccountName()
    var accountName: String = defaultAccountName
        get() = field.ifBlank { defaultAccountName }
        private set


    var uiState by mutableStateOf(
        HardwareWalletUiState(
            accountCreated = accountCreated,
            submitButtonType = submitButtonType,
            type = type,
            accountType = accountType,
            accountName = accountName,
            invalidXPubKey = invalidXPubKey,
            invalidZcashUfvk = invalidZcashUfvk,
            zcashNetworkType = zcashNetworkType,
            zcashAccountIndex = zcashAccountIndexInput,
            invalidZcashAccountIndex = !isZcashAccountIndexValid(),
            zcashPairingRequestId = zcashPairingNavigationRequestId,
            zcashAwaitingQr = zcashAwaitingQr,
        )
    )
        private set

    private fun emitState() {
        uiState = HardwareWalletUiState(
            accountCreated = accountCreated,
            submitButtonType = submitButtonType,
            type = type,
            accountType = accountType,
            accountName = accountName,
            invalidXPubKey = invalidXPubKey,
            invalidZcashUfvk = invalidZcashUfvk,
            zcashNetworkType = zcashNetworkType,
            zcashAccountIndex = zcashAccountIndexInput,
            invalidZcashAccountIndex = !isZcashAccountIndexValid(),
            zcashPairingRequestId = zcashPairingNavigationRequestId,
            zcashAwaitingQr = zcashAwaitingQr,
        )
    }

    fun onEnterAccountName(v: String) {
        accountNameEdited = v.isNotBlank()
        accountName = v
    }

    fun onEnterZcashUfvk(value: String?) {
        handleZcashUfvk(value, activateAccountOnSuccess = false)
    }

    fun onPairingQrScanned(value: String?) {
        handleZcashUfvk(value, activateAccountOnSuccess = true)
    }

    private fun handleZcashUfvk(
        value: String?,
        activateAccountOnSuccess: Boolean,
    ) {
        zcashValidationJob?.cancel()

        val normalized = value?.trim().orEmpty()

        zcashUfvkInput = normalized
        zcashUfvk = null
        zcashUnifiedAddress = null
        zcashSeedFingerprint = null
        zcashAccountIndex = null
        invalidZcashUfvk = false

        if (normalized.isBlank()) {
            invalidZcashUfvk = activateAccountOnSuccess
            syncSubmitButtonType()
            emitState()
            return
        }

        val pairingPayloadParts = AccountType.ZcashHardware.decodeHitoPayload(normalized)
        val ufvk = pairingPayloadParts[2]
        val seedFingerprint = pairingPayloadParts[3]
        val accountIndex = pairingPayloadParts[4].toLong()
        val externalNsk = pairingPayloadParts[5]
        val internalNsk = pairingPayloadParts[6]

        val network = when {
            ufvk.startsWith("uviewtest1") -> ZcashNetwork.Testnet
            ufvk.startsWith("uview1") -> ZcashNetwork.Mainnet
            else -> null
        }

        val configuredAccountIndex = parsedZcashAccountIndex()
        val selectedNetwork = zcashNetworkType?.network

        if (
            network == null ||
            network != selectedNetwork ||
            !seedFingerprintRegex.matches(seedFingerprint) ||
            accountIndex == null ||
            accountIndex !in 0..MAX_ACCOUNT_INDEX ||
            accountIndex != configuredAccountIndex
        ) {
            invalidZcashUfvk = true
            syncSubmitButtonType()
            emitState()
            return
        }

        zcashSeedFingerprint = seedFingerprint
        zcashAccountIndex = accountIndex
        zcashInternalNsk = internalNsk
        zcashExternalNsk = externalNsk

        syncSubmitButtonType()
        emitState()

        zcashValidationJob = viewModelScope.launch {
            val result = runCatching {
                zcashAddressDeriver.derive(ufvk, network)
            }

            // Ignore a stale result if the input has already changed.
            if (zcashUfvkInput != normalized) {
                return@launch
            }

            result.onSuccess { unifiedAddress ->
                zcashUfvk = ufvk
                zcashUnifiedAddress = unifiedAddress
                invalidZcashUfvk = false
            }.onFailure {
                Log.e(
                    "HardwareWalletViewModel",
                    "Failed to derive unified address from UFVK",
                    it
                )
                zcashUfvk = null
                zcashUnifiedAddress = null
                invalidZcashUfvk = true
            }

            syncSubmitButtonType()
            if (activateAccountOnSuccess && !invalidZcashUfvk) {
                onClickDone()
            } else {
                emitState()
            }
        }
    }

    fun onEnterZcashAccountIndex(value: String) {
        zcashAccountIndexInput = value.filter(Char::isDigit)
        clearZcashQrResult()
        resetZcashPairing()
        syncSubmitButtonType()
        emitState()
    }

    fun onEnterAddress(v: Address?) {
        address = v
        if (!accountNameEdited) {
            accountName = v?.domain ?: defaultAccountName
        }

        syncSubmitButtonType()
        emitState()
    }

    fun onEnterXPubKey(v: String) {
        xPubKey = try {
            val hdKey = HDExtendedKey(v)
            require(hdKey.isPublic) {
                throw HDExtendedKey.ParsingError.WrongVersion
            }
            invalidXPubKey = false
            v
        } catch (t: Throwable) {
            invalidXPubKey = v.isNotBlank()
            null
        }

        syncSubmitButtonType()
        emitState()
    }

    fun blockchainSelectionOpened() {
        accountType = null

        emitState()
    }

    fun onClickNext() {
        if (type == Type.ZcashKeyHardware) {
            createZcashPairingRequest()
        } else {
            accountType = getAccountType()
        }

        emitState()
    }

    fun onZcashPairingNavigationHandled() {
        zcashPairingNavigationRequestId = null
        emitState()
    }

    fun onBleFlowResumed() {
        val requestId = zcashPairingRequestId ?: return

        when (pairingRequestRepository.status(requestId)) {
            HardwareWalletSigningRequestStatus.Completed -> {
                pairingRequestRepository.consumeResult(requestId)
                zcashPairingRequestId = null
                zcashPairingNavigationRequestId = null
                zcashAwaitingQr = true
                syncSubmitButtonType()
                emitState()
            }
            null -> {
                zcashPairingRequestId = null
                zcashPairingNavigationRequestId = null
                zcashAwaitingQr = false
                syncSubmitButtonType()
                emitState()
            }
            else -> Unit
        }
    }

    fun retryZcashPairing() {
        createZcashPairingRequest()
        emitState()
    }

    fun onClickDone() {
        try {
            val accountType = getAccountType() ?: throw Exception()

            hardwareWalletService.hardwareAll(accountType, accountName)

            accountCreated = true
            emitState()
        } catch (_: Exception) {

        }
    }

    fun onSetType(type: Type) {
        resetZcashPairing()
        this.type = type

        address = null
        xPubKey = null

        clearZcashQrResult()
        zcashNetworkType = null
        zcashAccountIndexInput = "0"

        if (!accountNameEdited) {
            accountName = defaultAccountName
        }

        syncSubmitButtonType()
        emitState()
    }

    fun onSetZcashNetworkType(zcashNetworkType: ZcashNetworkType) {
        resetZcashPairing()
        this.zcashNetworkType = zcashNetworkType

        address = null
        xPubKey = null

        clearZcashQrResult()

        if (!accountNameEdited) {
            accountName = defaultAccountName
        }

        syncSubmitButtonType()
        emitState()
    }

    private fun syncSubmitButtonType() {
        submitButtonType = when (type) {
            Type.EvmAddressHardware    -> SubmitButtonType.Next(address != null)
            Type.StellarAddressHardware -> SubmitButtonType.Next(address != null)
            Type.ZcashKeyHardware -> SubmitButtonType.Next(
                zcashNetworkType != null &&
                    isZcashAccountIndexValid() &&
                    zcashPairingRequestId == null &&
                    !zcashAwaitingQr,
            )
            //Type.XPubKeyHardware       -> SubmitButtonType.Next(xPubKey != null)
            Type.SolanaAddressHardware -> SubmitButtonType.Next(address != null)
            //Type.TronAddressHardware   -> SubmitButtonType.Done(address != null)
        }
    }

    private fun createZcashPairingRequest() {
        val networkType = zcashNetworkType ?: return
        val accountIndex = parsedZcashAccountIndex() ?: return

        resetZcashPairing()
        val requestId = pairingRequestRepository.create(
            payload = byteArrayOf(),
            blockchainType = BlockchainType.Zcash,
            networkCoinType = networkType.networkCoinType,
            accountIndex = accountIndex,
        )
        zcashPairingRequestId = requestId
        zcashPairingNavigationRequestId = requestId
        zcashAwaitingQr = false
        syncSubmitButtonType()
    }

    private fun resetZcashPairing() {
        zcashPairingRequestId?.let(pairingRequestRepository::remove)
        zcashPairingRequestId = null
        zcashPairingNavigationRequestId = null
        zcashAwaitingQr = false
    }

    private fun clearZcashQrResult() {
        zcashValidationJob?.cancel()
        zcashUfvkInput = ""
        zcashUfvk = null
        zcashUnifiedAddress = null
        zcashSeedFingerprint = null
        zcashAccountIndex = null
        invalidZcashUfvk = false
    }

    private fun parsedZcashAccountIndex(): Long? =
        zcashAccountIndexInput.toLongOrNull()?.takeIf { it in 0..MAX_ACCOUNT_INDEX }

    private fun isZcashAccountIndexValid(): Boolean =
        parsedZcashAccountIndex() != null

    private fun getAccountType(): AccountType? = when (type) {
        Type.EvmAddressHardware ->
            address?.let { AccountType.EvmAddressHardware(it.hex) }

        Type.SolanaAddressHardware ->
            address?.let { AccountType.SolanaAddressHardware(it.hex) }

        Type.StellarAddressHardware ->
            address?.let { AccountType.StellarAddressHardware(it.hex) }

        Type.ZcashKeyHardware -> {
            val ufvk = zcashUfvk
            val unifiedAddress = zcashUnifiedAddress
            val seedFingerprint = zcashSeedFingerprint
            val accountIndex = zcashAccountIndex
            val externalNsk = zcashExternalNsk
            val internalNsk = zcashInternalNsk

            if (
                ufvk != null &&
                unifiedAddress != null &&
                seedFingerprint != null &&
                accountIndex != null
            ) {
                AccountType.ZcashHardware(
                    ufvk = ufvk,
                    unifiedAddress = unifiedAddress,
                    seedFingerprint = seedFingerprint,
                    accountIndex = accountIndex,
                    externalNsk = externalNsk!!,
                    internalNsk = internalNsk!!,
                    isTestNet = ufvk.startsWith("uviewtest1")
                )
            } else {
                null
            }
        }
    }

    override fun onCleared() {
        zcashValidationJob?.cancel()
        resetZcashPairing()
        super.onCleared()
    }

    enum class ZcashNetworkType(
        val titleResId: Int,
        val network: ZcashNetwork,
        val networkCoinType: Int,
    ) {
        Mainnet(
            R.string.Hardware_LinkBy_TypeZcash_Mainnet,
            ZcashNetwork.Mainnet,
            ZCASH_MAINNET_COIN_TYPE,
        ),
        Testnet(
            R.string.Hardware_LinkBy_TypeZcash_Testnet,
            ZcashNetwork.Testnet,
            TESTNET_COIN_TYPE,
        ),
    }

    enum class Type(val titleResId: Int, val subtitleResId: Int) {
        EvmAddressHardware(R.string.Hardware_LinkBy_TypeEvmAddress, R.string.Hardware_LinkBy_TypeEvmAddress_Subtitle),
        StellarAddressHardware(R.string.Hardware_LinkBy_TypeStellarAddress, R.string.Hardware_LinkBy_TypeStellarAddress_Subtitle),
        ZcashKeyHardware(R.string.Hardware_LinkBy_TypeZcashKey, R.string.Hardware_LinkBy_TypeZcashKey_Subtitle),
        //TronAddressHardware(R.string.Watch_TypeTronAddress, R.string.Watch_TypeTronAddress_Subtitle),
        SolanaAddressHardware(R.string.Hardware_LinkBy_TypeSolanaAddress, R.string.Hardware_LinkBy_TypeSolanaAddress_Subtitle),
        //XPubKeyHardware(R.string.Watch_TypeXPubKey, R.string.Watch_TypeXPubKey_Subtitle),
    }
}

data class HardwareWalletUiState(
    val accountCreated: Boolean,
    val submitButtonType: SubmitButtonType,
    val type: HardwareWalletViewModel.Type,
    val accountType: AccountType?,
    val accountName: String?,
    val invalidXPubKey: Boolean,
    val invalidZcashUfvk: Boolean,
    val zcashNetworkType: HardwareWalletViewModel.ZcashNetworkType?,
    val zcashAccountIndex: String,
    val invalidZcashAccountIndex: Boolean,
    val zcashPairingRequestId: String?,
    val zcashAwaitingQr: Boolean,
)

sealed class SubmitButtonType {
    data class Done(val enabled: Boolean) : SubmitButtonType()
    data class Next(val enabled: Boolean) : SubmitButtonType()
}
