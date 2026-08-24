package com.witvpn.ikev2.presentation.ui.profile

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.observe
import androidx.navigation.fragment.findNavController
import com.witvpn.ikev2.R
import com.witvpn.ikev2.databinding.FragmentMnemonicBinding
import com.witvpn.ikev2.databinding.FragmentProfileBinding
import com.witvpn.ikev2.domain.model.Package
import com.witvpn.ikev2.domain.model.Status
import com.witvpn.ikev2.presentation.MyApp
import com.witvpn.ikev2.presentation.base.BaseFragment
import com.witvpn.ikev2.presentation.ui.MainTabFragment
import com.witvpn.ikev2.presentation.ui.MainTabUIDelegate
import com.witvpn.ikev2.presentation.ui.ShareViewModel
import com.witvpn.ikev2.presentation.ui.entropy.MnemonicView
import com.witvpn.ikev2.presentation.ui.entropy.MnemonicViewModel
import com.witvpn.ikev2.presentation.utils.*
import com.witvpn.ikev2.presentation.widget.bottomnav.BottomNavBar
import dagger.hilt.android.AndroidEntryPoint
import java.util.*

@AndroidEntryPoint
class ProfileFragment : /*BaseFragment<FragmentProfileBinding>(R.layout.fragment_profile), MainTabFragment.OnTabChanged, View.OnClickListener {
    private val shareViewModel: ShareViewModel by activityViewModels()

    private val delegate: MainTabUIDelegate? by lazy {
        return@lazy FragmentUtils.getParent(this, MainTabUIDelegate::class.java)
    }

    override fun initBinding(view: View): FragmentProfileBinding {
        return FragmentProfileBinding.bind(view)
    }

    override fun initView() {
        binding.tvEdit.setOnClickListener(this)
        //binding.tvUpgrade.setOnClickListener(this)
        binding.tvLogout.setOnClickListener(this)
    }

    override fun initObserve() {
        shareViewModel.userLiveData.observe(viewLifecycleOwner) {
            binding.tvEmail.text = it.email
        }

        shareViewModel.purchaseLiveData.observe(viewLifecycleOwner) {
            when (it.status) {
                Status.SUCCESS -> {
                    //binding.tvUpgrade.visibility = View.GONE
                    if (Feature.FEATURE_CANCEL_SUBSCRIPTION) {
                        //binding.viewPackage.visibility = View.GONE
                    }
                    val calendar = Calendar.getInstance()
                        .apply {
                            timeInMillis = it.data?.purchaseTime ?: 0L
                        }
                    val packages = shareViewModel.user?.packages ?: listOf()
                    val packageIdMonth = packages.firstOrNull { p: Package -> p.packageDuration == "monthly" }?.packageId

                    //val isMonthly = it.data?.sku == packageIdMonth

                    //binding.tvAccountType.text = if (isMonthly) {
                        calendar.add(Calendar.MONTH, 1)
                        getString(R.string.gold_monthly_package, calendar.toStringWithPattern())
                    } //else {
                        //calendar.add(Calendar.YEAR, 1)
                        //getString(R.string.gold_yearly_package, calendar.toStringWithPattern())
                    //}
                //}
                /*else -> {
                    binding.tvAccountType.text = getString(R.string.free)
                    binding.tvUpgrade.visibility = View.GONE
                    binding.viewPackage.visibility = View.GONE
                }*/
                else -> {}
            }
        }
    }


    override fun onChange(tabIndex: Int) {
        if (tabIndex != BottomNavBar.TAB_PROFILE) {
            return
        }
        activity?.updateColorStatusBar(R.color.colorPrimary)
    }

    override fun onClick(p0: View?) {
        when (p0) {
            /*binding.tvUpgrade -> {
                delegate?.setCurrentTab(BottomNavBar.TAB_PREMIUM)
            }
            binding.tvEdit -> {
                findNavController().navigate(R.id.action_mainFragment_to_newPasswordFragment)
            }*/
            binding.tvLogout -> {
                removePref(SharePrefs.KEY_USER_ID)
                findNavController().navigate(R.id.action_mainFragment_to_splashFragment)
            }
        }
    }
}*/BaseFragment<FragmentMnemonicBinding>(R.layout.fragment_mnemonic), MnemonicView {
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

    override fun initBinding(view: View): FragmentMnemonicBinding {
        return FragmentMnemonicBinding.bind(view)
    }

    override fun initView() {
        viewModel.bindView(this)

        binding.toolbar.apply {
            onBtnLeftClicked = {
                try { findNavController().popBackStack() } catch (_: Exception) {}
                true
            }
        }
        if (arguments != null) {
            arguments?.also { args ->
                if (args.containsKey(MODE_KEY)) {
                    when(args.getInt(MODE_KEY)) {
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
            initRestoreFromMnemonic()
        }
    }

    private fun initRestoreFromMnemonic() {
        binding.toolbar.title = getString(R.string.restore_account)
        binding.inputMnemonic.apply {
            label.text = getString(R.string.control_phrase)
            editText.addTextChangedListener {
                viewModel.onMnemonicInput(it.toString())
            }
        }
        binding.copyIcon.apply {
            isVisible = false
            setImageResource(R.drawable.baseline_content_paste_go_24)
            setOnClickListener {
                // paste
            }
        }
        binding.restoreButton.apply {
            isVisible = true
            isEnabled = false
            setOnClickListener {
                if (isEnabled)
                    viewModel.onRestoreClick()
            }
        }
    }

    private fun initShowMnemonic(mnemonic: String) {
        binding.toolbar.title = getString(R.string.account_mnemonic)
        binding.inputMnemonic.apply {
            label.text = getString(R.string.control_phrase)
            editText.isEnabled = false
            editText.setText(mnemonic)
        }
        binding.copyIcon.apply {
            isVisible = true
            setImageResource(R.drawable.baseline_copy_all_24)
            setOnClickListener {
                viewModel.onCopyClick()
            }
        }
        binding.restoreButton.isVisible = false
    }

    override fun setInput(mnemonic: String) {
        binding.inputMnemonic.editText.setText(mnemonic)
    }

    override fun getInput(): String {
        return binding.inputMnemonic.editText.text.toString()
    }

    override fun setInputError(error: Int) {
        binding.inputMnemonic.editText.error = getString(error)
    }

    override fun setInputError(error: String) {
        binding.inputMnemonic.editText.error = error
    }

    override fun clearError() {
        binding.inputMnemonic.editText.error = null
    }

    override fun setRestoreButton(isEnabled: Boolean) {
        binding.restoreButton.isEnabled = isEnabled
    }

    override fun setInputHint(hint: Int) {
    }

    override fun showInfoAlert(alert: Int) {
        Toast.makeText(context, alert, Toast.LENGTH_SHORT).show()
    }

    override fun getViewContext() = requireContext()
    override fun reloadUser() {
        removePref(SharePrefs.KEY_USER_ID)
        try { findNavController().navigate(R.id.action_mnemonicFragment_to_splashFragment) } catch (_: Exception) {}
    }
}