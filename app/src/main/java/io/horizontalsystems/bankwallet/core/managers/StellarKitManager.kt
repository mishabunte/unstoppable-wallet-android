package io.horizontalsystems.bankwallet.core.managers

import android.util.Log
import io.horizontalsystems.bankwallet.core.AdapterState
import io.horizontalsystems.bankwallet.core.App
import io.horizontalsystems.bankwallet.core.UnsupportedAccountException
import io.horizontalsystems.bankwallet.entities.Account
import io.horizontalsystems.bankwallet.entities.AccountType
import io.horizontalsystems.bankwallet.modules.hardwarewallet.HardwareWalletURLRequestHandler
import io.horizontalsystems.core.BackgroundManager
import io.horizontalsystems.core.BackgroundManagerState
import io.horizontalsystems.marketkit.models.TokenType
import io.horizontalsystems.stellarkit.Network
import io.horizontalsystems.stellarkit.StellarKit
import io.horizontalsystems.stellarkit.StellarWallet
import io.horizontalsystems.stellarkit.SyncState
import io.horizontalsystems.stellarkit.room.StellarAsset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal

const val MAINNET_NETWORK_ID = "7ac33997544e3175d266bd022439b22cdb16508c01163f26e5cb2a3e1045a979"
const val TESTNET_NETWORK_ID = "cee0302d59844d32bdca915c8203dd44b33fbb7edc19051ea37abedf28ecd472"

class StellarKitManager(
    private val backgroundManager: BackgroundManager,
) {
    private val scope = CoroutineScope(Dispatchers.Default)
    private var job: Job? = null
    private val _kitStartedFlow = MutableStateFlow(false)
    val kitStartedFlow: StateFlow<Boolean> = _kitStartedFlow

    var stellarKitWrapper: StellarKitWrapper? = null
        private set(value) {
            field = value

            _kitStartedFlow.update { value != null }
        }

    private var useCount = 0
    var currentAccount: Account? = null
        private set

    val statusInfo: Map<String, Any>?
        get() = stellarKitWrapper?.stellarKit?.statusInfo()

    @Synchronized
    fun getStellarKitWrapper(account: Account): StellarKitWrapper {
        if (this.stellarKitWrapper != null && currentAccount != account) {
            stop()
        }
        var isHardwareSigner = false

        if (this.stellarKitWrapper == null) {
            val accountType = account.type
            this.stellarKitWrapper = when (accountType) {
                is AccountType.Mnemonic,
                is AccountType.StellarAddress,
                is AccountType.StellarSecretKey -> {
                    createKitInstance(accountType, account, isHardwareSigner)
                }
                is AccountType.StellarAddressHardware -> {
                    isHardwareSigner = true
                    createKitInstance(accountType, account, isHardwareSigner)
                }

                else -> throw UnsupportedAccountException()
            }
            scope.launch {
                start()
            }
            useCount = 0
            currentAccount = account
        }

        useCount++
        return this.stellarKitWrapper!!
    }

    private fun createKitInstance(accountType: AccountType, account: Account, isHardwareAccount: Boolean?=false): StellarKitWrapper {
        val kit = StellarKit.getInstance(accountType.toStellarWallet(), Network.TestNet, App.instance, account.id)

        return StellarKitWrapper(kit, isHardwareAccount)
    }

    @Synchronized
    fun unlink(account: Account) {
        if (account == currentAccount) {
            useCount -= 1

            if (useCount < 1) {
                stop()
            }
        }
    }

    private fun stop() {
        stellarKitWrapper?.stellarKit?.stop()
        job?.cancel()
        stellarKitWrapper = null
        currentAccount = null
    }

    private suspend fun start() {
        stellarKitWrapper?.stellarKit?.start()
        job = scope.launch {
            backgroundManager.stateFlow.collect { state ->
                if (state == BackgroundManagerState.EnterForeground) {
                    stellarKitWrapper?.stellarKit?.let { kit ->
                        delay(1000)
                        kit.refresh()
                    }
                }
            }
        }
    }
}

class StellarKitWrapper(
    val stellarKit: StellarKit,
    val isHardwareAccount: Boolean? = false
) {
    fun createUnsignedTransactionHex(assetId: String?, destination: String, amount: BigDecimal, memo: String?): String {
        return if (assetId == null) {
            Log.d("AAA", "StellarKitWrapper.createUnsignedTransactionHex: native, amount=$amount, destination=$destination, memo=$memo")
            stellarKit.createUnsignedTxNativeBase64(amount, destination, memo)
        } else {
            Log.d("AAA", "StellarKitWrapper.createUnsignedTransactionHex: assetId=$assetId, amount=$amount, destination=$destination, memo=$memo")
            stellarKit.createUnsignedTxAssetBase64(assetId, destination, amount, memo)
        }
    }

    fun getNetworkPassphrase(): String {
        if (stellarKit.isMainNet) {
            return MAINNET_NETWORK_ID
        } else {
            return TESTNET_NETWORK_ID
        }
    }
}

fun StellarKit.statusInfo(): Map<String, Any> =
    buildMap {
        put("Sync State", syncStateFlow.value.toAdapterState())
        put("Operation Sync State", operationsSyncStateFlow.value.toAdapterState())
    }

val StellarAsset.Asset.tokenType
    get() = TokenType.Asset(code, issuer)

fun SyncState.toAdapterState(): AdapterState = when (this) {
    is SyncState.NotSynced -> AdapterState.NotSynced(error)
    is SyncState.Synced -> AdapterState.Synced
    is SyncState.Syncing -> AdapterState.Syncing()
}


fun AccountType.toStellarWallet() = when (this) {
    is AccountType.Mnemonic -> StellarWallet.Seed(seed)
    is AccountType.StellarAddress -> StellarWallet.WatchOnly(address)
    is AccountType.StellarAddressHardware -> StellarWallet.WatchOnly(address)
    is AccountType.StellarSecretKey -> StellarWallet.SecretKey(key)
    else -> throw IllegalArgumentException("Account type ${this.javaClass.simpleName} can not be converted to StellarWallet.Wallet")
}
