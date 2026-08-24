package com.witvpn.ikev2.presentation.ui.billing

import android.view.View
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import com.witvpn.ikev2.R
import com.witvpn.ikev2.databinding.FragmentBillingBinding
import com.witvpn.ikev2.presentation.base.BaseFragment
import com.witvpn.ikev2.presentation.base.BaseViewModel
import com.witvpn.ikev2.presentation.ui.ShareViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class BillingFragment : BaseFragment<FragmentBillingBinding>(R.layout.fragment_billing) {
    private val viewModel: BillingViewModel by viewModels()
    private val shareViewModel: ShareViewModel by activityViewModels()
    private var selectedPlan = 6 // 1, 3, or 6

    override fun initBinding(view: View): FragmentBillingBinding {
        return FragmentBillingBinding.bind(view)
    }

    override fun initView() {
        updateSelectionUI()

        binding.sixMonthCard.setOnClickListener {
            selectedPlan = 6
            updateSelectionUI()
        }
        
        binding.threeMonthCard.setOnClickListener {
            selectedPlan = 3
            updateSelectionUI()
        }
        
        binding.oneMonthCard.setOnClickListener {
            selectedPlan = 1
            updateSelectionUI()
        }
        
        binding.btnBuy.setOnClickListener {
            when (selectedPlan) {
                1 -> viewModel.onOneMonthClick(this)
                3 -> viewModel.onThreeMonthClick(this)
                6 -> viewModel.onSixMonthClick(this)
            }
        }
        
        binding.ivClose.setOnClickListener {
            activity?.onBackPressed()
        }
    }

    private fun updateSelectionUI() {
        // Reset all to unselected
        val unselectedBg = ContextCompat.getDrawable(requireContext(), R.drawable.bg_premium_card_unselected)
        val selectedBg = ContextCompat.getDrawable(requireContext(), R.drawable.bg_premium_card_selected)
        
        val radioUnchecked = ContextCompat.getDrawable(requireContext(), R.drawable.radio_unchecked)
        val radioChecked = ContextCompat.getDrawable(requireContext(), R.drawable.radio_checked)
        
        binding.sixMonthCard.background = unselectedBg
        binding.threeMonthCard.background = unselectedBg
        binding.oneMonthCard.background = unselectedBg
        
        binding.sixMonthRadio.setImageDrawable(radioUnchecked)
        binding.threeMonthRadio.setImageDrawable(radioUnchecked)
        binding.oneMonthRadio.setImageDrawable(radioUnchecked)
        
        // Set selected
        when (selectedPlan) {
            6 -> {
                binding.sixMonthCard.background = selectedBg
                binding.sixMonthRadio.setImageDrawable(radioChecked)
            }
            3 -> {
                binding.threeMonthCard.background = selectedBg
                binding.threeMonthRadio.setImageDrawable(radioChecked)
            }
            1 -> {
                binding.oneMonthCard.background = selectedBg
                binding.oneMonthRadio.setImageDrawable(radioChecked)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume(this) { user ->
            shareViewModel.replaceUser(user)
        }
    }

    override fun initViewModel(): BaseViewModel? = null
}