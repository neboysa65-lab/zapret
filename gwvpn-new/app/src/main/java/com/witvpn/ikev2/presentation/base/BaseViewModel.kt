package com.witvpn.ikev2.presentation.base

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineExceptionHandler
import timber.log.Timber

open class BaseViewModel : ViewModel() {
    val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Timber.e(throwable.localizedMessage)
        handleError(throwable.localizedMessage)
    }

    open fun handleError(msg: String?) {

    }
}