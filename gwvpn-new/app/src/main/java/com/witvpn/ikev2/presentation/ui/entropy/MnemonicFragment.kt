package com.witvpn.ikev2.presentation.ui.entropy

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.witvpn.ikev2.R
import com.witvpn.ikev2.databinding.FragmentMnemonic2Binding
import com.witvpn.ikev2.databinding.FragmentMnemonicBinding
import com.witvpn.ikev2.presentation.base.BaseFragment
import com.witvpn.ikev2.presentation.utils.SharePrefs
import com.witvpn.ikev2.presentation.utils.removePref
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MnemonicFragment : BaseFragment<FragmentMnemonic2Binding>(R.layout.fragment_mnemonic2),
    MnemonicView {
    companion object {
        private const val MODE_KEY = "view_mode"
        private const val RESTORE_MODE = 1
        private const val VIEW_MODE = 2
        fun newReadMnemonic() = Bundle().apply {
            putInt(MODE_KEY, VIEW_MODE)
        }

        fun newRestoreFromMnemonic() = Bundle().apply {
            putInt(MODE_KEY, RESTORE_MODE)
        }
    }

    private val viewModel: MnemonicViewModel by viewModels()

    override fun initBinding(view: View): FragmentMnemonic2Binding {
        return FragmentMnemonic2Binding.bind(view)
    }

    override fun initView() {
        viewModel.bindView(this)

//        binding.toolbar.apply {
//            onBtnLeftClicked = {
//                findNavController().popBackStack()
//                true
//            }
//        }
        binding.btnLeft.setOnClickListener {
            findNavController().popBackStack()
        }
        if (arguments != null) {
            arguments?.also { args ->
                if (args.containsKey(MODE_KEY)) {
                    when (args.getInt(MODE_KEY)) {
                        RESTORE_MODE -> {
                            initRestoreFromMnemonic()
                        }

                        VIEW_MODE -> {
                            initShowMnemonic(viewModel.getMnemonic())
                        }
                    }
                }
            }
        } else {
//            initRestoreFromMnemonic()
            initShowMnemonic(viewModel.getMnemonic())
        }
        binding.copyIcon.apply {
            isVisible = true
//            setImageResource(R.drawable.baseline_content_paste_go_24)
            setOnClickListener {
                // paste
                viewModel.onCopyClick()
            }
        }
    }

    private fun initRestoreFromMnemonic() {
//        binding.toolbar.title = getString(R.string.restore_account)
//        binding.inputMnemonic.apply {
//            label.text = getString(R.string.control_phrase)
//            editText.addTextChangedListener {
//                viewModel.onMnemonicInput(it.toString())
//            }
//        }
//        binding.copyIcon.apply {
//            isVisible = false
//            setImageResource(R.drawable.baseline_content_paste_go_24)
//            setOnClickListener {
//                // paste
//            }
//        }
//        binding.restoreButton.apply {
//            isVisible = true
//            isEnabled = false
//            setOnClickListener {
//                if (isEnabled)
//                    viewModel.onRestoreClick()
//            }
//        }
    }

    private fun initShowMnemonic(mnemonic: String) {
//        binding.toolbar.title = getString(R.string.account_mnemonic)
//        binding.inputMnemonic.apply {
//            label.text = getString(R.string.control_phrase)
//            editText.isEnabled = false
//            editText.setText(mnemonic)
//        }
        binding.inputMnemonic.setText(mnemonic)
        binding.copyIcon.apply {
            isVisible = true
//            setImageResource(R.drawable.baseline_copy_all_24)
            setOnClickListener {
                viewModel.onCopyClick()
            }
        }
//        binding.restoreButton.isVisible = false
    }

    override fun setInput(mnemonic: String) {
//        binding.inputMnemonic.editText.setText(mnemonic)
        binding.inputMnemonic.setText(mnemonic)
    }

    override fun getInput(): String {
//        return binding.inputMnemonic.editText.text.toString()
        return binding.inputMnemonic.text.toString()
    }

    override fun setInputError(error: Int) {
//        binding.inputMnemonic.editText.error = getString(error)
        binding.inputMnemonic.error = getString(error)
    }

    override fun setInputError(error: String) {
//        binding.inputMnemonic.editText.error = error
        binding.inputMnemonic.error = error
    }

    override fun clearError() {
//        binding.inputMnemonic.editText.error = null
        binding.inputMnemonic.error = null
    }

    override fun setRestoreButton(isEnabled: Boolean) {
//        binding.restoreButton.isEnabled = isEnabled
    }

    override fun setInputHint(hint: Int) {
    }

    override fun showInfoAlert(alert: Int) {
        Toast.makeText(context, alert, Toast.LENGTH_SHORT).show()
    }

    override fun getViewContext() = requireContext()
    override fun reloadUser() {
        removePref(SharePrefs.KEY_USER_ID)
        findNavController().navigate(R.id.action_mnemonicFragment_to_splashFragment)
    }
}
