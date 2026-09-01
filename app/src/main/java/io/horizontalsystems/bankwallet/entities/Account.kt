package io.horizontalsystems.bankwallet.entities

import android.os.Parcelable
import io.horizontalsystems.bankwallet.R
import io.horizontalsystems.bankwallet.core.App
import io.horizontalsystems.bankwallet.core.managers.PassphraseValidator
import io.horizontalsystems.bankwallet.core.providers.Translator
import io.horizontalsystems.bankwallet.core.shorten
import io.horizontalsystems.ethereumkit.core.signer.Signer
import io.horizontalsystems.ethereumkit.models.Chain
import io.horizontalsystems.hdwalletkit.HDExtendedKey
import io.horizontalsystems.hdwalletkit.HDWallet
import io.horizontalsystems.hdwalletkit.Language
import io.horizontalsystems.hdwalletkit.Mnemonic
import io.horizontalsystems.hdwalletkit.WordList
import io.horizontalsystems.marketkit.models.BlockchainType
import io.horizontalsystems.marketkit.models.TokenType
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.tool.DerivationTool
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import java.math.BigInteger
import java.text.Normalizer

@Parcelize
data class Account(
    val id: String,
    val name: String,
    val type: AccountType,
    val origin: AccountOrigin,
    val level: Int,
    val isBackedUp: Boolean = false,
    val isFileBackedUp: Boolean = false,
) : Parcelable {

    @IgnoredOnParcel
    val hasAnyBackup = isBackedUp || isFileBackedUp

    @IgnoredOnParcel
    val isWatchAccount: Boolean
        get() = type.isWatchAccountType

    @IgnoredOnParcel
    val isHardwareAccount: Boolean
        get() = when (this.type) {
            is AccountType.EvmAddressHardware -> true
            is AccountType.SolanaAddressHardware -> true
            is AccountType.StellarAddressHardware -> true
            is AccountType.TronAddressHardware -> true
            is AccountType.ZcashHardware -> true
            is AccountType.HdExtendedKeyHardware -> this.type.hdExtendedKey.isPublic
            else -> false
        }

    @IgnoredOnParcel
    val nonStandard: Boolean by lazy {
        if (type is AccountType.Mnemonic) {
            val words = type.words.joinToString(separator = " ")
            val passphrase = type.passphrase
            val normalizedWords = words.normalizeNFKD()
            val normalizedPassphrase = passphrase.normalizeNFKD()

            when {
                words != normalizedWords -> true
                passphrase != normalizedPassphrase -> true
                else -> try {
                    Mnemonic().validateStrict(type.words)
                    false
                } catch (exception: Exception) {
                    true
                }
            }
        } else {
            false
        }
    }

    @IgnoredOnParcel
    val nonRecommended: Boolean by lazy {
        if (type is AccountType.Mnemonic) {
            val englishWords = WordList.wordList(Language.English).validWords(type.words)
            val standardPassphrase = PassphraseValidator().containsValidCharacters(type.passphrase)
            !englishWords || !standardPassphrase
        } else {
            false
        }
    }

    override fun equals(other: Any?): Boolean {
        if (other is Account) {
            return id == other.id
        }

        return false
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}

@Parcelize
sealed class CexType : Parcelable {
    @Parcelize
    class Binance(val apiKey: String, val secretKey: String) : CexType()

    fun serialized() = when (this) {
        is Binance -> listOf("binance", apiKey, secretKey).joinToString(dataSeparator)
    }

    fun sameType(other: CexType): Boolean {
        return when (this) {
            is Binance -> other is Binance
        }
    }

    fun name(): String {
        return when (this) {
            is Binance -> "Binance"
        }
    }

    companion object {
        fun deserialize(value: String): CexType? {
            val parts = value.split(dataSeparator)

            return when (parts[0]) {
                "binance" -> Binance(parts[1], parts[2])
                else -> null
            }

        }

        private val dataSeparator = "@"
    }
}

@Parcelize
sealed class AccountType : Parcelable {
    @Parcelize
    data class Cex(val cexType: CexType) : AccountType()

    @Parcelize
    data class EvmAddress(val address: String) : AccountType()

    @Parcelize
    data class SolanaAddress(val address: String) : AccountType()

    @Parcelize
    data class TronAddress(val address: String) : AccountType()

    @Parcelize
    data class EvmAddressHardware(val address: String) : AccountType()

    @Parcelize
    data class SolanaAddressHardware(val address: String) : AccountType()

    @Parcelize
    data class ZcashHardware(
        val ufvk: String,
        val unifiedAddress: String,
        val seedFingerprint: String,
        val accountIndex: Long,
        val externalNsk: String,
        val internalNsk: String,
        val isTestNet: Boolean
    ) : AccountType() {

        init {
            require(ufvk.isNotBlank()) {
                "UFVK must not be blank"
            }

            require(unifiedAddress.isNotBlank()) {
                "Unified address must not be blank"
            }

            require(isHex32(seedFingerprint)) {
                "Seed fingerprint must contain 32 bytes encoded as hex"
            }

            require(isHex32(externalNsk)) {
                "External nsk must contain 32 bytes encoded as hex"
            }

            require(isHex32(internalNsk)) {
                "Internal nsk must contain 32 bytes encoded as hex"
            }

            require(accountIndex in 0..MAX_ACCOUNT_INDEX) {
                "Account index must be a valid hardened ZIP-32 index"
            }
        }

        val serialized: String
            get() = listOf(
                STORAGE_VERSION,
                ufvk,
                unifiedAddress,
                seedFingerprint.lowercase(),
                accountIndex.toString(),
                externalNsk.lowercase(),
                internalNsk.lowercase(),
                if (isTestNet) "1" else "0"
            ).joinToString("|")

        companion object {
            private const val STORAGE_VERSION = "hito-zcash-v2"

            private const val MAINNET_COIN_TYPE = 133L
            private const val TESTNET_COIN_TYPE = 1L
            private const val MAX_ACCOUNT_INDEX = 0x7FFF_FFFFL

            private val HEX_32_REGEX = Regex("^[0-9a-fA-F]{64}$")

            private fun isHex32(value: String): Boolean =
                HEX_32_REGEX.matches(value)

            fun decodeHitoPayload(payload: String): List<String> {
                val parts = payload.split('|')

                require(parts.size == 7) {
                    "Invalid Zcash pairing payload"
                }

                require(parts[0] == "hito-zcash-v2") {
                    "Unsupported Zcash pairing payload version"
                }

                val coinType = parts[1].toLong()

                require(coinType == MAINNET_COIN_TYPE || coinType == TESTNET_COIN_TYPE) {
                    "Unsupported Zcash coin type: $coinType"
                }

                return parts
            }

            fun partsFromSerialized(serialized: String): List<String> {
                val parts = serialized.split('|')

                require(parts.size == 8) {
                    "Invalid serialized ZcashHardware"
                }

                require(parts[0] == STORAGE_VERSION) {
                    "Unsupported ZcashHardware version: ${parts[0]}"
                }

                return parts
            }

            fun fromSerialized(serialized: String): ZcashHardware {
                val parts: List<String> = partsFromSerialized(serialized)

                val isTestNet = when (parts[7]) {
                    "0" -> false
                    "1" -> true
                    else -> throw IllegalArgumentException(
                        "Invalid Zcash network flag"
                    )
                }

                return ZcashHardware(
                    ufvk = parts[1],
                    unifiedAddress = parts[2],
                    seedFingerprint = parts[3],
                    accountIndex = parts[4].toLong(),
                    externalNsk = parts[5],
                    internalNsk = parts[6],
                    isTestNet = isTestNet
                )
            }

            fun fromPairingPayload(
                payload: String,
                unifiedAddress: String = ""
            ): ZcashHardware {
                val parts = payload.split('|')

                require(parts.size == 7) {
                    "Invalid Zcash pairing payload"
                }

                require(parts[0] == "hito-zcash-v2") {
                    "Unsupported Zcash pairing payload version"
                }

                val coinType = parts[1].toLong()

                val isTestNet = when (coinType) {
                    MAINNET_COIN_TYPE -> false
                    TESTNET_COIN_TYPE -> true
                    else -> throw IllegalArgumentException(
                        "Unsupported Zcash coin type: $coinType"
                    )
                }

                return ZcashHardware(
                    ufvk = parts[2],
                    unifiedAddress = unifiedAddress,
                    seedFingerprint = parts[3],
                    accountIndex = parts[4].toLong(),
                    externalNsk = parts[5],
                    internalNsk = parts[6],
                    isTestNet = isTestNet
                )
            }
        }
    }

    @Parcelize
    data class StellarAddressHardware(val address: String) : AccountType()

    @Parcelize
    data class TronAddressHardware(val address: String): AccountType()

    @Parcelize
    data class TonAddress(val address: String) : AccountType()

    @Parcelize
    data class StellarAddress(val address: String) : AccountType()

    @Parcelize
    data class BitcoinAddress(val address: String, val blockchainType: BlockchainType, val tokenType: TokenType) : AccountType() {

        val serialized: String
            get() = "$address|${blockchainType.uid}|${tokenType.id}"

        companion object {
            fun fromSerialized(serialized: String): BitcoinAddress {
                val split = serialized.split("|")
                return BitcoinAddress(
                    split[0],
                    BlockchainType.fromUid(split[1]),
                    TokenType.fromId(split[2])!!
                )
            }
        }
    }

    @Parcelize
    data class Mnemonic(val words: List<String>, val passphrase: String) : AccountType() {
        @IgnoredOnParcel
        val seed by lazy { Mnemonic().toSeed(words, passphrase) }

        override fun equals(other: Any?): Boolean {
            return other is Mnemonic
                    && words.toTypedArray().contentEquals(other.words.toTypedArray())
                    && passphrase == other.passphrase
        }

        override fun hashCode(): Int {
            return words.toTypedArray().contentHashCode() + passphrase.hashCode()
        }
    }

    @Parcelize
    data class StellarSecretKey(val key: String) : AccountType() {
        override fun equals(other: Any?): Boolean {
            return other is StellarSecretKey && key == other.key
        }

        override fun hashCode(): Int {
            return key.hashCode()
        }
    }

    @Parcelize
    data class EvmPrivateKey(val key: BigInteger) : AccountType() {
        override fun equals(other: Any?): Boolean {
            return other is EvmPrivateKey && key == other.key
        }

        override fun hashCode(): Int {
            return key.hashCode()
        }
    }

    @Parcelize
    data class HdExtendedKey(val keySerialized: String) : AccountType() {
        val hdExtendedKey: HDExtendedKey
            get() = HDExtendedKey(keySerialized)

        override fun equals(other: Any?): Boolean {
            return other is HdExtendedKey && keySerialized.contentEquals(other.keySerialized)
        }

        override fun hashCode(): Int {
            return keySerialized.hashCode()
        }
    }

    @Parcelize
    data class HdExtendedKeyHardware(val keySerialized: String) : AccountType() {
        val hdExtendedKey: HDExtendedKey
            get() = HDExtendedKey(keySerialized)

        override fun equals(other: Any?): Boolean {
            return other is HdExtendedKeyHardware && keySerialized.contentEquals(other.keySerialized)
        }

        override fun hashCode(): Int {
            return keySerialized.hashCode()
        }
    }

    @Parcelize
    enum class Derivation(val value: String) : Parcelable {
        bip44("bip44"),
        bip49("bip49"),
        bip84("bip84"),
        bip86("bip86");

        val recommended: String
            get() = when (this) {
                bip84 -> " " + Translator.getString(R.string.Restore_Bip_Recommended)
                else -> ""
            }

        val addressType: String
            get() = when (this) {
                bip44 -> "Legacy"
                bip49 -> "SegWit"
                bip84 -> "Native SegWit"
                bip86 -> "Taproot"
            }

        val rawName: String
            get() = when (this) {
                bip44 -> "BIP 44"
                bip49 -> "BIP 49"
                bip84 -> "BIP 84"
                bip86 -> "BIP 86"
            }

        val purpose: HDWallet.Purpose
            get() = when (this) {
                bip44 -> HDWallet.Purpose.BIP44
                bip49 -> HDWallet.Purpose.BIP49
                bip84 -> HDWallet.Purpose.BIP84
                bip86 -> HDWallet.Purpose.BIP86
            }

        val order: Int
            get() = when (this) {
                bip84 -> 0
                bip86 -> 1
                bip49 -> 2
                bip44 -> 3
            }

        companion object {
            val default = bip84
            private val map = values().associateBy(Derivation::value)

            fun fromString(value: String?): Derivation? = map[value]
        }
    }

    val description: String
        get() = when (this) {
            is Mnemonic -> {
                val count = words.size

                if (passphrase.isNotBlank()) {
                    Translator.getString(R.string.ManageAccount_NWordsWithPassphrase, count)
                } else {
                    Translator.getString(R.string.ManageAccount_NWords, count)
                }
            }
            is BitcoinAddress -> "BTC Address"
            is EvmAddress -> "EVM Address"
            is SolanaAddress -> "Solana Address"
            is TronAddress -> "Tron Address"
            is TonAddress -> "Ton Address"
            is StellarAddress -> "Stellar Address"
            is EvmPrivateKey -> "EVM Private Key"
            is StellarSecretKey -> "Stellar Secret Key"
            is ZcashHardware -> "Zcash UFVK Hardware"
            is HdExtendedKey -> {
                when (this.hdExtendedKey.derivedType) {
                    HDExtendedKey.DerivedType.Master -> "BIP32 Root Key"
                    HDExtendedKey.DerivedType.Account -> {
                        if (hdExtendedKey.isPublic) {
                            "Account xPubKey"
                        } else {
                            "Account xPrivKey"
                        }
                    }
                    else -> ""
                }
            }
            is EvmAddressHardware -> "EVM Address Hardware"
            is SolanaAddressHardware -> "Solana Address Hardware"
            is StellarAddressHardware -> "Stellar Address Hardware"
            is TronAddressHardware -> "Tron Address Hardware"
            is HdExtendedKeyHardware -> {
                when (this.hdExtendedKey.derivedType) {
                    HDExtendedKey.DerivedType.Master -> "BIP32 Root Key Hardware"
                    HDExtendedKey.DerivedType.Account -> {
                        if (hdExtendedKey.isPublic) {
                            "Account xPubKey Hardware"
                        } else {
                            "Account xPrivKey Hardware"
                        }
                    }
                    else -> ""
                }
            }
            is Cex -> "Cex"
        }

    val supportedDerivations: List<Derivation>
        get() = when (this) {
            is Mnemonic -> {
                listOf(Derivation.bip44, Derivation.bip49, Derivation.bip84, Derivation.bip86)
            }
            is HdExtendedKey -> {
                hdExtendedKey.purposes.map { it.derivation }
            }
            else -> emptyList()
        }

    val hideZeroBalances: Boolean
        get() = this is EvmAddress || this is SolanaAddress

    val detailedDescription: String
        get() = when (this) {
            is EvmAddress -> this.address.shorten()
            is ZcashHardware -> this.unifiedAddress.shorten()
            is SolanaAddress -> this.address.shorten()
            is TronAddress -> this.address.shorten()
            is EvmAddressHardware -> this.address.shorten()
            is SolanaAddressHardware -> this.address.shorten()
            is StellarAddressHardware -> this.address.shorten()
            is TronAddressHardware -> this.address.shorten()
            is TonAddress -> this.address.shorten()
            is StellarAddress -> this.address.shorten()
            is BitcoinAddress -> this.address.shorten()
            else -> this.description
        }

    val canAddTokens: Boolean
        get() = when (this) {
            is Mnemonic, is EvmPrivateKey, is EvmAddressHardware, is HdExtendedKeyHardware -> true
            else -> false
        }

    val supportsWalletConnect: Boolean
        get() = when (this) {
            is Mnemonic, is EvmPrivateKey, is EvmAddressHardware, is HdExtendedKeyHardware  -> true
            else -> false
        }

    val isWatchAccountType: Boolean
        get() = when (this) {
            is EvmAddress -> true
            is SolanaAddress -> true
            is TronAddress -> true
            is TonAddress -> true
            is StellarAddress -> true
            is BitcoinAddress -> true
            is HdExtendedKey -> hdExtendedKey.isPublic
            else -> false
        }

    fun evmAddress(chain: Chain) = when (this) {
        is Mnemonic -> Signer.address(seed, chain)
        is EvmPrivateKey -> Signer.address(key)
        else -> null
    }

    fun solanaAddress() = when (this) {
        is Mnemonic -> io.horizontalsystems.solanakit.Signer.address(seed)
        is SolanaAddress -> address
        is SolanaAddressHardware -> address
        else -> null
    }

    suspend fun unifiedZcashAddress(network: ZcashNetwork): String? {
        return when (this) {
            is ZcashHardware ->
                DerivationTool.getInstance().deriveUnifiedAddress(ufvk, network)

            else -> null
        }
    }

    fun stellarAddress() = when (this) {
        //is Mnemonic -> io.horizontalsystems.stellarkit.StellarWallet
        is StellarAddress -> address
        is StellarAddressHardware -> address
        else -> null
    }

    fun sign(message: ByteArray, isLegacy: Boolean = false): ByteArray? {
        val signer = when (this) {
            is Mnemonic -> {
                Signer.getInstance(seed, App.evmBlockchainManager.getChain(BlockchainType.Ethereum))
            }
            is EvmPrivateKey -> {
                Signer.getInstance(key, App.evmBlockchainManager.getChain(BlockchainType.Ethereum))
            }
            else -> null
        } ?: return null

        return if (isLegacy) {
            signer.signByteArrayLegacy(message)
        } else {
            signer.signByteArray(message)
        }
    }
}

val HDWallet.Purpose.derivation: AccountType.Derivation
    get() = when (this) {
        HDWallet.Purpose.BIP44 -> AccountType.Derivation.bip44
        HDWallet.Purpose.BIP49 -> AccountType.Derivation.bip49
        HDWallet.Purpose.BIP84 -> AccountType.Derivation.bip84
        HDWallet.Purpose.BIP86 -> AccountType.Derivation.bip86
    }

val HDWallet.Purpose.tokenTypeDerivation: TokenType.Derivation
    get() = when (this) {
        HDWallet.Purpose.BIP44 -> TokenType.Derivation.Bip44
        HDWallet.Purpose.BIP49 -> TokenType.Derivation.Bip49
        HDWallet.Purpose.BIP84 -> TokenType.Derivation.Bip84
        HDWallet.Purpose.BIP86 -> TokenType.Derivation.Bip86
    }

@Parcelize
enum class AccountOrigin(val value: String) : Parcelable {
    Created("Created"),
    Restored("Restored");
}

fun String.normalizeNFKD(): String = Normalizer.normalize(this, Normalizer.Form.NFKD)
