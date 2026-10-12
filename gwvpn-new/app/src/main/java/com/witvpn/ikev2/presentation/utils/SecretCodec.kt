package com.witvpn.ikev2.presentation.utils

import android.util.Base64

/**
 * Кодирование URL, чтобы в APK (resources/BuildConfig) и в JSON Remote Config
 * не было открытых строк https://... — именно их ищут DPI-парсеры и реверсеры.
 *
 * Формат: gwenc1: + base64( reverse( ((plain[i] XOR key[i mod len]) + 0x5D) mod 256 ) )
 * [encode] нужен только для генерации значений в консоль Firebase / дефолты;
 * в рантайме приложение вызывает [decode] непосредственно перед использованием.
 */
object SecretCodec {

    private const val PREFIX = "gwenc1:"
    private const val OFFSET = 0x5D

    private val KEY = intArrayOf(
        0x37, 0x5B, 0x71, 0x2E, 0x49, 0x6D, 0x23, 0x58,
        0x0F, 0x66, 0x3C, 0x7A, 0x11, 0x44, 0x69, 0x05
    )

    /**
     * Расшифровка значения. Значения без префикса (plaintext из старых версий
     * консоли) и битые данные возвращаются как есть — приложение никогда
     * не остаётся без URL.
     */
    fun decode(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (!value.startsWith(PREFIX)) return value
        return try {
            val data = Base64.decode(value.removePrefix(PREFIX), Base64.DEFAULT)
            val out = ByteArray(data.size)
            for (i in data.indices) {
                val shifted = (data[data.size - 1 - i].toInt() and 0xFF) - OFFSET
                out[i] = ((shifted and 0xFF) xor KEY[i % KEY.size]).toByte()
            }
            String(out, Charsets.UTF_8)
        } catch (e: Throwable) {
            value
        }
    }

    /** Для генерации новых закодированных значений (консоль Firebase, дефолты). */
    fun encode(plain: String): String {
        val data = plain.toByteArray(Charsets.UTF_8)
        val out = ByteArray(data.size)
        for (i in data.indices) {
            val shifted = ((data[i].toInt() xor KEY[i % KEY.size]) + OFFSET) and 0xFF
            out[data.size - 1 - i] = shifted.toByte()
        }
        return PREFIX + Base64.encodeToString(out, Base64.NO_WRAP)
    }
}
