package com.witvpn.ikev2.presentation.ui.forgotpassword

import android.view.View
import com.witvpn.ikev2.R
import com.witvpn.ikev2.databinding.FragmentForgotPasswordBinding
import com.witvpn.ikev2.presentation.base.BaseFragment
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ForgotPasswordFragment : BaseFragment<FragmentForgotPasswordBinding>(R.layout.fragment_forgot_password) {

    override fun initBinding(view: View): FragmentForgotPasswordBinding {
        return FragmentForgotPasswordBinding.bind(view)
    }

    override fun initView() {
    }
}