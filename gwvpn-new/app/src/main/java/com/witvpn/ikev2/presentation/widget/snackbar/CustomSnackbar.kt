package com.witvpn.ikev2.presentation.widget.snackbar

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.witvpn.ikev2.R

class CustomSnackbar(parent: ViewGroup, content: CustomSnackbarView) : BaseTransientBottomBar<CustomSnackbar>(parent, content, content) {

    init {
        getView().setBackgroundColor(
            ContextCompat.getColor(
                view.context,
                android.R.color.transparent
            )
        )
        getView().setPadding(0, 0, 0, 0)
    }

    companion object {

        fun make(viewGroup: ViewGroup, @StringRes message: Int, @DrawableRes icon: Int = R.drawable.ic_done): CustomSnackbar {
            val customView = LayoutInflater.from(viewGroup.context).inflate(
                R.layout.layout_custom_snackbar,
                viewGroup,
                false
            ) as CustomSnackbarView
            val snackbar = CustomSnackbar(viewGroup, customView)

            val tvMessage = customView.findViewById<TextView>(R.id.tvMessage)
            tvMessage.text = viewGroup.context.getString(message)

            val imgIcon = customView.findViewById<ImageView>(R.id.imgIcon)
            imgIcon.setImageResource(icon)

            return snackbar
        }

    }

}