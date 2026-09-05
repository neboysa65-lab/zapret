package com.witvpn.ikev2.presentation.utils

import android.content.Context

/**
 * Хранилище последнего рабочего адреса API для перебора (ApiFailoverInterceptor).
 *
 * Когда перебор адресов находит живой домен, он сохраняется здесь, чтобы:
 *   - следующие запросы сразу шли на рабочий адрес без повторного перебора;
 *   - выбор переживал перезапуск приложения;
 *   - оплата (getPaymentUrl) следовала за рабочим доменом.
 */
object ApiEndpointStore {

    private const val PREFS_NAME = "api_failover"
    private const val KEY_PREFERRED = "preferred_base"

    /** Последний рабочий базовый URL API (со слэшем на конце) или null. */
    fun getPreferred(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PREFERRED, null)
            ?.takeIf { it.isNotBlank() }
    }

    /** Запомнить рабочий базовый URL API. */
    fun setPreferred(context: Context, baseUrl: String) {
        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PREFERRED, normalized)
            .apply()
    }
}
