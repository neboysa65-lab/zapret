package com.witvpn.ikev2.presentation.utils.interceptor

import android.content.Context
import com.witvpn.ikev2.presentation.utils.ApiEndpointStore
import com.witvpn.ikev2.presentation.utils.RemoteConfigManager
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber
import java.io.IOException

/**
 * Перебор адресов API при блокировке текущего (ТСПУ и т.п.).
 *
 * Список кандидатов приходит из Remote Config:
 *   - api_base_url  — основной адрес (для совместимости остаётся в списке);
 *   - api_base_urls — дополнительные адреса, каждый с новой строки.
 *
 * Как работает:
 *   1. Запрос уходит на «предпочтительный» адрес — последний рабочий
 *      (ApiEndpointStore) или первый из Remote Config, если такового ещё нет.
 *   2. Если запрос упал по сети (таймаут / connection refused / TLS — то есть
 *      домен заблокирован), интерцептор молча пробует следующий адрес из
 *      списка, и так пока не получит ответ. HTTP-ошибки (401/500 и т.п.)
 *      перебор НЕ запускают — раз пришёл ответ, API жив.
 *   3. Найденный рабочий адрес сохраняется в ApiEndpointStore: все последующие
 *      запросы идут сразу на него, выбор переживает перезапуск приложения,
 *      а оплата следует за рабочим доменом (см. RemoteConfigManager.getPaymentUrl).
 *
 * Смена хоста выполняется на каждом запросе (scheme/host/port), поэтому
 * новый адрес из Remote Config подхватывается и внутри текущей сессии.
 * Пути у всех адресов должны совпадать — меняется только домен.
 *
 * Интерцептор должен стоять ПЕРВЫМ в цепочке, до подписи запроса
 * (ModifyRequestInterceptor), чтобы подпись считалась по итоговому хосту.
 */
class ApiFailoverInterceptor(
    private val context: Context
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        val candidates = RemoteConfigManager.getApiBaseUrls(context)
            .mapNotNull { it.toHttpUrlOrNull() }
            .distinct()
        if (candidates.isEmpty()) return chain.proceed(request)

        // Порядок: последний рабочий, затем все из Remote Config по порядку.
        val preferred = ApiEndpointStore.getPreferred(context)?.toHttpUrlOrNull()
        val ordered = (listOfNotNull(preferred) + candidates).distinct()

        var lastError: IOException? = null
        for (base in ordered) {
            val newUrl = request.url.newBuilder()
                .scheme(base.scheme)
                .host(base.host)
                .port(base.port)
                .build()
            val newRequest = if (newUrl == request.url) request
            else request.newBuilder().url(newUrl).build()

            try {
                val response = chain.proceed(newRequest)
                if (preferred != null && base != preferred) {
                    Timber.i("ApiFailover: рабочий адрес API — %s", base)
                }
                if (base.toString() != preferred?.toString()) {
                    ApiEndpointStore.setPreferred(context, base.toString())
                }
                return response
            } catch (e: IOException) {
                lastError = e
                Timber.w(
                    e, "ApiFailover: %s недоступен (%s), пробую следующий адрес",
                    base.host, e.javaClass.simpleName
                )
            }
        }
        throw lastError ?: IOException("ApiFailover: все адреса API недоступны")
    }
}
