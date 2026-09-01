package io.horizontalsystems.bankwallet.modules.hardwarewallet.importmnemonic

import io.horizontalsystems.bankwallet.modules.hardwarewallet.ble.HardwareWalletBleSession
import io.horizontalsystems.bankwallet.modules.hardwarewallet.ble.HardwareWalletConnectionState
import io.horizontalsystems.hdwalletkit.Language
import io.horizontalsystems.hdwalletkit.Mnemonic
import io.horizontalsystems.hdwalletkit.WordList
import java.text.Normalizer
import java.util.Locale

private const val MNEMONIC_COMMAND_PREFIX = "mnemonic "
private val SUPPORTED_WORD_COUNTS = setOf(12, 15, 18, 21, 24)

sealed interface MnemonicValidationResult {
    data class Valid(val mnemonic: CharArray, val wordCount: Int) : MnemonicValidationResult
    data object Empty : MnemonicValidationResult
    data class UnsupportedWordCount(val actual: Int) : MnemonicValidationResult
    data object InvalidWord : MnemonicValidationResult
    data object InvalidChecksum : MnemonicValidationResult
}

fun interface HardwareWalletMnemonicValidator {
    fun validate(input: CharArray): MnemonicValidationResult
}

class Bip39HardwareWalletMnemonicValidator : HardwareWalletMnemonicValidator {
    private val englishWords = WordList.wordListStrict(Language.English)

    override fun validate(input: CharArray): MnemonicValidationResult {
        val normalized = normalizeMnemonic(input)
        if (normalized.isEmpty()) return MnemonicValidationResult.Empty

        val phrase = normalized.concatToString()
        val words = phrase.split(' ')
        if (words.size !in SUPPORTED_WORD_COUNTS) {
            normalized.fill('\u0000')
            return MnemonicValidationResult.UnsupportedWordCount(words.size)
        }
        if (!englishWords.validWords(words)) {
            normalized.fill('\u0000')
            return MnemonicValidationResult.InvalidWord
        }

        return try {
            Mnemonic().toEntropy(words, englishWords).fill(0)
            MnemonicValidationResult.Valid(normalized, words.size)
        } catch (_: Exception) {
            normalized.fill('\u0000')
            MnemonicValidationResult.InvalidChecksum
        }
    }
}

/**
 * Applies the NFKD and lower-case normalization used by the app's strict BIP-39 flow, then
 * collapses every whitespace run to one ASCII space.
 */
internal fun normalizeMnemonic(input: CharArray): CharArray {
    val normalized = Normalizer.normalize(input.concatToString(), Normalizer.Form.NFKD)
        .lowercase(Locale.ROOT)
    val output = CharArray(normalized.length)
    var outputSize = 0
    var pendingSpace = false

    for (character in normalized) {
        if (character.isWhitespace() || Character.isSpaceChar(character)) {
            pendingSpace = outputSize > 0
        } else {
            if (pendingSpace) output[outputSize++] = ' '
            output[outputSize++] = character
            pendingSpace = false
        }
    }

    return output.copyOf(outputSize).also { output.fill('\u0000') }
}

sealed interface ImportMnemonicResult {
    data object Success : ImportMnemonicResult
    data object FirmwareRejected : ImportMnemonicResult
    data object MalformedResponse : ImportMnemonicResult
}

fun interface HardwareWalletMnemonicImporter {
    /** The supplied array is consumed and erased before this call returns. */
    suspend fun importMnemonic(mnemonic: CharArray): ImportMnemonicResult
}

class DefaultHardwareWalletMnemonicImporter(
    private val session: HardwareWalletBleSession,
    private val responseTimeoutMillis: Long = 120_000L,
) : HardwareWalletMnemonicImporter {

    override suspend fun importMnemonic(mnemonic: CharArray): ImportMnemonicResult {
        var command: ByteArray? = null
        return try {
            check(session.connectionState.value is HardwareWalletConnectionState.Connected) {
                "The Hito device is not connected"
            }
            command = buildMnemonicCommand(mnemonic)
            val response = session.exchange(command, responseTimeoutMillis)
            try {
                parseMnemonicResponse(response)
            } finally {
                response.fill(0)
            }
        } finally {
            command?.fill(0)
            mnemonic.fill('\u0000')
        }
    }
}

internal fun buildMnemonicCommand(mnemonic: CharArray): ByteArray {
    require(mnemonic.all { it.code in 0..0x7f }) {
        "The normalized mnemonic must contain ASCII only"
    }
    val prefix = MNEMONIC_COMMAND_PREFIX.encodeToByteArray()
    val command = ByteArray(prefix.size + mnemonic.size + 1)
    prefix.copyInto(command)
    prefix.fill(0)

    mnemonic.forEachIndexed { index, character ->
        command[MNEMONIC_COMMAND_PREFIX.length + index] = character.code.toByte()
    }
    command[command.lastIndex] = '\n'.code.toByte()
    return command
}

internal fun parseMnemonicResponse(response: ByteArray): ImportMnemonicResult =
    when (response.decodeToString().trim().lowercase(Locale.ROOT)) {
        "ok" -> ImportMnemonicResult.Success
        "err" -> ImportMnemonicResult.FirmwareRejected
        else -> ImportMnemonicResult.MalformedResponse
    }
