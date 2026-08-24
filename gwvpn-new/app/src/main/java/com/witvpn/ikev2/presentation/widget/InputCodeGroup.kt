package com.witvpn.ikev2.presentation.widget

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import com.witvpn.ikev2.R
import com.witvpn.ikev2.databinding.ViewInputCodeGroupBinding

class InputCodeGroup(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {
    private var binding: ViewInputCodeGroupBinding

    var error: Boolean = false
        set(value) {
            field = value
            post {
                inputCodes.forEach { it.error = field }
            }
        }
    private val inputCodes = mutableListOf<InputCode>()

    var onInputOTPCodeCompleted: ((String) -> Unit)? = null

    init {
        val view = View.inflate(context, R.layout.view_input_code_group, this)
        binding = ViewInputCodeGroupBinding.bind(view)
    }

    override fun onFinishInflate() {
        super.onFinishInflate()

        inputCodes.add(binding.code1)
        inputCodes.add(binding.code2)
        inputCodes.add(binding.code3)
        inputCodes.add(binding.code4)
        inputCodes.add(binding.code5)
        inputCodes.add(binding.code6)

        binding.code1.isSelected = true

        binding.editInput.addTextChangedListener(object : TextWatcher {
            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {
                p0 ?: return
                error = false
                var newString = p0.toString()
                if (p0.length > 6) {
                    newString = p0.substring(0, 5)
                }
                val arr = newString.split("").filter { it.isNotEmpty() }

                inputCodes.filterIndexed { index, _ -> index >= arr.count() - 1 }.forEach { it.setText("") }

                arr.forEachIndexed { index, s ->
                    inputCodes[index].setText(s)
                }

                inputCodes.forEachIndexed { index, inputCode ->
                    inputCode.isSelected = index == arr.count()
                }

                if (arr.count() == 6) {
                    onInputOTPCodeCompleted?.invoke(newString)
                }
            }

            override fun afterTextChanged(p0: Editable?) {}
            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
        })
    }

    fun getEditText():EditText = binding.editInput
}