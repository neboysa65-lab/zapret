package com.witvpn.ikev2.presentation.ui.billing.grays

import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.Toast
import com.witvpn.ikev2.BuildConfig
import com.witvpn.ikev2.R
import com.witvpn.ikev2.databinding.FragmentGreysBillingBinding
import com.witvpn.ikev2.presentation.base.BaseFragment
import com.witvpn.ikev2.presentation.utils.RemoteConfigManager
import com.witvpn.ikev2.presentation.utils.getSessionUserId

class GraysBillingFragment: BaseFragment<FragmentGreysBillingBinding>(R.layout.fragment_greys_billing) {
    private var aWebView: WebView? = null

    override fun initBinding(view: View) =
        FragmentGreysBillingBinding.bind(view)

    override fun initView() {

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        with(binding) {
            aWebView = webView

            with(webView.settings) {
                javaScriptEnabled = true
                loadsImagesAutomatically = true
                databaseEnabled = true
                domStorageEnabled = true
            }

            with(webView) {
                webViewClient = BillingWebClient()
            }

            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        }

        getSessionUserId().let { userId ->
            if (userId != null) {
                // URL оплаты берём из Remote Config (fallback — BuildConfig.GRAYS_BILLING_URL).
                val paymentUrl = RemoteConfigManager.getPaymentUrl(requireContext())
                binding.webView.loadUrl(paymentUrl.format(userId))
            } else {
                Toast.makeText(context, "userId not defined", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        aWebView?.onPause()
    }

    override fun onResume() {
        aWebView?.onResume()
        super.onResume()
    }

    override fun onDestroy() {
        aWebView?.let {
            it.destroy()
            aWebView = null
        }
        super.onDestroy()
    }
}