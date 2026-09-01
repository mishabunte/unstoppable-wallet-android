package io.horizontalsystems.bankwallet.core.managers

import android.os.Handler
import android.os.Looper
import android.util.Log
import io.horizontalsystems.bankwallet.core.App
import io.horizontalsystems.bankwallet.core.UnsupportedAccountException
import io.horizontalsystems.bankwallet.core.hexToByteArray
import io.horizontalsystems.bankwallet.core.providers.AppConfigProvider
import io.horizontalsystems.bankwallet.entities.Account
import io.horizontalsystems.bankwallet.entities.AccountType
import io.horizontalsystems.bankwallet.modules.hardwarewallet.HardwareWalletURLRequestHandler
import io.horizontalsystems.bitcoincore.crypto.Base58
import io.horizontalsystems.core.BackgroundManager
import io.horizontalsystems.core.BackgroundManagerState
import io.horizontalsystems.core.toHexString
import io.horizontalsystems.solanakit.Signer
import io.horizontalsystems.solanakit.SolanaKit
import io.reactivex.Observable
import io.reactivex.Single
import io.reactivex.subjects.PublishSubject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx2.asFlow
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.encoding.Base64
import kotlin.math.min

class SolanaKitManager(
    private val appConfigProvider: AppConfigProvider,
    private val rpcSourceManager: SolanaRpcSourceManager,
    private val walletManager: SolanaWalletManager,
    private val backgroundManager: BackgroundManager
) {

    private val coroutineScope = CoroutineScope(Dispatchers.Default)
    private var backgroundEventListenerJob: Job? = null
    private var rpcUpdatedJob: Job? = null
    private var tokenAccountJob: Job? = null

    var solanaKitWrapper: SolanaKitWrapper? = null

    private var useCount = 0
    var currentAccount: Account? = null
        private set
    private val solanaKitStoppedSubject = PublishSubject.create<Unit>()

    val kitStoppedObservable: Observable<Unit>
        get() = solanaKitStoppedSubject

    val statusInfo: Map<String, Any>?
        get() = solanaKitWrapper?.solanaKit?.statusInfo()

    private fun handleUpdateNetwork() {
        stopKit()

        solanaKitStoppedSubject.onNext(Unit)
    }

    @Synchronized
    fun getSolanaKitWrapper(account: Account): SolanaKitWrapper {
        if (this.solanaKitWrapper != null && currentAccount != account) {
            stopKit()
        }

        if (this.solanaKitWrapper == null) {
            val accountType = account.type
            //Log.d("SolanaKitManager", "Creating SolanaKit for account: ${account.id}, type: $accountType")
            this.solanaKitWrapper = when (accountType) {
                is AccountType.Mnemonic -> {
                    createKitInstance(accountType, account)
                }
                is AccountType.SolanaAddress -> {
                    createKitInstance(accountType, account)
                }
                is AccountType.SolanaAddressHardware -> {
                    createKitInstance(accountType, account)
                }
                else -> throw UnsupportedAccountException()
            }
            startKit()
            useCount = 0
            currentAccount = account
        }

        useCount++
        return this.solanaKitWrapper!!
    }

    private fun createKitInstance(
        accountType: AccountType,
        account: Account
    ): SolanaKitWrapper {
        val address: Any
        var isHardwareSigner = false
        var signer: Signer? = null
        when (accountType) {
            is AccountType.Mnemonic -> {
                val seed: ByteArray = accountType.seed
                address = Signer.address(seed)
                signer = Signer.getInstance(seed)
            }
            is AccountType.SolanaAddressHardware -> {
                isHardwareSigner = true
                address = accountType.address
            }
            else -> throw IllegalArgumentException("Unsupported account type: $accountType")
        }
        val kit = SolanaKit.getInstance(
            application = App.instance,
            addressString = address,
            rpcSource = rpcSourceManager.rpcSource,
            walletId = account.id,
        )
        return SolanaKitWrapper(kit, signer, isHardwareSigner, rpcSourceManager.rpcSource.url.toString())
    }


    @Synchronized
    fun unlink(account: Account) {
        if (account == currentAccount) {
            useCount -= 1

            if (useCount < 1) {
                stopKit()
            }
        }
    }

    private fun stopKit() {
        solanaKitWrapper?.solanaKit?.stop()
        solanaKitWrapper = null
        currentAccount = null
        tokenAccountJob?.cancel()
        backgroundEventListenerJob?.cancel()
        rpcUpdatedJob?.cancel()
    }

    private fun startKit() {
        solanaKitWrapper?.solanaKit?.let { kit ->
            tokenAccountJob = coroutineScope.launch {
                kit.start()
                kit.fungibleTokenAccountsFlow.collect {
                    walletManager.add(it)
                }
            }
        }
    }

    private fun subscribeToEvents() {
        backgroundEventListenerJob = coroutineScope.launch {
            backgroundManager.stateFlow.collect { state ->
                if (state == BackgroundManagerState.EnterForeground) {
                    solanaKitWrapper?.solanaKit?.let { kit ->
                        Handler(Looper.getMainLooper()).postDelayed({
                            kit.refresh()
                        }, 1000)
                    }
                }
            }
        }
        rpcUpdatedJob = coroutineScope.launch {
            rpcSourceManager.rpcSourceUpdateObservable.asFlow().collect {
                handleUpdateNetwork()
            }
        }
    }

}

class SolanaTxHandler(
    private val rpcSourceUrl: String
) {
    @kotlin.io.encoding.ExperimentalEncodingApi
    suspend fun sendRawTransaction(
        signedTxHex: String
    ): String? = withContext(Dispatchers.IO) {
        val base64Tx = Base64.encode(signedTxHex.removePrefix("0x").hexToByteArray())
        HardwareWalletURLRequestHandler().sendSolanaRawTransaction(rpcSourceUrl, base64Tx)
    }
}

class SolanaKitWrapper(
    val solanaKit: SolanaKit,
    val signer: Signer?,
    val isHardwareSigner: Boolean,
    private val rpcSourceUrl: String
) {
    suspend fun createUnsignedTransactionHex(
        from: String,
        to: String,
        mintAddress: String? = null,
        decimals: Int = 9,
        amount: Long
    ): ByteArray = withContext(Dispatchers.IO) {
        val recentBlockhash = HardwareWalletURLRequestHandler()
            .getSolanaLatestBlockhash(rpcSourceUrl)
            ?: throw IllegalStateException("Recent blockhash is not available")
        if (mintAddress != null) {
            Log.d("SolanaKit", "Creating SPL unsigned transaction from: $from, to: $to, mintAddress: $mintAddress, amount: $amount, recentBlockhash: $recentBlockhash")
            val res = solanaKit.getSplTransactionHex(
                mintAddress = mintAddress,
                fromPublicKey = from,
                destinationAddress = to,
                amount = amount,
                decimals = decimals,
                recentBlockHash = recentBlockhash
            ).blockingGet()
            Log.d("SolanaKit", "Created SPL unsigned transaction hex: ${res.toHexString()}")
            res
        } else {
            solanaKit.getSolTransactionHex(
                from = from,
                destination = to,
                amount = amount,
                recentBlockHash = recentBlockhash
            ).blockingGet()
        }
    }

    @kotlin.io.encoding.ExperimentalEncodingApi
    suspend fun sendRawTransaction(
        signedTxHex: String
    ): String? {
        //Log.d("SolanaKitWrapper", "Sending raw transaction hex: $signedTxHex")
        val res = SolanaTxHandler(
            rpcSourceUrl = rpcSourceUrl
        ).sendRawTransaction(signedTxHex)
        //Log.d("SolanaKitWrapper", "Sent raw transaction result: $res")
        return res
    }
}