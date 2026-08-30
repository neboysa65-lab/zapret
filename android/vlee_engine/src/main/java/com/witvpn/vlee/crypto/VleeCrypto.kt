package com.witvpn.vlee.crypto

import org.bouncycastle.crypto.agreement.ECDHBasicAgreement
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPrivateKeyParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object VleeCrypto {

    private val AAD: String get() = VleeObf.AAD
    private val KEY_LABEL: String get() = VleeObf.KEY_LABEL

    private val curve = CustomNamedCurves.getByName("secp256k1")
    private val domain = ECDomainParameters(curve.curve, curve.g, curve.n)

    fun normalizePrivateKey(privHex: String): ByteArray {
        val cleaned = privHex.trim().lowercase().removePrefix("0x")
        val scalar = BigInteger(1, hexToBytes(cleaned))
        return bigIntTo32(scalar)
    }

    fun decryptConfigEnvelope(
        privateKey: ByteArray,
        ephPubHex: String,
        ctBase64: String,
        ivBase64: String,
    ): String {
        val scalar = BigInteger(1, privateKey)
        val ephPoint = curve.curve.decodePoint(hexToBytes(ephPubHex)).normalize()
        val agreement = ECDHBasicAgreement()
        agreement.init(ECPrivateKeyParameters(scalar, domain))
        val shared = agreement.calculateAgreement(ECPublicKeyParameters(ephPoint, domain))

        val aesKey = sha256(bigIntTo32(shared) + KEY_LABEL.toByteArray(Charsets.ISO_8859_1))
        val iv = Base64.getDecoder().decode(ivBase64)
        val ct = Base64.getDecoder().decode(ctBase64)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(AAD.toByteArray(Charsets.ISO_8859_1))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    fun bigIntTo32(value: BigInteger): ByteArray {
        val raw = value.toByteArray()
        val out = ByteArray(32)
        when {
            raw.size == 32 -> System.arraycopy(raw, 0, out, 0, 32)
            raw.size > 32 -> System.arraycopy(raw, raw.size - 32, out, 0, 32)
            else -> System.arraycopy(raw, 0, out, 32 - raw.size, raw.size)
        }
        return out
    }

    fun hexToBytes(hex: String): ByteArray {
        val clean = if (hex.length % 2 != 0) "0$hex" else hex
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(clean[i * 2], 16)
            val lo = Character.digit(clean[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) { "invalid hex" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
