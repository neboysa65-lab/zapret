package com.witvpn.ikev2.presentation.widget

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.text.method.PasswordTransformationMethod
import android.util.AttributeSet
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.RelativeLayout
import androidx.core.content.ContextCompat
import com.witvpn.ikev2.R
import com.witvpn.ikev2.databinding.ViewInputBinding
import com.witvpn.ikev2.presentation.utils.dp2Px
import com.witvpn.ikev2.presentation.utils.setFontFamilyHint
import com.witvpn.ikev2.presentation.utils.showKeyboard

class InputView(context: Context?, attrs: AttributeSet?) : RelativeLayout(context, attrs), View.OnClickListener {
    companion object {
        const val ACTION_NONE = 0
        const val ACTION_CANCEL = 1
        const val ACTION_HINT = 2
    }

    private var binding: ViewInputBinding

    private var actionType = ACTION_NONE

    var onTextChanged: ((String) -> Unit)? = null
    val text: String
        get() {
            return binding.editText.text.toString()
        }

    var error: Boolean = false
        set(value) {
            val change = field != value
            field = value
            if (change) {
                if (field) {
                    clearFocus()
                }
                post {
                    refreshDrawableState()
                }
            }
        }

    init {
        val view = View.inflate(context, R.layout.view_input, this)
        binding = ViewInputBinding.bind(view)

        setBackgroundResource(R.drawable.bg_input_view)
        val paddingHorizontal = context.dp2Px(16f)
        val paddingVertical = context.dp2Px(8f)
        setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical)
        initView()
        initAttrs(context, attrs)
        this.setOnClickListener(this)
    }

    private fun initView() {
        binding.editText.setOnFocusChangeListener { _, hasFocus ->
            this.isSelected = hasFocus
            val color = if (hasFocus) {
                this.error = false
                ContextCompat.getColor(context, R.color.colorInputText)
            } else {
                ContextCompat.getColor(context, R.color.colorInputText)
            }
            binding.editText.setTextColor(color)
            toggleIcon()
        }
        binding.editText.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(p0: Editable?) {

            }

            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
            }

            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
                onTextChanged?.invoke(binding.editText.text.toString())
                toggleIcon()
            }
        })
        binding.cbIcon.setOnClickListener {
            when (actionType) {
                ACTION_CANCEL -> binding.editText.setText("")
                ACTION_HINT -> {
                    binding.cbIcon.isActivated = !binding.cbIcon.isActivated
                    binding.editText.transformationMethod = if (binding.cbIcon.isActivated) {
                        null
                    } else {
                        PasswordTransformationMethod()
                    }
                    binding.editText.setSelection(binding.editText.length())
                }
            }
        }
    }

    private fun initAttrs(context: Context?, attrs: AttributeSet?) {
        context?.obtainStyledAttributes(attrs, R.styleable.InputView)
            ?.apply {
                val label = this.getString(R.styleable.InputView_iv_label)
                val hint = this.getString(R.styleable.InputView_iv_hint)
                val inputType = this.getInt(R.styleable.InputView_android_inputType, EditorInfo.TYPE_TEXT_VARIATION_NORMAL)
                actionType = this.getInteger(R.styleable.InputView_iv_action, ACTION_NONE)
                setLabel(label)
                setInputText(inputType, hint)
                setIcon(actionType)
            }
            ?.recycle()

    }

    private fun toggleIcon() {
        val text = binding.editText.text.toString()

        if (actionType == ACTION_NONE) {
            return
        }

        binding.cbIcon.visibility = if (text.isEmpty() || !this.isSelected) View.INVISIBLE else View.VISIBLE
    }

    private fun setLabel(label: String?) {
        binding.tvLabel.text = label
    }

    private fun setInputText(inputType: Int, hint: String?) {
        binding.editText.hint = hint
        binding.editText.inputType = inputType
        binding.editText.setFontFamilyHint(R.font.campton_book)
    }

    private fun setIcon(type: Int) {
        val iconRes = when (type) {
            ACTION_CANCEL -> R.drawable.ic_cancel
            ACTION_HINT -> R.drawable.ic_eye_state
            else -> -1
        }

        if (iconRes != -1) {
            binding.cbIcon.setBackgroundResource(iconRes)
        }
    }

    override fun onCreateDrawableState(extraSpace: Int): IntArray {
        val drawableState = super.onCreateDrawableState(extraSpace + 1)

        if (error) {
            View.mergeDrawableStates(drawableState, InputViewConstant.STATE_ERROR)
        }

        return drawableState
    }

    override fun clearFocus() {
        super.clearFocus()
        binding.editText.clearFocus()
    }

    override fun onClick(p0: View?) {
        isSelected = true
        binding.editText.requestFocus()
        context.showKeyboard(binding.editText)
    }

    fun reset() {
        binding.editText.setText("")
        error = false
    }
}