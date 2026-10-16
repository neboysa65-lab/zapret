package com.witvpn.ikev2.presentation.utils

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.witvpn.ikev2.BuildConfig
import timber.log.Timber
import java.util.Locale

/** Сравнение versionName вида 3.1.4 / 3.1.10 / v3.2-beta (суффиксы игнорируются). */
object AppVersion {
    private val VALID = Regex("^[vV]?\\d+(\\.\\d+)*")

    fun isValid(version: String?): Boolean = version != null && VALID.containsMatchIn(version.trim())

    /** > 0 — [a] новее [b], 0 — равны, < 0 — [a] старше. */
    fun compare(a: String, b: String): Int {
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    private fun parts(version: String): List<Int> =
        (VALID.find(version.trim())?.value ?: "0")
            .trimStart('v', 'V')
            .split('.')
            .map { it.toIntOrNull() ?: 0 }
}

enum class UpdateKind { NONE, OPTIONAL, REQUIRED }

data class UpdateInfo(
    val kind: UpdateKind,
    val installedVersion: String,
    val targetVersion: String,
    val updateUrl: String,
    val notes: String,
)

object AppUpdatePrompt {

    /**
     * REQUIRED — установленная версия ниже [minSupported];
     * OPTIONAL — ниже [latest]; иначе NONE. Невалидные значения игнорируются.
     */
    fun evaluate(installed: String, latest: String?, minSupported: String?): UpdateKind {
        if (AppVersion.isValid(minSupported) && AppVersion.compare(installed, minSupported!!) < 0) {
            return UpdateKind.REQUIRED
        }
        if (AppVersion.isValid(latest) && AppVersion.compare(latest!!, installed) > 0) {
            return UpdateKind.OPTIONAL
        }
        return UpdateKind.NONE
    }

    /** Данные для окна или null, если обновлять не нужно. */
    fun resolve(context: Context): UpdateInfo? {
        val cfg = RemoteConfigManager.getUpdateConfig(context)
        val installed = BuildConfig.VERSION_NAME
        val kind = evaluate(installed, cfg.latestVersion, cfg.minSupportedVersion)
        if (kind == UpdateKind.NONE) return null

        val latest = cfg.latestVersion.trim()
        val target = if (AppVersion.isValid(latest) && AppVersion.compare(latest, installed) > 0) {
            latest
        } else {
            cfg.minSupportedVersion.trim()
        }
        val isRu = context.resources.configuration.locales[0]?.language
            ?.lowercase(Locale.ROOT) == "ru"
        val notes = if (isRu) cfg.notesRu.ifBlank { cfg.notesEn } else cfg.notesEn
        return UpdateInfo(
            kind = kind,
            installedVersion = installed,
            targetVersion = target.trimStart('v', 'V'),
            updateUrl = cfg.updateUrl.trim(),
            // В консоли Firebase перенос строки удобно писать как \n
            notes = notes.replace("\\n", "\n").trim(),
        )
    }

    /**
     * Открыть страницу обновления: свой URL из Remote Config, затем Google Play
     * (приложение), затем Play в браузере. false — открыть нечем.
     */
    fun openStore(context: Context, customUrl: String?): Boolean {
        val pkg = context.packageName
        val intents = buildList {
            customUrl?.takeIf { it.isNotBlank() }?.let {
                add(Intent(Intent.ACTION_VIEW, Uri.parse(it)))
            }
            add(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
                    .setPackage("com.android.vending")
            )
            add(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")))
        }
        for (intent in intents) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: ActivityNotFoundException) {
                Timber.w("Update: no handler for ${intent.data?.scheme}")
            } catch (e: Exception) {
                Timber.w(e, "Update: failed to open store")
            }
        }
        return false
    }
}
