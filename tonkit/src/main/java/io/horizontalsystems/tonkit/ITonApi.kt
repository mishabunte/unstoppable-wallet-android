package io.horizontalsystems.tonkit

import io.horizontalsystems.tonkit.entities.TonTransaction
import java.math.BigDecimal

interface ITonApi {
    val balance: BigDecimal
    val address: String

    fun start()
    fun stop()
    fun refresh()
    suspend fun transactions(limit: Int, transactionHash: String?, lt: Long?): List<TonTransaction>
}
