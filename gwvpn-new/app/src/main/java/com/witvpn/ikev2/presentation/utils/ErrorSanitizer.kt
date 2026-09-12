package com.witvpn.ikev2.presentation.utils

/**
 * Зачистка текстов ошибок перед показом пользователю.
 *
 * Java-стек сетевых исключений (UnknownHostException, ConnectException и т.п.)
 * содержит hostname/IP API-сервера: без зачистки тост при отсутствии интернета
 * показывает юзеру реальный адрес бэкенда («Unable to resolve host
 * "api-…": No address associated with hostname»). То же — в ошибках GW-туннеля,
 * где светится IP ноды.
 */
object ErrorSanitizer {

    private val URL_REGEX = Regex("(?i)\\bhttps?://\\S+")
    private val HOST_REGEX = Regex("(?i)\\b(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z]{2,}\\b")
    private val IP_REGEX = Regex("\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b")

    // Признаки сетевой ошибки: текст таких исключений целиком заменяем понятной
    // фразой (error_no_internet), а не выковыриваем хост по кусочкам.
    private val NETWORK_ERROR_PATTERNS = listOf(
        "unable to resolve host",
        "no address associated with hostname",
        "failed to connect",
        "connection refused",
        "connection reset",
        "connection timed out",
        "timed out",
        "timeout",
        "software caused connection abort",
        "network is unreachable",
        "network unreachable",
        "network error",
        "broken pipe",
        "unexpected end of stream",
        "hostname not verified",
        "handshake",
        "socket",
        "ssl",
        "econnrefused",
        "econnreset",
        "econnaborted",
        "enetunreach",
        "ehostunreach",
        "etimedout",
    )

    fun isNetworkError(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        val lowered = message.lowercase()
        return NETWORK_ERROR_PATTERNS.any { it in lowered }
    }

    /**
     * Убирает из текста URL/домены/IP, остальное оставляет как есть.
     * Человеческие сообщения бэкенда (ApiExceptionInterceptor) не содержат
     * адресов и проходят насквозь без изменений.
     */
    fun stripEndpoints(message: String?): String? {
        if (message.isNullOrBlank()) return message
        var out = URL_REGEX.replace(message, "server")
        out = HOST_REGEX.replace(out, "server")
        out = IP_REGEX.replace(out, "server")
        return out
    }
}
