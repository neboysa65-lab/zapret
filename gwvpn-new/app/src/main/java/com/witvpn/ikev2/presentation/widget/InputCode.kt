package com.witvpn.ikev2.presentation.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import com.witvpn.ikev2.R
import com.witvpn.ikev2.databinding.ViewInputCodeBinding

class InputCode(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {
    private var binding: ViewInputCodeBinding

    var error: Boolean = false
        set(value) {
            field = value
            post {
                refreshDrawableState()
            }
        }

    init {
        val view = View.inflate(context, R.layout.view_input_code, this)
        binding = ViewInputCodeBinding.bind(view)
    }

    override fun onCreateDrawableState(extraSpace: Int): IntArray {
        val drawableState = super.onCreateDrawableState(extraSpace + 1)

        if (error) {
            View.mergeDrawableStates(drawableState, InputViewConstant.STATE_ERROR)
        }

        return drawableState
    }

    fun setText(text: String) {
        binding.tvNumber.text = text
    }
}