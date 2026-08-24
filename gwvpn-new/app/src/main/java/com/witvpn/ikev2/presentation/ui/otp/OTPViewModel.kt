package com.witvpn.ikev2.presentation.ui.otp

import com.witvpn.ikev2.data.AppSettings
import com.witvpn.ikev2.presentation.base.BaseViewModel
import com.witvpn.ikev2.features.entropy.EntropyUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class OTPViewModel @Inject constructor(
    private val entropyUseCase: EntropyUseCase,
    val appSettings: AppSettings
) : BaseViewModel() {
    fun clearEntropyData() {
        entropyUseCase.clear()
    }

}