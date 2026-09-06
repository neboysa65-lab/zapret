# =============================================================================
# R8 rules (release)
#
# ИСТОРИЯ: раньше здесь были blanket-правила вида
#   -keep class io.flutter.** { *; }
#   -keep class com.google.firebase.** { *; }
#   -keep class com.google.android.gms.** { *; }
#   -keep class com.yandex.mobile.ads.** { *; }
#   -keep class go.** / libv2ray.** / xray.** ...
# Они запрещали R8 переименовывать бОльшую часть DEX-классов, из-за чего
# Google Play показывал «Обфускация 20%» (порог — 25% с февраля 2027).
#
# Удалены, потому что:
#  - Flutter/Firebase/Play Services/Yandex сами поставляют consumer-rules
#    со всем необходимым; standard Flutter-шаблон вообще не содержит keep-ов;
#  - io.flutter.embedding ** обфусцируется штатно (Flutter инжектирует только
#    -dontwarn io.flutter.plugin.** и allowobfuscation-keep для FlutterPlugin);
#  - libxray.so исполняется как бинарник (0 JNI-символов), пакетов go.*/,
#    libv2ray.**, xray.** в классах нет — правила матчили пустоту;
#  - классы из AndroidManifest (Activity/Service/Provider/Receiver)
#    AGP держит автоматически через aapt-rules.
#
# Оставлены только правила, реально нужные против reflection/JNI.
# =============================================================================

# --- JNI: имена классов и native-методов нельзя переименовывать ---
-keepclasseswithmembernames class * {
    native <methods>;
}

# --- Enum: values()/valueOf() вызываются рефлективно ---
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- Parcelable/Serializable (передаются через Intent extras) ---
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
# КРИТИЧНО: имена классов, передаваемых через Intent (BroadcastReceiver),
# не переименовывать (сами поля/методы обфусцируются штатно).
-keepnames class * implements java.io.Serializable
-keepnames class * implements android.os.Parcelable

# --- flutter_v2ray_plus: XrayConfig (Serializable) читается из Intent extras,
#     его поля маппятся на строковые ключи. Пакет крошечный (~1 класс). ---
-keep class com.wisecodex.flutter_v2ray.xray.dto.** { *; }
-keep class com.wisecodex.flutter_v2ray.xray.enums.** { *; }

# --- Подавление warning'ов (чистые, на рантайм не влияют) ---
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn javax.naming.**

# Play Core (Flutter Deferred Components): классы не линкуются, но и не нужны
-dontwarn com.google.android.play.core.tasks.**
-dontwarn com.google.android.play.core.splitinstall.**
-dontwarn com.google.android.play.core.splitcompat.**
