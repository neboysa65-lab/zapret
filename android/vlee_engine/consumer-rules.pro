# =============================================================================
# consumer-rules для vlee_engine (попадают в R8 приложения)
#
# История: раньше здесь был blanket-keep всего BouncyCastle
#   -keep class org.bouncycastle.** { *; }
# (после инцидента «R8 (full mode) выпиливал X25519/XDH в проде»).
#
# Теперь: android.enableR8.fullMode=false (compat mode) — R8 сам сохраняет
# классы из META-INF/services; плюс наш код инстанцирует
# BouncyCastleProvider() напрямую. Строковая адресация внутри JCE
# (java.security.Provider создаёт классы по именам из "Alg.XXX" таблиц)
# затрагивает ТОЛЬКО пакеты jcajce.provider / jce.provider — их и держим.
# Всё остальное (org.bouncycastle.crypto.**, math, util, asn1) достижимо
# прямыми вызовами из sshj/нашего кода и обфусцируется как обычный код.
# =============================================================================

# sshj резолвится статически (никаких ServiceLoader/Class.forName) —
# обфусцируется целиком.
-dontwarn net.schmizz.sshj.**
-dontwarn com.hierynomus.sshj.**

# sshj тянет net.i2p.crypto.eddsa, который ссылается на JDK-классы,
# отсутствующие на Android — это чистые warnings, глушим.
-dontwarn sun.security.x509.X509Key
-dontwarn net.i2p.crypto.eddsa.**

# BouncyCastle: держим только строково-резолвимую поверхность JCE-провайдера.
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-dontwarn org.bouncycastle.**

# JNI (hev-socks5-tunnel ищет класс по literal-имени — своя страховка,
# дублируется с правилом приложения, безопасно).
-keepclasseswithmembernames class * {
    native <methods>;
}
