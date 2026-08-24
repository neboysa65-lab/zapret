package com.witvpn.ikev2.presentation.ui.splash

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.witvpn.ikev2.data.AppSettings
import com.witvpn.ikev2.domain.model.Resource
import com.witvpn.ikev2.domain.model.User
import com.witvpn.ikev2.domain.repository.UserRepository
import com.witvpn.ikev2.presentation.base.BaseViewModel
import com.witvpn.ikev2.features.entropy.EntropyUseCase
import com.witvpn.ikev2.presentation.utils.SharePrefs.KEY_USER_ID
import com.witvpn.ikev2.presentation.utils.getSessionUserId
import com.witvpn.ikev2.presentation.utils.putStringPref
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SplashViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val userRepository: UserRepository,
    private val entropyUseCase: EntropyUseCase,
    private val appSettings: AppSettings
) : BaseViewModel() {
    private val _userMutableLiveData = MutableLiveData<Resource<User>>()
    val userLiveData: LiveData<Resource<User>> = _userMutableLiveData
    val user: User? by lazy {
        return@lazy userLiveData.value?.data
    }

    fun execute(splashFragment: SplashFragment, createNew: Boolean) {
        _userMutableLiveData.postValue(Resource.loading(null))

        viewModelScope.launch(exceptionHandler) {
            loadUser(splashFragment, createNew)
        }
    }

    private suspend fun loadUser(splashFragment: SplashFragment, createNew: Boolean) {
        var userId = getSessionUserId()
        if (!createNew && userId == null && entropyUseCase.pubKey == "") {
            splashFragment.showNewUserDialog()
            return
        }
        val param = mutableMapOf<String, Any>()
        if (userId == null || userId.length < 128) {
            if (entropyUseCase.pubKey == "") {
                entropyUseCase.refresh()
            }
            param["deviceId"] = entropyUseCase.pubKey
            try {
                userId = userRepository.createAnonymousUser(param).id
            } catch (e: Exception) {
                splashFragment.showNewUserDialog()
                throw e
            }
        }
        if (userId != null) {
            putStringPref(KEY_USER_ID, userId)
        }
        param.clear()
        param["userId"] = userId as Any
        val user = userRepository.profile(param)
        appSettings.lastUserIsPremium = user.hasPremiumSubscribe

//        if (!user.hasPremiumSubscribe) {
//            Server.AUTO_CONNECT_STAB.saveDraft()
//        }

        param["os"] = "android"
        val packages = userRepository.packages(param)
        user.packages = packages

        val ads = userRepository.ads(param)
        user.ads = ads

        _userMutableLiveData.postValue(Resource.success(user))
    }


    override fun handleError(msg: String?) {
        _userMutableLiveData.postValue(Resource.error(msg, null))
    }

    fun provide(splashFragment: SplashFragment) {

    }
}