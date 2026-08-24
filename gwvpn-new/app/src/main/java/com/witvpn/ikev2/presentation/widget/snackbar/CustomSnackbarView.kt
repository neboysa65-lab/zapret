package com.witvpn.ikev2.presentation.widget.snackbar

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import com.google.android.material.snackbar.ContentViewCallback
import com.witvpn.ikev2.R

class CustomSnackbarView @JvmOverloads constructor(context: Context, attributeSet: AttributeSet? = null, defaultStyle: Int = 0) : ConstraintLayout(context, attributeSet, defaultStyle),
    ContentViewCallback {

    init {
        View.inflate(context, R.layout.item_custom_snackbar, this)
    }

    override fun animateContentIn(delay: Int, duration: Int) {
        // TODO("Use some animation")
    }

    override fun animateContentOut(delay: Int, duration: Int) {
        // TODO("Use some animation")
    }

}