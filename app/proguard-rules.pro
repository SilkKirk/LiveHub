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

# Rhino evaluates Douyu's signing script at runtime. R8 cannot see that reflection, so the
# engine and the classes it instantiates have to be kept verbatim.
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
-keepclassmembers class org.mozilla.javascript.** { public *; }

# Room: the generated `<Name>_Impl` class is instantiated **reflectively**, through its no-arg
# constructor obtained via `Class.getDeclaredConstructor()`, never called directly. R8 therefore
# sees no use for that constructor and removes it. WorkManager's consumer rules keep the class
# name — which is why the failure named `androidx.work.impl.WorkDatabase_Impl` at all — but not
# the constructor, so every launch died inside androidx.startup.InitializationProvider with:
#
#   NoSuchMethodException: androidx.work.impl.WorkDatabase_Impl.<init> []
#
# Only a minified build can show this, which is why it took a release APK on a real device to
# find. The CI smoke test now installs and launches one, so a regression fails the build.
-keep class * extends androidx.room.RoomDatabase {
    <init>();
}
-keepclassmembers class * extends androidx.room.RoomDatabase {
    <init>();
}
-keep class androidx.work.impl.WorkDatabase_Impl { *; }

# Rhino references java.beans / javax.script which are absent on Android.
-dontwarn java.beans.**
-dontwarn javax.script.**
