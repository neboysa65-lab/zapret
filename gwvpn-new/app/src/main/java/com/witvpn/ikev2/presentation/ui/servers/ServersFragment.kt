package com.witvpn.ikev2.presentation.ui.servers

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.observe
import androidx.navigation.fragment.findNavController
import com.witvpn.ikev2.R
import com.witvpn.ikev2.databinding.FragmentServers2Binding
import com.witvpn.ikev2.domain.model.Server
import com.witvpn.ikev2.domain.model.Server.Companion.isAutoConnect
import com.witvpn.ikev2.presentation.base.BaseFragment
import com.witvpn.ikev2.presentation.ui.MainTabUIDelegate
import com.witvpn.ikev2.presentation.ui.ShareViewModel
import com.witvpn.ikev2.presentation.ui.servers.tab.TabFragment
import com.witvpn.ikev2.presentation.utils.FragmentUtils
import com.witvpn.ikev2.presentation.utils.setLetterSpacing
import com.witvpn.ikev2.presentation.utils.updateColorNavigationBar
import com.witvpn.ikev2.presentation.utils.updateColorStatusBar
import com.witvpn.ikev2.presentation.widget.bottomnav.BottomNavBar
import dagger.hilt.android.AndroidEntryPoint

interface ServersUIDelegate {
    fun handleServerClicked(server: Server?)
}

@AndroidEntryPoint
class ServersFragment : BaseFragment<FragmentServers2Binding>(R.layout.fragment_servers2), ServersUIDelegate {

    private val viewModel: ServersViewModel by activityViewModels()
    private val shareViewModel: ShareViewModel by activityViewModels()

    private val tabs by lazy {
        val protocol = arguments?.getString("PROTOCOL") ?: "IKEv2"
        listOf(
            TabFragment(false, protocol),
            TabFragment(true, protocol)
        )
    }

    override fun initBinding(view: View): FragmentServers2Binding {
        return FragmentServers2Binding.bind(view)
    }

    override fun initView() {
        binding.btnLeft.setOnClickListener {
//            FragmentUtils.getParent(this, MainTabUIDelegate::class.java)?.setCurrentTab(BottomNavBar.TAB_HOME)
            findNavController().popBackStack()
        }

        binding.viewPager.apply {
            adapter = ViewPagerAdapter(childFragmentManager)
        }
        binding.tabLayout.setupWithViewPager(binding.viewPager)
        binding.tabLayout.setLetterSpacing(0.12f)
    }

    override fun initObserve() {
        shareViewModel.userLiveData.value?.let { user ->
            viewModel.execute(user)
        }

        viewModel.serversList.observe(viewLifecycleOwner) {
//            when (it.status) {
//                Status.LOADING -> binding.progress.visibility = View.VISIBLE
//                else -> {
//                    binding.progress.visibility = View.GONE
//                }
//            }
//            if(it.isEmpty()){
//                binding.progress.visibility = View.VISIBLE
//            } else {
//                binding.progress.visibility = View.GONE
//            }
            showLoading(it.isEmpty())
        }
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

    inner class ViewPagerAdapter(fm: FragmentManager) : FragmentPagerAdapter(fm, BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {
        override fun getItem(position: Int): Fragment {
            return tabs[position]
        }

        override fun getCount(): Int {
            return tabs.count()
        }

        override fun getPageTitle(position: Int): CharSequence? {
            return if (position == 0) context?.getString(R.string.tab_all_location) else context?.getString(
                R.string.tab_premium
            )
        }
    }

    override fun handleServerClicked(server: Server?) {
        if (shareViewModel.isPremium || (server != null && server.isAutoConnect)) {
            FragmentUtils.getParent(this, MainTabUIDelegate::class.java)?.setServerToConnect(server)
        } else {
            FragmentUtils.getParent(this, MainTabUIDelegate::class.java)?.setCurrentTab(BottomNavBar.TAB_PREMIUM)
        }
    }

    private fun showLoading(show :Boolean){
        if(show){
            binding.progress.visibility = View.VISIBLE
        } else {
            binding.progress.visibility = View.GONE
        }
    }
}