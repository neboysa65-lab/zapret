package com.witvpn.ikev2.presentation.utils.interceptor

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber

/**
 * Перенаправляет исходящие запросы на актуальный домен API «на лету».
 *
 * Retrofit фиксирует baseUrl в момент создания клиента (один раз за запуск).
 * Этот интерцептор позволяет применить новый домен из Remote Config уже в
 * ТЕКУЩЕЙ сессии, не дожидаясь перезапуска приложения: перед отправкой каждого
 * запроса он подменяет scheme/host/port на те, что заданы в актуальном
 * api_base_url.
 *
 * Смена префикса пути (напр. .../vpn/api/v1/ -> .../api/v2/) применится при
 * следующем холодном старте, когда Retrofit пересоберётся с новым baseUrl.
 * Для основного сценария («домен заблокировали — сменили адрес в панели»)
 * достаточно подмены хоста, которая работает мгновенно.
 *
 * @param currentBaseUrlProvider лямбда, возвращающая актуальный базовый URL API.
 */
class DynamicBaseUrlInterceptor(
    private val currentBaseUrlProvider: () -> String
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        val configured = currentBaseUrlProvider().toHttpUrlOrNull()
            ?: return chain.proceed(request) // некорректный конфиг — не трогаем запрос

        val originalUrl = request.url

        // Уже на нужном хосте — ничего не меняем.
        if (originalUrl.host == configured.host &&
            originalUrl.scheme == configured.scheme &&
            originalUrl.port == configured.port
        ) {
            return chain.proceed(request)
        }

        val newUrl = originalUrl.newBuilder()
            .scheme(configured.scheme)
            .host(configured.host)
            .port(configured.port)
            .build()

        Timber.d("DynamicBaseUrl: %s -> %s", originalUrl.host, configured.host)

        val newRequest = request.newBuilder()
            .url(newUrl)
            .build()

        return chain.proceed(newRequest)
    }
}
