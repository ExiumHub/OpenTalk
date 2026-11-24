# ProGuard / R8 Security & Stability Rules for OpenTalk

# Keep custom UI views referenced in XML layouts
-keep class com.opentalk.android.ui.** { *; }

# Keep data models & enums intact
-keep class com.opentalk.android.models.** { *; }

# Keep Tink / AndroidX Security Crypto internals used by EncryptedSharedPreferences
-keep class com.google.crypto.tink.** { *; }
-keep class androidx.security.crypto.** { *; }
-dontwarn com.google.crypto.tink.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# Keep Google libphonenumber classes & metadata loaders intact
-keep class com.google.i18n.phonenumbers.** { *; }
-dontwarn com.google.i18n.phonenumbers.**

# Strip verbose/debug/info/warning Android Log calls in release builds to prevent metadata leakage via logcat
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
}
