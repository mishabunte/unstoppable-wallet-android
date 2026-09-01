package io.horizontalsystems.bankwallet.modules.hardwarewallet.ble

import io.horizontalsystems.bankwallet.modules.hardwarewallet.importmnemonic.HardwareWalletMnemonicImportTest
import io.horizontalsystems.bankwallet.modules.hardwarewallet.importmnemonic.buildMnemonicCommand
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HitoBleUploadPacketsTest {
    @Test
    fun `24 word command spans ordered BLE chunks and terminator is last`() {
        val command = buildMnemonicCommand(
            HardwareWalletMnemonicImportTest.PUBLIC_VECTOR_24.toCharArray(),
        )
        val packetSize = 19
        val chunks = command.indices.step(packetSize).map { offset ->
            HitoBleUploadPackets.data(command, offset, packetSize)
        }

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.first() == 'd'.code.toByte() })
        val reconstructed = chunks.flatMap { it.drop(1) }.toByteArray()
        assertArrayEquals(command, reconstructed)
        assertEquals('\n'.code.toByte(), reconstructed.last())

        val info = HitoBleUploadPackets.info(command.size)
        val encodedSize = (info[1].toInt() and 0xff) or
            ((info[2].toInt() and 0xff) shl 8) or
            ((info[3].toInt() and 0xff) shl 16) or
            ((info[4].toInt() and 0xff) shl 24)
        assertEquals(command.size, encodedSize)

        chunks.forEach { it.fill(0) }
        reconstructed.fill(0)
        info.fill(0)
        command.fill(0)
    }
}
