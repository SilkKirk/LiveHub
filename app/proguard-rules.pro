# Keep kotlinx.serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** serializer(...);
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.livehub.**$$serializer { *; }

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# JmDNS needs its service type classes
-keep class javax.jmdns.** { *; }
-dontwarn javax.jmdns.**

# Media3
-dontwarn androidx.media3.**

# Platform adapters are resolved reflectively by the module list only, but keep the
# public API surface so the adapters stay constructible after shrinking.
-keep class com.livehub.platform.** { *; }
