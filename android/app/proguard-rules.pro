# Retrofit
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.insituledger.app.data.remote.dto.** { *; }

# Gson
-keepattributes Signature
-keep class com.google.gson.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase

# Strip android.util.Log calls in release builds. Logs may carry PII
# (account names, descriptions, server URLs); we only want them in debug.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}
