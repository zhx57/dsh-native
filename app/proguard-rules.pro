-dontwarn org.slf4j.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.bouncycastle.**
-dontwarn org.jetbrains.annotations.**

-keepattributes *Annotation*, InnerClasses, Signature

# Tink (backing androidx.security.crypto's EncryptedSharedPreferences)
-keep class com.google.crypto.tink.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.crypto.tink.**
-dontwarn com.google.protobuf.**

# kotlinx.serialization generated serializers
-keep,includedescriptorclasses class dev.dsh.nativeapp.**$$serializer { *; }
-keepclassmembers class dev.dsh.nativeapp.** {
    *** Companion;
}
-keepclasseswithmembers class dev.dsh.nativeapp.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp / Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-keepclassmembers class okhttp3.internal.platform.** { *; }

# Compose runtime keeps its own rules; silence reflective lookups here.
-dontwarn androidx.compose.**
