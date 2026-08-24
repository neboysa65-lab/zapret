package com.witvpn.ikev2.presentation.ui.otp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.witvpn.ikev2.R
import com.witvpn.ikev2.databinding.FragmentOtpBinding
import com.witvpn.ikev2.databinding.FragmentSettingsBinding
import com.witvpn.ikev2.features.telegram.launchTelegram
import com.witvpn.ikev2.presentation.base.BaseFragment
import com.witvpn.ikev2.presentation.ui.ShareViewModel
import com.witvpn.ikev2.presentation.ui.entropy.MnemonicFragment
import com.witvpn.ikev2.presentation.utils.InAppReviewHelper
import com.witvpn.ikev2.presentation.utils.parseApiDate
import com.witvpn.ikev2.presentation.utils.removeSessionUserId
import dagger.hilt.android.AndroidEntryPoint
import org.strongswan.android.logic.VpnStateService

@AndroidEntryPoint
class OTPFragment : BaseFragment<FragmentSettingsBinding>(R.layout.fragment_settings) {
    private val shareViewModel: ShareViewModel by activityViewModels()
    private val viewModel: OTPViewModel by viewModels()

    override fun initBinding(view: View): FragmentSettingsBinding {
        return FragmentSettingsBinding.bind(view)
    }

    override fun initView() {
//        binding.close.setOnClickListener {
//            findNavController().popBackStack()
//        }

//        binding.accountKey.apply {
//            setOnClickListener {
//                findNavController().navigate(R.id.action_OTPFragment_to_mnemonicFragment, MnemonicFragment.newReadMnemonic())
//            }
//        }

        shareViewModel.userLiveData.observe(this) { user ->
            if (user.hasPremiumSubscribe) {
                binding.plan.text = "${getString(R.string.current_plan)} - ${getString(R.string.subs_type_premium)}"
                binding.plan.setTextColor(0xFFFFFFFF.toInt())
                val endDate = user.premiumEnd?.parseApiDate()
                if (!endDate.isNullOrEmpty()) {
                    binding.planEndDate.text = getString(R.string.active_until_date, endDate)
                    binding.planEndDate.visibility = View.VISIBLE
                } else {
                    binding.planEndDate.visibility = View.GONE
                }
            } else {
                binding.plan.text = "${getString(R.string.current_plan)} - ${getString(R.string.subs_type_free)}"
                binding.plan.setTextColor(0xFF8C9197.toInt())
                binding.planEndDate.visibility = View.GONE
            }
        }

//        binding.shareKeyQr.apply {
//            setOnClickListener {
//                findNavController().navigate(R.id.action_OTPFragment_to_QRFragment)
//            }
//        }

//        binding.showLogs.setOnClickListener {
//            startActivity(Intent(requireContext(), com.witvpn.ikev2.presentation.ui.LogViewerActivity::class.java))
//        }

//        binding.accountExit.apply {

        binding.logout.apply {
            setOnClickListener {
                clearUserAndNav()
            }
        }

//        binding.deleteProfile.apply {
        binding.delete.apply {
            setOnClickListener {
                clearUserAndNav()
            }
        }
        binding.splitTunneling.setOnClickListener {
            try { findNavController().navigate(R.id.splitTunnelFragment) } catch (_: Exception) {}
        }
        binding.account.setOnClickListener {
            try { findNavController().navigate(R.id.mnemonicFragment) } catch (_: Exception) {}
        }
        binding.prime.setOnClickListener { navPaywall() }
        binding.rate.setOnClickListener { review() }
        binding.telegram.setOnClickListener { context?.launchTelegram() }
        binding.faq.setOnClickListener { context?.launchTelegram() }
        binding.subscriptions.setOnClickListener { navSubscription() }
    }

    private fun clearUserAndNav() {
        viewModel.clearEntropyData()
        removeSessionUserId()
        try { findNavController().navigate(R.id.action_OTPFragment_to_splashFragment) } catch (_: Exception) {}
    }

    private fun navPaywall(){
        try { findNavController().navigate(R.id.billingFragment) } catch (_: Exception) {}
    }

    private fun review(){
        val packageName = "app.greywebs.vpn"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
        } catch (e: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
        }
    }

    private fun navSubscription(){
        try { findNavController().navigate(R.id.restoreFragment) } catch (_: Exception) {}
    }
}