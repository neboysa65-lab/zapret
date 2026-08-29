# ══════════════════════════════════════════════════════════════════════════
#  РЕЖИМ: обфусцируется ТОЛЬКО протокол GW (com.witvpn.gw.**).
#  Весь остальной код сохраняет исходные имена, не удаляется и не
#  оптимизируется — поведение приложения остаётся 1:1.
# ══════════════════════════════════════════════════════════════════════════

# Только переименование: без удаления кода и без агрессивных оптимизаций.
-dontshrink
-dontoptimize

# Всё, КРОМЕ GW-модуля, остаётся с исходными именами: явный список пакетов
# в файле proguard-keep-others.pro (автогенерация по фактическому списку
# классов APK). Пакет com.witvpn.gw.** в нём отсутствует — только он
# переименовывается.

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

# ══════════════════════════════════════════════════════════════════════════
#  Усиление обфускации для GW-классов
# ══════════════════════════════════════════════════════════════════════════

# Мусорные идентификаторы (lI1O0...) вместо a/b/c — тяжело читать в jadx.
-obfuscationdictionary proguard-dict.txt
-classobfuscationdictionary proguard-dict.txt
-packageobfuscationdictionary proguard-dict.txt

# Спрятать оригинальную структуру пакетов GW-классов.
-repackageclasses ''

# Убрать следы исходников из стектрейсов (mapping.txt остаётся для
# Firebase Crashlytics — он загружается плагином автоматически).
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# Строки-константы не «склеивать» с местами использования.
-adaptclassstrings com.witvpn.gw.**

# ─── Missing classes: compile-only ссылки сторонних SDK (не влияют на работу) ───
-dontwarn com.google.ads.mediation.common.AgeRestrictedTreatmentUtils
-dontwarn java.beans.ConstructorProperties
-dontwarn java.beans.Transient
-dontwarn okhttp3.internal.Util
-dontwarn org.w3c.dom.bootstrap.DOMImplementationRegistry
-dontwarn sun.security.x509.X509Key
