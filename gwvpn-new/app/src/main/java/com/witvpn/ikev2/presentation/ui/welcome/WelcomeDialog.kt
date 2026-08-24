package com.witvpn.ikev2.presentation.ui.welcome

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import com.witvpn.ikev2.databinding.DialogWelcomeBinding

class WelcomeDialog(
    context: Context,
    private val newUser: () -> Unit,
    private val mnemonicPhrase: () -> Unit,
    //Theme.AppCompat.Light.DarkActionBar
) : AlertDialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {
    private val layout = DialogWelcomeBinding.inflate(LayoutInflater.from(context))

    override fun onCreate(savedInstanceState: Bundle?) {
        setView(layout.root)
        layout.newUser.setOnClickListener {
            newUser()
            dismiss()
        }
        layout.mnemonic.setOnClickListener {
            mnemonicPhrase()
            dismiss()
        }
        setCancelable(false)
        super.onCreate(savedInstanceState)
    }
}