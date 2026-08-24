package com.witvpn.ikev2.presentation.ui.profile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.annotation.StringRes
import com.witvpn.ikev2.R
import com.witvpn.ikev2.presentation.base.BaseViewModel
import com.witvpn.ikev2.features.entropy.EntropyUseCase
import com.witvpn.ikev2.features.entropy.InvalidMnemonic
import com.witvpn.ikev2.presentation.ui.entropy.MnemonicView
import com.witvpn.ikev2.presentation.utils.EntropyHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

interface MnemonicView {
    fun setInput(mnemonic: String)
    fun getInput(): String
    fun setInputError(@StringRes error: Int)
    fun clearError()
    fun setRestoreButton(isEnabled: Boolean)
    fun setInputHint(@StringRes hint: Int)
    fun showInfoAlert(@StringRes alert: Int)
    fun getViewContext(): Context
    fun reloadUser()
}

@HiltViewModel
class ProfileViewModel /*@Inject constructor() : BaseViewModel() {

}*/ @Inject constructor(
    private val entropyUseCase: EntropyUseCase
) : BaseViewModel() {
    private var aView: MnemonicView? = null
    private fun clearInputError() {
        aView?.clearError()
        aView?.setRestoreButton(true)
    }

    private fun setInputError(@StringRes error: Int) {
        aView?.setInputError(error)
        aView?.setRestoreButton(false)
    }

    fun bindView(view: MnemonicView) {
        aView = view
    }

    fun onMnemonicInput(mnemonic: String) {
        when (EntropyHelper.getMnemonicSize(mnemonic)) {
            EntropyUseCase.WORD_COUNT -> {
                clearInputError()
            }

            0 -> {
                setInputError(R.string.error_mnemonic_notbeempty)
            }

            else -> {
                setInputError(R.string.error_mnemonic_words)
            }
        }
    }

    fun onRestoreClick() {
        aView?.let { aView ->
            try {
                entropyUseCase.restore(aView.getInput())
                aView.reloadUser()
            } catch (ex: InvalidMnemonic) {
                aView.setInputError(R.string.error_mnemonic_invalid)
            }
        }
    }

    fun onCopyClick() {
        aView?.let { aView ->
            val clipboard = aView.getViewContext()
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Mnemonic", entropyUseCase.mnemonic))
            aView.showInfoAlert(R.string.alert_mnemonic_copied)
        }
    }

    fun getMnemonic(): String {
        return entropyUseCase.mnemonic
    }
}