-keep class org.matrix.TEESimulator.interception.keystore.** { *; }

-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-dontwarn javax.naming.**

-keepclasseswithmembers class org.matrix.TEESimulator.App {
    public static void main(java.lang.String[]);
}

-keepclasseswithmembers class org.matrix.TEESimulator.pki.NativeCertGen {
    native <methods>;
    *;
}
-keep class org.matrix.TEESimulator.pki.CertGenConfig { *; }

# OmegaRelay consumer dependencies.
# msgpack-core's MessageBuffer<clinit> uses Class.forName() to pick between
# the unsafe-based and unsafe-free implementations; R8 can't see those
# references statically. Keep the whole codec class set + the alternate
# buffer implementation explicitly.
-keep class org.msgpack.core.** { *; }
-keep class org.msgpack.value.** { *; }
-dontwarn org.msgpack.**
-dontwarn sun.misc.**

# OkHttp ships optional transitive deps (Conscrypt, BouncyCastle JSSE, etc.)
# that are referenced reflectively but not present on Android.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn org.bouncycastle.jsse.**

# Keep our own relay package; it's the entry point for OkHttp's listener
# callbacks via reflection inside OkHttp itself.
-keep class org.matrix.TEESimulator.relay.** { *; }
