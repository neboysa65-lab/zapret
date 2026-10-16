package com.witvpn.ikev2.presentation.ui.servers

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.SimpleItemAnimator
import com.witvpn.ikev2.R
import com.witvpn.ikev2.databinding.FragmentServers2Binding
import com.witvpn.ikev2.domain.model.Server
import com.witvpn.ikev2.presentation.base.BaseFragment
import com.witvpn.ikev2.presentation.ui.MainTabUIDelegate
import com.witvpn.ikev2.presentation.ui.ShareViewModel
import com.witvpn.ikev2.presentation.utils.FragmentUtils
import com.witvpn.ikev2.presentation.utils.SubscriptionNameFlags
import com.witvpn.ikev2.presentation.utils.Util
import com.witvpn.ikev2.presentation.utils.updateColorNavigationBar
import com.witvpn.ikev2.presentation.utils.updateColorStatusBar
import com.witvpn.ikev2.presentation.widget.bottomnav.BottomNavBar
import com.witvpn.ikev2.vless.VlessConfig
import com.witvpn.ikev2.vless.VlessManager
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale

interface ServersUIDelegate {
    fun handleServerClicked(server: Server?)
}

/**
 * Единый экран списка серверов для GW, AWG 3.1 и VLESS: «Текущий сервер»
 * сверху, ниже «Автовыбор» и все серверы одним списком (Pro — с замком).
 * Протокол приходит аргументом [ARG_PROTOCOL]: "GW" | "AWG" | "VLESS".
 */
@AndroidEntryPoint
class ServersFragment : BaseFragment<FragmentServers2Binding>(R.layout.fragment_servers2), ServersUIDelegate {

    companion object {
        const val ARG_PROTOCOL = "PROTOCOL"
        const val PROTOCOL_GW = "GW"
        const val PROTOCOL_AWG = "AWG"
        const val PROTOCOL_VLESS = "VLESS"

        fun sameVless(a: VlessConfig?, b: VlessConfig?): Boolean =
            a != null && b != null &&
                a.uuid == b.uuid && a.address == b.address &&
                a.port == b.port && a.protocol == b.protocol

        fun vlessKey(c: VlessConfig) = "${c.protocol}|${c.address}|${c.port}|${c.uuid}"
    }

    private val viewModel: ServersViewModel by activityViewModels()
    private val shareViewModel: ShareViewModel by activityViewModels()

    private val protocol: String by lazy {
        when (arguments?.getString(ARG_PROTOCOL)?.uppercase(Locale.ROOT)) {
            PROTOCOL_AWG -> PROTOCOL_AWG
            PROTOCOL_VLESS -> PROTOCOL_VLESS
            else -> PROTOCOL_GW
        }
    }

    private val adapter by lazy { ServerListAdapter(::onRowClicked) }

    private var vlessLoading = false
    private var vlessFailed = false

    override fun initBinding(view: View): FragmentServers2Binding {
        return FragmentServers2Binding.bind(view)
    }

    override fun initView() {
        binding.btnLeft.setOnClickListener { findNavController().popBackStack() }
        binding.tvProtocol.text = when (protocol) {
            PROTOCOL_AWG -> "AWG 3.1"
            PROTOCOL_VLESS -> "VLESS"
            else -> "GW"
        }
        binding.recyclerView.adapter = adapter
        // Обновления нагрузки приходят часто — без «мигания» строк
        (binding.recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        binding.currentCard.rowRoot.isClickable = false
        binding.currentCard.rowRoot.setBackgroundResource(R.drawable.bg_srv_current_card)
        binding.btnRetry.setOnClickListener { retry() }
    }

    override fun initObserve() {
        // Как и раньше: при открытии экрана освежаем списки и нагрузку
        shareViewModel.userLiveData.observe(viewLifecycleOwner) { user ->
            if (protocol != PROTOCOL_VLESS) viewModel.execute(user)
            render()
        }
        viewModel.serversAwgList.observe(viewLifecycleOwner) { render() }
        viewModel.serversGwList.observe(viewLifecycleOwner) { render() }
        viewModel.serversLoadMap.observe(viewLifecycleOwner) { render() }

        if (protocol == PROTOCOL_VLESS) {
            viewModel.fetchLoad()
            loadVless()
        }
        render()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.updateColorStatusBar(R.color.black_almost2)
        activity?.updateColorNavigationBar(R.color.black_almost2)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        activity?.updateColorStatusBar(R.color.black_almost)
        activity?.updateColorNavigationBar(R.color.black_almost)
    }

    // region render

    private fun render() {
        if (view == null) return
        val loadMap = viewModel.serversLoadMap.value ?: emptyMap()
        val autoOn = ServerAutoSelect.isEnabled(protocol)
        if (protocol == PROTOCOL_VLESS) renderVless(loadMap, autoOn) else renderNodes(loadMap, autoOn)
    }

    /** Список GW/AWG в том же виде, в каком его раньше собирали вкладки. */
    private fun nodeServers(): List<Server> {
        val servers = if (protocol == PROTOCOL_AWG) {
            viewModel.serversAwgList.value.orEmpty().map { Server.fromAwg(it) }
        } else {
            viewModel.serversGwList.value.orEmpty()
                // premium-нода free-юзера приходит без конфига (meta-only):
                // показываем её с замком, подключение ведёт на оплату.
                .filter { it.status && (it.config != null || it.premium) }
                .map { Server.fromGw(it) }
        }
        // Бесплатные сверху, Pro ниже; внутри групп — порядок сервера
        return servers.sortedBy { it.premium == true }
    }

    private fun renderNodes(loadMap: Map<String, Int>, autoOn: Boolean) {
        val premiumUser = shareViewModel.isPremium
        val servers = nodeServers()
        val draft = Server.getDraftForProtocol(protocol)

        val rows = ArrayList<ServerRow>(servers.size + 1)
        if (servers.isNotEmpty()) {
            rows += ServerRow.Auto(selected = autoOn, subtitle = getString(R.string.auto_select_desc))
        }
        servers.mapTo(rows) { s ->
            ServerRow.Node(
                key = s.id,
                title = s.country.orEmpty(),
                subtitle = s.state,
                iconRes = flagRes(s.countryCode),
                hysteriaLogo = false,
                load = s.ipAddress?.let { loadMap[it] },
                selected = !autoOn && draft == s,
                locked = s.premium == true && !premiumUser,
                payload = s,
            )
        }
        adapter.submitList(rows)

        // «Текущий сервер»
        val current: ServerRow? = when {
            draft == null -> null
            autoOn -> ServerRow.Auto(selected = true, subtitle = currentAutoSubtitle(draft.country))
            else -> ServerRow.Node(
                key = draft.id,
                title = draft.country.orEmpty(),
                subtitle = draft.state,
                iconRes = flagRes(draft.countryCode),
                hysteriaLogo = false,
                load = draft.ipAddress?.let { loadMap[it] },
                selected = true,
                locked = false,
                payload = draft,
            )
        }
        renderCurrent(current)

        val bothEmpty = viewModel.serversAwgList.value.isNullOrEmpty() &&
            viewModel.serversGwList.value.isNullOrEmpty()
        // Как раньше: крутим прогресс, пока не пришёл ни один из списков
        showState(loading = servers.isEmpty() && bothEmpty, empty = servers.isEmpty() && !bothEmpty, failed = false)
    }

    private fun renderVless(loadMap: Map<String, Int>, autoOn: Boolean) {
        val servers = VlessManager.vlessServers
        val selected = VlessManager.selectedServer

        val rows = ArrayList<ServerRow>(servers.size + 1)
        if (servers.isNotEmpty()) {
            rows += ServerRow.Auto(selected = autoOn, subtitle = getString(R.string.auto_select_desc))
        }
        servers.mapTo(rows) { vlessRow(it, loadMap, selected = !autoOn && sameVless(selected, it)) }
        adapter.submitList(rows)

        val current: ServerRow? = when {
            selected == null -> null
            autoOn -> ServerRow.Auto(
                selected = true,
                subtitle = currentAutoSubtitle(SubscriptionNameFlags.parse(selected.name).displayName)
            )
            else -> vlessRow(selected, loadMap, selected = true)
        }
        renderCurrent(current)

        showState(
            loading = servers.isEmpty() && vlessLoading,
            empty = servers.isEmpty() && !vlessLoading,
            failed = vlessFailed
        )
    }

    private fun vlessRow(c: VlessConfig, loadMap: Map<String, Int>, selected: Boolean): ServerRow.Node {
        val parsed = SubscriptionNameFlags.parse(c.name)
        val subtitle = listOfNotNull(
            parsed.countryCode?.uppercase(Locale.ROOT),
            c.displayProtocolLabel()
        ).joinToString(" · ")
        return ServerRow.Node(
            key = vlessKey(c),
            title = parsed.displayName.ifBlank { c.address },
            subtitle = subtitle,
            iconRes = flagRes(parsed.countryCode),
            hysteriaLogo = c.isHysteria2(),
            load = loadMap[c.address],
            selected = selected,
            locked = false,
            payload = c,
        )
    }

    private fun renderCurrent(row: ServerRow?) {
        binding.currentSection.visibility = View.VISIBLE
        val card = binding.currentCard
        if (row == null) {
            card.imgIcon.background = null
            card.imgIcon.setPadding(0, 0, 0, 0)
            card.imgIcon.setImageResource(R.drawable.ic_globe)
            card.tvTitle.text = getString(R.string.server_not_selected)
            card.tvTitle.setCompoundDrawablesRelative(null, null, null, null)
            card.tvSubtitle.visibility = View.GONE
            ServerRowBinder.setLoad(card, null)
            ServerRowBinder.setMark(card, RowMark.NONE)
        } else {
            ServerRowBinder.bind(card, row, RowMark.CURRENT)
        }
        // Карточке текущего сервера оставляем свой синий фон (bind трогает только selected)
        card.rowRoot.isSelected = false
    }

    private fun currentAutoSubtitle(name: String?): String =
        if (name.isNullOrBlank()) getString(R.string.auto_select_desc)
        else getString(R.string.auto_select_now, name)

    private fun showState(loading: Boolean, empty: Boolean, failed: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        val showEmpty = !loading && empty
        binding.emptyState.visibility = if (showEmpty) View.VISIBLE else View.GONE
        if (showEmpty) {
            binding.tvEmpty.setText(if (failed) R.string.server_list_load_failed else R.string.server_list_empty)
        }
    }

    private fun flagRes(countryCode: String?): Int =
        Util.getResId(countryCode)?.takeIf { it != 0 && it != -1 } ?: R.drawable.ic_globe

    // endregion

    // region actions

    private fun onRowClicked(row: ServerRow) {
        when (row) {
            is ServerRow.Auto -> onAutoClicked()
            is ServerRow.Node -> when (val p = row.payload) {
                is Server -> pickServer(p)
                is VlessConfig -> pickVless(p, auto = false)
            }
        }
    }

    /** Логика выбора прежняя: free/премиум-юзер — выбор, иначе — оплата. */
    private fun pickServer(server: Server) {
        if (server.premium != true || shareViewModel.isPremium) {
            ServerAutoSelect.setEnabled(protocol, false)
            shareViewModel.execute(server)
            close()
        } else {
            try { findNavController().navigate(R.id.billingFragment) } catch (_: Exception) {}
        }
    }

    private fun pickVless(server: VlessConfig, auto: Boolean) {
        ServerAutoSelect.setEnabled(PROTOCOL_VLESS, auto)
        val previous = VlessManager.selectedServer
        VlessManager.selectedServer = server
        // Переподключение (если VPN активен) выполняет ConnectFragment
        if (!sameVless(previous, server)) shareViewModel.notifyVlessServerPicked()
        close()
    }

    private fun onAutoClicked() {
        val loadMap = viewModel.serversLoadMap.value ?: emptyMap()
        if (protocol == PROTOCOL_VLESS) {
            val best = ServerAutoSelect.pickBest(
                VlessManager.vlessServers,
                VlessManager.selectedServer,
                keyOf = { vlessKey(it) },
                loadOf = { loadMap[it.address] }
            )
            if (best == null) {
                context?.let { Toast.makeText(it, R.string.auto_select_unavailable, Toast.LENGTH_SHORT).show() }
                return
            }
            pickVless(best, auto = true)
            return
        }

        val premiumUser = shareViewModel.isPremium
        val candidates = autoCandidates(premiumUser)
        val best = ServerAutoSelect.pickBest(
            candidates,
            Server.getDraftForProtocol(protocol),
            keyOf = { it.id },
            loadOf = { s -> s.ipAddress?.let { loadMap[it] } }
        )
        if (best == null) {
            context?.let { Toast.makeText(it, R.string.auto_select_unavailable, Toast.LENGTH_SHORT).show() }
            return
        }
        ServerAutoSelect.setEnabled(protocol, true)
        shareViewModel.execute(best)
        close()
    }

    /** Кандидаты для автовыбора: доступные пользователю, рабочие, с конфигом. */
    private fun autoCandidates(premiumUser: Boolean): List<Server> =
        if (protocol == PROTOCOL_AWG) {
            viewModel.serversAwgList.value.orEmpty()
                .filter { it.status && (!it.premium || premiumUser) }
                .map { Server.fromAwg(it) }
        } else {
            viewModel.serversGwList.value.orEmpty()
                .filter { it.status && it.config != null && (!it.premium || premiumUser) }
                .map { Server.fromGw(it) }
        }

    private fun retry() {
        if (protocol == PROTOCOL_VLESS) {
            viewModel.fetchLoad()
            loadVless()
        } else {
            shareViewModel.userLiveData.value?.let { viewModel.execute(it) }
        }
    }

    private fun loadVless() {
        if (VlessManager.vlessServers.isNotEmpty() || vlessLoading) {
            render()
            return
        }
        vlessLoading = true
        vlessFailed = false
        render()
        VlessManager.fetchSubscription(requireContext()) { success ->
            vlessLoading = false
            vlessFailed = !success
            render()
        }
    }

    private fun close() {
        try { findNavController().popBackStack() } catch (_: Exception) {}
    }

    // endregion

    override fun handleServerClicked(server: Server?) {
        if (shareViewModel.isPremium) {
            FragmentUtils.getParent(this, MainTabUIDelegate::class.java)?.setServerToConnect(server)
        } else {
            FragmentUtils.getParent(this, MainTabUIDelegate::class.java)?.setCurrentTab(BottomNavBar.TAB_PREMIUM)
        }
    }
}
