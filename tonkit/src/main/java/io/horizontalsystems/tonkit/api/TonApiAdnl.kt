package io.horizontalsystems.tonkit.api

import android.util.Log
import io.horizontalsystems.tonkit.ITonApi
import io.horizontalsystems.tonkit.entities.TonTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.ton.api.liteclient.config.LiteClientConfigGlobal
import org.ton.bitstring.BitString
import org.ton.block.AccountInfo
import org.ton.block.AddrStd
import org.ton.lite.client.LiteClient
import org.ton.lite.client.internal.TransactionId
import org.ton.lite.client.internal.TransactionInfo
import java.math.BigDecimal
import java.net.URL

class TonApiAdnl(
    private val words: List<String>,
    private val passphrase: String,
    seed: ByteArray,
    privKey: ByteArray,
) : ITonApi {
    override var balance: BigDecimal = BigDecimal.ZERO
    override val address = "UQBpAeJL-VSLCigCsrgGQHCLeiEBdAuZBlbrrUGI4BVQJoPM"
    private val addrStd = AddrStd.parse(address)

    private val coroutineScope = CoroutineScope(Dispatchers.Default)
    private val json = Json {
        ignoreUnknownKeys = true
    }
    private val config = json.decodeFromString(
        LiteClientConfigGlobal.serializer(),
//        URL("https://ton.org/testnet-global.config.json").readText()
        URL("https://ton.org/global.config.json").readText()
    )
    private val liteClient = LiteClient(Dispatchers.Default, config)

//    init {
//        val tonSeed = Mnemonic.toSeed(words, passphrase)
//        val privateKey = PrivateKeyEd25519(privKey)
//        val publicKey = privateKey.publicKey()
//        val wallet = WalletV4R2Contract(0, publicKey)
//
//        val addrStd = wallet.address as AddrStd
//        address = addrStd.toString(true)
//
//
//        Log.e("AAA", "address: $address")
//        Log.e("AAA", " privKey: ${privKey.size} ${privKey.map { it.toInt() }}")
//        Log.e("AAA", "hrs seed: ${seed.size} ${seed.map { it.toInt() }}")
//        Log.e("AAA", "ton seed: ${tonSeed.size} ${tonSeed.map { it.toInt() }}")
//        Log.e("AAA", "words: $words, $passphrase")
//
//
//            val lastBlockId = liteClient.getLastBlockId()
//
//            // Get block by ID until it appears in the database on the light server
//            val block: Block
//            while (true) {
//                block = liteClient.getBlock(lastBlockId) ?: continue
//                break
//            }
//
//    }

    override fun start() {
        coroutineScope.launch {
            val fullAccountState = liteClient.getAccountState(addrStd)

            val account = fullAccountState.account.value
            balance = if (account is AccountInfo) {
                BigDecimal(account.storage.balance.coins.toString())
            } else {
                BigDecimal.ZERO
            }
        }
    }

    override fun stop() {
        coroutineScope.cancel()
    }

    override fun refresh() {

    }

    override suspend fun transactions(limit: Int, transactionHash: String?, lt: Long?): List<TonTransaction> {
        val transactionId = when {
            transactionHash != null && lt != null -> TransactionId(BitString(transactionHash), lt)
            else -> liteClient.getAccountState(addrStd).lastTransactionId
        } ?: return listOf()

        val transactions = liteClient.getTransactions(addrStd, transactionId, limit)
        Log.e("AAA", "transactions size: ${transactions.size}, limit: $limit")
        return transactions.map { createTonTransaction(it) }
    }

    private fun createTonTransaction(info: TransactionInfo) = TonTransaction(
        hash = info.id.hash.toHex(),
        lt = info.id.lt,
        timestamp = info.transaction.value.now.toLong()
    )
}
