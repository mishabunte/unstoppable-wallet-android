package io.horizontalsystems.tonkit.entities

import java.math.BigDecimal

data class TonTransaction(
    val hash: String,
    val lt: Long,
    val timestamp: Long,
    val value: BigDecimal,
    val type: TransactionType
)

enum class TransactionType {
    Incoming, Outgoing
}
