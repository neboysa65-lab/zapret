package com.witvpn.ikev2.presentation.ui.qr

import android.graphics.Bitmap
import androidx.annotation.WorkerThread
import androidx.lifecycle.viewModelScope
import com.witvpn.ikev2.presentation.base.BaseViewModel
import com.witvpn.ikev2.features.entropy.EntropyUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.g0dkar.qrcode.QRCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class QRViewModel @Inject constructor(
    private val entropyUseCase: EntropyUseCase
): BaseViewModel() {
    private val state = MutableStateFlow<Bitmap?>(null)
    val flow = state.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.Default) {
            val qrCodeBitmap = getQRBitmap(entropyUseCase.rsa)
            state.update { qrCodeBitmap }
        }
    }

    @WorkerThread
    private fun getQRBitmap(rsaEntropy: String): Bitmap =
        QRCode(rsaEntropy).render().nativeImage() as Bitmap

}