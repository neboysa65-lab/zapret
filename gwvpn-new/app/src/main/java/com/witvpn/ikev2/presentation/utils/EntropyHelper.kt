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
}