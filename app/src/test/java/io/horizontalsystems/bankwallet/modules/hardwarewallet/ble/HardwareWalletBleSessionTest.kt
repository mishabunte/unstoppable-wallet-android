package io.horizontalsystems.bankwallet.modules.hardwarewallet.ble

import android.bluetooth.BluetoothDevice
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class HardwareWalletBleSessionTest {
    @Test
    fun `selected device is retained by session and used for manager creation`() {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        val device = HitoDevice(mock(BluetoothDevice::class.java))
        var managerDevice: HitoDevice? = null
        val session = DefaultHardwareWalletBleSession(context) { _, selected ->
            managerDevice = selected
            FakeHitoBle()
        }

        session.selectDevice(device)

        assertSame(device, session.selectedDevice.value)
        assertSame(device, managerDevice)
        session.close()
    }

    @Test
    fun `exchange serializes send through final response`() = runTest {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        val manager = BlockingHitoBle()
        val session = DefaultHardwareWalletBleSession(context) { _, _ -> manager }
        session.selectDevice(HitoDevice(mock(BluetoothDevice::class.java)))
        session.connect()

        val first = async { session.exchange(byteArrayOf(1), 1_000) }
        runCurrent()
        val second = async { session.exchange(byteArrayOf(2), 1_000) }
        runCurrent()

        assertEquals(listOf(listOf<Byte>(1)), manager.sent.map(ByteArray::toList))
        manager.firstResponse.complete(Unit)
        runCurrent()
        first.await()
        second.await()
        assertEquals(
            listOf(listOf<Byte>(1), listOf<Byte>(2)),
            manager.sent.map(ByteArray::toList),
        )
        session.close()
    }

    @Test
    fun `cancelling exchange releases manager to discard partial command`() = runTest {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        val manager = BlockingHitoBle()
        val session = DefaultHardwareWalletBleSession(context) { _, _ -> manager }
        session.selectDevice(HitoDevice(mock(BluetoothDevice::class.java)))
        session.connect()

        val exchange = async { session.exchange(byteArrayOf(1), 1_000) }
        runCurrent()
        exchange.cancelAndJoin()

        assertTrue(manager.released)
        assertTrue(session.connectionState.value is HardwareWalletConnectionState.Disconnected)
        session.close()
    }

    private class FakeHitoBle : IHitoBle {
        override val state = MutableStateFlow(IHitoBle.State.NOT_AVAILABLE)
        override val installationProgress = MutableStateFlow(-1.0)
        override fun getDeviceName(): String? = "hito"
        override suspend fun connect() = Unit
        override suspend fun sendPayload(payload: ByteArray) = Unit
        override suspend fun receivePayload(timeoutMillis: Long): ByteArray = byteArrayOf()
        override suspend fun requestDeviceVersion(): DeviceVersionInfo? = null
        override fun release() = Unit
    }

    private class BlockingHitoBle : IHitoBle {
        override val state = MutableStateFlow(IHitoBle.State.READY)
        override val installationProgress = MutableStateFlow(-1.0)
        val firstResponse = CompletableDeferred<Unit>()
        val sent = mutableListOf<ByteArray>()
        var released = false
        private var responses = 0

        override fun getDeviceName(): String? = "hito"
        override suspend fun connect() = Unit
        override suspend fun sendPayload(payload: ByteArray) {
            sent += payload.copyOf()
        }
        override suspend fun receivePayload(timeoutMillis: Long): ByteArray {
            if (responses++ == 0) firstResponse.await()
            return "ok\n".encodeToByteArray()
        }
        override suspend fun requestDeviceVersion(): DeviceVersionInfo? = null
        override fun release() {
            released = true
        }
    }
}
