package com.witvpn.ikev2.presentation.utils

import android.content.Context
import com.google.firebase.ktx.Firebase
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings
import com.witvpn.ikev2.BuildConfig
import com.witvpn.ikev2.R
import timber.log.Timber
import java.security.SecureRandom

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
 * Ключи Remote Config:
 *   - encrypted_api_url          — базовый URL API (формат gwenc1, SecretCodec)
 *   - encrypted_payment_url      — URL оплаты с %s для userId (gwenc1)
 *   - encrypted_subscription_url — URL подписки (gwenc1)
 *   - heartbeat_enabled / heartbeat_interval_seconds — управление heartbeat
 *
 * URL новый клиент читает ТОЛЬКО из encrypted_*: открытые api_base_url /
 * payment_url / subscription_url (нужны старым версиям) намеренно
 * игнорируются — в JSON Remote Config не должно быть URL, который выдают
 * новые версии. Смена домена теперь только через encrypted_* (или дефолт APK).
 *   - heartbeat_enabled         — вкл/выкл периодического heartbeat (true/false)
 *   - heartbeat_interval_seconds — период heartbeat в секундах (зажимается 15..240)
 *
 * URL-значения в консоли и в дефолтах APK хранятся закодированными
 * (формат gwenc1:..., см. SecretCodec) — в Remote Config JSON и в билде нет
 * открытых https:// строк. decode() принимает и открытый текст, поэтому
 * значения в консоли можно менять постепенно.
 * ВАЖНО: переводить консоль на закодированные значения можно только когда
 * старые версии приложения (без decode) почти полностью ушли из онлайна —
 * иначе они прочитают закодированную строку как URL и сломаются.
 *
 * Поддержка wildcard-масок (защита от блокировок домена):
 *   Вместо конкретного домена в api_base_url / payment_url можно указать маску
 *   вида "*.api.gwapp.store" со схемой https и путём /vpn/api/v1/ — приложение
 *   само сгенерирует случайный субдомен (напр. fE53Hkf92H.api.gwapp.store) и будет
 *   им пользоваться. Если хост перестал отвечать (заблокировали) — при первом
 *   же сетевом сбое генерируется НОВЫЙ случайный субдомен и запрос повторяется
 *   (ротацию выполняет DynamicBaseUrlInterceptor). Количество смен субдомена
 *   не ограничено. Выбранный субдомен сохраняется в SharedPreferences и
 *   переживает перезапуск приложения, пока работает.
 */
object RemoteConfigManager {

    const val KEY_ENCRYPTED_API_URL = "encrypted_api_url"
    const val KEY_ENCRYPTED_PAYMENT_URL = "encrypted_payment_url"
    const val KEY_ENCRYPTED_SUBSCRIPTION_URL = "encrypted_subscription_url"
    const val KEY_HEARTBEAT_ENABLED = "heartbeat_enabled"
    const val KEY_HEARTBEAT_INTERVAL_SECONDS = "heartbeat_interval_seconds"

    private const val MIN_HEARTBEAT_SECONDS = 15L
    private const val MAX_HEARTBEAT_SECONDS = 240L
    private const val DEFAULT_HEARTBEAT_INTERVAL_SECONDS = 60L

    private const val PREFS_NAME = "remote_config_cache"
    private const val PREF_WILDCARD_LABEL = "wildcard_subdomain_label"

    // Длина случайного субдомена для wildcard-масок (*.api.example.com).
    private const val WILDCARD_LABEL_LENGTH = 10
    private const val WILDCARD_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

    private val random = SecureRandom()

    // Как часто разрешаем реально ходить в сеть за конфигом.
    // В debug — сразу (0), в release — раз в час, чтобы не упираться в троттлинг Firebase.
    private val MIN_FETCH_INTERVAL_SECONDS: Long =
        if (BuildConfig.DEBUG) 0L else 3600L

    @Volatile
    private var firebaseAvailable = false

    private var remoteConfig: FirebaseRemoteConfig? = null

    @Volatile
    private var cachedWildcardLabel: String? = null

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
                        persist(context, KEY_ENCRYPTED_API_URL, rc.getString(KEY_ENCRYPTED_API_URL))
                        persist(context, KEY_ENCRYPTED_PAYMENT_URL, rc.getString(KEY_ENCRYPTED_PAYMENT_URL))
                        persist(context, KEY_ENCRYPTED_SUBSCRIPTION_URL, rc.getString(KEY_ENCRYPTED_SUBSCRIPTION_URL))
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
        val raw = resolveUrl(
            context,
            encryptedKey = KEY_ENCRYPTED_API_URL,
            resourceDefault = context.getString(R.string.base_url)
        )
        val expanded = expandWildcardTemplate(raw, context)
        return if (expanded.endsWith("/")) expanded else "$expanded/"
    }

    /**
     * URL оплаты. Содержит плейсхолдер %s для подстановки userId.
     */
    fun getPaymentUrl(context: Context): String {
        return expandWildcardTemplate(
            resolveUrl(
                context,
                encryptedKey = KEY_ENCRYPTED_PAYMENT_URL,
                resourceDefault = BuildConfig.GRAYS_BILLING_URL
            ),
            context
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
        return resolveUrl(
            context,
            encryptedKey = KEY_ENCRYPTED_SUBSCRIPTION_URL,
            resourceDefault = context.getString(R.string.subscription_url)
        )
    }

    /**
     * Включён ли heartbeat (периодические action=heartbeat). Выключение
     * останавливает только периодические запросы; connect/disconnect уходят
     * всегда, иначе счётчик онлайна на бэкенде «зависает».
     */
    fun isHeartbeatEnabled(context: Context): Boolean {
        val raw = resolve(context, KEY_HEARTBEAT_ENABLED, "true")
        return raw.trim().lowercase() !in setOf("false", "0", "off")
    }

    /**
     * Период heartbeat в секундах. Зажат в [15..240]: снизу — защита от
     * флуда в WAF при опечатке в консоли, сверху — окно онлайна на бэкенде
     * (ONLINE_WINDOW_MINUTES = 5 минут), больший интервал роняет счётчик.
     */
    fun getHeartbeatIntervalSeconds(context: Context): Long {
        val raw = resolve(
            context,
            key = KEY_HEARTBEAT_INTERVAL_SECONDS,
            resourceDefault = DEFAULT_HEARTBEAT_INTERVAL_SECONDS.toString()
        )
        return raw.trim().toLongOrNull()?.coerceIn(MIN_HEARTBEAT_SECONDS, MAX_HEARTBEAT_SECONDS)
            ?: DEFAULT_HEARTBEAT_INTERVAL_SECONDS
    }

    /**
     * Ротация случайного субдомена wildcard-маски + возврат нового api_base_url.
     * Вернёт null, если в конфиге нет маски — тогда ротировать нечего.
     */
    fun rotateWildcardAndReturnApiBaseUrl(context: Context): String? {
        val raw = resolveUrl(
            context,
            encryptedKey = KEY_ENCRYPTED_API_URL,
            resourceDefault = context.getString(R.string.base_url)
        )
        if (!isWildcardTemplate(raw)) return null
        val newLabel = generateAndPersistLabel(context)
        Timber.d("RemoteConfig: rotated wildcard subdomain -> %s", newLabel)
        return getApiBaseUrl(context)
    }

    // --- внутреннее ---

    /**
     * Чтение URL ТОЛЬКО из encrypted_* ключа с валидацией расшифровки.
     * Открытые ключи (api_base_url и т.п.) намеренно не читаются: их значения
     * в JSON Remote Config видны перехватчикам, а в новых версиях их быть
     * не должно. Битое значение пропускается — работает следующий источник.
     */
    private fun resolveUrl(
        context: Context,
        encryptedKey: String,
        resourceDefault: String
    ): String {
        if (firebaseAvailable) {
            try {
                val raw = remoteConfig?.getString(encryptedKey).orEmpty()
                if (raw.isNotBlank()) {
                    val decoded = SecretCodec.decode(raw)
                    if (isValidDecoded(raw, decoded)) return decoded
                    Timber.w("RemoteConfig: '$encryptedKey' has broken encoded value, using cache/default")
                }
            } catch (e: Throwable) {
                Timber.e(e, "RemoteConfig: read URL keys failed")
            }
        }
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cached = stored.getString(encryptedKey, null)
        if (!cached.isNullOrBlank()) {
            val decoded = SecretCodec.decode(cached)
            if (isValidDecoded(cached, decoded)) return decoded
        }
        return SecretCodec.decode(resourceDefault)
    }

    private fun resolve(context: Context, key: String, resourceDefault: String): String {
        // Для не-URL значений (флаги/числа): Firebase -> кэш -> дефолт.
        if (firebaseAvailable) {
            try {
                val remote = remoteConfig?.getString(key).orEmpty()
                if (remote.isNotBlank()) return remote.trim()
            } catch (e: Throwable) {
                Timber.e(e, "RemoteConfig: read '$key' failed")
            }
        }
        val cached = context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(key, null)
        if (!cached.isNullOrBlank()) return cached.trim()
        return resourceDefault
    }

    /**
     * Расшифрованное закодированное значение обязано быть URL. Значения без
     * префикса (plaintext legacy, флаги, числа) проходят всегда.
     */
    private fun isValidDecoded(raw: String, decoded: String): Boolean =
        !SecretCodec.isEncoded(raw) || decoded.contains("://")

    private fun persist(context: Context, key: String, value: String) {
        if (value.isBlank()) return
        prefs(context).edit()
            .putString(key, value)
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- wildcard-маски ---

    private fun isWildcardTemplate(url: String): Boolean = wildcardHost(url).contains("*")

    private fun wildcardHost(url: String): String =
        url.substringAfter("://", url).substringBefore('/')

    /**
     * Заменяет '*' в host-части на текущий случайный субдомен.
     * Маска без схемы (напр. *.api.gwapp.store) считается https.
     */
    private fun expandWildcardTemplate(rawUrl: String, context: Context): String {
        if (!isWildcardTemplate(rawUrl)) return rawUrl

        val scheme = if (rawUrl.contains("://")) rawUrl.substringBefore("://") else "https"
        val rest = rawUrl.substringAfter("://", rawUrl)
        val host = rest.substringBefore('/')
        val path = rest.substringAfter('/', "")
        val expandedHost = host.replace("*", getCurrentWildcardLabel(context))
        return if (path.isEmpty()) "$scheme://$expandedHost" else "$scheme://$expandedHost/$path"
    }

    private fun getCurrentWildcardLabel(context: Context): String {
        cachedWildcardLabel?.let { return it }
        val stored = prefs(context).getString(PREF_WILDCARD_LABEL, null)
        if (!stored.isNullOrBlank()) {
            cachedWildcardLabel = stored
            return stored
        }
        return generateAndPersistLabel(context)
    }

    private fun generateAndPersistLabel(context: Context): String {
        val sb = StringBuilder(WILDCARD_LABEL_LENGTH)
        // Первый символ — буква: некоторые резолверы плохо живут с label-цифрой в начале.
        sb.append(WILDCARD_ALPHABET[random.nextInt(26)])
        repeat(WILDCARD_LABEL_LENGTH - 1) {
            sb.append(WILDCARD_ALPHABET[random.nextInt(WILDCARD_ALPHABET.length)])
        }
        val label = sb.toString()
        prefs(context).edit().putString(PREF_WILDCARD_LABEL, label).apply()
        cachedWildcardLabel = label
        return label
    }
}
