package com.witvpn.ikev2.presentation.utils

import android.content.Context
import com.google.firebase.ktx.Firebase
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings
import com.witvpn.ikev2.BuildConfig
import com.witvpn.ikev2.R
import timber.log.Timber

/**
 * Централизованный источник «живых» доменов приложения.
 *
 * Раньше домены были зашиты в build.gradle / коде:
 *   - base_url          (resValue)      -> API
 *   - GRAYS_BILLING_URL (BuildConfig)   -> оплата
 *   - SUBSCRIPTION_URL  (VlessManager)  -> подписка со списком vless/hy2 нод
 *
 * Теперь актуальные адреса приходят из Firebase Remote Config и могут быть
 * изменены из консоли Firebase без выпуска обновления в Google Play.
 *
 * Логика получения значения (fallback-цепочка, всегда возвращает что-то валидное):
 *   1) Активное значение из Firebase Remote Config (если Firebase доступен).
 *   2) Последнее успешно применённое значение из SharedPreferences
 *      (чтобы уже на «холодном старте», ДО завершения сетевого fetch,
 *       использовать актуальный домен с прошлого запуска).
 *   3) Значение по умолчанию, зашитое в ресурсы/BuildConfig (старое поведение).
 *
 * Ключи Remote Config (их и надо менять в панели Firebase):
 *   - api_base_url     — базовый URL API, напр. https://api.example.com/vpn/api/v1/
 *   - payment_url      — URL оплаты с плейсхолдером %s для userId
 *   - subscription_url — URL подписки со списком vless/hysteria2 нод
 */
object RemoteConfigManager {

    const val KEY_API_BASE_URL = "api_base_url"
    const val KEY_PAYMENT_URL = "payment_url"
    const val KEY_SUBSCRIPTION_URL = "subscription_url"

    private const val PREFS_NAME = "remote_config_cache"

    // Как часто разрешаем реально ходить в сеть за конфигом.
    // В debug — сразу (0), в release — раз в час, чтобы не упираться в троттлинг Firebase.
    private val MIN_FETCH_INTERVAL_SECONDS: Long =
        if (BuildConfig.DEBUG) 0L else 3600L

    @Volatile
    private var firebaseAvailable = false

    private var remoteConfig: FirebaseRemoteConfig? = null

    /**
     * Инициализация + первичная загрузка конфига. Вызывать один раз из
     * Application.onCreate(). Метод неблокирующий: сеть подтягивается в фоне,
     * а до её завершения работают закэшированные значения / дефолты.
     */
    fun init(context: Context) {
        try {
            val rc = Firebase.remoteConfig
            rc.setConfigSettingsAsync(
                remoteConfigSettings {
                    minimumFetchIntervalInSeconds = MIN_FETCH_INTERVAL_SECONDS
                }
            )
            // Дефолты из res/xml/remote_config_defaults.xml — совпадают со старыми
            // «зашитыми» доменами, чтобы приложение работало и без сети.
            rc.setDefaultsAsync(R.xml.remote_config_defaults)
            remoteConfig = rc
            firebaseAvailable = true

            rc.fetchAndActivate()
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        Timber.d("RemoteConfig: fetch/activate ok (updated=${task.result})")
                        // Кэшируем актуальные значения, чтобы они были доступны
                        // синхронно уже на следующем холодном старте.
                        persist(context, KEY_API_BASE_URL, rc.getString(KEY_API_BASE_URL))
                        persist(context, KEY_PAYMENT_URL, rc.getString(KEY_PAYMENT_URL))
                        persist(context, KEY_SUBSCRIPTION_URL, rc.getString(KEY_SUBSCRIPTION_URL))
                    } else {
                        Timber.w(task.exception, "RemoteConfig: fetch/activate failed")
                    }
                }
        } catch (e: Throwable) {
            // Firebase может быть недоступен (нет google-services.json и т.п.) —
            // тогда просто работаем на кэше/дефолтах, ничего не падает.
            firebaseAvailable = false
            Timber.e(e, "RemoteConfig: init failed, using cached/default domains")
        }
    }

    /**
     * Базовый URL API. Всегда заканчивается на "/" — этого требует Retrofit.
     */
    fun getApiBaseUrl(context: Context): String {
        val raw = resolve(
            context,
            key = KEY_API_BASE_URL,
            resourceDefault = context.getString(R.string.base_url)
        )
        return if (raw.endsWith("/")) raw else "$raw/"
    }

    /**
     * URL оплаты. Содержит плейсхолдер %s для подстановки userId.
     */
    fun getPaymentUrl(context: Context): String {
        return resolve(
            context,
            key = KEY_PAYMENT_URL,
            resourceDefault = BuildConfig.GRAYS_BILLING_URL
        )
    }

    /**
     * URL подписки со списком рабочих vless/hysteria2 нод.
     *
     * Даёт возможность заменить домен подписки «на лету» из консоли Firebase
     * (ключ subscription_url), если текущий адрес заблокируют, — без выпуска
     * нового билда в Google Play. Дефолт совпадает с прежним «зашитым»
     * значением из VlessManager (res string subscription_url).
     */
    fun getSubscriptionUrl(context: Context): String {
        return resolve(
            context,
            key = KEY_SUBSCRIPTION_URL,
            resourceDefault = context.getString(R.string.subscription_url)
        )
    }

    // --- внутреннее ---

    private fun resolve(context: Context, key: String, resourceDefault: String): String {
        // 1) Firebase Remote Config (активное значение)
        if (firebaseAvailable) {
            try {
                val remote = remoteConfig?.getString(key).orEmpty()
                if (remote.isNotBlank()) return remote
            } catch (e: Throwable) {
                Timber.e(e, "RemoteConfig: read '$key' failed")
            }
        }
        // 2) Последнее применённое значение из кэша
        val cached = context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(key, null)
        if (!cached.isNullOrBlank()) return cached

        // 3) Дефолт (старое поведение)
        return resourceDefault
    }

    private fun persist(context: Context, key: String, value: String) {
        if (value.isBlank()) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(key, value)
            .apply()
    }
}
