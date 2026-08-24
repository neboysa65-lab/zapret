package com.witvpn.ikev2.presentation.utils.connectivity

abstract class ConnectivityProviderBaseImpl : ConnectivityProvider {

    override fun subscribe() {
        subscribeListener()
        //init status
        dispatchChange(getNetworkState())
    }

    protected fun dispatchChange(state: ConnectivityProvider.NetworkState) {
        val networkState = if (state.hasInternet()) CONNECTED else DISCONNECTED
        if (networkState != NetWorkManger.networkStatus.value) {
            NetWorkManger.networkStatus.postValue(networkState)
        }
    }

    private fun ConnectivityProvider.NetworkState.hasInternet(): Boolean {
        return (this as? ConnectivityProvider.NetworkState.ConnectedState)?.hasInternet == true
    }

    protected abstract fun subscribeListener()
}