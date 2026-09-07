# ══════════════════════════════════════════════════════════════════════════
#  ПОЛНОЦЕННЫЙ R8: сжатие + оптимизация + обфускация.
#  Требование Google Play (метрики «Оптимизация приложения»): с фев 2027
#  ниже 25% по любой из категорий — приложение реже показывается в поиске.
#
#  Точечные keep-правила ниже покрывают границы, где имена/поля критичны:
#   - JNI (нативка ищет методы по имени) и фиксированные имена из C-кода;
#   - Gson-сериализация (имена полей = ключи JSON, у части моделей без
#     @SerializedName — политика LOWER_CASE_WITH_UNDERSCORES);
#   - JCA-криптопровайдеры (BouncyCastle регистрирует классы по именам);
#   - retrofit-эндпоинты.
#  Сторонние SDK используют собственные consumer-rules из их AAR.
#  mapping.txt загружается в Firebase Crashlytics плагином автоматически.
# ══════════════════════════════════════════════════════════════════════════

# Атрибуты, нужные рантайму (аннотации Hilt/Retrofit/Gson, generics, JNI).
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions,RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations

# Нативные методы (JNI ищет их по имени) — во всех пакетах.
-keepclasseswithmembernames class * {
    native <methods>;
}

# Перечисления/значения enum, используемые через valueOf.
-keepclassmembers enum * {
    values();
    valueOf(java.lang.String);
}

# Parcelable-поля восстанавливаются через CREATOR по имени.
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# ══════════════════════════════════════════════════════════════════════════
#  Gson: имена полей = ключи JSON
# ══════════════════════════════════════════════════════════════════════════
# Модели API (часть полей без @SerializedName — стратегия
# LOWER_CASE_WITH_UNDERSCORES) и черновики выбора сервера (Server.saveDraft),
# HashRequestObject (подпись запросов).
-keepclassmembers class com.witvpn.ikev2.data.remote.model.** { <fields>; }
-keepclassmembers class com.witvpn.ikev2.domain.model.** { <fields>; }
-keepclassmembers class com.witvpn.ikev2.presentation.utils.interceptor.HashRequestObject { <fields>; }

# Retrofit-эндпоинты (интерфейсные методы читаются рефлексией).
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}

# Retrofit suspend + R8 full mode: тип ответа suspend-метода живёт только в
# generic-сигнатуре Continuation. Даже с библиотечными -if-правилами R8 full
# mode стирал сигнатуру эндпоинтов -> Gson отдавал LinkedTreeMap вместо
# LoginResponse -> ClassCastException в createAnonymousUser (qa-сборка).
# Интерфейс целиком: имена методов, аннотации и Signature-атрибуты.
-keep interface com.witvpn.ikev2.data.remote.ApiService { *; }

# Модели ответов целиком (имена классов + поля) — конвертер Gson резолвит
# тип по generic-сигнатуре эндпоинта.
-keep class com.witvpn.ikev2.data.remote.model.** { *; }

# ══════════════════════════════════════════════════════════════════════════
#  Крипто/JCA и библиотеки, ищущие классы по именам
# ══════════════════════════════════════════════════════════════════════════
# BouncyCastle: провайдер регистрирует реализации по именам классов
# (SecureRandom/Digest/подписи в sshj и web3j). Обфускация имён ломает JCA.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# sshj (SSH-транспорт внутри gw-tunnel): фабрики алгоритмов по именам.
-keep class net.schmizz.** { *; }
-dontwarn net.schmizz.**

# Crypto-1.2.jar (локальный, без consumer-rules).
-keep class ru.zoommax.** { *; }

# ══════════════════════════════════════════════════════════════════════════
#  Усиление обфускации для GW-классов
# ══════════════════════════════════════════════════════════════════════════

# Мусорные идентификаторы (lI1O0...) вместо a/b/c — тяжело читать в jadx.
-obfuscationdictionary proguard-dict.txt
-classobfuscationdictionary proguard-dict.txt
-packageobfuscationdictionary proguard-dict.txt

# Спрятать оригинальную структуру пакетов обфусцируемых классов.
-repackageclasses ''

# Убрать следы исходников из стектрейсов (mapping.txt остаётся для
# Firebase Crashlytics — он загружается плагином автоматически).
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# Строки-константы не «склеивать» с местами использования.
-adaptclassstrings com.witvpn.gw.**

# ─── Рефлексия по именам R-полей ───
# Utils.getResId() ищет drawable через R.drawable::class.java.getDeclaredField
# ("germany" -> R.drawable.germany). Полный R8 переименовывает/удаляет поля
# R-класса — флаги в списках серверов пропадали. Имена полей R.drawable
# становятся частью «API» приложения, keep обязателен. (AGP сам держит
# правила для aapt-ресурсов, но поля R-класса под minify не защищены.)
-keepclassmembers class com.witvpn.ikev2.R$drawable { public static <fields>; }

# ─── Missing classes: compile-only ссылки сторонних SDK (не влияют на работу) ───
-dontwarn com.google.ads.mediation.common.AgeRestrictedTreatmentUtils
-dontwarn java.beans.ConstructorProperties
-dontwarn java.beans.Transient
-dontwarn okhttp3.internal.Util
-dontwarn org.w3c.dom.bootstrap.DOMImplementationRegistry
-dontwarn sun.security.x509.X509Key
