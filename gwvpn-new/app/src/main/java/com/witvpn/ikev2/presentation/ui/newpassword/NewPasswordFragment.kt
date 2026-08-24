package com.witvpn.ikev2.presentation.ui.newpassword

import android.view.View
import com.witvpn.ikev2.R
import com.witvpn.ikev2.databinding.FragmentNewPasswordBinding
import com.witvpn.ikev2.presentation.base.BaseFragment
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class NewPasswordFragment : BaseFragment<FragmentNewPasswordBinding>(R.layout.fragment_new_password) {

    override fun initBinding(view: View): FragmentNewPasswordBinding {
        return FragmentNewPasswordBinding.bind(view)
    }

    override fun initView() {
    }
}