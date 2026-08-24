package com.witvpn.ikev2.presentation.ui.qr

import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.witvpn.ikev2.R
import com.witvpn.ikev2.databinding.FragmentQr2Binding
import com.witvpn.ikev2.presentation.base.BindingFragment
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.filterNotNull

@AndroidEntryPoint
class QRFragment: BindingFragment<FragmentQr2Binding>() {
    private val viewModel: QRViewModel by activityViewModels()

    override fun inflateBinding(inflater: LayoutInflater): FragmentQr2Binding =
        FragmentQr2Binding.inflate(inflater)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.flow
            .filterNotNull()
            .lifecycleCollect(viewLifecycleOwner, ::showQr)
        binding.pro.root.setOnClickListener { navPaywall() }
    }

    private fun navPaywall(){
        findNavController().navigate(R.id.billingFragment)
    }

    private fun showQr(bitmap :Bitmap){
        binding.progress.isVisible = false
        binding.qrcode.setImageBitmap(bitmap)
    }
}