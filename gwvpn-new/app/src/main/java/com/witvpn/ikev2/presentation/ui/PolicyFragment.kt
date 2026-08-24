package com.witvpn.ikev2.presentation.ui

import android.view.View
import androidx.navigation.fragment.findNavController
import com.witvpn.ikev2.R
import com.witvpn.ikev2.databinding.FragmentPolicyBinding
import com.witvpn.ikev2.presentation.base.BaseFragment

class PolicyFragment : BaseFragment<FragmentPolicyBinding>(R.layout.fragment_policy) {

    override fun initBinding(view: View): FragmentPolicyBinding {
        return FragmentPolicyBinding.bind(view)
    }

    override fun initView() {
        binding.toolbar.apply {
            onBtnLeftClicked = {
                findNavController().popBackStack()
                true
            }
        }
    }
}