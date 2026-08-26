package com.witvpn.ikev2.presentation.utils

import ru.zoommax.Crypto
import java.security.SecureRandom

object EntropyHelper {
    private val securityRandom = SecureRandom()

    fun generateNewEntropy(bitLength: Int) = ByteArray(bitLength / 8).apply {
        securityRandom.nextBytes(this)
    }

    fun decodeMnemonicFromEntropy(entropy: String): String {
        return decodeMnemonicFromEntropy(convertEntropyFrom_ISO_8859_1(entropy))
    }
    fun decodeMnemonicFromEntropy(entropy: ByteArray): String {
        return Crypto.getMnemonic(entropy)
    }
    fun convertEntropyTo_ISO_8859_1(entropy: ByteArray): String {
        return String(entropy, Charsets.ISO_8859_1)
    }
    fun convertEntropyFrom_ISO_8859_1(entropy: String): ByteArray {
        return entropy.toByteArray(Charsets.ISO_8859_1)
    }
    fun getMnemonicSize(mnemonic: String): Int {
        return mnemonic.split(" ")
            .map(String::trim)
            .filter(String::isNotEmpty)
            .size
    }

    /**
     * Каноничная форма публичного ключа GW: x||y, ровно 64 байта (128 hex).
     *
     * Crypto.getPubkeyAccount() возвращает BigInteger.toByteArray(), а у точек
     * со старшим битом координаты x он даёт знак-байт "00" спереди — ключ
     * выходит длиной 130 hex ("00||x||y"). Бэкенд такую точку не парсит:
     * конверты не шифруются ни на одну ноду, и GW-раздел пустеет (~46%
     * сгенерированных пар). Срезаем знак-байт до 128 hex.
     */
    fun canonicalPubKey(pubHex: String): String {
        var pub = pubHex.trim()
        if (pub.startsWith("0x")) pub = pub.substring(2)
        if (pub.startsWith("0X")) pub = pub.substring(2)
        if (pub.length == 130 && pub.startsWith("00")) {
            pub = pub.substring(2)
        }
        return pub
    }
}