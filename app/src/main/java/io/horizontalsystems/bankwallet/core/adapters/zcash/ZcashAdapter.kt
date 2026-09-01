package io.horizontalsystems.bankwallet.core.adapters.zcash

import android.content.Context
import android.util.Log
import cash.z.ecc.android.sdk.CloseableSynchronizer
import cash.z.ecc.android.sdk.SdkSynchronizer
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.WalletInitMode
import cash.z.ecc.android.sdk.block.processor.CompactBlockProcessor
import cash.z.ecc.android.sdk.ext.ZcashSdk
import cash.z.ecc.android.sdk.ext.collectWith
import cash.z.ecc.android.sdk.ext.convertZatoshiToZec
import cash.z.ecc.android.sdk.ext.convertZecToZatoshi
import cash.z.ecc.android.sdk.ext.fromHex
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.AccountBalance
import cash.z.ecc.android.sdk.model.AccountCreateSetup
import cash.z.ecc.android.sdk.model.AccountImportSetup
import cash.z.ecc.android.sdk.model.AccountPurpose
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.PercentDecimal
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.SaplingNsk
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.UnifiedFullViewingKey
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.model.Zip32AccountIndex
import cash.z.ecc.android.sdk.tool.DerivationTool
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import io.horizontalsystems.bankwallet.core.AdapterState
import io.horizontalsystems.bankwallet.core.App
import io.horizontalsystems.bankwallet.core.AppLogger
import io.horizontalsystems.bankwallet.core.IAdapter
import io.horizontalsystems.bankwallet.core.IBalanceAdapter
import io.horizontalsystems.bankwallet.core.ILocalStorage
import io.horizontalsystems.bankwallet.core.IReceiveAdapter
import io.horizontalsystems.bankwallet.core.ISendZcashAdapter
import io.horizontalsystems.bankwallet.core.ITransactionsAdapter
import io.horizontalsystems.bankwallet.core.UnsupportedAccountException
import io.horizontalsystems.bankwallet.core.ZcashBalanceData
import io.horizontalsystems.bankwallet.core.managers.RestoreSettings
import io.horizontalsystems.bankwallet.entities.AccountOrigin
import io.horizontalsystems.bankwallet.entities.AccountType
import io.horizontalsystems.bankwallet.entities.LastBlockInfo
import io.horizontalsystems.bankwallet.entities.Wallet
import io.horizontalsystems.bankwallet.entities.transactionrecords.TransactionRecord
import io.horizontalsystems.bankwallet.entities.transactionrecords.bitcoin.BitcoinIncomingTransactionRecord
import io.horizontalsystems.bankwallet.entities.transactionrecords.bitcoin.BitcoinOutgoingTransactionRecord
import io.horizontalsystems.bankwallet.entities.transactionrecords.zcash.ZcashShieldingTransactionRecord
import io.horizontalsystems.bankwallet.modules.transactions.FilterTransactionType
import io.horizontalsystems.bitcoincore.extensions.toReversedHex
import io.horizontalsystems.marketkit.models.Token
import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import io.reactivex.Single
import io.reactivex.subjects.PublishSubject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.util.regex.Pattern
import kotlin.math.max
private const val HARDWARE_KEY_SOURCE = "hardware-wallet"
class ZcashAdapter(
    context: Context,
    private val wallet: Wallet,
    restoreSettings: RestoreSettings,
    private val localStorage: ILocalStorage,
) : IAdapter, IBalanceAdapter, IReceiveAdapter, ITransactionsAdapter, ISendZcashAdapter {

    private val pcztMutex = kotlinx.coroutines.sync.Mutex()
    private var accountBirthday = 0L
    private val existingWallet = localStorage.zcashAccountIds.contains(wallet.account.id)
    private val confirmationsThreshold = 10
    private val decimalCount = 8
    //private val network: ZcashNetwork = ZcashNetwork.Mainnet
    private val feeChangeHeight: Long = 1_077_550

    private fun ByteArray.toHex(): String {
        val alphabet = "0123456789abcdef"
        val result = CharArray(size * 2)

        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            result[index * 2] = alphabet[value ushr 4]
            result[index * 2 + 1] = alphabet[value and 0x0f]
        }

        return String(result)
    }

    private val synchronizer: CloseableSynchronizer
    private val transactionsProvider: ZcashTransactionsProvider

    private val adapterStateUpdatedSubject: PublishSubject<Unit> = PublishSubject.create()
    private val lastBlockUpdatedSubject: PublishSubject<Unit> = PublishSubject.create()
    private val balanceUpdatedSubject: PublishSubject<Unit> = PublishSubject.create()

    private val accountType: AccountType = wallet.account.type
    private val hardwareAccountType: AccountType.ZcashHardware?
        get() = accountType as? AccountType.ZcashHardware

    private val mnemonicAccountType: AccountType.Mnemonic?
        get() = accountType as? AccountType.Mnemonic
    private val network: ZcashNetwork = when (val type = accountType) {
        is AccountType.ZcashHardware -> {
            if (type.isTestNet) ZcashNetwork.Testnet else ZcashNetwork.Mainnet
        }

        // Mnemonic accounts currently have no persisted network flag.
        is AccountType.Mnemonic -> ZcashNetwork.Mainnet
        else -> throw UnsupportedAccountException()
    }

    private fun String.toNskBytes(name: String): ByteArray {
        val normalized = trim()

        require(Regex("^[0-9a-fA-F]{64}$").matches(normalized)) {
            "$name must contain 32 bytes encoded as 64 hex characters"
        }

        return normalized.fromHex().also { bytes ->
            require(bytes.size == 32) {
                "$name must contain exactly 32 bytes"
            }
        }
    }

    private val lightWalletEndpoint: LightWalletEndpoint =
        if (network == ZcashNetwork.Testnet) {
            LightWalletEndpoint(
                host = "testnet.zec.rocks",
                port = 443,
                isSecure = true
            )
        } else {
            LightWalletEndpoint(
                host = "zec.rocks",
                port = 443,
                isSecure = true
            )
        }

    override val isMainNet: Boolean
        get() = network == ZcashNetwork.Mainnet
//    private val seed = accountType.seed

    override fun isHardwareAccount(): Boolean {
        return accountType is AccountType.ZcashHardware
    }

    override fun isTestNet(): Boolean {
        return network == ZcashNetwork.Testnet
    }

    private val zcashAccount: Account
    private var pendingPcztWithProofs: Pczt? = null

    private val minimalShieldThreshold: BigDecimal = BigDecimal("0.0004") // minimal transparent balance to shielding

    override val receiveAddress: String

    init {
        val walletInitMode = if (existingWallet) {
            WalletInitMode.ExistingWallet
        } else when (wallet.account.origin) {
            AccountOrigin.Created -> WalletInitMode.NewWallet
            AccountOrigin.Restored -> WalletInitMode.RestoreWallet
        }

        val birthday = when (wallet.account.origin) {
            AccountOrigin.Created -> runBlocking {
                BlockHeight.ofLatestCheckpoint(context, network)
            }

            AccountOrigin.Restored -> restoreSettings.birthdayHeight
                ?.let { height ->
                    max(network.saplingActivationHeight.value, height)
                }
                ?.let {
                    BlockHeight.new(it)
                }
        }

        birthday?.value?.let {
            accountBirthday = it
        }
        val alias = getValidAliasFromAccountId(wallet.account.id)

        val accountCreateSetup = mnemonicAccountType?.let { mnemonic ->
            AccountCreateSetup(
                accountName = wallet.account.name,
                keySource = null,
                seed = FirstClassByteArray(mnemonic.seed)
            )
        }
        Log.d(
            "ZcashAdapter",
            "App account type: ${wallet.account.type::class.qualifiedName}"
        )
        fun openSynchronizer(
            mode: WalletInitMode
        ): CloseableSynchronizer {
            return Synchronizer.newBlocking(
                context = context,
                zcashNetwork = network,
                alias = alias,
                lightWalletEndpoint = lightWalletEndpoint,
                setup = accountCreateSetup,
                birthday = birthday,
                walletInitMode = mode,
                isTorEnabled = false,
                isExchangeRateEnabled = false
            )
        }

        Log.d("ZcashAdapter", "Opening initial synchronizer")

        var currentSynchronizer = openSynchronizer(walletInitMode)

        Log.d("ZcashAdapter", "Initial synchronizer opened")

        var accountImported = false

        var currentAccount = runBlocking {
            when (val type = accountType) {
                is AccountType.ZcashHardware -> {
                    val seedFingerprint = type.seedFingerprint.toSeedFingerprintBytes()
                    val accountIndex = Zip32AccountIndex.new(type.accountIndex)

                    val existingAccount = currentSynchronizer.getAccounts()
                        .firstOrNull { account ->
                            account.ufvk == type.ufvk
                        }

                    if (existingAccount != null) {
                        val storedSeedFingerprint = existingAccount.seedFingerprint
                        val storedAccountIndex = existingAccount.hdAccountIndex

                        check(storedSeedFingerprint != null && storedAccountIndex != null) {
                            "Hardware account was imported without ZIP-32 derivation metadata. " +
                                    "Recreate the Zcash SDK wallet and import the hardware account again."
                        }

                        check(storedSeedFingerprint.contentEquals(seedFingerprint)) {
                            "Stored seed fingerprint does not match the hardware wallet"
                        }

                        check(storedAccountIndex == accountIndex) {
                            "Stored ZIP-32 account index does not match the hardware wallet"
                        }

                        Log.d(
                            "ZcashAdapter",
                            "Existing hardware account found with ZIP-32 metadata"
                        )

                        existingAccount
                    } else {
                        Log.d("ZcashAdapter", "Importing hardware spending account")

                        currentSynchronizer.importAccountByUfvk(
                            AccountImportSetup(
                                accountName = wallet.account.name,
                                keySource = HARDWARE_KEY_SOURCE,
                                purpose = AccountPurpose.Spending(
                                    seedFingerprint = seedFingerprint,
                                    zip32AccountIndex = accountIndex
                                ),
                                ufvk = UnifiedFullViewingKey(type.ufvk),
                                birthday = birthday
                            )
                        ).also {
                            accountImported = true

                            Log.d(
                                "ZcashAdapter",
                                "Hardware account imported: " +
                                        "keySource=${it.keySource}, " +
                                        "accountIndex=${it.hdAccountIndex?.index}, " +
                                        "hasSeedFingerprint=${it.seedFingerprint != null}"
                            )
                        }
                    }
                }

                is AccountType.Mnemonic -> {
                    currentSynchronizer.getAccounts().firstOrNull()
                        ?: error("Mnemonic account was not created")
                }

                else -> throw UnsupportedAccountException()
            }
        }

        if (accountImported) {
            Log.d("ZcashAdapter", "Closing initial synchronizer")

            currentSynchronizer.close()

            Log.d("ZcashAdapter", "Reopening synchronizer")

            currentSynchronizer = openSynchronizer(
                WalletInitMode.ExistingWallet
            )

            currentAccount = runBlocking {
                val hardwareType = accountType as AccountType.ZcashHardware

                currentSynchronizer.getAccounts()
                    .firstOrNull { account ->
                        account.ufvk == hardwareType.ufvk
                    }
                    ?: error("Imported hardware account was not found")
            }

            Log.d(
                "ZcashAdapter",
                "Synchronizer reopened: keySource=${currentAccount.keySource}"
            )
        }

        synchronizer = currentSynchronizer
        zcashAccount = currentAccount
        receiveAddress = when (val type = accountType) {
            is AccountType.ZcashHardware -> type.unifiedAddress
            is AccountType.Mnemonic -> runBlocking {
                synchronizer.getUnifiedAddress(zcashAccount)
            }
            else -> throw UnsupportedAccountException()
        }
        transactionsProvider = ZcashTransactionsProvider(zcashAccount.accountUuid, synchronizer as SdkSynchronizer)
        synchronizer.onProcessorErrorHandler = ::onProcessorError
        synchronizer.onChainErrorHandler = ::onChainError
    }

    private var syncState: AdapterState = AdapterState.Syncing()
        set(value) {
            if (value != field) {
                field = value
                adapterStateUpdatedSubject.onNext(Unit)
            }
        }

    override fun start() {
        subscribe(synchronizer as SdkSynchronizer)
        if (!existingWallet) {
            localStorage.zcashAccountIds += wallet.account.id
        }
    }

    override fun stop() {
        synchronizer.close()
    }

    override fun refresh() {
    }

    private fun BigDecimal.toZatoshiExact(): Zatoshi {
        require(signum() > 0) {
            "Amount must be greater than zero"
        }

        val value = movePointRight(8).longValueExact()
        return Zatoshi(value)
    }

    private fun String.toSeedFingerprintBytes(): ByteArray {
        val normalized = trim()

        require(Regex("^[0-9a-fA-F]{64}$").matches(normalized)) {
            "Seed fingerprint must contain 64 hex characters"
        }

        return normalized.fromHex().also { bytes ->
            require(bytes.size == 32) {
                "Seed fingerprint must contain 32 bytes"
            }
        }
    }

    private fun ByteArray.readUInt32Le(offset: Int): UInt {
        require(size >= offset + 4)

        return (this[offset].toUInt() and 0xffu) or
                ((this[offset + 1].toUInt() and 0xffu) shl 8) or
                ((this[offset + 2].toUInt() and 0xffu) shl 16) or
                ((this[offset + 3].toUInt() and 0xffu) shl 24)
    }

    private fun logRawTransactionBranchId(
        rawTransactionBytes: ByteArray,
    ) {
        //val rawTransaction = rawTransactionHex.hexToByteArray()
        val branchId = rawTransactionBytes.readUInt32Le(offset = 8)

        Log.d("ZcashAdapter", "Raw transaction branch ID: 0x${branchId.toString(16)}")
    }

    fun printPcztChunks(
        pcztHex: String,
        chunkSize: Int = 3000
    ) {
        val chunks = pcztHex.chunked(chunkSize)
        Log.d("ZcashAdapter", "BEGIN PCZT length=${pcztHex.length} chunks=${chunks.size}")
        chunks.forEachIndexed { index, chunk ->
            Log.d("ZcashAdapter", "CHUNK[$index]=$chunk")
        }
        Log.d("ZcashAdapter", "END PCZT")
    }


    override suspend fun sendRawTransaction(
        pcztHex: String,
        logger: AppLogger
    ) {
        Log.d("ZcashAdapter", "Finalizing signed Zcash PCZT: ${pcztHex.length} hex characters")
        val pendingPczt = checkNotNull(pendingPcztWithProofs) {
            "Pending PCZT missing"
        }
//        logRawTransactionBranchId(pcztWithProofs.toByteArray())

        try {
            val signedPcztBytes = decodePcztHex(pcztHex)
            val pcztWithSignatures = Pczt(signedPcztBytes)

            printPcztChunks(pcztHex)


            val pcztWithProofs = synchronizer.addProofsToPczt(
                pendingPczt
            )

            Log.d("ZcashAdapter", "Finalizing signed PCZT: signatures=${signedPcztBytes.size} bytes, " +
                    "proofs=${pcztWithProofs.toByteArray().size} bytes"
            )

            val result = synchronizer.createTransactionFromPczt(
                pcztWithProofs = pcztWithProofs,
                pcztWithSignatures = pcztWithSignatures
            ).first()

            // Once a result is emitted, the transaction has already been created
            // and stored by the SDK, even if network submission failed.
            pendingPcztWithProofs = null

            when (result) {
                is TransactionSubmitResult.Success -> {
                    Log.e(
                        "ZcashAdapter",
                        "Transaction submitted successfully: txId=${result.txIdString()}"
                    )
                    logger.info(
                        "Zcash transaction submitted: ${result.txIdString()}"
                    )
                }

                is TransactionSubmitResult.Failure -> {
                    Log.e(
                        "ZcashAdapter",
                        "Transaction submission failed: code=${result.code}, grpcError=${result.grpcError}, description=${result.description ?: "None"}, txId=${result.txIdString()}"
                    )
                    throw IllegalStateException(
                        buildString {
                            append("Zcash transaction submission failed")
                            append(": code=${result.code}")
                            append(", grpcError=${result.grpcError}")

                            result.description?.let {
                                append(", description=$it")
                            }

                            append(", txId=${result.txIdString()}")
                        }
                    )
                }

                is TransactionSubmitResult.NotAttempted -> {
                    Log.e(
                        "ZcashAdapter",
                        "Transaction was created but not submitted: txId=${result.txIdString()}"
                    )
                    throw IllegalStateException(
                        "Zcash transaction was created but not submitted: " +
                                result.txIdString()
                    )
                }
            }
        } catch (error: Throwable) {
            Log.e("ZcashAdapter", "Error sending raw transaction: ${error.message}")
            throw error
        }
    }

    override suspend fun getUnsignedTransaction(
        amount: BigDecimal,
        address: String,
        memo: String,
        logger: AppLogger
    ): String = pcztMutex.withLock {
        val proposal = synchronizer.proposeTransfer(
            account = zcashAccount,
            recipient = address,
            amount = amount.toZatoshiExact(),
            memo = memo
        )

        createUnsignedHardwareTransaction(proposal)
    }

    suspend fun getUnsignedShieldTransaction(): String = pcztMutex.withLock {
        val proposal = shieldProposal()
            ?: throw IllegalStateException("Couldn't create shield proposal")

        createUnsignedHardwareTransaction(proposal)
    }

    private suspend fun createUnsignedHardwareTransaction(proposal: Proposal): String {
        val hardwareAccount = checkNotNull(hardwareAccountType) {
            "Hardware signing requires a Zcash hardware account"
        }

        require(proposal.transactionCount() == 1) {
            "Hardware signing does not support multi-step proposals"
        }

        val createdPczt = synchronizer.createPcztFromProposal(
            accountUuid = zcashAccount.accountUuid,
            proposal = proposal
        )

        printPcztChunks(createdPczt.toByteArray().toHex())

        val requiresSaplingProofs =
            synchronizer.pcztRequiresSaplingProofs(createdPczt)

        val pcztWithProofs = if (requiresSaplingProofs) {
            val externalNsk =
                hardwareAccount.externalNsk.toNskBytes("External nsk")
            val internalNsk =
                hardwareAccount.internalNsk.toNskBytes("Internal nsk")

            try {
                val pcztWithKeys =
                    synchronizer.addSaplingProofGenerationKeys(
                        pczt = createdPczt,
                        ufvk = UnifiedFullViewingKey(hardwareAccount.ufvk),
                        externalNsk = SaplingNsk.new(externalNsk),
                        internalNsk = SaplingNsk.new(internalNsk)
                    )

                synchronizer.addProofsToPczt(pcztWithKeys)
            } finally {
                externalNsk.fill(0)
                internalNsk.fill(0)
            }
        } else {
            createdPczt
        }

        printPcztChunks(pcztWithProofs.toByteArray().toHex())

        pendingPcztWithProofs = pcztWithProofs

        val pcztForSigner =
            synchronizer.redactPcztForSigner(pcztWithProofs)

        printPcztChunks(pcztForSigner.toByteArray().toHex())

        return pcztForSigner.toByteArray().toHex()
    }

//    override suspend fun getUnsignedTransaction(
//        amount: BigDecimal,
//        address: String,
//        memo: String,
//        logger: AppLogger
//    ): String = pcztMutex.withLock {
//        val proposal = synchronizer.proposeTransfer(
//            account = zcashAccount,
//            recipient = address,
//            amount = amount.toZatoshiExact(),
//            memo = memo
//        )
//
//        require(proposal.transactionCount() == 1) {
//            "Hardware signing does not support multi-step proposals"
//        }
//
//        val createdPczt = synchronizer.createPcztFromProposal(
//            accountUuid = zcashAccount.accountUuid,
//            proposal = proposal
//        )
//
//        val pczt = if (synchronizer.pcztRequiresSaplingProofs(createdPczt)) {
//            synchronizer.addProofsToPczt(createdPczt)
//        } else {
//            createdPczt
//        }
////        pendingPcztWithProofs = synchronizer.addProofsToPczt(
////            pczt.clonePczt()
////        )
//        pendingPcztWithProofs = pczt
//
//        val pcztForSigner = synchronizer.redactPcztForSigner(pczt)
//
//        pcztForSigner.toByteArray().toHex()
//    }

    override suspend fun submitHardwareSignedTransaction(signedPayload: ByteArray) =
        pcztMutex.withLock {
            val originalPczt = pendingPcztWithProofs
                ?: throw IllegalStateException("No pending hardware wallet transaction")
            try {
                val results = synchronizer.createTransactionFromPczt(
                    originalPczt,
                    Pczt(signedPayload.copyOf()),
                ).toList()
                results.forEach { result ->
                    when (result) {
                        is TransactionSubmitResult.Success -> Unit
                        is TransactionSubmitResult.Failure -> throw IllegalStateException(
                            "Transaction submission failed: ${result.description ?: result.grpcError}",
                        )
                        is TransactionSubmitResult.NotAttempted -> throw IllegalStateException(
                            "Transaction was not submitted: ${result.txIdString()}",
                        )
                    }
                }
            } finally {
                pendingPcztWithProofs = null
                signedPayload.fill(0)
            }
        }

    override fun clearHardwareSigningRequest() {
        pendingPcztWithProofs = null
    }

    private fun decodePcztHex(payload: String): ByteArray {
        var hex = payload.trim()

        // Also accept the transport form: "pczt 0x..."
        if (hex.startsWith("pczt ", ignoreCase = true)) {
            hex = hex.substring(5).trim()
        }

        if (hex.startsWith("0x", ignoreCase = true)) {
            hex = hex.substring(2)
        }

        hex = hex.filterNot { it.isWhitespace() }

        require(hex.isNotEmpty()) {
            "Signed PCZT is empty"
        }
        require(hex.length % 2 == 0) {
            "Signed PCZT contains an odd number of hex characters"
        }
        require(hex.all { it.digitToIntOrNull(16) != null }) {
            "Signed PCZT contains invalid hex characters"
        }

        val bytes = ByteArray(hex.length / 2) { index ->
            val high = hex[index * 2].digitToInt(16)
            val low = hex[index * 2 + 1].digitToInt(16)
            ((high shl 4) or low).toByte()
        }

        require(
            bytes.size >= 4 &&
                    bytes[0] == 'P'.code.toByte() &&
                    bytes[1] == 'C'.code.toByte() &&
                    bytes[2] == 'Z'.code.toByte() &&
                    bytes[3] == 'T'.code.toByte()
        ) {
            "Input is not a serialized PCZT"
        }

        return bytes
    }

    override val debugInfo: String
        get() = ""

    override val balanceState: AdapterState
        get() = syncState

    override val balanceStateUpdatedFlowable: Flowable<Unit>
        get() = adapterStateUpdatedSubject.toFlowable(BackpressureStrategy.BUFFER)

    override val balanceData: ZcashBalanceData
        get() = ZcashBalanceData(balanceAvailable, balancePending, balanceUnshielded)

    val statusInfo: Map<String, Any>
        get() {
            val statusInfo = LinkedHashMap<String, Any>()
            statusInfo["Last Block Info"] = lastBlockInfo ?: ""
            statusInfo["Sync State"] = syncState
            statusInfo["Birthday Height"] = accountBirthday
            return statusInfo
        }

    private val accountBalance: AccountBalance?
        get() = synchronizer.walletBalances.value?.get(zcashAccount.accountUuid)

    private val balanceAvailable: BigDecimal
        get() = accountBalance?.available.convertZatoshiToZec(decimalCount)

    private val balancePending: BigDecimal
        get() = accountBalance?.pending.convertZatoshiToZec(decimalCount)

    private val balanceUnshielded: BigDecimal
        get() = accountBalance?.unshielded.convertZatoshiToZec(decimalCount)

    override val balanceUpdatedFlowable: Flowable<Unit>
        get() = balanceUpdatedSubject.toFlowable(BackpressureStrategy.BUFFER)

    override val explorerTitle: String
        get() = "zcashexplorer.app"

    override val transactionsState: AdapterState
        get() = syncState

    override val transactionsStateUpdatedFlowable: Flowable<Unit>
        get() = adapterStateUpdatedSubject.toFlowable(BackpressureStrategy.BUFFER)

    override val lastBlockInfo: LastBlockInfo?
        get() = synchronizer.latestHeight?.value?.toInt()?.let { LastBlockInfo(it) }

    override val lastBlockUpdatedFlowable: Flowable<Unit>
        get() = lastBlockUpdatedSubject.toFlowable(BackpressureStrategy.BUFFER)

    override fun sendAllowed(): Boolean {
        return balanceState is AdapterState.Synced || balanceState is AdapterState.Syncing
    }

    override fun getTransactionsAsync(
        from: TransactionRecord?,
        token: Token?,
        limit: Int,
        transactionType: FilterTransactionType,
        address: String?,
    ): Single<List<TransactionRecord>> {
        val fromParams = from?.let {
            val transactionHash = it.transactionHash.fromHex().reversedArray()
            Triple(transactionHash, it.timestamp, it.transactionIndex)
        }
        return transactionsProvider.getTransactions(fromParams, transactionType, address, limit)
            .map { transactions ->
                transactions.map {
                    getTransactionRecord(it)
                }
            }
    }

    override fun getTransactionRecordsFlowable(
        token: Token?,
        transactionType: FilterTransactionType,
        address: String?,
    ): Flowable<List<TransactionRecord>> {
        return transactionsProvider.getNewTransactionsFlowable(transactionType, address)
            .map { transactions ->
                transactions.map { getTransactionRecord(it) }
            }
    }

    override fun getTransactionUrl(transactionHash: String): String {
        val prefix = if (network == ZcashNetwork.Testnet) {
            "testnet"
        } else {
            "mainnet"
        }
        return "https://$prefix.zcashexplorer.app/transactions/$transactionHash"
    }

    override val availableBalance: BigDecimal
        get() = balanceAvailable

    override val fee: BigDecimal
        get() = ZcashSdk.MINERS_FEE.convertZatoshiToZec(decimalCount)

    override suspend fun validate(address: String): ZCashAddressType {
        if (address == receiveAddress) throw ZcashError.SendToSelfNotAllowed
        return when (synchronizer.validateAddress(address)) {
            is AddressType.Invalid -> throw ZcashError.InvalidAddress
            is AddressType.Transparent -> ZCashAddressType.Transparent
            is AddressType.Shielded -> ZCashAddressType.Shielded
            is AddressType.Tex -> ZCashAddressType.Shielded
            AddressType.Unified -> ZCashAddressType.Unified
        }
    }

    suspend fun sendShieldProposal() {
        val shieldProposal = shieldProposal() ?: throw IllegalStateException("Couldn't create shield proposal")
        send(shieldProposal)
    }

    suspend fun shieldTransactionFee(): BigDecimal? =
        shieldProposal()?.totalFeeRequired()?.convertZatoshiToZec()

    private suspend fun shieldProposal(): Proposal? = synchronizer.proposeShielding(
        account = zcashAccount,
        shieldingThreshold = minimalShieldThreshold.convertZecToZatoshi(),
        memo = ""
    )

    override suspend fun send(amount: BigDecimal, address: String, memo: String, logger: AppLogger) {
        logger.info("call sendTransferProposal")
        sendTransferProposal(amount, address, memo)
    }

    private suspend fun transferProposal(
        amount: BigDecimal,
        address: String,
        memo: String
    ) = synchronizer.proposeTransfer(
        account = zcashAccount,
        recipient = address,
        amount = amount.convertZecToZatoshi(),
        memo = memo
    )

    private suspend fun send(proposal: Proposal) {
        when (val type = accountType) {
            is AccountType.Mnemonic -> sendWithMnemonic(proposal, type)
            is AccountType.ZcashHardware -> sendWithHardwareWallet(proposal)
            else -> throw UnsupportedAccountException()
        }
    }

    private suspend fun sendWithHardwareWallet(proposal: Proposal) {
        // 1. Create PCZT from the proposal.
        // 2. Add Sapling/Orchard proofs on the phone.
        // 3. Redact data that the signer does not need.
        // 4. Send PCZT to the hardware wallet.
        // 5. Receive signed PCZT.
        // 6. Finalize and broadcast it through the SDK.

        throw UnsupportedOperationException(
            "Hardware wallet PCZT signing is not connected yet"
        )
    }

    private suspend fun sendWithMnemonic(proposal: Proposal, accountType: AccountType.Mnemonic) {
        val spendingKey = DerivationTool.getInstance().deriveUnifiedSpendingKey(accountType.seed, network, Zip32AccountIndex.new(0))

        try {
            val results = synchronizer.createProposedTransactions(proposal, spendingKey).toList()
            results.forEach { result ->
                when (result) {
                    is TransactionSubmitResult.Success -> {}

                    is TransactionSubmitResult.Failure -> {
                        val errorMsg = buildString {
                            append("Transaction submission failed. ")
                            append("TxId: ${result.txIdString()}, ")
                            append("gRPC error: ${result.grpcError}, ")
                            append("Code: ${result.code}, ")
                            append("Description: ${result.description ?: "None"}")
                        }
                        throw IllegalStateException(errorMsg)
                    }

                    is TransactionSubmitResult.NotAttempted -> {
                        throw IllegalStateException("Transaction not attempted. TxId: ${result.txIdString()}")
                    }
                }
            }
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid proposal: ${e.message}", e)
        } catch (e: Exception) {
            throw RuntimeException("Unexpected error while sending Zcash: ${e.message}", e)
        }
    }

    private suspend fun sendTransferProposal(
        amount: BigDecimal,
        address: String,
        memo: String
    ) {
        val transferProposal = transferProposal(amount, address, memo)
        send(transferProposal)
    }

    // Subscribe to a synchronizer on its own scope and begin responding to events
    private fun subscribe(synchronizer: SdkSynchronizer) {
        // Note: If any of these callback functions directly touch the UI, then the scope used here
        //       should not live longer than that UI or else the context and view tree will be
        //       invalid and lead to crashes. For now, we use a scope that is cancelled whenever
        //       synchronizer.stop is called.
        //       If the scope of the view is required for one of these, then consider using the
        //       related viewModelScope instead of the synchronizer's scope.
        //       synchronizer.coroutineScope cannot be accessed until the synchronizer is started
        val scope = synchronizer.coroutineScope
        scope.launch {
            synchronizer.allTransactions.collect { transactionOverviews ->
                runCatching {
                    transactionsProvider.onTransactions(transactionOverviews)
                }.onFailure { error ->
                    Log.e(
                        "ZcashAdapter",
                        "Failed to process transaction snapshot",
                        error
                    )
                }
            }
        }
        synchronizer.status.collectWith(scope, ::onStatus)
        synchronizer.progress.collectWith(scope, ::onDownloadProgress)
        synchronizer.walletBalances
            .mapNotNull { it?.get(zcashAccount.accountUuid) }
            .collectWith(scope, ::onBalance)
        synchronizer.processorInfo.collectWith(scope, ::onProcessorInfo)
    }

    private fun onProcessorError(error: Throwable?): Boolean {
        error?.printStackTrace()
        return true
    }

    private fun onChainError(errorHeight: BlockHeight, rewindHeight: BlockHeight) {
    }

    private fun onStatus(status: Synchronizer.Status) {
        syncState = when (status) {
            Synchronizer.Status.STOPPED -> AdapterState.NotSynced(Exception("stopped"))
            Synchronizer.Status.DISCONNECTED -> AdapterState.NotSynced(Exception("disconnected"))
            Synchronizer.Status.SYNCING -> AdapterState.Syncing()
            Synchronizer.Status.SYNCED -> AdapterState.Synced
            else -> syncState
        }
    }

    private fun onDownloadProgress(progress: PercentDecimal) {
        syncState = AdapterState.Syncing(progress.toPercentage())
    }

    private fun onProcessorInfo(processorInfo: CompactBlockProcessor.ProcessorInfo) {
        syncState = AdapterState.Syncing()
        lastBlockUpdatedSubject.onNext(Unit)
    }

    private fun onBalance(balance: AccountBalance?) {
        balanceUpdatedSubject.onNext(Unit)
    }

    private fun getTransactionRecord(transaction: ZcashTransaction): TransactionRecord {
        val transactionHashHex = transaction.transactionHash.toReversedHex()

        return when {
            transaction.shieldDirection != null -> {
                ZcashShieldingTransactionRecord(
                    token = wallet.token,
                    uid = transactionHashHex,
                    transactionHash = transactionHashHex,
                    transactionIndex = transaction.transactionIndex,
                    blockHeight = transaction.minedHeight?.toInt(),
                    confirmationsThreshold = confirmationsThreshold,
                    timestamp = transaction.timestamp,
                    fee = transaction.feePaid?.convertZatoshiToZec(decimalCount),
                    failed = transaction.failed,
                    lockInfo = null,
                    conflictingHash = null,
                    showRawTransaction = false,
                    amount = transaction.value.convertZatoshiToZec(decimalCount),
                    direction = ZcashShieldingTransactionRecord.Direction.from(transaction.shieldDirection),
                    memo = transaction.memo,
                    source = wallet.transactionSource
                )
            }

            transaction.isIncoming -> {
                BitcoinIncomingTransactionRecord(
                    token = wallet.token,
                    uid = transactionHashHex,
                    transactionHash = transactionHashHex,
                    transactionIndex = transaction.transactionIndex,
                    blockHeight = transaction.minedHeight?.toInt(),
                    confirmationsThreshold = confirmationsThreshold,
                    timestamp = transaction.timestamp,
                    fee = transaction.feePaid.convertZatoshiToZec(decimalCount),
                    failed = transaction.failed,
                    lockInfo = null,
                    conflictingHash = null,
                    showRawTransaction = false,
                    amount = transaction.value.convertZatoshiToZec(decimalCount),
                    from = null,
                    memo = transaction.memo,
                    source = wallet.transactionSource
                )
            }

            else -> {
                BitcoinOutgoingTransactionRecord(
                    token = wallet.token,
                    uid = transactionHashHex,
                    transactionHash = transactionHashHex,
                    transactionIndex = transaction.transactionIndex,
                    blockHeight = transaction.minedHeight?.toInt(),
                    confirmationsThreshold = confirmationsThreshold,
                    timestamp = transaction.timestamp,
                    fee = transaction.feePaid.convertZatoshiToZec(decimalCount),
                    failed = transaction.failed,
                    lockInfo = null,
                    conflictingHash = null,
                    showRawTransaction = false,
                    amount = transaction.value.convertZatoshiToZec(decimalCount).negate(),
                    to = transaction.recipients?.firstOrNull()?.addressValue,
                    sentToSelf = false,
                    memo = transaction.memo,
                    source = wallet.transactionSource,
                    replaceable = false
                )
            }
        }
    }

    enum class ZCashAddressType {
        Shielded, Transparent, Unified
    }

    sealed class ZcashError : Exception() {
        object InvalidAddress : ZcashError()
        object SendToSelfNotAllowed : ZcashError()
    }

    companion object {
        private const val ALIAS_PREFIX = "zcash_"

        private fun getValidAliasFromAccountId(accountId: String): String {
            return ALIAS_PREFIX + accountId.replace("-", "_")
        }

        fun clear(accountId: String) {
            runBlocking {
                Synchronizer.erase(App.instance, ZcashNetwork.Mainnet, getValidAliasFromAccountId(accountId))
            }
        }
    }
}

object ZcashAddressValidator {
    fun validate(address: String): Boolean {
        return isValidZcashAddress(address)
    }

    private fun isValidTransparentAddress(address: String): Boolean {
        val transparentPattern = Pattern.compile("^t[0-9a-zA-Z]{34}$")
        return transparentPattern.matcher(address).matches()
    }

    private fun isValidShieldedAddress(address: String): Boolean {
        val shieldedPattern = Pattern.compile("^z[0-9a-zA-Z]{77}$")
        return shieldedPattern.matcher(address).matches()
    }

    private fun isValidZcashAddress(address: String): Boolean {
        return isValidTransparentAddress(address) || isValidShieldedAddress(address)
    }
}

val AccountBalance.available: Zatoshi
    get() = this.sapling.available + this.orchard.available + this.ironwood.available

val AccountBalance.pending: Zatoshi
    get() = this.sapling.pending + this.orchard.pending + this.ironwood.pending
