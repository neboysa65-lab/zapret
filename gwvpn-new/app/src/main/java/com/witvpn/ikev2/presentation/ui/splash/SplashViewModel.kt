package com.witvpn.ikev2.presentation.ui.splash

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.witvpn.ikev2.data.AppSettings
import com.witvpn.ikev2.domain.model.GwIdentity
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
            val pub = if (createNew && entropyUseCase.pubKey == "") {
                // Явное «создать новый аккаунт» из WelcomeDialog: здесь новая
                // пара и должна быть новой, поэтому refresh(), а не
                // восстановление из мнемоники.
                withContext(Dispatchers.Default) { entropyUseCase.refresh() }
                entropyUseCase.pubKey
            } else {
                // Восстановление вместо генерации: если жива мнемоника, аккаунт
                // сохраняется, а не подменяется новым. Раньше здесь стоял
                // refresh(), который сразу стирал всё и делал новую пару.
                ensureGwIdentity().orEmpty()
            }
            // pubKey из prefs — источник истины, но если запись производных полей
            // почему-то не прошла, лучше отправить фактический ключ, чем пустой
            // deviceId (сервер на пустой deviceId создаёт мусорный аккаунт).
            param["deviceId"] = entropyUseCase.pubKey.ifEmpty { pub }
            try {
                userId = userRepository.createAnonymousUser(param).id
            } catch (e: Exception) {
                splashFragment.showNewUserDialog()
                throw e
            }
        } else {
            // userId есть — а GW-ключа может не быть. Раньше этот случай не
            // обрабатывался вообще: создание идентичности жило ТОЛЬКО в ветке
            // выше, под условием «userId отсутствует», поэтому при живом userId
            // ключ уже не появлялся никогда. Именно так GW-раздел становился
            // пустым насовсем (вход по OTP после выхода из аккаунта, смерть
            // процесса между двумя чистками prefs, потеря ключа Keystore) — и
            // лечилось только стиранием данных приложения.
            //
            // Проверка идёт не по `pubKey == ""`: ENTROPY_PUBKEY_STR хранится в
            // открытом виде и переживает потерю ключа Keystore, при которой сама
            // энтропия уже не расшифровывается. То есть «pubkey есть» ещё не
            // значит «ключ рабочий», и условие пропускало ровно тот случай, для
            // которого чинить и нужно. ensure() сам разбирается: рабочую
            // идентичность оставляет как есть (плюс прогревает кэш до первого
            // запроса списка), сломанную восстанавливает.
            //
            // userId при этом НЕ трогаем: аккаунт, премиум и подписка остаются,
            // а бэкенд шифрует GW-конверт на любой присланный pubkey.
            ensureGwIdentity()
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


    /**
     * Keystore-расшифровка плюс вывод secp256k1-ключа — десятки миллисекунд,
     * держать их на Main незачем: splash и без того ждёт сеть.
     */
    private suspend fun ensureGwIdentity(): String? =
        withContext(Dispatchers.Default) { GwIdentity.ensure(appContext) }

    override fun handleError(msg: String?) {
        _userMutableLiveData.postValue(Resource.error(msg, null))
    }

    fun provide(splashFragment: SplashFragment) {

    }
}