package com.witvpn.ikev2.presentation.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.firebase.ktx.Firebase
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings
import com.witvpn.ikev2.BuildConfig
import com.witvpn.ikev2.R
import timber.log.Timber
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
 *   - update_latest_version / update_min_version / update_url /
 *     update_notes_ru / update_notes_en — окно «Доступно обновление»
 *     (см. AppUpdatePrompt)
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

    // Окно обновления: пустое значение = выключено.
    const val KEY_UPDATE_LATEST_VERSION = "update_latest_version"
    const val KEY_UPDATE_MIN_VERSION = "update_min_version"
    const val KEY_UPDATE_URL = "update_url"
    const val KEY_UPDATE_NOTES_RU = "update_notes_ru"
    const val KEY_UPDATE_NOTES_EN = "update_notes_en"

    // Сколько ждём первый fetch, прежде чем работать на уже активированном
    // (с прошлого запуска) конфиге.
    private const val INITIAL_FETCH_WAIT_MS = 8_000L

    // Оба активных бэкенд-URL (RU = media-net, не-RU = 45.95.96.203) в gwenc1:
    // используются как автоматический failover, если основной домен из Remote
    // Config/кэша временно недоступен или сработал гео-флип при активном VPN.
    private const val FALLBACK_RU_API_URL_ENC =
        "gwenc1:smptfY6wacOdXop1z2vI0WWwb9nTsHqFtnPSoL1kxs1wtWC/1Gm0l7tijLw="
    private const val FALLBACK_NON_RU_API_URL_ENC =
        "gwenc1:u8eeh12RzbKvc9bUsWfDep7GdpCtx4Ggb7CY1Gm0l7tijLw="

    private const val MIN_HEARTBEAT_SECONDS = 15L
    private const val MAX_HEARTBEAT_SECONDS = 240L
    private const val DEFAULT_HEARTBEAT_INTERVAL_SECONDS = 60L

    private const val PREFS_NAME = "remote_config_cache"
    private const val PREF_WILDCARD_LABEL = "wildcard_subdomain_label"

    // Длина случайного субдомена для wildcard-масок (*.api.example.com).
    private const val WILDCARD_LABEL_LENGTH = 10
    private const val WILDCARD_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

    private val random = SecureRandom()

    // Безопасный интервал для продакшна (5 минут): при 0L на десятках тысяч
    // устройств проект упирается в часовую серверную квоту Firebase
    // (FirebaseRemoteConfigFetchThrottledException). Мгновенная доставка
    // изменений из консоли обеспечивается real-time listener'ом ниже
    // (addOnConfigUpdateListener обходит minimumFetchInterval официально).
    private val MIN_FETCH_INTERVAL_SECONDS: Long =
        if (BuildConfig.DEBUG) 0L else 300L

    private val refreshScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var refreshJob: Job? = null

    @Volatile
    private var firebaseAvailable = false

    private var remoteConfig: FirebaseRemoteConfig? = null

    @Volatile
    private var cachedWildcardLabel: String? = null

    // Если основной URL упал по сети и сработал failover на альтернативный
    // бэкенд (RU <-> не-RU), держим рабочий URL в памяти до следующего
    // успешного обновления Remote Config.
    @Volatile
    private var activeFailoverApiUrl: String? = null

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val readyLock = Any()
    private var initialFetchFinished = false // guarded by readyLock

    private val revisionCounter = AtomicLong(0)
    private val _configRevision = MutableLiveData<Long>()

    /**
     * Меняется после каждой активации конфига: первый fetch (или его таймаут),
     * real-time пуш из консоли, периодический и foreground re-fetch. Подписчики
     * (окно обновления) перечитывают значения — иначе живой процесс VPN-приложения
     * неделями показывал бы решение, принятое по старому конфигу.
     */
    val configRevision: LiveData<Long> = _configRevision

    private val foregroundFetchInFlight = AtomicBoolean(false)

    /**
     * Инициализация + первичная загрузка конфига. Вызывать один раз из
     * Application.onCreate(). Метод неблокирующий: сеть подтягивается в фоне,
     * а до её завершения работают закэшированные значения / дефолты.
     */
    fun init(context: Context) {
        val appContext = context.applicationContext
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
                        persistRemoteUrls(appContext, rc)
                    } else {
                        Timber.w(task.exception, "RemoteConfig: fetch/activate failed")
                    }
                    markInitialFetchFinished()
                    if (task.isSuccessful) bumpRevision()
                }
            mainHandler.postDelayed({ markInitialFetchFinished() }, INITIAL_FETCH_WAIT_MS)

            // Real-time обновления Remote Config: как только в консоли
            // публикуется новое значение, SDK получает пуш и активирует его
            // мгновенно, обходя minimumFetchInterval без риска троттлинга.
            runCatching {
                rc.addOnConfigUpdateListener(
                    object : com.google.firebase.remoteconfig.ConfigUpdateListener {
                        override fun onUpdate(configUpdate: com.google.firebase.remoteconfig.ConfigUpdate) {
                            rc.activate().addOnCompleteListener { task ->
                                if (task.isSuccessful) {
                                    activeFailoverApiUrl = null
                                    persistRemoteUrls(appContext, rc)
                                    bumpRevision()
                                }
                            }
                        }

                        override fun onError(error: com.google.firebase.remoteconfig.FirebaseRemoteConfigException) {
                            Timber.w(error, "RemoteConfig: real-time update error")
                        }
                    }
                )
            }
        } catch (e: Throwable) {
            // Firebase может быть недоступен (нет google-services.json и т.п.) —
            // тогда просто работаем на кэше/дефолтах, ничего не падает.
            firebaseAvailable = false
            Timber.e(e, "RemoteConfig: init failed, using cached/default domains")
            markInitialFetchFinished()
        }
    }

    /** Первый fetch завершён (успешно, с ошибкой или по таймауту). */
    fun isInitialFetchFinished(): Boolean = synchronized(readyLock) { initialFetchFinished }

    private fun markInitialFetchFinished() {
        synchronized(readyLock) {
            if (initialFetchFinished) return
            initialFetchFinished = true
        }
        bumpRevision()
    }

    private fun bumpRevision() {
        _configRevision.postValue(revisionCounter.incrementAndGet())
    }

    /**
     * Re-fetch при возврате приложения на экран: страховка, если real-time
     * канал недоступен (блокировки, сеть). SDK сам соблюдает
     * minimumFetchInterval — чаще раза в 5 минут в сеть не ходит, троттлинга нет.
     */
    fun refreshOnForeground(context: Context) {
        val rc = remoteConfig ?: return
        if (!firebaseAvailable || !isInitialFetchFinished()) return
        if (!foregroundFetchInFlight.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        try {
            rc.fetchAndActivate().addOnCompleteListener { task ->
                foregroundFetchInFlight.set(false)
                if (task.isSuccessful) {
                    persistRemoteUrls(appContext, rc)
                    bumpRevision()
                } else {
                    Timber.w(task.exception, "RemoteConfig: foreground fetch failed")
                }
            }
        } catch (e: Throwable) {
            foregroundFetchInFlight.set(false)
            Timber.w(e, "RemoteConfig: foreground fetch failed")
        }
    }

    /** Настройки окна обновления из Remote Config (пустые строки = не задано). */
    fun getUpdateConfig(context: Context): UpdateConfig = UpdateConfig(
        latestVersion = resolve(context, KEY_UPDATE_LATEST_VERSION, ""),
        minSupportedVersion = resolve(context, KEY_UPDATE_MIN_VERSION, ""),
        // URL можно задать открытым текстом или в gwenc1 (SecretCodec)
        updateUrl = resolve(context, KEY_UPDATE_URL, "")
            .takeIf { it.isNotBlank() }
            ?.let { SecretCodec.decode(it) }
            .orEmpty(),
        notesRu = resolve(context, KEY_UPDATE_NOTES_RU, ""),
        notesEn = resolve(context, KEY_UPDATE_NOTES_EN, ""),
    )

    data class UpdateConfig(
        val latestVersion: String,
        val minSupportedVersion: String,
        val updateUrl: String,
        val notesRu: String,
        val notesEn: String,
    )

    /**
     * Периодический re-fetch конфига в живом процессе: VPN-процесс живёт
     * днями, а до этого смена домена доезжала только после перезапуска
     * приложения. Первая серия — с удвоением паузы (до 10 минут), дальше
     * штатный цикл раз в 10 минут.
     */
    fun startPeriodicRefresh(context: Context) {
        if (refreshJob?.isActive == true) return
        val appContext = context.applicationContext
        refreshJob = refreshScope.launch {
            var delayMs = 30_000L
            while (isActive) {
                delay(delayMs)
                val ok = try {
                    val rc = remoteConfig
                    if (rc != null) {
                        Tasks.await(rc.fetchAndActivate(), 20, TimeUnit.SECONDS)
                        persistRemoteUrls(appContext, rc)
                        bumpRevision()
                        true
                    } else false
                } catch (it: Throwable) {
                    Timber.w(it, "RemoteConfig: periodic fetch failed")
                    false
                }
                delayMs = if (ok) 600_000L else (delayMs * 2).coerceAtMost(600_000L)
            }
        }
    }

    /**
     * Базовый URL API. Всегда заканчивается на "/" — этого требует Retrofit.
     */
    fun getApiBaseUrl(context: Context): String {
        activeFailoverApiUrl?.let { return normalizeTrailingSlash(it) }
        return getPrimaryApiBaseUrl(context)
    }

    private fun getPrimaryApiBaseUrl(context: Context): String {
        val raw = resolveUrl(
            context,
            encryptedKey = KEY_ENCRYPTED_API_URL,
            resourceDefault = context.getString(R.string.base_url)
        )
        val expanded = expandWildcardTemplate(raw, context)
        return normalizeTrailingSlash(expanded)
    }

    private fun normalizeTrailingSlash(url: String): String =
        if (url.endsWith("/")) url else "$url/"

    /**
     * Ротация/failover при сетевой ошибке:
     * 1) если в конфиге wildcard-маска (*.api...) — генерируем новый субдомен;
     * 2) если обычный URL (например, RU media-net или не-RU 45.95.96.203) и
     *    текущий хост недоступен (блокировка или гео-флип после VPN) —
     *    переключаемся на альтернативный бэкенд, чтобы запрос прошёл без ошибки.
     */
    fun rotateOrFallbackApiBaseUrl(context: Context, failedHost: String? = null): String? {
        val raw = resolveUrl(
            context,
            encryptedKey = KEY_ENCRYPTED_API_URL,
            resourceDefault = context.getString(R.string.base_url)
        )
        if (isWildcardTemplate(raw)) {
            val newLabel = generateAndPersistLabel(context)
            Timber.d("RemoteConfig: rotated wildcard subdomain -> %s", newLabel)
            activeFailoverApiUrl = null
            return getPrimaryApiBaseUrl(context)
        }
        val primary = getPrimaryApiBaseUrl(context)
        val ruFallback = normalizeTrailingSlash(SecretCodec.decode(FALLBACK_RU_API_URL_ENC))
        val nonRuFallback = normalizeTrailingSlash(SecretCodec.decode(FALLBACK_NON_RU_API_URL_ENC))
        val candidates = listOf(primary, ruFallback, nonRuFallback).distinct()
        val currentHost = failedHost ?: wildcardHost(getApiBaseUrl(context))
        val next = candidates.firstOrNull { candidate ->
            val host = wildcardHost(candidate)
            host.isNotEmpty() && !host.equals(currentHost, ignoreCase = true)
        } ?: return null
        activeFailoverApiUrl = next
        Timber.d("RemoteConfig: failover API base url -> %s", wildcardHost(next))
        return next
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
        return rotateOrFallbackApiBaseUrl(context)
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

    /**
     * Сохраняем в SharedPreferences ТОЛЬКО значения, реально пришедшие с
     * сервера Firebase (VALUE_SOURCE_REMOTE), чтобы дефолты из XML никогда
     * не затирали живой домен, полученный на прошлом запуске.
     */
    private fun persistRemoteUrls(context: Context, rc: FirebaseRemoteConfig) {
        for (key in listOf(KEY_ENCRYPTED_API_URL, KEY_ENCRYPTED_PAYMENT_URL, KEY_ENCRYPTED_SUBSCRIPTION_URL)) {
            val value = rc.getValue(key)
            if (value.source == FirebaseRemoteConfig.VALUE_SOURCE_REMOTE) {
                val raw = value.asString()
                val decoded = SecretCodec.decode(raw)
                if (raw.isNotBlank() && isValidDecoded(raw, decoded)) {
                    persist(context, key, raw)
                    if (key == KEY_ENCRYPTED_API_URL) {
                        activeFailoverApiUrl = null
                    }
                }
            }
        }
    }

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
