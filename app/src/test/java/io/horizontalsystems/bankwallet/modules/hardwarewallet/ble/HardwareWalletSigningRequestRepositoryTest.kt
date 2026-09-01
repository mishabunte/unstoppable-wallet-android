package io.horizontalsystems.bankwallet.modules.hardwarewallet.ble

import io.horizontalsystems.marketkit.models.BlockchainType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareWalletSigningRequestRepositoryTest {
    private val repository = InMemoryHardwareWalletSigningRequestRepository()

    @Test
    fun `request is obtained by id without exposing stored byte array`() {
        val source = byteArrayOf(1, 2, 3)
        val id = repository.create(source, BlockchainType.Zcash, 133, 0)
        source.fill(9)

        assertArrayEquals(byteArrayOf(1, 2, 3), repository.get(id)?.payload)
    }

    @Test
    fun `request preserves payload metadata`() {
        val id = repository.create(byteArrayOf(1), BlockchainType.Zcash, 133, 7)

        val request = repository.get(id)

        assertEquals(BlockchainType.Zcash, request?.blockchainType)
        assertEquals(133, request?.networkCoinType)
        assertEquals(7L, request?.accountIndex)
    }

    @Test
    fun `completed request can be consumed only once`() {
        val id = repository.create(byteArrayOf(1), BlockchainType.Zcash, 133, 0)
        assertTrue(repository.acquire(id) != null)
        assertTrue(repository.complete(id, byteArrayOf(7, 8)))

        assertArrayEquals(byteArrayOf(7, 8), repository.consumeResult(id))
        assertNull(repository.consumeResult(id))
        assertNull(repository.get(id))
    }

    @Test
    fun `active request cannot be acquired twice`() {
        val id = repository.create(byteArrayOf(1), BlockchainType.Zcash, 133, 0)

        assertTrue(repository.acquire(id) != null)
        assertNull(repository.acquire(id))
        assertEquals(HardwareWalletSigningRequestStatus.InProgress, repository.status(id))
    }

    @Test
    fun `removed request is no longer available`() {
        val id = repository.create(byteArrayOf(1), BlockchainType.Zcash, 133, 0)
        repository.remove(id)
        assertNull(repository.get(id))
    }
}
