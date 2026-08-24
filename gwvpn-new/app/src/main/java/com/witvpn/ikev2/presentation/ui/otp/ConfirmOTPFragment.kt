package com.witvpn.ikev2.presentation.ui.otp

import android.os.Bundle
import android.view.View
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.observe
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import com.witvpn.ikev2.R
import com.witvpn.ikev2.databinding.FragmentConfirmOtpBinding
import com.witvpn.ikev2.domain.model.Status
import com.witvpn.ikev2.presentation.base.BaseFragment
import com.witvpn.ikev2.presentation.base.BaseViewModel
import com.witvpn.ikev2.presentation.ui.ShareViewModel
import com.witvpn.ikev2.presentation.utils.hideSoftKeyboard
import com.witvpn.ikev2.presentation.utils.show
import com.witvpn.ikev2.presentation.utils.showKeyboard
import com.witvpn.ikev2.presentation.utils.showToast
import com.witvpn.ikev2.presentation.widget.snackbar.CustomSnackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ConfirmOTPFragment : BaseFragment<FragmentConfirmOtpBinding>(R.layout.fragment_confirm_otp) {
    companion object{
        const val MAX_COUNTDOWN = 60
    }
    private val viewModel: ConfirmOTPViewModel by viewModels()

    private val args: ConfirmOTPFragmentArgs by navArgs()

    private var countJob = Job()
    private var count = MAX_COUNTDOWN
        set(value) {
            field = value
            binding.tvResendCode.text = if (count > 0) {
                binding.tvResendCode.alpha = 0.5f
                getString(R.string.resend_code) + " ($count)"
            } else {
                binding.tvResendCode.alpha = 1f
                getString(R.string.resend_code)
            }
            binding.tvResendCode.isEnabled = count <= 0
        }

    override fun initBinding(view: View): FragmentConfirmOtpBinding {
        return FragmentConfirmOtpBinding.bind(view)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        viewModel.signUp(args.emailArg)
    }

    override fun initView() {
        activity?.showKeyboard(binding.inputCode.getEditText())
        binding.toolbar.apply {
            onBtnLeftClicked = {
                try { findNavController().popBackStack() } catch (_: Exception) {}
                true
            }
        }

        binding.inputCode.apply {
            onInputOTPCodeCompleted = { code ->
                activity?.hideSoftKeyboard()
                viewModel.login(code, args.emailArg)
            }
        }

        binding.tvResendCode.setOnClickListener {
            viewModel.signUp(args.emailArg)
        }
    }

    override fun initViewModel(): BaseViewModel? {
        return viewModel
    }

    override fun initObserve() {
        viewModel.getSignUpState().observe(viewLifecycleOwner) {
            binding.loading.show(it.status == Status.LOADING)

            if (it.status == Status.SUCCESS) {
                countDown()
                return@observe
            }

            if (it.status == Status.ERROR) {
                context?.showToast(it.message)
            }

        }

        viewModel.getLoginState().observe(viewLifecycleOwner) {
            binding.loading.show(it.status == Status.LOADING)

            if (it.status == Status.SUCCESS) {
                try { findNavController().navigate(R.id.action_confirmOTPFragment_to_splashFragment) } catch (_: Exception) {}
                return@observe
            }

            if (it.status == Status.ERROR) {
                binding.inputCode.error = true
                CustomSnackbar.make(binding.coordinator, R.string.message_invalid_otp, R.drawable.ic_warning)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        countJob.cancel()
    }

    private fun countDown() {
        count = MAX_COUNTDOWN
        lifecycleScope.launch(countJob) {
            repeat(MAX_COUNTDOWN) {
                count--
                delay(1000L)
            }
        }
    }
}