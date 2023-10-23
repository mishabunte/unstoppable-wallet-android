package io.horizontalsystems.tonkit

import io.horizontalsystems.tonkit.entities.TonTransaction

class TonKit(private val tonApi: ITonApi) {
    val balance by tonApi::balance
    val address by tonApi::address

    fun start() {
        tonApi.start()
    }

    fun stop() {
        tonApi.stop()
    }

    fun refresh() {
        tonApi.refresh()
    }

    suspend fun transactions(
        limit: Int,
        transactionHash: String?,
        lt: Long?
    ): List<TonTransaction> {
        return tonApi.transactions(limit, transactionHash, lt)
    }
}
