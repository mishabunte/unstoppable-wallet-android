package io.horizontalsystems.bankwallet.core.adapters

import io.horizontalsystems.bankwallet.core.AdapterState
import io.horizontalsystems.bankwallet.core.BalanceData
import io.horizontalsystems.bankwallet.core.ISendStellarAdapter
import io.horizontalsystems.bankwallet.core.managers.StellarKitWrapper
import io.horizontalsystems.bankwallet.core.managers.toAdapterState
import io.horizontalsystems.stellarkit.StellarKit
import io.horizontalsystems.stellarkit.room.StellarAsset
import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import io.reactivex.subjects.PublishSubject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal

class StellarAssetAdapter(
    private val stellarKitWrapper: StellarKitWrapper,
    code: String,
    issuer: String
) : BaseStellarAdapter(stellarKitWrapper), ISendStellarAdapter {

    private val stellarAsset = StellarAsset.Asset(code, issuer)
    private var assetBalance: BigDecimal? = null

    private val balanceUpdatedSubject: PublishSubject<Unit> = PublishSubject.create()
    private val balanceStateUpdatedSubject: PublishSubject<Unit> = PublishSubject.create()

    private val balance: BigDecimal
        get() = assetBalance ?: BigDecimal.ZERO

    override var balanceState: AdapterState = AdapterState.Syncing()
    override val balanceStateUpdatedFlowable: Flowable<Unit>
        get() = balanceStateUpdatedSubject.toFlowable(BackpressureStrategy.BUFFER)
    override val balanceData: BalanceData
        get() = BalanceData(balance)
    override val balanceUpdatedFlowable: Flowable<Unit>
        get() = balanceUpdatedSubject.toFlowable(BackpressureStrategy.BUFFER)

    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    val activationFee = stellarKit.sendFee

    override fun start() {
        coroutineScope.launch {
            stellarKit.getBalanceFlow(stellarAsset).collect { balance ->
                assetBalance = balance?.balance
                balanceUpdatedSubject.onNext(Unit)
            }
        }
        coroutineScope.launch {
            stellarKit.syncStateFlow.collect {
                balanceState = it.toAdapterState()
                balanceStateUpdatedSubject.onNext(Unit)
            }
        }
    }

    override suspend fun getChangeTrustAssetTransaction(assetId: String, memo: String?): String {
        return stellarKitWrapper.createChangeTrustAssetTransactionHex(assetId, memo)
    }

    override fun stop() {
        coroutineScope.cancel()
    }

    override fun refresh() {
    }

    override val fee: BigDecimal
        get() = stellarKit.sendFee

    override fun isHardwareAccount(): Boolean {
        return stellarKitWrapper.isHardwareAccount == true
    }

    override suspend fun getUnsignedTransaction(assetId: String?, destination: String, amount: BigDecimal, memo: String?): String {
        return stellarKitWrapper.createUnsignedTransactionHex(assetId, destination, amount, memo)
    }

    override val maxSendableBalance: BigDecimal
        get() = balance

    override suspend fun getMinimumSendAmount(address: String) = null

    override suspend fun sendRawTransaction(xdrBase64: String) {
        stellarKit.sendRawTransaction(xdrBase64)
    }

    override fun getNetworkPassphrase(): String {
        return stellarKitWrapper.getNetworkPassphrase()
    }

    override suspend fun send(amount: BigDecimal, address: String, memo: String?) {
        stellarKit.sendAsset(stellarAsset.id, address, amount, memo)
    }

    override fun validate(address: String) {
        StellarKit.validateAddress(address)

        if (!stellarKit.isAssetEnabled(stellarAsset, address)) {
            throw NoTrustlineError(stellarAsset.code)
        }
    }

    suspend fun isTrustlineEstablished() = withContext(Dispatchers.Default) {
        assetBalance != null || stellarKit.isAssetEnabled(stellarAsset)
    }

    fun activate() {
        stellarKit.enableAsset(stellarAsset.id, null)
    }

    fun getStellarAssetId(): String {
        return stellarAsset.id
    }

    fun validateActivation() {
        stellarKit.validateEnablingAsset()
    }

    data class NoTrustlineError(val code: String) : Error()
}
