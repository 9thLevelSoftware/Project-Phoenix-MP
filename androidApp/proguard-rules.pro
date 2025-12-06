# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in the SDK tools.

# ==================== Koin ====================
-keepclassmembers class * {
    @org.koin.* <methods>;
}
-keep class org.koin.** { *; }

# ==================== Kotlin Serialization ====================
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.devil.phoenixproject.**$$serializer { *; }
-keepclassmembers class com.devil.phoenixproject.** {
    *** Companion;
}
-keepclasseswithmembers class com.devil.phoenixproject.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ==================== Coroutines ====================
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keepclassmembers class kotlin.coroutines.SafeContinuation {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.flow.**

# ==================== SQLDelight ====================
-keep class com.devil.phoenixproject.database.** { *; }
-keep class app.cash.sqldelight.** { *; }
-keep interface app.cash.sqldelight.** { *; }
-keepclassmembers class app.cash.sqldelight.** { *; }

# ==================== Kable BLE ====================
-keep class com.juul.kable.** { *; }
-keep interface com.juul.kable.** { *; }
-dontwarn com.juul.kable.**

# ==================== Multiplatform Settings ====================
-keep class com.russhwolf.settings.** { *; }
-keep interface com.russhwolf.settings.** { *; }

# ==================== Kermit Logging ====================
-keep class co.touchlab.kermit.** { *; }
-dontwarn co.touchlab.kermit.**

# ==================== Compose ====================
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Keep Compose runtime internal classes
-keepclassmembers class androidx.compose.runtime.** {
    <fields>;
}

# ==================== Lifecycle / ViewModel ====================
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keep class * extends androidx.lifecycle.AndroidViewModel { *; }
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# ==================== Domain Models ====================
# Keep all domain models for reflection/serialization
-keep class com.devil.phoenixproject.domain.model.** { *; }
-keep class com.devil.phoenixproject.data.preferences.** { *; }

# ==================== Okio (used by SQLDelight) ====================
-dontwarn org.codehaus.mojo.animal_sniffer.*
-dontwarn okio.**
-keep class okio.** { *; }

# ==================== General ====================
# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Parcelables
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Remove logging in release (optional - uncomment to strip logs)
# -assumenosideeffects class android.util.Log {
#     public static int v(...);
#     public static int d(...);
#     public static int i(...);
# }
