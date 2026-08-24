package com.witvpn.ikev2.presentation.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.witvpn.ikev2.domain.model.Resource
import com.witvpn.ikev2.domain.model.Server
import com.witvpn.ikev2.domain.model.Server.Companion.isAutoConnect
import com.witvpn.ikev2.domain.model.User
import com.witvpn.ikev2.domain.repository.ServerRepository
import com.witvpn.ikev2.domain.repository.UserRepository
import com.witvpn.ikev2.presentation.base.BaseViewModel
import com.witvpn.ikev2.presentation.ui.servers.SelectDefaultServerCase
import com.witvpn.ikev2.presentation.utils.parseCertificate
import com.witvpn.ikev2.presentation.utils.storeCertificate
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.strongswan.android.data.VpnProfile
import org.strongswan.android.data.VpnProfileDataSource
import org.strongswan.android.data.VpnType
import timber.log.Timber
import java.lang.Exception
import javax.inject.Inject

@HiltViewModel
class ShareViewModel @Inject constructor(
    private val dataSource: VpnProfileDataSource,
    private val userRepos: UserRepository,
    private val servers: ServerRepository,
) : BaseViewModel() {
    private val _userMutableLiveData = MutableLiveData<User>()
    val userLiveData: LiveData<User> = _userMutableLiveData
    val user: User?
        get() = userLiveData.value

    private val _serverMutableLiveData = MutableLiveData<Resource<Server?>>()
    val serverLiveData: LiveData<Resource<Server?>> = _serverMutableLiveData

    init {
        viewModelScope.launch {
            servers.stateFlow
                .filter { it.isNotEmpty() }
                .first()
                .let { SelectDefaultServerCase().execute(it) }
                .also { execute(it) }
        }
    }

    val isPremium: Boolean
        get() = user?.hasPremiumSubscribe == true

    fun execute(server: Server?) {
        if (server == null) return
        viewModelScope.launch(Dispatchers.IO + exceptionHandler) {
            _serverMutableLiveData.postValue(Resource.loading(server))
            if (server.protocol.equals("ikev2", ignoreCase = true) || server.isAutoConnect) {
                refreshAndInsertVPNProfile(server)
            } else {
                server.saveDraft()
            }
            _serverMutableLiveData.postValue(Resource.success(server))
        }
    }

    fun refreshAndInsertVPNProfile(value: Server) {
        dataSource.open()
        val certificate = value.ca_file?.parseCertificate()
        certificate?.storeCertificate()
        val vpnProfile = VpnProfile()
            .apply {
                gateway = value.ipAddress
                vpnType = VpnType.IKEV2_EAP
                name = value.ipAddress
                username = value.u_nsm
                password = value.p_nsm
            }
        value.uuid = vpnProfile.uuid.toString()
        dataSource.insertProfile(vpnProfile)
        dataSource.close()
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
