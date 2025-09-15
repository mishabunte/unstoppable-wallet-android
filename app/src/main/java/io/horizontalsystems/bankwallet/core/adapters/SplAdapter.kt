package io.horizontalsystems.bankwallet.core.adapters

import io.horizontalsystems.bankwallet.core.AdapterState
import io.horizontalsystems.bankwallet.core.BalanceData
import io.horizontalsystems.bankwallet.core.ISendSolanaAdapter
import io.horizontalsystems.bankwallet.core.managers.SolanaKitWrapper
import io.horizontalsystems.bankwallet.entities.Wallet
import io.horizontalsystems.core.toHexString
import io.horizontalsystems.solanakit.SolanaKit
import io.horizontalsystems.solanakit.models.Address
import io.horizontalsystems.solanakit.models.FullTransaction
import io.reactivex.Flowable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.rx2.asFlowable
import java.math.BigDecimal

class SplAdapter(
        private val solanaKitWrapper: SolanaKitWrapper,
        wallet: Wallet,
        private val mintAddressString: String
) : BaseSolanaAdapter(solanaKitWrapper, wallet.decimal), ISendSolanaAdapter {

    private val mintAddress = Address(mintAddressString)

    init {
        solanaKit.addTokenAccount(mintAddressString, wallet.decimal)
    }

    // IAdapter

    override fun start() {
        // started via EthereumKitManager
    }

    override fun stop() {
        // stopped via EthereumKitManager
    }

    override fun refresh() {
        // refreshed via EthereumKitManager
    }

    // IBalanceAdapter

    override val balanceState: AdapterState
        get() = convertToAdapterState(solanaKit.tokenBalanceSyncState)

    override val balanceStateUpdatedFlowable: Flowable<Unit>
        get() = solanaKit.tokenBalanceSyncStateFlow.map { }.asFlowable()

    override val balanceData: BalanceData
        get() = BalanceData(
                solanaKit.tokenAccount(mintAddressString)?.let {
                    it.tokenAccount.balance.movePointLeft(it.tokenAccount.decimals)
                } ?: BigDecimal.ZERO
        )

    override val balanceUpdatedFlowable: Flowable<Unit>
        get() = solanaKit.tokenAccountFlow(mintAddressString).map { }.asFlowable()

    // ISendSolanaAdapter

    override suspend fun getUnsignedTransaction(from: String, to: String, mintAddress: String?, amount: Long, decimals: Int): String {
        return solanaKitWrapper.createUnsignedTransactionHex(from=from, to=to, mintAddress=mintAddress, amount=amount, decimals=decimals).toHexString()
    }

    override fun isHardwareAccount(): Boolean {
        return solanaKitWrapper.isHardwareSigner
    }
    @kotlin.io.encoding.ExperimentalEncodingApi
    override suspend fun sendRawTransaction(txHex: String): String {
        return solanaKitWrapper.sendRawTransaction(txHex)?: throw Exception("Failed to send transaction")
    }


    override val availableBalance: BigDecimal
        get() = balanceData.available

    override suspend fun send(amount: BigDecimal, to: Address): FullTransaction {
        if (signer == null) throw Exception()

        return solanaKit.sendSpl(mintAddress, to, amount.movePointRight(decimal).toLong(), signer)
    }

    private fun convertToAdapterState(syncState: SolanaKit.SyncState): AdapterState = when (syncState) {
        is SolanaKit.SyncState.Synced -> AdapterState.Synced
        is SolanaKit.SyncState.NotSynced -> AdapterState.NotSynced(syncState.error)
        is SolanaKit.SyncState.Syncing -> AdapterState.Syncing()
    }

}
