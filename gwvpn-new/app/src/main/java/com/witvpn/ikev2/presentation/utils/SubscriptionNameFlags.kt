package com.witvpn.ikev2.presentation.utils

/**
 * Достаёт emoji-флаг из названия сервера подписки (например «🇩🇪 GWAPP DE01»)
 * и возвращает ISO-код страны (de) + имя без флага.
 */
object SubscriptionNameFlags {

    data class Parsed(
        /** ISO 3166-1 alpha-2 lowercase, или null если флага нет */
        val countryCode: String?,
        /** Имя без emoji-флага в начале */
        val displayName: String,
    )

    private const val RI_START = 0x1F1E6 // 🇦
    private const val RI_END = 0x1F1FF   // 🇿

    fun parse(rawName: String?): Parsed {
        val name = rawName?.trim().orEmpty()
        if (name.isEmpty()) return Parsed(null, "")

        val cp0 = name.codePointAt(0)
        if (cp0 !in RI_START..RI_END) {
            return Parsed(null, name)
        }
        val len0 = Character.charCount(cp0)
        if (name.length <= len0) {
            return Parsed(null, name)
        }
        val cp1 = name.codePointAt(len0)
        if (cp1 !in RI_START..RI_END) {
            return Parsed(null, name)
        }
        val len1 = Character.charCount(cp1)
        val c1 = ('a'.code + (cp0 - RI_START)).toChar()
        val c2 = ('a'.code + (cp1 - RI_START)).toChar()
        val code = "$c1$c2"
        val rest = name.substring(len0 + len1).trim()
        return Parsed(code, if (rest.isNotEmpty()) rest else name)
    }

}
