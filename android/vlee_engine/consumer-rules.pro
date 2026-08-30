-keep class net.schmizz.sshj.** { *; }
-keep class com.hierynomus.sshj.** { *; }
-dontwarn net.schmizz.sshj.**
-dontwarn com.hierynomus.sshj.**
# sshj тянет net.i2p.crypto.eddsa, который ссылается на JDK-классы,
# отсутствующие на Android — это чистые warnings, глушим.
-dontwarn sun.security.x509.X509Key
-dontwarn net.i2p.crypto.eddsa.**
-keep class org.bouncycastle.jcajce.provider.asymmetric.edec.** { *; }
# The full provider is registered reflectively by algorithm-name strings; keep
# the whole tree or R8 (release, minifyEnabled) will strip X25519/XDH again.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-keepclasseswithmembernames class * {
    native <methods>;
}
