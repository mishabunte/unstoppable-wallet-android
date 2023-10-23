package io.horizontalsystems.tonkit.entities

data class TonTransaction(
    val hash: String,
    val lt: Long,
    val timestamp: Long
)
