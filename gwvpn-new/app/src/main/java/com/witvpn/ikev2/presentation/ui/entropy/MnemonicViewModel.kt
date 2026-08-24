package com.witvpn.ikev2.presentation.ui.entropy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.annotation.StringRes
import androidx.lifecycle.viewModelScope
import com.witvpn.ikev2.R
import com.witvpn.ikev2.domain.model.User
import com.witvpn.ikev2.domain.repository.UserRepository
import com.witvpn.ikev2.features.entropy.EntropyUseCase
import com.witvpn.ikev2.features.entropy.InvalidMnemonic
import com.witvpn.ikev2.presentation.base.BaseViewModel
import com.witvpn.ikev2.presentation.utils.EntropyHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

interface MnemonicView {
    fun setInput(mnemonic: String)
    fun getInput(): String
    fun setInputError(@StringRes error: Int)
    fun setInputError(error: String)
    fun clearError()
    fun setRestoreButton(isEnabled: Boolean)
    fun setInputHint(@StringRes hint: Int)
    fun showInfoAlert(@StringRes alert: Int)
    fun getViewContext(): Context
    fun reloadUser()
}

@HiltViewModel
class MnemonicViewModel @Inject constructor(
    private val entropyUseCase: EntropyUseCase,
    private val userRepository: UserRepository,
) : BaseViewModel() {
    var user: User? = null
        private set
    lateinit var userConsumer: (User?) -> Unit//FIXME: crunch
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
            val mnemonic = aView.getInput()
            try {
                entropyUseCase.restore(mnemonic)
                viewModelScope.launch {
                    val param = mutableMapOf<String, Any>()
                    param["deviceId"] = entropyUseCase.pubKey
                    val userId = userRepository.createAnonymousUser(param).id
                    val userParams = mutableMapOf<String, Any>()
                    userParams["userId"] = userId as Any
                    user = userRepository.profile(userParams)
                    aView.reloadUser()
                }
            } catch (ex: InvalidMnemonic) {
                aView.setInputError(R.string.error_mnemonic_invalid)
            } catch (e: Exception) {
                aView.setInputError(e.message ?: "Incorrect")
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

