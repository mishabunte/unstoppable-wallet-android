package io.horizontalsystems.bankwallet.modules.hardwarewallet.importmnemonic

import io.horizontalsystems.bankwallet.modules.hardwarewallet.ble.DeviceVersionInfo
import io.horizontalsystems.bankwallet.modules.hardwarewallet.ble.HardwareWalletBleSession
import io.horizontalsystems.bankwallet.modules.hardwarewallet.ble.HardwareWalletConnectionState
import io.horizontalsystems.bankwallet.modules.hardwarewallet.ble.HitoDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareWalletMnemonicImportTest {
    private val validator = Bip39HardwareWalletMnemonicValidator()

    @Test
    fun `normalization collapses repeated spaces`() {
        val normalized = normalizeMnemonic("  abandon   abandon  about  ".toCharArray())
        assertEquals("abandon abandon about", normalized.concatToString())
        normalized.fill('\u0000')
    }

    @Test
    fun `normalization collapses tabs and line breaks`() {
        val normalized = normalizeMnemonic("abandon\tabandon\n\rabout".toCharArray())
        assertEquals("abandon abandon about", normalized.concatToString())
        normalized.fill('\u0000')
    }

    @Test
    fun `public BIP39 12 word test vector is valid`() {
        val result = validator.validate(PUBLIC_VECTOR_12.toCharArray())
        assertTrue(result is MnemonicValidationResult.Valid)
        (result as MnemonicValidationResult.Valid).mnemonic.fill('\u0000')
    }

    @Test
    fun `public BIP39 24 word test vector is valid`() {
        val result = validator.validate(PUBLIC_VECTOR_24.toCharArray())
        assertTrue(result is MnemonicValidationResult.Valid)
        (result as MnemonicValidationResult.Valid).mnemonic.fill('\u0000')
    }

    @Test
    fun `empty phrase is rejected`() {
        assertEquals(MnemonicValidationResult.Empty, validator.validate(" \n\t ".toCharArray()))
    }

    @Test
    fun `unsupported word count is rejected`() {
        val result = validator.validate("abandon abandon about".toCharArray())
        assertEquals(MnemonicValidationResult.UnsupportedWordCount(3), result)
    }

    @Test
    fun `invalid English word is rejected`() {
        val result = validator.validate(
            PUBLIC_VECTOR_12.replace("about", "notaword").toCharArray(),
        )
        assertEquals(MnemonicValidationResult.InvalidWord, result)
    }

    @Test
    fun `invalid checksum is rejected`() {
        val result = validator.validate(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon ability"
                .toCharArray(),
        )
        assertEquals(MnemonicValidationResult.InvalidChecksum, result)
    }

    @Test
    fun `command construction is exact and newline terminated`() {
        val command = buildMnemonicCommand(PUBLIC_VECTOR_12.toCharArray())
        assertEquals("mnemonic $PUBLIC_VECTOR_12\n", command.decodeToString())
        assertEquals('\n'.code.toByte(), command.last())
        command.fill(0)
    }

    @Test
    fun `import waits for successful firmware response`() = runTest {
        val session = FakeSession("ok\n".encodeToByteArray())
        val importer = DefaultHardwareWalletMnemonicImporter(session)

        val result = importer.importMnemonic(PUBLIC_VECTOR_12.toCharArray())

        assertEquals(ImportMnemonicResult.Success, result)
        assertEquals("mnemonic $PUBLIC_VECTOR_12\n", session.command?.decodeToString())
        session.command?.fill(0)
    }

    @Test
    fun `firmware validation failure is not success`() = runTest {
        val result = DefaultHardwareWalletMnemonicImporter(
            FakeSession("err\n".encodeToByteArray()),
        ).importMnemonic(PUBLIC_VECTOR_12.toCharArray())

        assertEquals(ImportMnemonicResult.FirmwareRejected, result)
    }

    @Test
    fun `locked or non-empty vault generic firmware error is recoverable rejection`() = runTest {
        // Current firmware exposes all command and vault failures as the same textual err response.
        val result = DefaultHardwareWalletMnemonicImporter(
            FakeSession("err\n".encodeToByteArray()),
        ).importMnemonic(PUBLIC_VECTOR_24.toCharArray())

        assertEquals(ImportMnemonicResult.FirmwareRejected, result)
    }

    @Test
    fun `unexpected firmware response is malformed`() = runTest {
        val result = DefaultHardwareWalletMnemonicImporter(
            FakeSession("unrecognized\n".encodeToByteArray()),
        ).importMnemonic(PUBLIC_VECTOR_12.toCharArray())

        assertEquals(ImportMnemonicResult.MalformedResponse, result)
    }

    @Test(expected = IllegalStateException::class)
    fun `disconnected session cannot import`() = runTest {
        val session = FakeSession("ok\n".encodeToByteArray())
        session.connectionState.value = HardwareWalletConnectionState.Disconnected
        DefaultHardwareWalletMnemonicImporter(session).importMnemonic(PUBLIC_VECTOR_12.toCharArray())
    }

    private class FakeSession(private val response: ByteArray) : HardwareWalletBleSession {
        override val connectionState = MutableStateFlow<HardwareWalletConnectionState>(
            HardwareWalletConnectionState.Connected,
        )
        override val selectedDevice = MutableStateFlow<HitoDevice?>(null)
        override val transferProgress = MutableStateFlow(-1.0)
        var command: ByteArray? = null

        override fun selectDevice(device: HitoDevice) = Unit
        override suspend fun connect() = Unit
        override suspend fun send(payload: ByteArray) = Unit
        override suspend fun receive(timeoutMillis: Long): ByteArray = response.copyOf()
        override suspend fun exchange(payload: ByteArray, timeoutMillis: Long): ByteArray {
            command = payload.copyOf()
            return response.copyOf()
        }
        override suspend fun requestDeviceVersion(): DeviceVersionInfo? = null
        override fun deviceName(): String? = "hito"
        override suspend fun disconnect() = Unit
        override fun close() = Unit
    }

    companion object {
        // Public vectors from the BIP-39 specification; these are not user recovery phrases.
        const val PUBLIC_VECTOR_12 =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        const val PUBLIC_VECTOR_24 =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art"
    }
}
