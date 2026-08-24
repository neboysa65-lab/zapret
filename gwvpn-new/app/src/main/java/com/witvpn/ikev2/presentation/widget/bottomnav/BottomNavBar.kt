package com.witvpn.ikev2.presentation.widget.bottomnav

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AttributeSet
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import androidx.core.content.ContextCompat.startActivity
import com.witvpn.ikev2.R
import com.witvpn.ikev2.presentation.ui.MainActivity
import ru.zoommax.TelegramAD

class BottomNavBar(context: Context?, attrs: AttributeSet?) : LinearLayout(context, attrs), View.OnClickListener {
    companion object {
        const val TAB_HOME = 0
        const val TAB_PREMIUM = 1
        const val TAB_PROFILE = 2
        const val TAB_SERVERS = 3
        const val TAB_QR = 4
    }

    private lateinit var tabHome: BottomNavItem
    private lateinit var tabPremium: BottomNavItem
    private lateinit var tabProfile: BottomNavItem
    private lateinit var tabQR: BottomNavItem

    var listener: OnTabChangedListener? = null

    var currentTabSelected = -1
        set(value) {
            val changed = field != value
            if (changed) {
                if (toggle(value)) {
                    field = value
                }
            } else {
                listener?.reSelected(field)
            }
        }

    override fun onFinishInflate() {
        super.onFinishInflate()
        tabHome = findViewById(R.id.tab_home)
        //tabPremium = findViewById(R.id.tab_premium)
        tabProfile = findViewById(R.id.tab_profile)
        tabQR = findViewById(R.id.tab_qr)

        tabHome.setOnClickListener(this)
        //tabPremium.setOnClickListener(this)
        tabProfile.setOnClickListener(this)
        tabQR.setOnClickListener(this)
    }

    override fun onClick(p0: View?) {
        this.currentTabSelected = when (p0?.id) {
            R.id.tab_home -> {
                TAB_HOME
            }
            /*R.id.tab_premium -> {
                TAB_HOME
            }*/
            R.id.tab_qr -> {
                TAB_QR
            }
            R.id.tab_profile -> {
                TAB_PROFILE
            }
            else -> {
                TAB_PROFILE
            }
        }
    }

    private fun toggle(tabSelected: Int): Boolean {
        val change = listener?.changed(tabSelected)

        if (change == false) {
            return false
        }
        tabHome.isSelected = tabSelected == TAB_HOME || tabSelected == TAB_SERVERS
        //tabPremium.isSelected = tabSelected == TAB_PREMIUM
        tabProfile.isSelected = tabSelected == TAB_PROFILE
        tabQR.isSelected = tabSelected == TAB_QR
        return true
    }

    interface OnTabChangedListener {
        fun changed(tabIndex: Int): Boolean
        fun reSelected(tabIndex: Int)
    }

}