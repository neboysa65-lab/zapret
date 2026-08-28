# Flutter V2Ray Plus - сохраняем все классы библиотеки
-keep class com.wisecodex.flutter_v2ray.** { *; }
-keep class com.wisecodex.flutter_v2ray.xray.** { *; }
-keep class com.wisecodex.flutter_v2ray.xray.service.** { *; }

# V2Ray/Xray Core native библиотеки
-keep class go.** { *; }
-keep class libv2ray.** { *; }
-keep class v2ray.** { *; }
-keep class xray.** { *; }

# Gomobile generated code
-keep class org.golang.** { *; }

# Сохраняем JNI методы
-keepclasseswithmembernames class * {
    native <methods>;
}

# Flutter
-keep class io.flutter.** { *; }
-keep class io.flutter.plugins.** { *; }
-keep class io.flutter.embedding.** { *; }

# Firebase
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }

# Yandex Ads
-keep class com.yandex.mobile.ads.** { *; }

# Не обфускируем enum'ы
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Сохраняем Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Сохраняем Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# КРИТИЧНО: Сохраняем сами классы Serializable и Parcelable от обфускации имен,
# так как они передаются через Intent (BroadcastReceiver)
-keepnames class * implements java.io.Serializable
-keepnames class * implements android.os.Parcelable

# Сохраняем классы моделей данных V2Ray, которые передаются через Intent
-keep class com.wisecodex.flutter_v2ray.xray.dto.** { *; }
-keep class com.wisecodex.flutter_v2ray.xray.enums.** { *; }

# Не предупреждать о missing classes в библиотеках
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn javax.naming.**

# Игнорируем отсутствующие классы Play Core (Flutter Deferred Components)
-dontwarn com.google.android.play.core.tasks.**
-dontwarn com.google.android.play.core.splitinstall.**
-dontwarn com.google.android.play.core.splitcompat.**

# VPN Service - критично для работы VPN
-keep class android.net.VpnService { *; }
-keep class * extends android.net.VpnService { *; }
