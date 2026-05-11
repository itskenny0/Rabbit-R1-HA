# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.github.itskenny0.r1ha.**$$serializer { *; }
-keepclassmembers class com.github.itskenny0.r1ha.** {
    *** Companion;
}
-keepclasseswithmembers class com.github.itskenny0.r1ha.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Strip verbose logs from release
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
}
