package com.witvpn.ikev2.presentation.ui.connectlimit

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import com.witvpn.ikev2.databinding.ConnectLimitExpiredDialogBinding

class TimeLimitExpiredDialog(context: Context): Dialog(context) {
    private val binding = ConnectLimitExpiredDialogBinding.inflate(layoutInflater)
    val closeButton: View
        get() = binding.close
    val actionButton: View
        get() = binding.actionButton

    init {
        setContentView(binding.root)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setCancelable(true)
    }
}