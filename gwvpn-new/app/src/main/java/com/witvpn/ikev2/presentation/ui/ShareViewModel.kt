package com.witvpn.ikev2.presentation.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.witvpn.ikev2.domain.model.Resource
import com.witvpn.ikev2.domain.model.Server
import com.witvpn.ikev2.domain.model.User
import com.witvpn.ikev2.domain.repository.UserRepository
import com.witvpn.ikev2.presentation.base.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import java.lang.Exception
import javax.inject.Inject

@HiltViewModel
class ShareViewModel @Inject constructor(
    private val userRepos: UserRepository,
) : BaseViewModel() {
    private val _userMutableLiveData = MutableLiveData<User>()
    val userLiveData: LiveData<User> = _userMutableLiveData
    val user: User?
        get() = userLiveData.value

    private val _serverMutableLiveData = MutableLiveData<Resource<Server?>>()
    val serverLiveData: LiveData<Resource<Server?>> = _serverMutableLiveData

    // Ключ последнего события выбора сервера, УЖЕ обработанного получателем
    // (ConnectFragment). LiveData «липкая»: при пересоздании view (открыл
    // список серверов — вернулся назад) обсервер получает старое событие
    // заново; без этой отметки он перекрашивал протокол в протокол старого
    // черновика (например GW), затирая выбор пользователя.
    var lastProcessedServerEventKey: String? = null

    val isPremium: Boolean
        get() = user?.hasPremiumSubscribe == true

    fun execute(server: Server?) {
        if (server == null) return
        viewModelScope.launch(Dispatchers.IO + exceptionHandler) {
            _serverMutableLiveData.postValue(Resource.loading(server))
            server.saveDraft()
            _serverMutableLiveData.postValue(Resource.success(server))
        }
    }

    fun setUser(data: User?) {
        data?.let { _userMutableLiveData.postValue(it) }
    }

    fun replaceUser(user :User){
        _userMutableLiveData.value = user
    }

    fun updateSubscription(param: MutableMap<String, Any>, callback: () -> Unit) {
        viewModelScope.launch {
            try {
                userRepos.subscription(param = param)
            } catch (ex: Exception) {
                Timber.e(ex)
            } finally {
                callback.invoke()
            }
        }
    }
    //endregion
}
