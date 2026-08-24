package com.witvpn.ikev2.features.entropy

import com.witvpn.ikev2.data.AppSettings
import com.witvpn.ikev2.presentation.utils.EntropyHelper
import ru.zoommax.Crypto
import javax.inject.Inject

class EntropyUseCase @Inject constructor(
    private val appSettings: AppSettings
) {
    companion object {
        const val WORD_COUNT = 12
        private const val BIT_LENGTH = WORD_COUNT / 3 * 32
    }

    val rsa: String
        get() = appSettings.getEntropyRSA()
    val mnemonic: String
        get() = appSettings.getMnemonic()
    val pubKey: String
        get() = appSettings.getEntropyPubKey()

    fun refresh() {
        clear()
        EntropyHelper.generateNewEntropy(BIT_LENGTH)
            .let(::updateEntropy)
    }

    fun restore(mnemonic: String) {
        if(isMnemonicValid(mnemonic)) {
            updateEntropy(Crypto.getEntropy(mnemonic))
        } else {
            throw InvalidMnemonic()
        }
    }

    fun clear() {
        appSettings.clearEntropy()
        appSettings.clearMnemonic()
    }

    private fun updateEntropy(entropy: ByteArray) {
        val entropyStr = EntropyHelper.convertEntropyTo_ISO_8859_1(entropy)
        store(
            entropy = entropyStr,
            entropyMnemonic = EntropyHelper.decodeMnemonicFromEntropy(entropy),
            entropyRSA = Crypto.encrypt(entropy),
            pubKey = Crypto.getPubkeyAccount(entropy)
        )
    }

    private fun store(
        entropy: String,
        entropyMnemonic: String,
        entropyRSA: String,
        pubKey: String
    ) {
        appSettings.updateEntropy(
            entropy = entropy,
            entropyMnemonic = entropyMnemonic,
            entropyRSA = entropyRSA,
            pubKey = pubKey,
        )
    }

    private fun isMnemonicValid(mnemonic: String): Boolean =
        EntropyHelper.getMnemonicSize(mnemonic) == WORD_COUNT
}