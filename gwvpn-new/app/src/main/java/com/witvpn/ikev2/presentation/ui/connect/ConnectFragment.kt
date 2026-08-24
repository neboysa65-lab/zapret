package com.witvpn.ikev2.presentation.ui.connect

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import androidx.navigation.fragment.findNavController
import com.witvpn.ikev2.BuildConfig
import com.witvpn.ikev2.R
import com.witvpn.ikev2.databinding.FragmentConnect2Binding
import com.witvpn.ikev2.domain.model.Server
import com.witvpn.ikev2.domain.model.Server.Companion.isAutoConnect
import com.witvpn.ikev2.domain.model.Status
import com.witvpn.ikev2.features.splittunnel.SplitTunnelMode
import com.witvpn.ikev2.features.splittunnel.SplitTunnelStore
import com.witvpn.ikev2.presentation.CasAds
import com.witvpn.ikev2.presentation.base.BaseFragment
import com.witvpn.ikev2.presentation.ui.MainDelegate
import com.witvpn.ikev2.presentation.ui.MainTabUIDelegate
import com.witvpn.ikev2.presentation.ui.ShareViewModel
import com.witvpn.ikev2.presentation.ui.connectlimit.TimeLimitExpiredDialog
import com.witvpn.ikev2.presentation.ui.connectlimit.VpnServiceLimitListener
import com.witvpn.ikev2.presentation.ui.servers.ServersViewModel
import com.witvpn.ikev2.presentation.utils.FragmentUtils
import com.witvpn.ikev2.presentation.utils.InAppReviewHelper
import com.witvpn.ikev2.presentation.utils.Util
import com.witvpn.ikev2.presentation.utils.ConnectionTracker
import com.witvpn.ikev2.presentation.utils.SharePrefs
import com.witvpn.ikev2.presentation.utils.parseApiDate
import com.witvpn.ikev2.presentation.utils.getStringPref
import com.witvpn.ikev2.presentation.utils.putStringPref
import com.witvpn.ikev2.presentation.widget.bottomnav.BottomNavBar
import dagger.hilt.android.AndroidEntryPoint
import org.strongswan.android.data.VpnProfile
import org.strongswan.android.logic.VpnStateService
import org.strongswan.android.ui.VpnProfileControlActivity
import org.strongswan.android.utils.Utils
import timber.log.Timber
import java.util.concurrent.TimeUnit

import android.content.res.ColorStateList
import com.witvpn.ikev2.vless.VlessManager
import android.net.VpnService
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import android.content.Context

import android.os.PowerManager
import android.provider.Settings
import android.net.Uri
import android.app.Dialog
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.preference.PreferenceManager
import org.strongswan.android.utils.Constants
import android.os.Bundle
import android.os.Build
import io.github.vyomtunnel.sdk.VyomVpnManager
import io.github.vyomtunnel.sdk.VyomState

import com.witvpn.ikev2.awg.AmneziaWGManager
import com.witvpn.gw.GwManager
import org.amnezia.awg.backend.Tunnel

@AndroidEntryPoint
class ConnectFragment: BaseFragment<FragmentConnect2Binding>(R.layout.fragment_connect2)
{
    private var isVlessMode = false
    private var isAwgMode = false
    private var isGwMode = false
    private var isVlessConnected = false
    private var isDisconnectingVless = false
    private var pendingAwgServer: com.witvpn.ikev2.domain.model.ServerAwg? = null
    private var pendingGwServer: com.witvpn.ikev2.domain.model.ServerGw? = null

    private companion object {
        const val GW_CONNECT_TIMEOUT_MS = 120_000L
    }

    class PowerWhitelistRequired : DialogFragment() {
        override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
            // Explicitly use a dark theme for the dialog builder
            val builder = AlertDialog.Builder(requireActivity(), androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            return builder
                .setTitle(R.string.power_whitelist_title)
                .setMessage(R.string.power_whitelist_text)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    try {
                        val intent = Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + requireContext().packageName)
                        )
                        startActivity(intent)
                    } catch (_: Exception) {
                        // Device may not support this intent
                    }
                }
                .create()
        }
    }

    private fun checkPowerWhitelist(): Boolean {
        if (!isAdded) return true
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val ctx = context ?: return true
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                try {
                    PowerWhitelistRequired().show(childFragmentManager, "PowerWhitelistRequired")
                } catch (e: Exception) {
                    // Fragment state may be invalid
                }
                return false
            }
        }
        return true
    }


    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val connectionTimeoutRunnable = Runnable {
        if (!isAdded) return@Runnable
        if (VyomVpnManager.currentState == VyomState.CONNECTING) {
            context?.let { VlessManager.stopVpn(it) }
            updateVlessState(false)
            showProgress(false)
            Toast.makeText(context, "Connection timed out", Toast.LENGTH_SHORT).show()
        }
        if (viewModel.stateLiveData.value == VpnStateService.State.CONNECTING) {
            viewModel.getCurrentVPNProfile()?.also { vpnProfile: VpnProfile ->
                disconnectTo(vpnProfile)
            } ?: run {
                 // Fallback if no profile is available but state is CONNECTING
                 // We can't easily disconnect without profile ID, but we can try to force UI reset
                 binding.tvState.text = getString(R.string.connect)
                 changeButtonColor(false)
                 showProgress(false)
            }
            Toast.makeText(context, "Connection timed out", Toast.LENGTH_SHORT).show()
        }
    }

    private val vpnPrepareLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVlessVpnInternal()
        } else {
            context?.let { Toast.makeText(it, "VPN permission denied", Toast.LENGTH_SHORT).show() }
            updateVlessState(false)
        }
    }

    private val awgVpnPrepareLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val server = pendingAwgServer ?: AmneziaWGManager.selectedServer
            if (server != null) {
                startAwgVpnInternal(server)
            } else {
                pendingAwgServer = null
                context?.let { Toast.makeText(it, "No AWG server selected", Toast.LENGTH_SHORT).show() }
                updateVlessState(false)
                showProgress(false)
            }
        } else {
            pendingAwgServer = null
            context?.let { Toast.makeText(it, "VPN permission denied", Toast.LENGTH_SHORT).show() }
            updateVlessState(false)
            showProgress(false)
        }
    }

    private val gwVpnPrepareLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val server = pendingGwServer
            val config = server?.config ?: GwManager.selectedServer
            if (config != null) {
                startGwConfigInternal(config)
            } else {
                pendingGwServer = null
                context?.let { Toast.makeText(it, "No GW server selected", Toast.LENGTH_SHORT).show() }
                updateVlessState(false)
                showProgress(false)
            }
        } else {
            pendingGwServer = null
            context?.let { Toast.makeText(it, "VPN permission denied", Toast.LENGTH_SHORT).show() }
            updateVlessState(false)
            showProgress(false)
        }
    }

    private val awgListener = object : AmneziaWGManager.StateListener {
        override fun onStateChange(state: Tunnel.State) {
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                when (state) {
                    Tunnel.State.UP -> {
                        showProgress(false)
                        updateVlessState(true) // Re-using state UI for simplicity
                    }
                    Tunnel.State.DOWN -> {
                        showProgress(false)
                        updateVlessState(false)
                    }
                    else -> {}
                }
            }
        }

        override fun onTrafficUpdate(rx: Long, tx: Long) {
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                binding.tvUpload.text = formatTraffic(tx)
                binding.tvDownload.text = formatTraffic(rx)
            }
        }
    }

    private val gwListener = object : GwManager.StateListener {
        override fun onStateChange(state: GwManager.State) {
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                when (state) {
                    GwManager.State.CONNECTING -> {
                        showProgress(true)
                        binding.tvState.text = getString(R.string.connecting, "...")
                        changeButtonColor(false)
                    }
                    GwManager.State.CONNECTED -> {
                        handler.removeCallbacks(gwConnectionTimeoutRunnable)
                        showProgress(false)
                        updateVlessState(true)
                    }
                    GwManager.State.DISCONNECTED -> {
                        handler.removeCallbacks(gwConnectionTimeoutRunnable)
                        showProgress(false)
                        updateVlessState(false)
                        // Сообщаем бэкенду об отключении и при штатном стопе, и при
                        // обрыве туннеля (ошибка/сбой) — иначе онлайн-счётчик «зависает».
                        val ctx = context
                        val userId = shareViewModel.userLiveData.value?.id ?: ""
                        if (ctx != null && userId.isNotEmpty()) {
                            ConnectionTracker.reportDisconnect(ctx, userId)
                        }
                        if (isGwMode && !GwManager.lastError.isNullOrBlank()) {
                            Toast.makeText(context, GwManager.lastError, Toast.LENGTH_LONG).show()
                        }
                    }
                    else -> {}
                }
            }
        }

        override fun onTrafficUpdate(rx: Long, tx: Long) {
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                binding.tvUpload.text = formatTraffic(tx)
                binding.tvDownload.text = formatTraffic(rx)
            }
        }
    }

    private val gwConnectionTimeoutRunnable = Runnable {
        if (!isAdded) return@Runnable
        if (GwManager.state == GwManager.State.CONNECTING) {
            context?.let { GwManager.stopVpn(it) }
            showProgress(false)
            updateVlessState(false)
            Toast.makeText(context, "Connection timed out", Toast.LENGTH_SHORT).show()
        }
    }

    private val vyomListener = object : VyomVpnManager.VyomListener {
        override fun onStateChanged(state: VyomState) {
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                Log.d("ConnectFragment", "Vyom state changed: $state")
                if (state != VyomState.CONNECTING) {
                    handler.removeCallbacks(connectionTimeoutRunnable)
                }
                when (state) {
                    VyomState.CONNECTED -> {
                        isVlessConnected = true
                        isDisconnectingVless = false
                        showProgress(false)
                        updateVlessState(true)
                    }
                    VyomState.DISCONNECTED, VyomState.ERROR -> {
                        isVlessConnected = false
                        isDisconnectingVless = false
                        showProgress(false)
                        updateVlessState(false)
                    }
                    VyomState.CONNECTING -> {
                        showProgress(true)
                    }
                    VyomState.STOPPING -> {
                        // Keep current UI state while stopping
                    }
                    else -> {}
                }
            }
        }

        override fun onTrafficUpdate(up: Long, down: Long) {
            activity?.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                try {
                    binding.tvUpload.text = formatTraffic(up)
                    binding.tvDownload.text = formatTraffic(down)
                } catch (e: Exception) {
                    // Fragment may be detached
                }
            }
        }
    }

    private fun formatTraffic(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    /**
     * Горячая кнопка Split Tunneling на главном экране: обновляет текст
     * статуса (выкл / N приложений) по данным SplitTunnelStore.
     * Вызывается из initView и onResume (после возврата из раздела
     * туннелирования статус сразу актуализируется).
     */
    private fun updateSplitTunnelBadge() {
        if (!isAdded) return
        val ctx = context ?: return
        try {
            val mode = SplitTunnelStore.getEffectiveMode(ctx)
            val count = when (mode) {
                SplitTunnelMode.ONLY_SELECTED -> SplitTunnelStore.getAllowedPackagesSorted(ctx).size
                SplitTunnelMode.EXCEPT_SELECTED -> SplitTunnelStore.getDisallowedPackagesSorted(ctx).size
                SplitTunnelMode.OFF -> 0
            }
            val active = mode != SplitTunnelMode.OFF && count > 0
            if (active) {
                binding.tvSplitTunnelStatus.text = getString(R.string.split_tunnel_quick_on, count)
                binding.tvSplitTunnelStatus.setTextColor(Color.WHITE)
                binding.splitTunnelIcon.imageTintList = ColorStateList.valueOf(Color.WHITE)
            } else {
                binding.tvSplitTunnelStatus.text = getString(R.string.split_tunnel_quick_off)
                binding.tvSplitTunnelStatus.setTextColor(0xFF8C9197.toInt())
                binding.splitTunnelIcon.imageTintList = ColorStateList.valueOf(0xFF8C9197.toInt())
            }
        } catch (e: Exception) {
            Timber.e(e, "updateSplitTunnelBadge failed")
        }
    }

    override fun onResume() {
        super.onResume()
        updateSplitTunnelBadge()
        val ctx = context ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            VyomVpnManager.registerListener(ctx, vyomListener)
            AmneziaWGManager.registerListener(awgListener)
            GwManager.registerListener(gwListener)
        }

        // Fix for state loss: Check Vyom state and update UI
        val vyomState = VyomVpnManager.currentState
        if (vyomState == VyomState.CONNECTED && !isDisconnectingVless) {
            if (!isVlessMode && !isAwgMode && !isGwMode) {
                setProtocol("VLESS")
            }
            isVlessConnected = true
            updateVlessState(true)
            showProgress(false)
        } else if (vyomState == VyomState.CONNECTING && !isDisconnectingVless) {
            if (!isVlessMode && !isAwgMode && !isGwMode) {
                setProtocol("VLESS")
            }
            showProgress(true)
        } else if (AmneziaWGManager.isConnected()) {
            if (!isAwgMode) {
                setProtocol("AWG")
            }
            updateVlessState(true)
            showProgress(false)
        } else if (GwManager.isConnected()) {
            if (!isGwMode) {
                setProtocol("GW")
            }
            updateVlessState(true)
            showProgress(false)
        } else if (GwManager.state == GwManager.State.CONNECTING) {
            if (!isGwMode) {
                setProtocol("GW")
            }
            showProgress(true)
        } else {
            // Ничего не подключено. Если мы на вкладке не-IKEv2 (GW/AWG/VLESS),
            // приводим кнопку в состояние «Connect» и убираем прогресс — иначе
            // после обрыва туннеля в фоне на экране «залипает» Connecting.../Disconnect.
            // IKEv2 сверяется отдельно через stateLiveData observer.
            if (isGwMode || isAwgMode || isVlessMode) {
                isVlessConnected = false
                showProgress(false)
                updateVlessState(false)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            context?.let { VyomVpnManager.unregisterListener(it) }
            AmneziaWGManager.unregisterListener(awgListener)
            GwManager.unregisterListener(gwListener)
        } catch (e: Exception) {
            // Listener not registered
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacksAndMessages(null)
    }

    private val shareViewModel: ShareViewModel by activityViewModels()

    private val viewModel: ConnectViewModel by viewModels()

    private var animator: ValueAnimator? = null

    private val delegate: MainDelegate? by lazy {
        return@lazy FragmentUtils.getParent(this, MainDelegate::class.java)
    }

    override fun initBinding(view: View): FragmentConnect2Binding {
        return FragmentConnect2Binding.bind(view)
    }

    @SuppressLint("SetTextI18n")
    override fun initView() {
        updateServerButton(getIkev2Draft())
        setProtocol(resolveInitialProtocol())
        
        binding.btnIpv4.setOnClickListener {
            if (isVlessConnected || VyomVpnManager.currentState == VyomState.CONNECTING || AmneziaWGManager.isConnected() || GwManager.isConnected() || GwManager.state == GwManager.State.CONNECTING) return@setOnClickListener
            val state = viewModel.stateLiveData.value
            if (state == VpnStateService.State.CONNECTED || state == VpnStateService.State.CONNECTING) return@setOnClickListener
            setProtocol("IKEv2")
        }
        binding.btnVless.setOnClickListener {
            val state = viewModel.stateLiveData.value
            if (state == VpnStateService.State.CONNECTED || state == VpnStateService.State.CONNECTING || AmneziaWGManager.isConnected()) return@setOnClickListener
            if (isVlessConnected || VyomVpnManager.currentState == VyomState.CONNECTING || GwManager.isConnected() || GwManager.state == GwManager.State.CONNECTING) return@setOnClickListener
            setProtocol("VLESS")
        }
        binding.btnAwg.setOnClickListener {
            val state = viewModel.stateLiveData.value
            if (state == VpnStateService.State.CONNECTED || state == VpnStateService.State.CONNECTING) return@setOnClickListener
            if (isVlessConnected || VyomVpnManager.currentState == VyomState.CONNECTING || AmneziaWGManager.isConnected()) return@setOnClickListener
            if (GwManager.isConnected() || GwManager.state == GwManager.State.CONNECTING) return@setOnClickListener
            setProtocol("AWG")
        }
        binding.btnGw.setOnClickListener {
            val state = viewModel.stateLiveData.value
            if (state == VpnStateService.State.CONNECTED || state == VpnStateService.State.CONNECTING) return@setOnClickListener
            if (isVlessConnected || VyomVpnManager.currentState == VyomState.CONNECTING || AmneziaWGManager.isConnected()) return@setOnClickListener
            if (GwManager.isConnected() || GwManager.state == GwManager.State.CONNECTING) return@setOnClickListener
            setProtocol("GW")
        }

        binding.btnServers.setOnClickListener {
            when {
                isVlessMode -> showVlessServers()
                else -> openServerList()
            }
        }
        // Горячая кнопка туннелирования: с главного экрана сразу в раздел
        // Split Tunneling (тот же экран, что открывается из профиля)
        binding.btnSplitTunnel.setOnClickListener {
            try {
                findNavController().navigate(R.id.splitTunnelFragment)
            } catch (e: Exception) {
                Timber.e(e, "Failed to open split tunnel screen")
            }
        }
        updateSplitTunnelBadge()
        binding.tvProtocolHint.setOnClickListener {
            context?.let { ProtocolInfoDialog(it).show() }
        }
        binding.btnConnect.setOnClickListener {
            if (isVlessMode) {
                // Allow cancel during CONNECTING state for VLESS
                if (VyomVpnManager.currentState == VyomState.CONNECTING) {
                    val ctx = context ?: return@setOnClickListener
                    isDisconnectingVless = true
                    isVlessConnected = false
                    handler.removeCallbacks(connectionTimeoutRunnable)
                    VlessManager.stopVpn(ctx)
                    updateVlessState(false)
                    showProgress(false)
                    return@setOnClickListener
                }
                toggleVlessConnection()
            } else if (isAwgMode) {
                if (AmneziaWGManager.isConnected()) {
                    AmneziaWGManager.stopVpn(requireContext())
                    updateVlessState(false)

                    // Report AWG disconnection to API
                    val userId = shareViewModel.userLiveData.value?.id ?: ""
                    if (userId.isNotEmpty()) {
                        val ctx = context ?: return@setOnClickListener
                        ConnectionTracker.reportDisconnect(ctx, userId)
                    }
                } else {
                    val awgServer = resolveAwgServerForConnection()
                    if (awgServer != null) {
                        if (shareViewModel.isPremium) {
                            startAwgVpnWithPermissionCheck(awgServer)
                        } else {
                            CasAds.showConnectAd(requireActivity()) {
                                startAwgVpnWithPermissionCheck(awgServer)
                            }
                        }
                    } else {
                        Toast.makeText(context, "AWG Server not found for this location", Toast.LENGTH_SHORT).show()
                    }
                }
            } else if (isGwMode) {
                handleGwConnectClick()
            } else if (it.isEnabled) {
                // Allow cancel during CONNECTING state for IKEv2
                if (viewModel.stateLiveData.value == VpnStateService.State.CONNECTING) {
                    viewModel.getCurrentVPNProfile()?.also { vpnProfile: VpnProfile ->
                        disconnectTo(vpnProfile)
                    }
                    // Reset UI immediately
                    handler.removeCallbacks(connectionTimeoutRunnable)
                    binding.tvState.text = getString(R.string.connect)
                    changeButtonColor(false)
                    showProgress(false)
                    return@setOnClickListener
                }
                if (BuildConfig.CONNECTION_TIME_LIMIT_FOR_FREE
                    && !shareViewModel.isPremium
                    && viewModel.isLimitElapsed.value == true
                    && viewModel.stateLiveData.value != VpnStateService.State.CONNECTED) {
                    limitElapsedDialog()
                } else {
                    checkAndShowAppUpdate()
                    val serverDraft = getIkev2Draft()
                    if (serverDraft == null) {
                        openServerList()
                    } else if (serverDraft.isAutoConnect) {
                        if (viewModel.stateLiveData.value == VpnStateService.State.CONNECTED) {
                            viewModel.getCurrentVPNProfile()?.also { vpnProfile: VpnProfile ->
                                disconnectTo(vpnProfile)
                            }
                        } else {
                            connectToRandom()
                        }
                    } else {
                        if (viewModel.stateLiveData.value == VpnStateService.State.CONNECTED) {
                            viewModel.getCurrentVPNProfile()?.also { vpnProfile: VpnProfile ->
                                disconnectTo(vpnProfile)
                            }
                        } else {
                            connectTo(serverDraft)
                        }
                    }
                }
            }
        }
//        binding.leftMenuIcon.setOnClickListener {
//            delegate?.openLeftMenu()
//        }

        if (!shareViewModel.isPremium) {
            viewModel.elapsedMillisLiveData.observe(viewLifecycleOwner) {
                val millis = VpnServiceLimitListener.MILLIS_ELAPSED_LIMIT - it
                if (millis < 0) {
                    binding.timeLimit.text = "00:00:00"
                } else {
                    TimeUnit.MILLISECONDS.toHours(millis).also { hours ->
                        val withoutHours = millis - TimeUnit.HOURS.toMillis(hours)
                        TimeUnit.MILLISECONDS.toMinutes(withoutHours).also { minutes ->
                            val withoutMinutes = withoutHours - TimeUnit.MINUTES.toMillis(minutes)
                            TimeUnit.MILLISECONDS.toSeconds(withoutMinutes).also { seconds ->
                                binding.timeLimit.text = "%02d:%02d:%02d".format(hours, minutes, seconds)
                            }
                        }
                    }
                }
                binding.timeLimit.isVisible = true
            }
            viewModel.isLimitElapsed.observe(viewLifecycleOwner) {
                if (it) {
                    limitElapsedDialog()
                    binding.timeLimit.setTextColor(0xFFFF3D41.toInt())
                } else {
                    binding.timeLimit.setTextColor(Color.WHITE)
                }
            }
//            binding.timeLimit.setOnClickListener {
//                AboutLimitDialog(requireContext()).apply {
//                    closeButton.setOnClickListener {
//                        dismiss()
//                    }
//                    actionButton.setOnClickListener {
//                        FragmentUtils.getParent(this@ConnectFragment, MainTabUIDelegate::class.java)?.setCurrentTab(BottomNavBar.TAB_PREMIUM)
//                        dismiss()
//                    }
//                }.show()
//            }
        }
        initChart()
        binding.pro.root.setOnClickListener { navToPaywall() }

        shareViewModel.userLiveData.observe(viewLifecycleOwner) { user ->
            if (user.hasPremiumSubscribe) {
                binding.pro.tvProText.text = getString(R.string.renew)
                val endDate = user.premiumEnd?.parseApiDate()
                if (!endDate.isNullOrEmpty()) {
                    binding.pro.tvProEndDate.text = getString(R.string.pro_active_until, endDate)
                    binding.pro.tvProEndDate.visibility = View.VISIBLE
                } else {
                    binding.pro.tvProEndDate.visibility = View.GONE
                }
            } else {
                binding.pro.tvProText.text = getString(R.string.get_pro)
                binding.pro.tvProEndDate.visibility = View.GONE
            }
        }
    }

    private fun limitElapsedDialog() {
        TimeLimitExpiredDialog(requireContext()).apply {
            closeButton.setOnClickListener {
                dismiss()
            }
            actionButton.setOnClickListener {
                FragmentUtils.getParent(this@ConnectFragment, MainTabUIDelegate::class.java)?.setCurrentTab(BottomNavBar.TAB_PREMIUM)
                dismiss()
            }
        }.show()
    }

    private var randomObserver: Observer<List<Server>>? = null

    private fun connectToRandom() {
        binding.btnConnect.isEnabled = false
        randomObserver?.let { viewModel2.serversList.removeObserver(it) }
        randomObserver = Observer<List<Server>> { servers ->
            if (servers.isNullOrEmpty()) return@Observer
            randomObserver?.let { viewModel2.serversList.removeObserver(it) }
            randomObserver = null
            binding.btnConnect.isEnabled = true
            val filtered = if (shareViewModel.isPremium) servers else servers.filter { it.premium != true }
            val server = filtered.randomOrNull() ?: servers.randomOrNull() ?: return@Observer
            shareViewModel.execute(server)
            connectTo(server)
        }
        viewModel2.serversList.observe(viewLifecycleOwner, randomObserver!!)
    }

    private fun connectTo(server: Server) {
        val activity = requireActivity()
        val intent = Intent(context, VpnProfileControlActivity::class.java).apply {
            action = VpnProfileControlActivity.START_PROFILE
            putExtra(VpnProfileControlActivity.EXTRA_VPN_PROFILE_ID, server.uuid)
        }
        if (shareViewModel.isPremium) {
            activity.startActivity(intent)
        } else {
            CasAds.showConnectAd(activity) {
                activity.startActivity(intent)
            }
        }
        // IKEv2 больше не репортим в API: онлайн IKEv2 сервер считает сам
        // по метрикам серверов (node_exporter, ipsec_clients).
    }

    private fun disconnectTo(profile: VpnProfile) {
        val activity = requireActivity()
        val intent = Intent(activity, VpnProfileControlActivity::class.java).apply {
            action = VpnProfileControlActivity.DISCONNECT
            putExtra(VpnProfileControlActivity.SILENT_OPTION, true)
            putExtra(VpnProfileControlActivity.EXTRA_VPN_PROFILE_ID, profile.uuid)
        }
        if (shareViewModel.isPremium) {
            activity.startActivity(intent)
        } else {
            // Force UI update immediately to reflect user intent
            if (isAdded) {
                binding.tvState.text = getString(R.string.connect)
                changeButtonColor(false)
                showProgress(false)
            }
            activity.startActivity(intent)
        }
        // IKEv2 disconnect больше не репортим в API (см. connectTo).
    }

    private fun checkAndShowAppUpdate() {
        if (viewModel.stateLiveData.value == VpnStateService.State.CONNECTED) {
            if (viewModel.appSettings.needShowInAppReview()) {
                activity?.also {
                    InAppReviewHelper.requestInAppReview(it)
                }
                viewModel.appSettings.resetAppReviewCounter()
            } else {
                viewModel.appSettings.approximateAppReviewCounter()
            }
        }
    }

    private val viewModel2: ServersViewModel by activityViewModels()

    override fun initObserve() {
        shareViewModel.userLiveData.observe(viewLifecycleOwner){
            viewModel2.execute(it)
        }
//        shareViewModel.userLiveData.value?.let { user ->
//            viewModel2.execute(user)
//        }
        viewModel2.serversAwgList.observe(viewLifecycleOwner) { _ ->
            if (isAwgMode) {
                val draft = getAwgDraft()
                if (draft == null && AmneziaWGManager.selectedServer == null) {
                    updateAwgServerUI()
                }
            }
        }
        viewModel2.serversGwList.observe(viewLifecycleOwner) { _ ->
            if (isGwMode) {
                updateGwServerUI()
            }
        }
        shareViewModel.serverLiveData.observe(viewLifecycleOwner) { (status, savedServer, _) ->
            if (status == Status.SUCCESS) {
                val servers = viewModel2.serversList.value
                val awgServers = viewModel2.serversAwgList.value?.map { com.witvpn.ikev2.domain.model.Server.fromAwg(it) }
                val allServers = mutableListOf<com.witvpn.ikev2.domain.model.Server>()
                servers?.let { allServers.addAll(it) }
                awgServers?.let { allServers.addAll(it) }

                Log.d("ConnectFragment", "initObserve 1 ${savedServer?.country} servers ${allServers.map { it.country }}")
                val shouldClearSavedServer = savedServer?.let { server ->
                    when {
                        server.isAutoConnect -> false
                        server.protocol.equals("awg", ignoreCase = true) -> {
                            val awgServerList = viewModel2.serversAwgList.value ?: emptyList()
                            awgServerList.isNotEmpty() && awgServerList.none { it.ipAddress == server.ipAddress }
                        }
                        server.protocol.equals("gw", ignoreCase = true) -> {
                            val gwServerList = viewModel2.serversGwList.value
                            gwServerList != null && gwServerList.isNotEmpty() && gwServerList.none { it.ipAddress == server.ipAddress }
                        }
                        else -> {
                            val ikev2ServerList = viewModel2.serversList.value ?: emptyList()
                            ikev2ServerList.isNotEmpty() && ikev2ServerList.none { it.ipAddress == server.ipAddress }
                        }
                    }
                } ?: false

                if (shouldClearSavedServer) {
                    Log.d(
                        "ConnectFragment",
                        "Saved server is not available. Clear draft. savedServer: ${savedServer?.country} servers ${allServers.map { it.country }}"
                    )
                    val clearedProtocol = savedServer?.protocol ?: "ikev2"
                    Server.clearDraftForProtocol(clearedProtocol)
                } else if (savedServer != null) {
                    if (savedServer.protocol.equals("awg", ignoreCase = true) && !isAwgMode) {
                        setProtocol("AWG")
                    }
                    if (savedServer.protocol.equals("gw", ignoreCase = true) && !isGwMode) {
                        setProtocol("GW")
                    }

                    // Check if we need to auto-reconnect IKEv2 to new server
                    val isSavedServerIkev2 = savedServer.protocol.equals("ikev2", ignoreCase = true) || savedServer.isAutoConnect
                    val isIkev2Connected = viewModel.stateLiveData.value == VpnStateService.State.CONNECTED 
                        || viewModel.stateLiveData.value == VpnStateService.State.CONNECTING
                    val currentProfile = viewModel.getCurrentVPNProfile()
                    val isDifferentServer = currentProfile != null && currentProfile.uuid.toString() != savedServer.uuid
                    
                    if (!isVlessMode && !isAwgMode && isSavedServerIkev2 && isIkev2Connected && isDifferentServer) {
                        // Auto-reconnect to new server without ads
                        updateServerButton(value = savedServer)
                        disconnectTo(currentProfile!!)
                        handler.removeCallbacks(connectionTimeoutRunnable)
                        showProgress(false)
                        handler.postDelayed({
                            if (isAdded) {
                                connectToWithoutAd(savedServer)
                            }
                        }, 800)
                    } else {
                        when {
                            isAwgMode -> updateAwgServerUI()
                            isGwMode -> reconnectGwIfServerChanged(savedServer)
                            !isVlessMode -> updateServerButton(value = savedServer)
                        }
                    }
                }
            }
        }

        viewModel.stateLiveData.observe(viewLifecycleOwner) { state ->
            Timber.d("State " + state?.name)
            // Skip IKEv2 state updates when VLESS is connected to avoid resetting VLESS UI
            if (isVlessConnected ||
                (isVlessMode && VyomVpnManager.currentState == VyomState.CONNECTED) ||
                isAwgMode || isGwMode) {
                return@observe
            }
            when (state) {
                VpnStateService.State.CONNECTED -> {
                    binding.tvState.text = getString(R.string.disconnect)
                    animator?.cancel()
                    showProgress(false)
                    changeButtonColor(true)
                    handler.removeCallbacks(connectionTimeoutRunnable)
                }
                VpnStateService.State.CONNECTING -> {
                    fakeProgress()
                    showProgress(true)
                    binding.tvState.text = getString(R.string.connecting, "...")
                    changeButtonColor(false)
                    handler.removeCallbacks(connectionTimeoutRunnable)
                    handler.postDelayed(connectionTimeoutRunnable, 30000)
                }
                else -> {
                    animator?.cancel()
                    handler.removeCallbacks(connectionTimeoutRunnable)
                    binding.tvState.text = getString(R.string.connect)
                    changeButtonColor(false)
                    showProgress(false)
                    viewModel.syncDataIfNeed(shareViewModel.userLiveData.value)
                }
            }
        }

        viewModel.trafficLiveData.observe(viewLifecycleOwner) { (upStreamSpeed, downStreamSpeed) ->
            binding.tvUpload.text = Utils.parseTotal(upStreamSpeed)
            binding.tvDownload.text = Utils.parseTotal(downStreamSpeed)
        }
    }

    //region #Private method
    private fun initChart() {
//        binding.traffic.chartUpload.init(intArrayOf(5, 30, 100, 65, 80))
//        binding.traffic.chartDownload.init(intArrayOf(5, 30, 65, 50, 100))
    }

    private fun fakeProgress(startDelay: Long = 0L) {
//        binding.viewProgress.visibility = View.VISIBLE
//        val layoutParam: FrameLayout.LayoutParams =
//            binding.viewProgress.layoutParams as FrameLayout.LayoutParams
//        val originWidth = binding.viewProgress.width
//        animator = ValueAnimator.ofFloat(0f, 100f)
//            .apply {
//                duration = 0L
//                interpolator = DecelerateInterpolator()
//                setStartDelay(startDelay)
//                addUpdateListener {
//                    val value = it.animatedValue as Float
//                    val process = originWidth.times(value) / 100
//                    layoutParam.width = process.toInt()
//                    //binding.tvState.text = getString(R.string.connecting, "${value.toInt()}%")
//                    binding.viewProgress.layoutParams = layoutParam
//                    if (binding.viewProgress.visibility == View.INVISIBLE) {
//                        binding.viewProgress.visibility = View.VISIBLE
//                    }
//                }
//
//                addListener(object : Animator.AnimatorListener {
//                    override fun onAnimationRepeat(p0: Animator) {
//                    }
//
//                    override fun onAnimationEnd(p0: Animator) {
//                        if (viewModel.stateLiveData.value != VpnStateService.State.CONNECTED) {
//                            binding.tvState.text = getString(R.string.waiting)
//                        }
//                    }
//
//                    override fun onAnimationCancel(p0: Animator) {
//                    }
//
//                    override fun onAnimationStart(p0: Animator) {
//                    }
//
//                })
//            }
        animator?.start()
    }

    private fun openServerList() {
        try {
            val bundle = android.os.Bundle().apply {
                putString("PROTOCOL", when {
                    isAwgMode -> "AWG"
                    isGwMode -> "GW"
                    else -> "IKEv2"
                })
            }
            findNavController().navigate(R.id.serversFragment, bundle)
        } catch (e: Exception) {
            // Navigation state may be invalid
        }
    }

    private fun updateServerButton(value: Server? = null) {
        binding.btnServers.apply {
//            setFlag(Util.getResId(value?.countryCode) ?: R.drawable.ic_globe)
            binding.connectionLocation.setImageResource(Util.getResId(value?.countryCode) ?: R.drawable.ic_globe)
//            setTitle(value?.country ?: getString(R.string.select_the_fastest_server))
            binding.connectionTitle.text = value?.country ?: getString(R.string.select_the_fastest_server)
            // Сброс лого Hysteria — иначе остаётся после вкладки VLESS
            clearConnectionTitleLeadingIcon()
//            setDescription(value?.state)
            value?.saveDraft()
        }
    }

    /** Убрать compound drawable у названия (лого Hysteria не должно «залипать» на IKEv2/AWG). */
    private fun clearConnectionTitleLeadingIcon() {
        if (!isAdded) return
        try {
            binding.connectionTitle.setCompoundDrawablesRelative(null, null, null, null)
            binding.connectionTitle.compoundDrawablePadding = 0
        } catch (_: Exception) {
        }
    }

    private fun applyHysteriaTitleIcon(show: Boolean) {
        if (!isAdded) return
        if (!show) {
            clearConnectionTitleLeadingIcon()
            return
        }
        val d = androidx.core.content.ContextCompat
            .getDrawable(requireContext(), R.drawable.ic_hysteria_logo)?.mutate()
        if (d != null) {
            val sizePx = (binding.connectionTitle.textSize * 1.12f).toInt().coerceAtLeast(1)
            val w = sizePx
            val h = (sizePx * 16f / 22f).toInt().coerceAtLeast(1)
            d.setBounds(0, 0, w, h)
            binding.connectionTitle.setCompoundDrawablesRelative(d, null, null, null)
            binding.connectionTitle.compoundDrawablePadding =
                (4f * resources.displayMetrics.density).toInt()
        }
    }

    private fun changeButtonColor(isConnect :Boolean){
        if(isConnect){
            binding.btnConnect.setBackgroundResource(R.drawable.bg_button_active)
        } else {
            binding.btnConnect.setBackgroundResource(R.drawable.bg_button_inactive)
        }
    }

    private fun showProgress(show :Boolean){
        if (!isAdded) return
        try {
            if(show){
                binding.connectionProgress.visibility = View.VISIBLE
                binding.connectInfo.visibility = View.GONE
            } else {
                binding.connectionProgress.visibility = View.GONE
                binding.connectInfo.visibility = View.VISIBLE
            }
        } catch (e: Exception) {
            // Fragment may be detached, binding unavailable
        }
    }

    private fun navToPaywall(){
        try {
            findNavController().navigate(R.id.billingFragment)
        } catch (e: Exception) {
            // Navigation state may be invalid
        }
    }

    private fun setProtocol(protocol: String) {
        isVlessMode = protocol == "VLESS"
        isAwgMode = protocol == "AWG"
        isGwMode = protocol == "GW"
        putStringPref(SharePrefs.KEY_SELECTED_PROTOCOL, protocol)

        val unselectedColor = Color.parseColor("#8C9197")
        binding.btnIpv4.background = null
        binding.btnIpv4.setTextColor(unselectedColor)
        binding.btnVless.background = null
        binding.btnVless.setTextColor(unselectedColor)
        binding.btnAwg.background = null
        binding.btnAwg.setTextColor(unselectedColor)
        binding.btnGw.background = null
        binding.btnGw.setTextColor(unselectedColor)

        when (protocol) {
            "VLESS" -> {
                binding.btnVless.setBackgroundResource(R.drawable.shape_stadium)
                binding.btnVless.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#3F4652"))
                binding.btnVless.setTextColor(Color.WHITE)

                updateVlessServerUI()

                if (VlessManager.vlessServers.isEmpty()) {
                    binding.connectionTitle.text = getString(R.string.loading_dots)
                    clearConnectionTitleLeadingIcon()
                    VlessManager.fetchSubscription(requireContext()) { success ->
                        if (!isAdded) return@fetchSubscription
                        if (success) {
                            updateVlessServerUI()
                        } else {
                            binding.connectionTitle.text = getString(R.string.error_loading)
                            clearConnectionTitleLeadingIcon()
                        }
                    }
                }
            }
            "AWG" -> {
                clearConnectionTitleLeadingIcon()
                binding.btnAwg.setBackgroundResource(R.drawable.shape_stadium)
                binding.btnAwg.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#3F4652"))
                binding.btnAwg.setTextColor(Color.WHITE)

                updateAwgServerUI()
            }
            "GW" -> {
                clearConnectionTitleLeadingIcon()
                binding.btnGw.setBackgroundResource(R.drawable.shape_stadium)
                binding.btnGw.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#3F4652"))
                binding.btnGw.setTextColor(Color.WHITE)

                updateGwServerUI()
            }
            else -> {
                // IKEv2 — сброс лого Hysteria с заголовка
                clearConnectionTitleLeadingIcon()
                binding.btnIpv4.setBackgroundResource(R.drawable.shape_stadium)
                binding.btnIpv4.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#3F4652"))
                binding.btnIpv4.setTextColor(Color.WHITE)

                updateServerButton(getIkev2Draft())
            }
        }

        // Only reset state UI if not currently connected/connecting to the selected protocol
        val vlessActive = isVlessMode && (VyomVpnManager.currentState == VyomState.CONNECTED || VyomVpnManager.currentState == VyomState.CONNECTING)
        val awgActive = isAwgMode && AmneziaWGManager.isConnected()
        val gwActive = isGwMode && (GwManager.isConnected() || GwManager.state == GwManager.State.CONNECTING)
        val ikeActive = !isVlessMode && !isAwgMode && !isGwMode &&
                (viewModel.stateLiveData.value == VpnStateService.State.CONNECTED || viewModel.stateLiveData.value == VpnStateService.State.CONNECTING)
        if (!vlessActive && !awgActive && !gwActive && !ikeActive) {
            binding.tvState.text = getString(R.string.connect)
            changeButtonColor(false)
            showProgress(false)
        } else {
            updateVlessState(true)
            showProgress(false)
        }
    }

    private fun resolveInitialProtocol(): String {
        val ikeState = viewModel.stateLiveData.value
        if (ikeState == VpnStateService.State.CONNECTED || ikeState == VpnStateService.State.CONNECTING) {
            return "IKEv2"
        }

        val vyomState = VyomVpnManager.currentState
        if (vyomState == VyomState.CONNECTED || vyomState == VyomState.CONNECTING) {
            return "VLESS"
        }

        if (AmneziaWGManager.isConnected()) {
            return "AWG"
        }

        if (GwManager.isConnected() || GwManager.state == GwManager.State.CONNECTING) {
            return "GW"
        }

        val savedProtocol = getStringPref(SharePrefs.KEY_SELECTED_PROTOCOL, "GW") ?: "GW"
        return when (savedProtocol) {
            "AWG", "VLESS", "IKEv2", "GW" -> savedProtocol
            else -> "GW"
        }
    }

    private fun updateVlessServerUI() {
        val server = VlessManager.selectedServer
        if (server == null) {
            binding.connectionLocation.setImageResource(R.drawable.ic_globe)
            binding.connectionTitle.text = getString(R.string.select_vless_server)
            clearConnectionTitleLeadingIcon()
            return
        }
        // Флаг из emoji в имени подписки, в заголовке — имя без флага
        val parsed = com.witvpn.ikev2.presentation.utils.SubscriptionNameFlags.parse(server.name)
        val flagRes = parsed.countryCode
            ?.let { Util.getResId(it) }
            ?.takeIf { it != 0 && it != -1 }
            ?: R.drawable.ic_globe
        binding.connectionLocation.setImageResource(flagRes)
        binding.connectionTitle.text = parsed.displayName
        // Лого Hysteria только для hy2; для обычного VLESS — сброс
        applyHysteriaTitleIcon(server.isHysteria2())
    }

    private fun updateAwgServerUI() {
        // Всегда сбрасываем лого Hysteria при уходе с VLESS-вкладки
        clearConnectionTitleLeadingIcon()
        val draft = getAwgDraft()
        if (draft != null) {
            updateServerButton(draft)
            return
        }

        val selected = AmneziaWGManager.selectedServer
        if (selected != null) {
            binding.connectionLocation.setImageResource(Util.getResId(selected.countryCode) ?: R.drawable.ic_globe)
            binding.connectionTitle.text = selected.country
            clearConnectionTitleLeadingIcon()
            val server = Server.fromAwg(selected)
            server.saveDraft()
            return
        }

        val awgServers = viewModel2.serversAwgList.value
        if (!awgServers.isNullOrEmpty()) {
            val freeServers = awgServers.filter { !it.premium }
            val randomServer = freeServers.randomOrNull() ?: awgServers.random()
            val server = Server.fromAwg(randomServer)
            updateServerButton(server)
            return
        }

        binding.connectionLocation.setImageResource(R.drawable.ic_globe)
        binding.connectionTitle.text = getString(R.string.select_the_fastest_server)
        clearConnectionTitleLeadingIcon()
    }

    private fun getIkev2Draft(): Server? {
        val draft = Server.getDraftForProtocol("ikev2") ?: return null
        return if (draft.protocol.equals("ikev2", ignoreCase = true) || draft.isAutoConnect) {
            draft
        } else {
            null
        }
    }

    private fun getAwgDraft(): Server? {
        val draft = Server.getDraftForProtocol("awg") ?: return null
        return if (draft.protocol.equals("awg", ignoreCase = true)) {
            draft
        } else {
            null
        }
    }

    private fun resolveAwgServerForConnection(): com.witvpn.ikev2.domain.model.ServerAwg? {
        val awgServers = viewModel2.serversAwgList.value ?: return null
        AmneziaWGManager.selectedServer?.let { selected ->
            awgServers.find { it.ipAddress == selected.ipAddress }?.let { return it }
        }
        val draft = getAwgDraft()
        return awgServers.find { it.ipAddress == draft?.ipAddress }
    }

    private fun getGwDraft(): Server? {
        val draft = Server.getDraftForProtocol("gw") ?: return null
        return if (draft.protocol.equals("gw", ignoreCase = true)) draft else null
    }

    /**
     * Пользователь выбрал GW-сервер из списка. Если туннель уже активен и выбран
     * ДРУГОЙ сервер — переключаемся на него (стоп + переподключение). Иначе просто
     * обновляем подпись. Проверка «сервер изменился» защищает от лишнего
     * переподключения, когда LiveData переотдаёт последнее значение новому
     * подписчику (например, при пересоздании фрагмента).
     */
    private fun reconnectGwIfServerChanged(savedServer: Server) {
        val current = GwManager.selectedServer
        val active = GwManager.isConnected() || GwManager.state == GwManager.State.CONNECTING
        val changed = current == null || current.ipAddress != savedServer.ipAddress
        if (active && changed) {
            onServerSelectedFromList(savedServer)
        } else {
            updateGwServerUI()
        }
    }

    private fun updateGwServerUI() {
        clearConnectionTitleLeadingIcon()
        val gwServers = viewModel2.serversGwList.value

        val draft = getGwDraft()
        if (draft != null && !gwServers.isNullOrEmpty()) {
            // draft.id хранится с префиксом ("gw_<id>"), у ServerGw.id префикса нет —
            // сравниваем с учётом префикса, иначе матч по id всегда ложный.
            val matched = gwServers.find { "gw_${it.id}" == draft.id || it.ipAddress == draft.ipAddress }
            if (matched != null) {
                GwManager.selectedServer = matched.config
                updateServerButton(Server.fromGw(matched))
                return
            }
        }

        if (!gwServers.isNullOrEmpty()) {
            val target = gwServers.find { !it.premium } ?: gwServers.first()
            GwManager.selectedServer = target.config
            updateServerButton(Server.fromGw(target))
            return
        }

        binding.connectionLocation.setImageResource(R.drawable.ic_globe)
        binding.connectionTitle.text = getString(R.string.select_the_fastest_server)
        clearConnectionTitleLeadingIcon()
    }

    private fun resolveGwServerForConnection(): com.witvpn.ikev2.domain.model.ServerGw? {
        val gwServers = viewModel2.serversGwList.value ?: return null
        GwManager.selectedServer?.let { selected ->
            gwServers.find { it.config?.ipAddress == selected.ipAddress }?.let { return it }
        }
        val draft = getGwDraft()
        return gwServers.find { it.ipAddress == draft?.ipAddress }
    }

    private fun handleGwConnectClick() {
        if (GwManager.isConnected()) {
            val ctx = context ?: return
            GwManager.stopVpn(ctx)
            updateVlessState(false)
            // reportDisconnect выполняется в gwListener при переходе в DISCONNECTED,
            // чтобы не дублировать и покрывать также самопроизвольный обрыв туннеля.
            return
        }
        if (GwManager.state == GwManager.State.CONNECTING) {
            val ctx = context ?: return
            GwManager.stopVpn(ctx)
            updateVlessState(false)
            showProgress(false)
            return
        }

        val server = resolveGwServerForConnection()
        if (server == null || server.config == null) {
            Toast.makeText(context, "GW Server not found for this location", Toast.LENGTH_SHORT).show()
            return
        }
        if (shareViewModel.isPremium) {
            startGwVpnWithPermissionCheck(server)
        } else {
            CasAds.showConnectAd(requireActivity()) {
                startGwVpnWithPermissionCheck(server)
            }
        }
    }

    private fun startGwVpnWithPermissionCheck(server: com.witvpn.ikev2.domain.model.ServerGw) {
        val config = server.config ?: return
        pendingGwServer = server
        GwManager.selectedServer = config
        requestGwPermissionThenStart(config)
    }

    private fun startGwConfigInternal(config: com.witvpn.gw.model.GwServerConfig) {
        if (!isAdded) return
        val ctx = context ?: return

        pendingGwServer = null
        GwManager.selectedServer = config
        showProgress(true)
        binding.tvState.text = getString(R.string.connecting, "...")

        val excludedApps: List<String>
        val allowedApps: List<String>
        when (SplitTunnelStore.getEffectiveMode(ctx)) {
            SplitTunnelMode.EXCEPT_SELECTED -> {
                excludedApps = SplitTunnelStore.getInstalledDisallowedPackages(ctx).toList()
                allowedApps = emptyList()
            }
            SplitTunnelMode.ONLY_SELECTED -> {
                excludedApps = emptyList()
                allowedApps = SplitTunnelStore.getInstalledAllowedPackages(ctx).toList()
            }
            else -> {
                excludedApps = emptyList()
                allowedApps = emptyList()
            }
        }
        GwManager.startVpn(ctx, config, excludedApps, allowedApps)

        handler.removeCallbacks(gwConnectionTimeoutRunnable)
        handler.postDelayed(gwConnectionTimeoutRunnable, GW_CONNECT_TIMEOUT_MS)

        val userId = shareViewModel.userLiveData.value?.id ?: ""
        if (userId.isNotEmpty()) {
            ConnectionTracker.reportConnect(ctx, userId, config.ipAddress, "gw")
        }
    }

    private fun requestGwPermissionThenStart(config: com.witvpn.gw.model.GwServerConfig) {
        if (!isAdded) return
        val ctx = context ?: return

        if (!checkPowerWhitelist()) {
            return
        }

        val intent = VpnService.prepare(ctx)
        if (intent != null) {
            try {
                gwVpnPrepareLauncher.launch(intent)
            } catch (e: Exception) {
                Log.e("ConnectFragment", "GW VPN permission request failed", e)
                Toast.makeText(ctx, "Could not request VPN permission", Toast.LENGTH_SHORT).show()
                updateVlessState(false)
                showProgress(false)
            }
            return
        }

        startGwConfigInternal(config)
    }

    private fun startAwgVpnWithPermissionCheck(serverAwg: com.witvpn.ikev2.domain.model.ServerAwg) {
        if (!isAdded) return
        val ctx = context ?: return

        pendingAwgServer = serverAwg
        AmneziaWGManager.selectedServer = serverAwg

        if (!checkPowerWhitelist()) {
            return
        }

        val intent = VpnService.prepare(ctx)
        if (intent != null) {
            try {
                awgVpnPrepareLauncher.launch(intent)
            } catch (e: Exception) {
                Log.e("ConnectFragment", "AWG VPN permission request failed", e)
                Toast.makeText(ctx, "Could not request VPN permission", Toast.LENGTH_SHORT).show()
                updateVlessState(false)
                showProgress(false)
            }
            return
        }

        startAwgVpnInternal(serverAwg)
    }

    private fun startAwgVpnInternal(serverAwg: com.witvpn.ikev2.domain.model.ServerAwg) {
        if (!isAdded) return
        val ctx = context ?: return

        pendingAwgServer = null
        AmneziaWGManager.selectedServer = serverAwg
        showProgress(true)
        binding.tvState.text = getString(R.string.connecting, "...")
        AmneziaWGManager.startVpn(ctx)

        val userId = shareViewModel.userLiveData.value?.id ?: ""
        if (userId.isNotEmpty()) {
            ConnectionTracker.reportConnect(ctx, userId, serverAwg.ipAddress, "awg")
        }
    }

    private fun showVlessServers() {
        try {
            val sheet = com.witvpn.ikev2.presentation.ui.vless.VlessServersBottomSheet()
            sheet.onServerSelected = { newServer ->
                // Check if we need to auto-reconnect (already connected or connecting)
                val wasConnected = isVlessConnected || VyomVpnManager.currentState == VyomState.CONNECTING
                
                if (wasConnected) {
                    val ctx = context
                    if (ctx != null) {
                        // Disconnect first
                        isDisconnectingVless = true
                        isVlessConnected = false
                        handler.removeCallbacks(connectionTimeoutRunnable)
                        VlessManager.stopVpn(ctx)
                        updateVlessState(false)
                        showProgress(false)
                        
                        // Wait a bit then connect to new server without ads
                        handler.postDelayed({
                            if (isAdded) {
                                updateVlessServerUI()
                                startVlessVpnInternal() // No ad check - direct connect
                            }
                        }, 800)
                    }
                } else {
                    // Just update UI
                    updateVlessServerUI()
                }
            }
            sheet.show(childFragmentManager, "VlessServers")
        } catch (e: Exception) {
            // Fragment state may be invalid
        }
    }

    private fun toggleVlessConnection() {
        if (VlessManager.selectedServer == null) {
            context?.let { Toast.makeText(it, "No server selected", Toast.LENGTH_SHORT).show() }
            return
        }

        if (isVlessConnected) {
            val ctx = context ?: return
            isDisconnectingVless = true
            isVlessConnected = false
            updateVlessState(false)
            VlessManager.stopVpn(ctx)
            // Report VLESS disconnection to API
            val userId = shareViewModel.userLiveData.value?.id ?: ""
            if (userId.isNotEmpty()) {
                ConnectionTracker.reportDisconnect(ctx, userId)
            }
            return
        }

        // Check if IKEv2 is connected and stop it
        if (viewModel.stateLiveData.value == VpnStateService.State.CONNECTED) {
            viewModel.getCurrentVPNProfile()?.let { disconnectTo(it) }
            showProgress(true)
            binding.tvState.text = getString(R.string.connecting, "...")
            // Wait for IKEv2 to disconnect
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (isAdded) {
                    startVlessVpnWithAdCheck()
                }
            }, 1500)
            return
        }

        startVlessVpnWithAdCheck()
    }

    private fun startVlessVpnWithAdCheck() {
        if (!isAdded) return
        val activity = requireActivity()
        if (shareViewModel.isPremium) {
            startVlessVpnInternal()
        } else {
            CasAds.showConnectAd(activity) {
                if (isAdded) {
                    startVlessVpnInternal()
                }
            }
        }
    }

    private fun startVlessVpnInternal() {
        if (!isAdded) return
        val ctx = context ?: return

        if (!checkPowerWhitelist()) {
            return
        }

        val intent = VpnService.prepare(ctx)
        if (intent != null) {
            try {
                vpnPrepareLauncher.launch(intent)
            } catch (e: Exception) {
                Log.e("ConnectFragment", "VPN permission request failed", e)
                Toast.makeText(ctx, "Could not request VPN permission", Toast.LENGTH_SHORT).show()
                updateVlessState(false)
            }
            return
        }

        showProgress(true)
        if (isAdded) {
            binding.tvState.text = getString(R.string.connecting, "...")
        }
        handler.removeCallbacks(connectionTimeoutRunnable)
        handler.postDelayed(connectionTimeoutRunnable, 30000)
        VlessManager.startVpn(ctx)

        // Репорт подключения: vless или hysteria2
        val userId = shareViewModel.userLiveData.value?.id ?: ""
        val selected = VlessManager.selectedServer
        val serverIp = selected?.address ?: ""
        val protocolTag = when {
            selected == null -> "vless"
            selected.isHysteria2() -> "hysteria2"
            else -> "vless"
        }
        if (userId.isNotEmpty() && serverIp.isNotEmpty()) {
            ConnectionTracker.reportConnect(ctx, userId, serverIp, protocolTag)
        }
    }

    private fun updateVlessState(connected: Boolean) {
        if (!isAdded) return
        try {
            if (connected) {
                binding.tvState.text = getString(R.string.disconnect)
                changeButtonColor(true)
            } else {
                binding.tvState.text = getString(R.string.connect)
                changeButtonColor(false)
            }
        } catch (e: Exception) {
            // Fragment may be detached
        }
    }

    /**
     * Called from MainTabFragment when user selects a server from the list.
     * Auto-reconnects without showing ads if already connected.
     */
    fun onServerSelectedFromList(
        server: Server?,
        serverAwg: com.witvpn.ikev2.domain.model.ServerAwg? = null,
        serverGw: com.witvpn.ikev2.domain.model.ServerGw? = null
    ) {
        if (!isAdded) return

        val selectedAwg = serverAwg ?: server
            ?.takeIf { it.protocol.equals("awg", ignoreCase = true) }
            ?.let { selected -> viewModel2.serversAwgList.value?.find { it.ipAddress == selected.ipAddress } }

        val selectedGw = serverGw ?: server
            ?.takeIf { it.protocol.equals("gw", ignoreCase = true) }
            ?.let { selected ->
                viewModel2.serversGwList.value?.find {
                    "gw_${it.id}" == selected.id || it.ipAddress == selected.ipAddress
                }
            }

        if (isAwgMode && selectedAwg != null) {
            if (AmneziaWGManager.isConnected()) {
                AmneziaWGManager.stopVpn(requireContext())
                showProgress(true)
                binding.tvState.text = getString(R.string.connecting, "...")
                changeButtonColor(false)

                AmneziaWGManager.selectedServer = selectedAwg
                handler.postDelayed({
                    if (isAdded) {
                        startAwgVpnWithPermissionCheck(selectedAwg)
                    }
                }, 800)
            } else {
                AmneziaWGManager.selectedServer = selectedAwg
                updateAwgServerUI()
            }
        } else if (isGwMode && selectedGw != null && selectedGw.config != null) {
            GwManager.selectedServer = selectedGw.config
            updateServerButton(Server.fromGw(selectedGw))
            if (GwManager.isConnected() || GwManager.state == GwManager.State.CONNECTING) {
                val ctx = context ?: return
                GwManager.stopVpn(ctx)
                showProgress(true)
                binding.tvState.text = getString(R.string.connecting, "...")
                changeButtonColor(false)
                handler.postDelayed({
                    if (isAdded) {
                        startGwVpnWithPermissionCheck(selectedGw)
                    }
                }, 800)
            }
        } else if (isVlessMode && server != null) {
            // VLESS mode: auto-reconnect without ads
            if (isVlessConnected || VyomVpnManager.currentState == VyomState.CONNECTING) {
                // Disconnect first, then connect to new server
                val ctx = context ?: return
                isDisconnectingVless = true
                isVlessConnected = false
                handler.removeCallbacks(connectionTimeoutRunnable)
                VlessManager.stopVpn(ctx)
                updateVlessState(false)
                showProgress(false)

                // Wait a bit then connect to new server without ads
                handler.postDelayed({
                    if (isAdded) {
                        updateVlessServerUI()
                        startVlessVpnInternal() // No ad check - direct connect
                    }
                }, 800)
            } else {
                // Not connected, just update UI
                updateVlessServerUI()
            }
        }
        // IKEv2 is handled by serverLiveData observer
    }

    /**
     * Connect to IKEv2 server without showing ads (for server switching).
     */
    private fun connectToWithoutAd(server: Server) {
        val activity = requireActivity()
        val intent = Intent(context, VpnProfileControlActivity::class.java).apply {
            action = VpnProfileControlActivity.START_PROFILE
            putExtra(VpnProfileControlActivity.EXTRA_VPN_PROFILE_ID, server.uuid)
        }
        activity.startActivity(intent)
        // IKEv2 больше не репортим в API: онлайн IKEv2 сервер считает сам
        // по метрикам серверов (node_exporter, ipsec_clients).
    }

    //endregion

//    override fun onChange(tabIndex: Int) {
//        if (tabIndex != BottomNavBar.TAB_HOME) {
//            return
//        }
//        activity?.updateColorStatusBar(R.color.colorPrimary)
//    }
}
