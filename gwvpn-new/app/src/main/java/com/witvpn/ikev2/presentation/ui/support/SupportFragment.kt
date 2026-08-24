package com.witvpn.ikev2.presentation.ui.support

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import com.witvpn.ikev2.R
import com.witvpn.ikev2.databinding.FragmentSupportBinding
import com.witvpn.ikev2.presentation.base.BaseFragment
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SupportFragment : BaseFragment<FragmentSupportBinding>(R.layout.fragment_support) {

    override fun initBinding(view: View): FragmentSupportBinding {
        return FragmentSupportBinding.bind(view)
    }

    override fun initView() {
        binding.telegramSupport.setOnClickListener {
            openTelegramSupport()
        }
        binding.telegramChannel.setOnClickListener {
            openTelegramChannel()
        }
        startBlinkAnimation()
    }

    private fun startBlinkAnimation() {
        val blinkAnimation = AlphaAnimation(1.0f, 0.3f).apply {
            duration = 1000
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
        binding.liveIndicator.startAnimation(blinkAnimation)
    }

    private fun openTelegramSupport() {
        val telegramUsername = "gwvpn_support"
        val intent = telegramUserIntent(requireContext(), telegramUsername)
        startActivity(intent)
    }

    private fun openTelegramChannel() {
        val channelId = "ZAo_7z5mSFY2OWFi"
        val intent = telegramJoinIntent(requireContext(), channelId)
        startActivity(intent)
    }

    private fun telegramUserIntent(context: Context, tg: String): Intent {
        return try {
            try {
                context.packageManager.getPackageInfo(
                    "org.telegram.messenger",
                    0
                )
            } catch (e: Exception) {
                context.packageManager.getPackageInfo(
                    "org.thunderdog.challegram",
                    0
                )
            }
            Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=$tg"))
        } catch (e: Exception) {
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.telegram.me/$tg"))
        }
    }

    private fun telegramJoinIntent(context: Context, tg: String): Intent {
        return try {
            try {
                context.packageManager.getPackageInfo(
                    "org.telegram.messenger",
                    0
                )
            } catch (e: Exception) {
                context.packageManager.getPackageInfo(
                    "org.thunderdog.challegram",
                    0
                )
            }
            Intent(Intent.ACTION_VIEW, Uri.parse("tg://join?invite=$tg"))
        } catch (e: Exception) {
            Intent(Intent.ACTION_VIEW, Uri.parse("http://www.telegram.me/+$tg"))
        }
    }
}
