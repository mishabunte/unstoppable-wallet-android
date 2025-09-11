package io.horizontalsystems.bankwallet.modules.hardwarewallet.firmwareupgrade

import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Sha256Hash
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.*
import android.util.Base64
import io.horizontalsystems.bankwallet.modules.hardwarewallet.firmwareupgrade.ble.BootloaderVersion
import android.util.Log
import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.bouncycastle.jce.ECNamedCurveTable

data class PackageHeader(
    val magic: String,
    val version: String,
    val chunk: UShort,
    val headerSize: Int,
    val compressedSize: UShort,
    val uncompressedSize: UShort
)

class FirmwarePackageVerifier {

    // Public key constants (replace with your actual keys)
    companion object {
        const val HITO_PUBLIC_KEY_LEGACY = "-----BEGIN PUBLIC KEY-----\n" +
                "MFYwEAYHKoZIzj0CAQYFK4EEAAoDQgAEZiBFDCvNpgXleP5fQE2j7P7JUyck3E/T\n" +
                "WgXa4bCYGe7GNT9dYz8QKAbkLRpCQ8WqOWMAxso230SdlFdi93kYvg==\n" +
                "-----END PUBLIC KEY-----"

        const val HITO_PUBLIC_KEY_BETA = "-----BEGIN PUBLIC KEY-----\n" +
                "MFYwEAYHKoZIzj0CAQYFK4EEAAoDQgAEkTTiu02UbC1oBzRq7Pt54pPHIgZ9kORj\n" +
                "kf6XUa/0WIhzykwbHjz2U+5WVF10MYeEVPQSx1GFGMbCzq/eVPASAQ==\n" +
                "-----END PUBLIC KEY-----"

        const val HITO_PUBLIC_KEY_GENESIS = "-----BEGIN PUBLIC KEY-----\n" +
                "MFYwEAYHKoZIzj0CAQYFK4EEAAoDQgAElVMF1xsRLlyeSkkMwoklku2sShr0ajWP\n" +
                "qDlqpt1+ZasW5elS8FbgcEWWOlIsv60R15bA5NevnwAl4mtOnevzrg==\n" +
                "-----END PUBLIC KEY-----"
    }

    fun checkPackage(buffer: ByteArray, bootVersion: BootloaderVersion): Pair<Boolean, String?> {
        Log.d("hito-ble","Checking package")

        var bufferCopy = buffer.copyOf()
        var version = ""
        var chunkNo = 0

        while (true) {
            val header = checkPackageChunkHeader(bufferCopy, chunkNo) ?: return Pair(false, null)

            if (version.isEmpty()) {
                version = header.version
            } else if (version != header.version) {
                Log.w("hito-ble","Wrong version ${header.version} of chunk #${header.chunk}, expected $version")
                return Pair(false, null)
            }

            val chunkSize = checkPackageChunkSignature(header, bufferCopy, chunkNo, bootVersion)

            if (chunkSize <= 0) {
                break
            }

            if (chunkSize >= bufferCopy.size) {
                break
            }

            bufferCopy = bufferCopy.sliceArray(chunkSize until bufferCopy.size)
            chunkNo++
        }

        val finalVersion = version.replace("+", "'")
        return Pair(true, finalVersion)
    }

    private fun readUInt32BE(buf: ByteArray, off: Int): Long {
        return ((buf[off].toLong() and 0xFF) shl 24) or
                ((buf[off + 1].toLong() and 0xFF) shl 16) or
                ((buf[off + 2].toLong() and 0xFF) shl 8) or
                (buf[off + 3].toLong() and 0xFF)
    }

    private fun readUInt32LE(buf: ByteArray, off: Int): Long {
        return ((buf[off].toLong() and 0xFF)) or
                ((buf[off + 1].toLong() and 0xFF) shl 8) or
                ((buf[off + 2].toLong() and 0xFF) shl 16) or
                ((buf[off + 3].toLong() and 0xFF) shl 24)
    }

    private fun readUInt16LE(buf: ByteArray, off: Int): UShort {
        val v = (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8)
        return v.toUShort()
    }
    
    private fun ByteArray.readUInt16LE(off: Int): Int =
        (this[off].toInt() and 0xFF) or ((this[off + 1].toInt() and 0xFF) shl 8)

    private fun readUInt8(buf: ByteArray, off: Int): Int {
        return buf[off].toInt() and 0xFF
    }

    private fun ByteArray.sliceToHex(start: Int, len: Int): String =
        this.copyOfRange(start, start + len).joinToString("") { "%02x".format(it) }

    private fun parseDerSignatureRS(der: ByteArray): Pair<java.math.BigInteger, java.math.BigInteger> {
        ASN1InputStream(der.inputStream()).use { asn1 ->
            val seq = asn1.readObject() as ASN1Sequence
            val r = (seq.getObjectAt(0) as ASN1Integer).value
            val s = (seq.getObjectAt(1) as ASN1Integer).value
            return r to s
        }
    }

    private fun buildSecp256k1PublicKey(rawUncompressed: ByteArray): ECPublicKeyParameters {
        Log.d("hito-ble","Building secp256k1 public key, raw: ${rawUncompressed}")
        val params = ECNamedCurveTable.getParameterSpec("secp256k1")
        val domain = ECDomainParameters(params.curve, params.g, params.n, params.h)
        val q = params.curve.decodePoint(rawUncompressed)
        return ECPublicKeyParameters(q, domain)
    }

    private fun checkPackageChunkHeader(buffer: ByteArray, chunkNo: Int): PackageHeader? {
        var offset = 0
        if (buffer.size < 4) return null

        // magic: UInt32 big-endian
        val magicBE = readUInt32BE(buffer, offset)
        offset += 4

        if (magicBE != 0x8ECD8E04L) {
            Log.e("hito-ble", "wrong magic: ${magicBE.toString(16)}")
            return null
        }
        val loadAddr = readUInt32LE(buffer, offset)
        val loadAddrHex = loadAddr.toString(16)
        offset += 4

        // compressed_size: UInt16 LE
        val compressedSize = readUInt16LE(buffer, offset)
        offset += 2

        // uncompressed_size: UInt16 LE
        val uncompressedSize = readUInt16LE(buffer, offset)
        offset += 2

        // version_major: UInt8
        val versionMajor = readUInt8(buffer, offset)
        offset += 1

        // version_minor: UInt8
        val versionMinor = readUInt8(buffer, offset)
        offset += 1

        // version_revision: UInt16 LE
        val versionRevision = readUInt16LE(buffer, offset).toInt()
        offset += 2

        // version_build: UInt32 LE
        val versionBuild = readUInt32LE(buffer, offset)
        offset += 4

        // version_chunk: UInt16 LE
        val versionChunk = readUInt16LE(buffer, offset)
        offset += 2

        val version = "$versionMajor.$versionMinor.$versionRevision+${versionBuild}"

        if (chunkNo == 0) {
            Log.d("hito-ble","Package version: $version")
        }

        val magicHex = magicBE.toString(16)

        return PackageHeader(
            magic = magicHex,
            version = version,
            chunk = versionChunk,
            headerSize = offset,
            compressedSize = compressedSize,
            uncompressedSize = uncompressedSize
        )
    }

    private fun String.decodePemToDer(): ByteArray {
        val base64 = this
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\n", "")
            .trim()
        Log.d("hito-ble","Decoding PEM key, base64 len: ${base64}")
        return Base64.decode(base64, Base64.DEFAULT)
    }


    private fun checkPackageChunkSignature(
        header: PackageHeader,
        buffer: ByteArray,
        chunkNo: Int,
        bootVersion: BootloaderVersion
    ): Int {
        val derPublicKey = when (bootVersion) {
            BootloaderVersion.BETA, BootloaderVersion.NONE -> HITO_PUBLIC_KEY_BETA.decodePemToDer()
            BootloaderVersion.GENESIS, BootloaderVersion.GENESIS_HOT -> HITO_PUBLIC_KEY_GENESIS.decodePemToDer()
        }

        if (derPublicKey.size <= 23) {
            Log.e("hito-ble","invalid DER public key size")
            return 0
        }
        val rawPublicKey = derPublicKey.copyOfRange(23, derPublicKey.size)

        val pubKeyParams = try {
            buildSecp256k1PublicKey(rawPublicKey)
        } catch (t: Throwable) {
            Log.e("hito-ble","invalid pub key: ${t.message}")
            return 0
        }

        var i = header.headerSize + header.compressedSize.toInt()

        val tlvMagic = buffer.readUInt16LE(i)
        val tlvMagicHex = "%04x".format(tlvMagic)
        i += 2
        if (tlvMagicHex != "6907") {
            Log.e("hito-ble","wrong tlv_magic: $tlvMagicHex")
        }

        val tlvTotal = buffer.readUInt16LE(i)
        i += 2

        var tlvHashMagic = "%04x".format(buffer.readUInt16LE(i)); i += 2
        var tlvHashSize = buffer.readUInt16LE(i); i += 2

        val keyHashStart = i
        val keyHashLen = tlvHashSize
        val keyHashHex = buffer.sliceToHex(keyHashStart, keyHashLen)
        i += keyHashLen

        val keyHashHitoHex = sha256(derPublicKey).joinToString("") { "%02x".format(it) }
        if (!keyHashHitoHex.equals(keyHashHex, ignoreCase = true)) {
            Log.e("hito-ble","public key hash is invalid: $keyHashHex, expected: $keyHashHitoHex")
            return 0
        } else if (chunkNo == 0) {
            Log.d("hito-ble","public key hash is valid")
        }

        tlvHashMagic = "%04x".format(buffer.readUInt16LE(i)); i += 2
        tlvHashSize = buffer.readUInt16LE(i); i += 2

        val bodyHashHex = buffer.sliceToHex(i, tlvHashSize)
        i += tlvHashSize

        val bodyEnd = header.headerSize + header.compressedSize.toInt()
        val bodyHashCalc = sha256(buffer.copyOfRange(0, bodyEnd))
        val bodyHashCalcHex = bodyHashCalc.joinToString("") { "%02x".format(it) }

        if (!bodyHashHex.equals(bodyHashCalcHex, ignoreCase = true)) {
            Log.e("hito-ble","body hash is invalid: $bodyHashCalcHex, expected: $bodyHashHex")
        } else if (chunkNo == 0) {
            Log.d("hito-ble","body hash is valid")
        }

        val tlvSigMagic = "%04x".format(buffer.readUInt16LE(i)); i += 2
        val tlvSigSize = buffer.readUInt16LE(i); i += 2

        val signature = buffer.copyOfRange(i, i + tlvSigSize)
        i += tlvSigSize

        val (r, s) = try {
            parseDerSignatureRS(signature)
        } catch (t: Throwable) {
            Log.e("hito-ble","couldn't parse signature: ${t.message}")
            return 0
        }

        val signer = ECDSASigner()
        signer.init(false, pubKeyParams)
        val verified = try {
            signer.verifySignature(bodyHashCalc, r, s)
        } catch (t: Throwable) {
            false
        }

        return if (verified) {
            if (chunkNo == 0) Log.d("hito-ble","signature is ok")
            i
        } else {
            Log.e("hito-ble","invalid signature")
            0
        }
    }

    private fun getPemKeyAsBytes(pemKey: String): ByteArray {
        val cleanKey = pemKey
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\n", "")
            .replace("\r", "")

        return Base64.decode(cleanKey, Base64.DEFAULT)
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun verifySignature(ecKey: ECKey, hash: ByteArray, signature: ByteArray): Boolean {
        return try {
            // Use Sha256Hash from bitcoinj
            val hash256 = Sha256Hash.wrap(hash)
            Log.d("hito-ble","Verifying signature... $hash, $hash256")
            ecKey.verify(hash, signature)
        } catch (e: Exception) {
            Log.e("hito-ble","Error verifying signature: ${e.message}")
            false
        }
    }
}