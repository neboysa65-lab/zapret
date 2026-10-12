package com.witvpn.ikev2.presentation.ui.billing.grays

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebView
import android.webkit.WebViewClient

class BillingWebClient : WebViewClient() {

    /** Вызывается по кнопке «Повторить» на локальной странице ошибки. */
    var onRetry: (() -> Unit)? = null

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        // Мост с локальной страницы ошибки: перезагрузить страницу оплаты.
        if (request?.url?.scheme == "gwretry") {
            onRetry?.invoke()
            return true
        }
        view?.loadUrl(request?.url.toString())
        return true
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?
    ) {
        super.onReceivedError(view, request, error)
        // Обрыв сети на главном фрейме: системная страница ошибки Android
        // («зелёный дроид») показывает ПОЛНЫЙ URL оплаты с доменом API —
        // не даём ей появиться, рисуем свою страницу без адресов.
        // Субресурсные ошибки (request.isForMainFrame == false) страницу
        // не ломают.
        if (request?.isForMainFrame == true) {
            view?.loadDataWithBaseURL(null, ERROR_HTML, "text/html", "UTF-8", null)
        }
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
    }

    override fun onPageCommitVisible(view: WebView?, url: String?) {
        super.onPageCommitVisible(view, url)
    }

    private companion object {
        // Повтор делается мостом gwretry:// (см. shouldOverrideUrlLoading):
        // сам URL оплаты в HTML страницы ошибки не подставляется.
        val ERROR_HTML = """
            <html><head><meta name="viewport" content="width=device-width, initial-scale=1">
            <style>
              body{background:#12151c;color:#fff;font-family:sans-serif;display:flex;
                   flex-direction:column;align-items:center;justify-content:center;
                   height:100vh;margin:0;text-align:center;padding:0 24px}
              a{display:inline-block;margin-top:20px;padding:12px 28px;border-radius:24px;
                background:#4CAF50;color:#000;text-decoration:none;font-weight:bold}
            </style></head>
            <body>
              <h3>Нет подключения к интернету</h3>
              <p style="color:#9aa0a6">Проверьте связь и попробуйте ещё раз</p>
              <a href="gwretry://retry">Повторить</a>
            </body></html>
        """.trimIndent()
    }
}
