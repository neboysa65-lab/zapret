package com.witvpn.ikev2.presentation.ui.servers.tab

import android.view.View
import androidx.core.os.bundleOf
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.observe
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.RecyclerView
import com.witvpn.ikev2.R
import com.witvpn.ikev2.databinding.FragmentTabBinding
import com.witvpn.ikev2.domain.model.Server
import com.witvpn.ikev2.presentation.base.BaseFragment
import com.witvpn.ikev2.presentation.ui.ShareViewModel
import com.witvpn.ikev2.presentation.ui.servers.ServersViewModel
import dagger.hilt.android.AndroidEntryPoint

const val PREMIUM = "premium"
const val PROTOCOL = "PROTOCOL"

@AndroidEntryPoint
class TabFragment() : BaseFragment<FragmentTabBinding>(R.layout.fragment_tab) {
    private val premium: Boolean by lazy {
        requireArguments().getBoolean(PREMIUM, false)
    }
    
    private val protocolFilter: String by lazy {
        requireArguments().getString(PROTOCOL, "IKEv2")
    }

    constructor(isPremium: Boolean, protocol: String = "IKEv2") : this() {
        arguments = bundleOf(PREMIUM to isPremium, PROTOCOL to protocol)
    }

    private val shareViewModel: ShareViewModel by activityViewModels()
    private val viewModel: ServersViewModel by activityViewModels()

    private val tabAdapter by lazy { TabAdapter(::pickServer) }

    override fun initBinding(view: View): FragmentTabBinding {
        return FragmentTabBinding.bind(view)
    }

    override fun initView() {
        binding.recyclerView.adapter = tabAdapter

        context?.getDrawable(R.drawable.div_height_10)?.let { drawable ->
            binding.recyclerView.addItemDecoration(
                DividerItemDecoration(requireContext(), RecyclerView.VERTICAL)
                    .also { it.setDrawable(drawable) }
            )
        }
    }

    override fun initObserve() {
        shareViewModel.serverLiveData.observe(viewLifecycleOwner) {
            tabAdapter?.notifyDataSetChanged()
        }

        viewModel.serversList.observe(viewLifecycleOwner) { _ ->
            updateList()
        }

        viewModel.serversAwgList.observe(viewLifecycleOwner) { _ ->
            updateList()
        }

        viewModel.serversGwList.observe(viewLifecycleOwner) { _ ->
            updateList()
        }

        viewModel.serversLoadMap.observe(viewLifecycleOwner) { _ ->
            updateList()
        }
    }

    private fun updateList() {
        val combined = mutableListOf<Server>()
        
        if (protocolFilter == "IKEv2") {
            val ikev2AndVless = viewModel.serversList.value?.filter { it.premium == premium } ?: emptyList()
            combined.addAll(ikev2AndVless)
        } else if (protocolFilter == "AWG") {
            val awgServers = viewModel.serversAwgList.value?.map { com.witvpn.ikev2.domain.model.Server.fromAwg(it) }?.filter { it.premium == premium } ?: emptyList()
            combined.addAll(awgServers)
        } else if (protocolFilter == "GW") {
            val gwServers = viewModel.serversGwList.value
                ?.filter { it.status && it.config != null }
                ?.map { com.witvpn.ikev2.domain.model.Server.fromGw(it) }
                ?.filter { it.premium == premium }
                ?: emptyList()
            combined.addAll(gwServers)
        }

        tabAdapter.updateData(
            combined,
            shareViewModel.isPremium,
            viewModel.serversLoadMap.value ?: emptyMap(),
            protocolFilter
        )
    }

    private fun pickServer(server: Server) {
        if (server.premium != true || shareViewModel.isPremium) {
            shareViewModel.execute(server)
            try { findNavController().popBackStack() } catch (_: Exception) {}
        } else {
            try { findNavController().navigate(R.id.billingFragment) } catch (_: Exception) {}
        }
    }
}