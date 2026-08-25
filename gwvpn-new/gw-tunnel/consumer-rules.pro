-keep class net.schmizz.sshj.** { *; }
-keep class com.hierynomus.sshj.** { *; }
-dontwarn net.schmizz.sshj.**
-dontwarn com.hierynomus.sshj.**
-keep class org.bouncycastle.jcajce.provider.asymmetric.edec.** { *; }
# The full provider is registered reflectively by algorithm-name strings; keep
# the whole tree or R8 (release, minifyEnabled) will strip X25519/XDH again.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclasseswithmembernames class * {
    native <methods>;
}
