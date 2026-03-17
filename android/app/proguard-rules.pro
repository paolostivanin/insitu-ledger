# Retrofit
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.insituledger.app.data.remote.dto.** { *; }

# Gson
-keepattributes Signature
-keep class com.google.gson.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
