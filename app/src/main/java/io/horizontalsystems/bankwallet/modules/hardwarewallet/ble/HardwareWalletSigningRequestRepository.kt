package io.horizontalsystems.bankwallet.modules.hardwarewallet.ble

import io.horizontalsystems.marketkit.models.BlockchainType
import java.util.UUID

data class HardwareWalletSigningRequest(
    val id: String,
    val payload: ByteArray,
    val blockchainType: BlockchainType,
    val networkCoinType: Int,
    val accountIndex: Long,
)

enum class HardwareWalletSigningRequestStatus {
    Ready,
    InProgress,
    Completed,
}

interface HardwareWalletSigningRequestRepository {
    fun create(
        payload: ByteArray,
        blockchainType: BlockchainType,
        networkCoinType: Int,
        accountIndex: Long,
    ): String

    fun get(requestId: String?): HardwareWalletSigningRequest?
    fun acquire(requestId: String?): HardwareWalletSigningRequest?
    fun releaseForRetry(requestId: String?)
    fun complete(requestId: String?, signedPayload: ByteArray): Boolean
    fun consumeResult(requestId: String?): ByteArray?
    fun status(requestId: String?): HardwareWalletSigningRequestStatus?
    fun remove(requestId: String?)
}

class InMemoryHardwareWalletSigningRequestRepository : HardwareWalletSigningRequestRepository {
    private data class Entry(
        val request: HardwareWalletSigningRequest,
        var status: HardwareWalletSigningRequestStatus,
        var signedPayload: ByteArray? = null,
    )

    private val entries = mutableMapOf<String, Entry>()

    @Synchronized
    override fun create(
        payload: ByteArray,
        blockchainType: BlockchainType,
        networkCoinType: Int,
        accountIndex: Long,
    ): String {
        val id = UUID.randomUUID().toString()
        entries[id] = Entry(
            request = HardwareWalletSigningRequest(
                id = id,
                payload = payload.copyOf(),
                blockchainType = blockchainType,
                networkCoinType = networkCoinType,
                accountIndex = accountIndex,
            ),
            status = HardwareWalletSigningRequestStatus.Ready,
        )
        return id
    }

    @Synchronized
    override fun get(requestId: String?): HardwareWalletSigningRequest? =
        if (requestId == null) null else entries[requestId]?.request?.copyPayload()

    @Synchronized
    override fun acquire(requestId: String?): HardwareWalletSigningRequest? {
        if (requestId == null) return null
        val entry = entries[requestId] ?: return null
        if (entry.status != HardwareWalletSigningRequestStatus.Ready) return null
        entry.status = HardwareWalletSigningRequestStatus.InProgress
        return entry.request.copyPayload()
    }

    @Synchronized
    override fun releaseForRetry(requestId: String?) {
        if (requestId == null) return
        entries[requestId]?.takeIf {
            it.status == HardwareWalletSigningRequestStatus.InProgress
        }?.status = HardwareWalletSigningRequestStatus.Ready
    }

    @Synchronized
    override fun complete(requestId: String?, signedPayload: ByteArray): Boolean {
        if (requestId == null) return false
        val entry = entries[requestId] ?: return false
        if (entry.status != HardwareWalletSigningRequestStatus.InProgress) return false
        entry.request.payload.fill(0)
        entry.signedPayload = signedPayload.copyOf()
        entry.status = HardwareWalletSigningRequestStatus.Completed
        return true
    }

    @Synchronized
    override fun consumeResult(requestId: String?): ByteArray? {
        if (requestId == null) return null
        val entry = entries[requestId] ?: return null
        if (entry.status != HardwareWalletSigningRequestStatus.Completed) return null
        entries.remove(requestId)
        val result = entry.signedPayload?.copyOf()
        entry.signedPayload?.fill(0)
        entry.request.payload.fill(0)
        return result
    }

    @Synchronized
    override fun status(requestId: String?): HardwareWalletSigningRequestStatus? =
        if (requestId == null) null else entries[requestId]?.status

    @Synchronized
    override fun remove(requestId: String?) {
        if (requestId == null) return
        entries.remove(requestId)?.let { entry ->
            entry.request.payload.fill(0)
            entry.signedPayload?.fill(0)
        }
    }

    private fun HardwareWalletSigningRequest.copyPayload() = copy(payload = payload.copyOf())
}
