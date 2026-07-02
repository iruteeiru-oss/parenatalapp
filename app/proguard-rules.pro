# proguard-rules.pro

# Keep data models
-keep class com.device.guardian.service.data.model.** { *; }
-keep class com.device.guardian.service.data.local.** { *; }

# Keep Firebase
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }

# Keep Accessibility Service
-keep class com.device.guardian.service.service.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *
