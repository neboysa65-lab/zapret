package com.witvpn.ikev2.presentation.utils.interceptor

import okhttp3.Interceptor
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import timber.log.Timber
import java.io.IOException

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
 * Авто-ротация wildcard-субдомена: если в Remote Config задана маска вида
 * "*.api.example.com" (схема https), то каждый запрос идёт на случайный субдомен из
 * RemoteConfigManager. Сетевая ошибка (домен заблокировали) означает, что
 * текущий субдомен больше не работает: запрашиваем новый случайный и
 * повторяем запрос. Количество смен субдоменов за жизнь приложения не
 * ограничено; ограничено только число попыток в рамках ОДНОГО запроса, чтобы
 * не зациклиться при полном блоке сети.
 *
 * @param currentBaseUrlProvider лямбда, возвращающая актуальный базовый URL API.
 * @param rotateBaseUrl лямбда ротации субдомена: возвращает новый базовый URL
 *        либо null, если в конфиге нет wildcard-маски (ротировать нечего).
 */
class DynamicBaseUrlInterceptor(
    private val currentBaseUrlProvider: () -> String,
    private val rotateBaseUrl: () -> String? = { null }
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var configured = currentBaseUrlProvider().toHttpUrlOrNull()
            ?: return chain.proceed(request) // некорректный конфиг — не трогаем запрос

        var attempt = 0
        while (true) {
            try {
                return chain.proceed(redirected(request, configured))
            } catch (e: IOException) {
                attempt++
                val next = if (attempt < MAX_ROTATION_ATTEMPTS) rotateBaseUrl() else null
                val nextUrl = next?.toHttpUrlOrNull()
                if (nextUrl == null || isSameEndpoint(nextUrl, configured)) {
                    // Ротации нет или она не меняет хост — отдаём исходную ошибку.
                    throw e
                }
                Timber.w(e, "DynamicBaseUrl: %s недоступен, пробуем %s", configured.host, nextUrl.host)
                configured = nextUrl
            }
        }
    }

    private fun redirected(request: Request, configured: HttpUrl): Request {
        val originalUrl = request.url

        // Уже на нужном хосте — ничего не меняем.
        if (isSameEndpoint(originalUrl, configured)) {
            return request
        }

        val newUrl = originalUrl.newBuilder()
            .scheme(configured.scheme)
            .host(configured.host)
            .port(configured.port)
            .build()

        Timber.d("DynamicBaseUrl: %s -> %s", originalUrl.host, configured.host)

        return request.newBuilder()
            .url(newUrl)
            .build()
    }

    private fun isSameEndpoint(a: HttpUrl, b: HttpUrl): Boolean =
        a.host == b.host && a.scheme == b.scheme && a.port == b.port

    private companion object {
        // Попыток на один запрос (исходная + ротации). Глобальных лимитов на
        // количество смен субдомена нет.
        const val MAX_ROTATION_ATTEMPTS = 5
    }
}
