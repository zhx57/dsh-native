-dontwarn org.slf4j.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-keepattributes *Annotation*, InnerClasses, Signature
-keep,includedescriptorclasses class dev.dsh.nativeapp.**$$serializer { *; }
-keepclassmembers class dev.dsh.nativeapp.** {
    *** Companion;
}
-keepclasseswithmembers class dev.dsh.nativeapp.** {
    kotlinx.serialization.KSerializer serializer(...);
}
