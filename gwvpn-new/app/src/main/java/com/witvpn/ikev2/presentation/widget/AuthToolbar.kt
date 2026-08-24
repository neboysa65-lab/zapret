package com.witvpn.ikev2.presentation.widget

import android.app.Activity
import android.content.Context
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.View
import android.widget.RelativeLayout
import com.witvpn.ikev2.R
import com.witvpn.ikev2.databinding.LayoutAuthToolbarBinding

class AuthToolbar(context: Context?, attrs: AttributeSet?) : RelativeLayout(context, attrs) {
    private var binding: LayoutAuthToolbarBinding

    init {
        val view = View.inflate(context, R.layout.layout_auth_toolbar, this)
        binding = LayoutAuthToolbarBinding.bind(view)
        initAttrs(context, attrs)
        initView()
    }

    var onBtnLeftClicked: (() -> Boolean)? = null
    var onBtnRightClicked: (() -> Unit)? = null

    var leftIcon: Drawable? = null
        set(value) {
            field = value
            field?.let {
                binding.btnLeft.setImageDrawable(leftIcon)
                binding.btnLeft.visibility = View.VISIBLE
            } ?: run {
                binding.btnLeft.visibility = View.INVISIBLE
            }
        }

    var title: String? = null
        set(value) {
            field = value
            field?.let {
                binding.tvTitle.text = it
                binding.tvTitle.visibility = View.VISIBLE
            } ?: run {
                binding.tvTitle.visibility = View.INVISIBLE
            }
        }

    var rightText: String? = null
        set(value) {
            field = value
            field?.let {
                binding.btnRight.text = it
                binding.btnRight.visibility = View.VISIBLE
            } ?: run {
                binding.btnRight.visibility = View.INVISIBLE
            }
        }

    private fun initAttrs(context: Context?, attrs: AttributeSet?) {
        context?.obtainStyledAttributes(attrs, R.styleable.AuthToolbar)
            ?.apply {
                leftIcon = this.getDrawable(R.styleable.AuthToolbar_atb_left)
                title = this.getString(R.styleable.AuthToolbar_atb_title)
                rightText = this.getString(R.styleable.AuthToolbar_atb_right)
            }
            ?.recycle()
    }

    private fun initView() {
        binding.btnLeft.setOnClickListener(this::handleBtnLeftClicked)
        binding.btnRight.setOnClickListener(this::handleBtnRightClicked)
    }

    private fun handleBtnLeftClicked(view: View) {
        val isUse = onBtnLeftClicked?.invoke()
        if (isUse == true) {
            return
        }
        (context as? Activity)?.finish()
    }

    private fun handleBtnRightClicked(view: View) {
        onBtnRightClicked?.invoke()
    }

}