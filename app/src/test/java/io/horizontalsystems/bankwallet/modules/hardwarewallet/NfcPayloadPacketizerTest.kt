package io.horizontalsystems.bankwallet.modules.hardwarewallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NfcPayloadPacketizerTest {

    @Test
    fun `short payload is terminated with newline`() {
        assertEquals(listOf("payload\n"), splitNfcPayload("payload"))
    }

    @Test
    fun `existing newline terminates payload`() {
        assertEquals(listOf("payload\n"), splitNfcPayload("payload\nignored"))
    }

    @Test
    fun `large payload is split into bounded packets`() {
        val payload = "a".repeat(NDEF_MAX_TEXT_PACKET_LEN * 2)

        val packets = splitNfcPayload(payload)

        assertEquals(payload + '\n', packets.joinToString(separator = ""))
        assertEquals(3, packets.size)
        assertTrue(packets.all { it.toByteArray(Charsets.UTF_8).size <= NDEF_MAX_TEXT_PACKET_LEN })
        assertTrue(packets.dropLast(1).none { it.endsWith('\n') })
        assertTrue(packets.last().endsWith('\n'))
    }

    @Test
    fun `multibyte characters are not split`() {
        val payload = "🙂".repeat(NDEF_MAX_TEXT_PACKET_LEN / 4 + 1)

        val packets = splitNfcPayload(payload)

        assertEquals(payload + '\n', packets.joinToString(separator = ""))
        assertTrue(packets.all { it.toByteArray(Charsets.UTF_8).size <= NDEF_MAX_TEXT_PACKET_LEN })
    }
}
