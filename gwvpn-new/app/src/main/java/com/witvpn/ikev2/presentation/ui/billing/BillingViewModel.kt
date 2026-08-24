package com.witvpn.ikev2.presentation.ui.billing

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.viewModelScope
import com.witvpn.ikev2.R
import com.witvpn.ikev2.domain.repository.PayRepository
import com.witvpn.ikev2.domain.repository.UserRepository
import com.witvpn.ikev2.presentation.base.BaseViewModel
import com.witvpn.ikev2.presentation.utils.getSessionUserId
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BillingViewModel @Inject constructor(
    private val payRepo: PayRepository,
    private val userRepository: UserRepository
) : BaseViewModel() {

    private var needToUpdateUser = false

    private fun goToPayPage(fragment: BillingFragment, plan: PayRepository.Plan) {
        viewModelScope.launch {
            try {
                val userId = getSessionUserId() ?: return@launch
                val payUri = Uri.parse(payRepo.getPayFK2Url(userId, plan))
                launch(Dispatchers.Main) {
                    needToUpdateUser = true
                    try {
                        fragment.startActivity(Intent(Intent.ACTION_VIEW, payUri))
                    } catch (e: android.content.ActivityNotFoundException) {
                        val ctx = fragment.context ?: return@launch
                        Toast.makeText(ctx, fragment.getString(R.string.no_browser_found), Toast.LENGTH_LONG)
                            .show()
                    }
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    val ctx = fragment.context ?: return@launch
                    Toast.makeText(ctx, ctx.getString(R.string.state_error), Toast.LENGTH_LONG)
                        .show()
                }
            }
        }
    }

    fun onOneMonthClick(fragment: BillingFragment) = goToPayPage(fragment, PayRepository.Plan.ONE_MONTH)

    fun onThreeMonthClick(fragment: BillingFragment) = goToPayPage(fragment, PayRepository.Plan.THREE_MONTH)

    fun onSixMonthClick(fragment: BillingFragment) = goToPayPage(fragment, PayRepository.Plan.SIX_MONTH)

    fun onResume(fragment: BillingFragment, onUserRefreshed: ((com.witvpn.ikev2.domain.model.User) -> Unit)? = null) {
        if (needToUpdateUser) {
            needToUpdateUser = false
            viewModelScope.launch {
                try {
                    val userId = getSessionUserId() ?: return@launch
                    val param = mutableMapOf<String, Any>("userId" to userId)
                    val user = userRepository.profile(param)
                    launch(Dispatchers.Main) {
                        onUserRefreshed?.invoke(user)
                    }
                } catch (_: Exception) {}
            }
        }
    }

}