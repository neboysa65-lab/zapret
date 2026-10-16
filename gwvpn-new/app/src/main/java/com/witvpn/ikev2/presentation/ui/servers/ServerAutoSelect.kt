package com.witvpn.ikev2.presentation.ui.servers

import com.witvpn.ikev2.presentation.utils.getBooleanPref
import com.witvpn.ikev2.presentation.utils.putBooleanPref
import java.util.Locale

/**
 * «Автовыбор» в списке серверов: флаг хранится отдельно для каждого протокола
 * (GW / AWG / VLESS), сервер выбирается по наименьшей нагрузке.
 */
object ServerAutoSelect {

    /** Разница нагрузки (в п.п.), ради которой стоит уходить с текущего сервера. */
    const val SWITCH_MARGIN_PERCENT = 5

    private fun prefKey(protocol: String) = "AutoServer_" + protocol.uppercase(Locale.ROOT)

    fun isEnabled(protocol: String): Boolean = getBooleanPref(prefKey(protocol), false)

    fun setEnabled(protocol: String, enabled: Boolean) {
        putBooleanPref(prefKey(protocol), enabled)
    }

    /**
     * Сервер с минимальной известной нагрузкой. Если нагрузка не известна ни для
     * одного кандидата — остаёмся на текущем (или берём первый). Текущий сервер
     * сохраняется, пока он хуже лучшего не более чем на [SWITCH_MARGIN_PERCENT],
     * чтобы не прыгать между узлами из-за пары процентов.
     */
    fun <T> pickBest(
        candidates: List<T>,
        current: T?,
        keyOf: (T) -> String?,
        loadOf: (T) -> Int?,
    ): T? {
        if (candidates.isEmpty()) return null
        val currentKey = current?.let(keyOf)
        val currentInList = currentKey?.let { key -> candidates.firstOrNull { keyOf(it) == key } }

        val withLoad = candidates.mapNotNull { c -> loadOf(c)?.let { c to it.coerceIn(0, 100) } }
        if (withLoad.isEmpty()) return currentInList ?: candidates.first()

        val (best, bestLoad) = withLoad.minByOrNull { it.second }!!
        val currentLoad = currentInList?.let(loadOf)?.coerceIn(0, 100)
        if (currentInList != null && currentLoad != null &&
            currentLoad - bestLoad <= SWITCH_MARGIN_PERCENT
        ) {
            return currentInList
        }
        return best
    }
}
