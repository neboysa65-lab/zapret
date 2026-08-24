package com.witvpn.ikev2.presentation.widget

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.RelativeLayout
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.witvpn.ikev2.R
import com.witvpn.ikev2.databinding.ItemLocationBinding
import com.witvpn.ikev2.databinding.ItemSelectionBinding
import com.witvpn.ikev2.domain.model.Server
import com.witvpn.ikev2.presentation.utils.Util

class SelectionItemView(context: Context?, attrs: AttributeSet?) : RelativeLayout(context, attrs) {
    companion object {
        const val ACTION_CHECK_BOX = 0
        const val ACTION_PREMIUM = 1
        const val ACTION_ARROW = 2
        const val ACTION_CHECK = 3
    }

//    private var binding: ItemSelectionBinding = ItemSelectionBinding.inflate(LayoutInflater.from(context), this)
    private var binding: ItemLocationBinding = ItemLocationBinding.inflate(LayoutInflater.from(context), this, true)

    init {
        initAttrs(context, attrs)
    }

    private fun initAttrs(context: Context?, attrs: AttributeSet?) {
        context?.obtainStyledAttributes(attrs, R.styleable.SelectionItemView)
            ?.apply {
                //Flag
                val icon = this.getDrawable(R.styleable.SelectionItemView_siv_icon)
                setFlag(icon)
                //Title
                val title = this.getString(R.styleable.SelectionItemView_siv_title)
                setTitle(title)

                //Descripton
                val description = this.getString(R.styleable.SelectionItemView_siv_description)
                setDescription(description)

                //Price
                val price = this.getString(R.styleable.SelectionItemView_siv_price)
                setPrice(price)

                //Action
                val ordinal = this.getInt(R.styleable.SelectionItemView_siv_action, 0)
                setEndAction(ordinal)
            }
            ?.recycle()
    }

    private fun setFlag(icon: Drawable?) {
        if (icon != null) {
            binding.imgFlag.setImageDrawable(icon)
            binding.imgFlag.visibility = View.VISIBLE
        } else {
            binding.imgFlag.visibility = View.GONE
        }
    }

    fun setFlag(resId: Int) {
        if (resId != -1) {
            binding.imgFlag.setImageResource(resId)
            binding.imgFlag.visibility = View.VISIBLE
        } else {
            binding.imgFlag.visibility = View.GONE
        }
    }

    fun setTitle(title: String?) {
        setTitle(title, leadingIconRes = 0)
    }

    /**
     * Заголовок; опционально маленькая иконка слева от текста
     * (размер ≈ textSize * 1.12 — чуть больше текста ~12%).
     */
    fun setTitle(title: String?, @DrawableRes leadingIconRes: Int) {
        binding.tvTitle.text = title
        if (leadingIconRes != 0) {
            val d = ContextCompat.getDrawable(context, leadingIconRes)?.mutate()
            if (d != null) {
                val sizePx = (binding.tvTitle.textSize * 1.12f).toInt().coerceAtLeast(1)
                // сохраняем пропорции viewport логотипа ~22:16
                val w = sizePx
                val h = (sizePx * 16f / 22f).toInt().coerceAtLeast(1)
                d.setBounds(0, 0, w, h)
                binding.tvTitle.setCompoundDrawablesRelative(d, null, null, null)
                binding.tvTitle.compoundDrawablePadding =
                    (4f * resources.displayMetrics.density).toInt()
            } else {
                binding.tvTitle.setCompoundDrawablesRelative(null, null, null, null)
                binding.tvTitle.compoundDrawablePadding = 0
            }
        } else {
            // Важно сбрасывать — иначе RecyclerView/переиспользование оставляет лого Hysteria
            binding.tvTitle.setCompoundDrawablesRelative(null, null, null, null)
            binding.tvTitle.compoundDrawablePadding = 0
        }
    }

    fun setDescription(description: String?) {
        if (description != null) {
            binding.tvDescription.text = description
            binding.tvDescription.visibility = View.VISIBLE
        } else {
            binding.tvDescription.visibility = View.GONE
        }
    }

    fun setEndAction(ordinal: Int) {
        val iconRes = when (ordinal) {
            ACTION_PREMIUM -> R.drawable.ic_lock
//            ACTION_ARROW -> R.drawable.ic_arrowright
//            else -> R.drawable.ic_check_state
            else -> 0
        }

        binding.imgCheck.setBackgroundResource(iconRes)
    }

    fun setPrice(price: String?) {
//        if (price != null) {
//            binding.tvPrice.text = price
//            binding.tvPrice.visibility = View.VISIBLE
//        } else {
//            binding.tvPrice.visibility = View.GONE
//        }
    }

    override fun setSelected(selected: Boolean) {
        super.setSelected(selected)
//        binding.imgCheck.isChecked = selected
        binding.imgCheck.setImageResource(if(selected) R.drawable.ic_check_green else 0)
    }

    fun purchase() {
        this.isEnabled = false
        binding.imgCheck.isEnabled = false
        binding.tvTitle.append(context?.getString(R.string.current))
        binding.tvTitle.alpha = 0.5f
//        binding.tvPrice.alpha = 0.5f
    }

    fun initWith(title: String) {
        this.isEnabled = true
        binding.imgCheck.isEnabled = true
        binding.tvTitle.text = title
        binding.tvTitle.alpha = 1f
//        binding.tvPrice.alpha = 1f
    }

    fun setState(server :Server){
        setFlag(Util.getResId(server.countryCode) ?: 0)
        setTitle(server.country)
        setDescription(server.state)
        if (server.protocol == "vless") {
            binding.tvProtocol.visibility = View.VISIBLE
            binding.tvProtocol.text = "VLESS"
            binding.tvProtocol.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
        } else {
            binding.tvProtocol.visibility = View.GONE
            binding.tvProtocol.setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0)
        }
    }

    fun setLoadPercentage(percent: Int?) {
        if (percent != null) {
            binding.flLoadIndicator.visibility = View.VISIBLE
            binding.progressLoad.progress = percent
            binding.tvLoadPercent.text = "${percent}%"

            val colorRes = when {
                percent >= 80 -> R.color.color_load_critical
                percent >= 60 -> R.color.color_load_high
                percent >= 30 -> R.color.color_load_medium
                else -> R.color.color_load_low
            }
            binding.progressLoad.setIndicatorColor(ContextCompat.getColor(context, colorRes))
        } else {
            binding.flLoadIndicator.visibility = View.GONE
        }
    }

    fun setStatus(status :Status){
        @DrawableRes val resource :Int = when(status){
            Status.UNLOCKED -> 0
            Status.LOCKED -> R.drawable.ic_lock
            Status.SELECTED -> R.drawable.ic_check_green
        }
        binding.imgCheck.setImageResource(resource)
    }

    enum class Status { UNLOCKED, LOCKED, SELECTED }
}
