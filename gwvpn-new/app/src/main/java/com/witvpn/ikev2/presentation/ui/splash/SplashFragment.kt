package com.witvpn.ikev2.presentation.ui.splash

import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.observe
import androidx.navigation.fragment.findNavController
import com.witvpn.ikev2.R
import com.witvpn.ikev2.databinding.FragmentSplashBinding
import com.witvpn.ikev2.domain.model.Status
import com.witvpn.ikev2.presentation.base.BaseFragment
import com.witvpn.ikev2.presentation.ui.ShareViewModel
import com.witvpn.ikev2.presentation.ui.entropy.MnemonicFragment
import com.witvpn.ikev2.presentation.ui.welcome.WelcomeDialog
import com.witvpn.ikev2.presentation.utils.showToast
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SplashFragment : BaseFragment<FragmentSplashBinding>(R.layout.fragment_splash) {

    private val viewModel: SplashViewModel by viewModels()
    private val shareViewModel: ShareViewModel by activityViewModels()

    override fun initBinding(view: View): FragmentSplashBinding {
        return FragmentSplashBinding.bind(view)
    }

    override fun initView() {
        viewModel.execute(this, false)
    }

    fun showNewUserDialog() {
//        NewUserDialog(requireContext()).apply {
//            anonimusButton.setOnClickListener {
//                viewModel.execute(this@SplashFragment, true)
//                dismiss()
//            }
//            restoreButton.setOnClickListener {
//                findNavController().navigate(R.id.action_splashFragment_to_mnemonicFragment, MnemonicFragment.newRestoreFromMnemonic())
//                dismiss()
//            }
//        }.show()
        WelcomeDialog(
            requireContext(),
            newUser = {
                viewModel.execute(this@SplashFragment, true)
            },
            mnemonicPhrase = {
                if (isAdded && isResumed) {
                    try {
                        findNavController().navigate(R.id.restoreFragment)
                    } catch (e: Exception) {
                        // Fragment not associated with NavController
                    }
                }
            }
        ).show()
    }

    override fun initObserve() {
        viewModel.userLiveData.observe(viewLifecycleOwner) {
            when (it.status) {
                Status.SUCCESS -> {
                    shareViewModel.setUser(it.data)
                    try {
                        findNavController().navigate(R.id.action_splashFragment_to_mainFragment)
                    } catch (e: Exception) {
                        // Navigation state may be invalid (duplicate navigation, fragment detached)
                    }
                }
                Status.ERROR -> {
                    context?.showToast(it.message)
                }
                else -> {
                }
            }
        }
    }
}