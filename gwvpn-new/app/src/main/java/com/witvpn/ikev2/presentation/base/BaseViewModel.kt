package com.witvpn.ikev2.presentation.base

import androidx.lifecycle.ViewModel
import com.witvpn.ikev2.presentation.utils.ErrorSanitizer
import kotlinx.coroutines.CoroutineExceptionHandler
import timber.log.Timber

open class BaseViewModel : ViewModel() {
    val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        // В лог — как есть (hostname/IP нужны для диагностики), наружу через
        // handleError — зачищенным: сырой текст сетевых исключений светит
        // адрес API-сервера, а UI показывает эти сообщения юзеру.
        Timber.e(throwable)
        handleError(ErrorSanitizer.stripEndpoints(throwable.localizedMessage))
    }

    open fun handleError(msg: String?) {

    }
}
