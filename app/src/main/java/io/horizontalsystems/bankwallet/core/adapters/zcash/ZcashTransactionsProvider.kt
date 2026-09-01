package io.horizontalsystems.bankwallet.core.adapters.zcash

import cash.z.ecc.android.sdk.SdkSynchronizer
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.TransactionRecipient
import io.horizontalsystems.bankwallet.modules.transactions.FilterTransactionType
import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import io.reactivex.Single
import io.reactivex.subjects.PublishSubject
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

class ZcashTransactionsProvider(
    private val accountUuid: AccountUuid,
    private val synchronizer: SdkSynchronizer
) {
    private val transactions = AtomicReference<List<ZcashTransaction>>(emptyList())
    private val newTransactionsSubject =
        PublishSubject.create<List<ZcashTransaction>>().toSerialized()

    /**
     * Processes complete snapshots sequentially from the allTransactions collector.
     */
    suspend fun onTransactions(transactionOverviews: List<TransactionOverview>) {
        val updatedTransactions = transactionOverviews.map { overview ->
            val recipients = if (overview.isSentTransaction) {
                synchronizer.getRecipients(overview)
                    .filterIsInstance<TransactionRecipient>()
                    .toList()
            } else {
                null
            }

            val memo = synchronizer.getMemos(overview).firstOrNull()
            ZcashTransaction(accountUuid, overview, recipients, memo)
        }.sortedDescending()

        val previousTransactions = transactions.getAndSet(updatedTransactions)
        val addedOrUpdatedTransactions = updatedTransactions.filter { updated ->
            previousTransactions.none { previous ->
                previous.hasSameContentAs(updated)
            }
        }

        // Notify observers only after the cache contains the new snapshot.
        if (addedOrUpdatedTransactions.isNotEmpty()) {
            newTransactionsSubject.onNext(addedOrUpdatedTransactions)
        }
    }

    fun getNewTransactionsFlowable(
        transactionType: FilterTransactionType,
        address: String?
    ): Flowable<List<ZcashTransaction>> {
        val filters = getFilters(transactionType, address)

        val observable = if (filters.isEmpty()) {
            newTransactionsSubject
        } else {
            newTransactionsSubject
                .map { transactionList ->
                    transactionList.filter { transaction ->
                        filters.all { filter -> filter(transaction) }
                    }
                }
                .filter { it.isNotEmpty() }
        }

        return observable.toFlowable(BackpressureStrategy.LATEST)
    }

    private fun getFilters(
        transactionType: FilterTransactionType,
        address: String?
    ) = buildList<(ZcashTransaction) -> Boolean> {
        when (transactionType) {
            FilterTransactionType.All -> Unit
            FilterTransactionType.Incoming -> add { it.isIncoming }
            FilterTransactionType.Outgoing -> add { !it.isIncoming }
            FilterTransactionType.Swap,
            FilterTransactionType.Approve -> add { false }
        }

        if (address != null) {
            add { transaction ->
                transaction.recipients
                    ?.any { recipient -> recipient.addressValue == address }
                    ?: false
            }
        }
    }

    fun getTransactions(
        from: Triple<ByteArray, Long, Int>?,
        transactionType: FilterTransactionType,
        address: String?,
        limit: Int
    ): Single<List<ZcashTransaction>> = Single.fromCallable {
        require(limit >= 0) { "Limit must not be negative" }

        val snapshot = transactions.get()
        val filters = getFilters(transactionType, address)
        val filtered = if (filters.isEmpty()) {
            snapshot
        } else {
            snapshot.filter { transaction ->
                filters.all { filter -> filter(transaction) }
            }
        }

        val fromIndex = from?.let { cursor ->
            val index = filtered.indexOfFirst { transaction ->
                transaction.transactionHash.contentEquals(cursor.first) &&
                        transaction.timestamp == cursor.second &&
                        transaction.transactionIndex == cursor.third
            }

            if (index >= 0) index + 1 else filtered.size
        } ?: 0

        val toIndex = min(
            filtered.size.toLong(),
            fromIndex.toLong() + limit.toLong()
        ).toInt()

        filtered.subList(fromIndex, toIndex)
    }

    private fun ZcashTransaction.hasSameContentAs(other: ZcashTransaction): Boolean {
        return transactionHash.contentEquals(other.transactionHash) &&
                minedHeight == other.minedHeight &&
                timestamp == other.timestamp &&
                transactionIndex == other.transactionIndex &&
                isIncoming == other.isIncoming &&
                recipients == other.recipients &&
                memo == other.memo &&
                feePaid == other.feePaid &&
                failed == other.failed &&
                shieldDirection == other.shieldDirection &&
                value == other.value
    }
}