package io.horizontalsystems.bankwallet.core.managers

import android.os.Handler
import android.os.Looper
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
            solscanApiKey = appConfigProvider.solscanApiKey
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
    suspend fun createUnsignedTxHex(
        from: String,
        to: String,
        amount: BigDecimal
    ): String = withContext(Dispatchers.IO) {
        //Log.d("SolanaKitWrapper", "Creating unsigned transaction from: $from to: $to amount: $amount")

        val fromPubkey = Base58.decode(from)
        val toPubkey = Base58.decode(to)
        //Log.d("SolanaKitWrapper", "Using RPC source: ${rpcSourceUrl}")
        val recentBlockhash = HardwareWalletURLRequestHandler().getSolanaLatestBlockhash(rpcSourceUrl)
            ?: throw IllegalStateException("Recent blockhash is not available")
//        println(recentBlockhash.toHexString())
//        val recentBlockhash = Base58.decode("11111111111111111111111111111111")
        //val recentBlockhash = hexToBytes("3f14086b3320e6a98af7774ddb3c975d994114640901d11a29f18c35e05ba45b")


        val lamports = amount.multiply(BigDecimal.TEN.pow(9)).toLong()

        // === Transaction Header ===
        val header = byteArrayOf(
            1, // numRequiredSignatures
            0, // numReadonlySignedAccounts
            1  // numReadonlyUnsignedAccounts
        )

        val systemProgramId = Base58.decode("11111111111111111111111111111111")
        val accountKeys = listOf(fromPubkey, toPubkey, systemProgramId)
        val keyCount = byteArrayOf(accountKeys.size.toByte())
        val keysSerialized = accountKeys.fold(ByteArray(0)) { acc, key -> acc + key }

        val blockhashBytes = recentBlockhash

        // === Instructions ===
        val instruction = ByteBuffer.allocate(17)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(2) // program_id_index = 2
            .put(2) // number of accounts
            .put(0) // from account index
            .put(1) // to account index
            .put(12) // data length (fix if needed)
            .put(2) // instruction: transfer
            .put(0) // padding
            .put(0) // padding
            .put(0) // padding
            .putLong(lamports) // amount
            .array()

        // === Message ===
        val message = header + keyCount + keysSerialized + blockhashBytes + byteArrayOf(1) + instruction

        // === Signatures ===
        val signaturePlaceholder = ByteArray(64) { 0x00 }
        val signedTx = byteArrayOf(0x01) + signaturePlaceholder + message

        signedTx.toHexString()
    }
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
        amount: BigDecimal
    ): String {
        val res = SolanaTxHandler(
            rpcSourceUrl = rpcSourceUrl
        ).createUnsignedTxHex(from, to, amount)
        //Log.d("SolanaKitWrapper", "Created unsigned transaction hex: $res")
        return res
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