package com.witvpn.ikev2.presentation.ui.deleteAccount

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import com.witvpn.ikev2.databinding.DialogDeleteAccountBinding

class DeleteAccountDialog(
    context: Context,
    private val stay: () -> Unit,
    private val delete: () -> Unit,
) : AlertDialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {
    private val layout = DialogDeleteAccountBinding.inflate(LayoutInflater.from(context))

    init {
        setView(layout.root)
        layout.stay.setOnClickListener {
            stay()
            dismiss()
        }
        layout.delete.setOnClickListener {
            delete()
            dismiss()
        }
        window?.setBackgroundDrawable(ColorDrawable(Color.parseColor("#131519")))
        setCancelable(false)
    }
}