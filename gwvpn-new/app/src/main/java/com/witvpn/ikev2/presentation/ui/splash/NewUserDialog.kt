package com.witvpn.ikev2.presentation.ui.splash

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import com.witvpn.ikev2.databinding.AboutConnectLimitDialogBinding
import com.witvpn.ikev2.databinding.NewUserDialogBinding

class NewUserDialog(context: Context): Dialog(context) {
    private val binding = NewUserDialogBinding.inflate(layoutInflater)
    val anonimusButton: View
        get() = binding.anonimus
    val restoreButton: View
        get() = binding.restore

    init {
        setContentView(binding.root)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setCancelable(false)
    }
}