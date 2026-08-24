package com.witvpn.ikev2.presentation.ui.splittunnel

import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.witvpn.ikev2.awg.AmneziaWGManager
import com.witvpn.ikev2.features.splittunnel.SplitTunnelMode
import com.witvpn.ikev2.features.splittunnel.SplitTunnelStore
import com.witvpn.ikev2.presentation.base.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.vyomtunnel.sdk.VyomState
import io.github.vyomtunnel.sdk.VyomVpnManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.strongswan.android.logic.VpnStateService
import java.text.Collator
import javax.inject.Inject

/**
 * A single row of the Split Tunneling screen.
 */
data class SplitTunnelApp(
    val packageName: String,
    val label: String,
    /** true — the app is ticked in the current mode's selection list. */
    val selected: Boolean
)

@HiltViewModel
class SplitTunnelViewModel @Inject constructor(
    @ApplicationContext private val contextApp: Context
) : BaseViewModel(), VpnStateService.VpnStateListener {

    private val allApps = MutableLiveData<List<SplitTunnelApp>?>(null)
    private val query = MutableLiveData("")

    /** The Split Tunneling mode currently persisted in [SplitTunnelStore]. */
    private val _mode = MutableLiveData(SplitTunnelStore.getMode(contextApp))
    val mode: LiveData<SplitTunnelMode> = _mode

    private val _isLoading = MutableLiveData(true)
    val isLoading: LiveData<Boolean> = _isLoading

    /** Filtered list shown by the adapter; null until the first load finishes. */
    val apps = MediatorLiveData<List<SplitTunnelApp>?>().apply {
        addSource(allApps) { value = filter(it, query.value) }
        addSource(query) { value = filter(allApps.value, it) }
    }

    /**
     * True when the user picked "Only selected" but has not ticked a single
     * app yet — in that degenerate state the tunnel still carries all traffic
     * (see [SplitTunnelStore.getEffectiveMode]) and the UI shows a hint.
     */
    val onlySelectionEmpty = MediatorLiveData<Boolean>().apply {
        val update = {
            val list = allApps.value
            value = _mode.value == SplitTunnelMode.ONLY_SELECTED &&
                list != null && list.none { it.selected }
        }
        addSource(allApps) { update() }
        addSource(_mode) { update() }
    }

    /** True while any of the VPN backends is connected or connecting. */
    val vpnActive = MutableLiveData(false)

    private var ikev2State: VpnStateService.State? = null
    private var stateService: VpnStateService? = null
    private var stateServiceBound = false

    private val stateServiceConnection = object : ServiceConnection {
        override fun onServiceDisconnected(name: ComponentName?) {
            stateService = null
            refreshVpnActive()
        }

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            stateService = (binder as? VpnStateService.LocalBinder)?.service
            stateService?.registerListener(this@SplitTunnelViewModel)
            ikev2State = stateService?.state
            refreshVpnActive()
        }
    }

    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // An app was installed, updated or removed — refresh the list.
            loadApps()
        }
    }

    init {
        try {
            stateServiceBound = contextApp.bindService(
                Intent(contextApp, VpnStateService::class.java),
                stateServiceConnection,
                Service.BIND_AUTO_CREATE
            )
        } catch (e: Exception) {
            stateServiceBound = false
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        // PACKAGE_* are protected system broadcasts — NOT_EXPORTED is correct
        // and required to be explicit when targeting API 34+.
        ContextCompat.registerReceiver(
            contextApp,
            packageChangeReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        loadApps()
    }

    override fun onCleared() {
        super.onCleared()
        try {
            contextApp.unregisterReceiver(packageChangeReceiver)
        } catch (e: Exception) {
            // Already unregistered.
        }
        stateService?.unregisterListener(this)
        if (stateServiceBound) {
            try {
                contextApp.unbindService(stateServiceConnection)
            } catch (e: Exception) {
                // Service already unbound.
            }
        }
    }

    /** VpnStateService.VpnStateListener — IKEv2 state changed. */
    override fun stateChanged() {
        ikev2State = stateService?.state
        refreshVpnActive()
    }

    fun refreshVpnActive() {
        val ikev2Active = ikev2State == VpnStateService.State.CONNECTED ||
            ikev2State == VpnStateService.State.CONNECTING
        val vyomActive = VyomVpnManager.currentState == VyomState.CONNECTED ||
            VyomVpnManager.currentState == VyomState.CONNECTING
        val awgActive = AmneziaWGManager.isConnected()
        vpnActive.postValue(ikev2Active || vyomActive || awgActive)
    }

    fun setQuery(value: String) {
        if (query.value != value) {
            query.value = value
        }
    }

    /** Switches the Split Tunneling mode and re-ticks the rows for its list. */
    fun setMode(newMode: SplitTunnelMode) {
        if (_mode.value == newMode) return
        SplitTunnelStore.setMode(contextApp, newMode)
        _mode.value = newMode

        // Each mode keeps its own selection list — re-map the rows so the
        // switches reflect the list that is now being edited.
        val selection = SplitTunnelStore.getSelectedPackages(contextApp, newMode)
        allApps.value = allApps.value?.map {
            it.copy(selected = selection.contains(it.packageName))
        }
    }

    /** Ticks/unticks one app in the current mode's list and persists it immediately. */
    fun setSelected(packageName: String, selected: Boolean) {
        val currentMode = _mode.value ?: return
        if (currentMode == SplitTunnelMode.OFF) return // the list is hidden in OFF mode

        SplitTunnelStore.setSelected(contextApp, currentMode, packageName, selected)

        val updated = allApps.value?.map {
            if (it.packageName == packageName) it.copy(selected = selected) else it
        }
        if (updated != null) {
            allApps.value = updated
        }
    }

    /**
     * Loads launchable applications in the background. Uses the LAUNCHER intent
     * query (declared in the manifest <queries> block), so no QUERY_ALL_PACKAGES
     * permission is required.
     */
    fun loadApps() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pm = contextApp.packageManager
                val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

                @Suppress("DEPRECATION")
                val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.queryIntentActivities(
                        launcherIntent,
                        PackageManager.ResolveInfoFlags.of(0L)
                    )
                } else {
                    pm.queryIntentActivities(launcherIntent, 0)
                }

                val ownPackage = contextApp.packageName
                val currentMode = SplitTunnelStore.getMode(contextApp)
                val selection = SplitTunnelStore.getSelectedPackages(contextApp, currentMode)

                val collator = Collator.getInstance()
                val entries = resolveInfos
                    .asSequence()
                    .mapNotNull { it.activityInfo }
                    .filter { it.packageName != ownPackage }
                    .distinctBy { it.packageName }
                    .map { activityInfo ->
                        val label = try {
                            activityInfo.applicationInfo.loadLabel(pm).toString()
                        } catch (e: Exception) {
                            activityInfo.packageName
                        }
                        SplitTunnelApp(
                            packageName = activityInfo.packageName,
                            label = label,
                            selected = selection.contains(activityInfo.packageName)
                        )
                    }
                    .sortedWith(
                        compareBy(collator) { it.label.lowercase() }
                    )
                    .toList()

                // Forget uninstalled apps so the stored sets stay clean.
                SplitTunnelStore.prune(contextApp, entries.map { it.packageName })

                allApps.postValue(entries)
            } catch (e: Exception) {
                // Extremely unlikely (PackageManager died) — show an empty list
                // instead of crashing.
                allApps.postValue(emptyList())
            } finally {
                _isLoading.postValue(false)
            }
        }
    }

    private fun filter(list: List<SplitTunnelApp>?, rawQuery: String?): List<SplitTunnelApp>? {
        if (list == null) return null
        val q = rawQuery?.trim().orEmpty()
        if (q.isEmpty()) return list
        return list.filter {
            it.label.contains(q, ignoreCase = true) ||
                it.packageName.contains(q, ignoreCase = true)
        }
    }
}
