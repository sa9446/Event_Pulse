# ============================================================
# EventPulse - R8 / ProGuard Optimization Rules
# ============================================================
# PERF: Aggressive R8 optimizations enabled by
# proguard-android-optimize.txt in build.gradle.kts.
# These rules supplement the default optimization config.

# ── Keep Rules (reflection / native / JNI) ──────────────────

-keep class com.bitchat.android.protocol.** { *; }
-keep class com.bitchat.android.crypto.** { *; }
-keep class com.bitchat.android.identity.SecureIdentityStateManager { private android.content.SharedPreferences prefs; *; }
-keep class com.bitchat.android.favorites.** { *; }
-keep class com.bitchat.android.nostr.** { *; }
-keep class com.bitchat.android.identity.** { *; }
-keep class com.eventpulse.mesh.** { *; }

# Room
-keepclassmembers class * extends androidx.room.RoomDatabase { <init>(); }

# Tor / Arti
-keep class com.bitchat.android.net.RealTorProvider { *; }
-keep class info.guardianproject.arti.** { *; }
-keep class org.torproject.arti.** { *; }
-dontwarn info.guardianproject.arti.**
-dontwarn org.torproject.arti.**

# Gson (used by EventPulsePayload)
-keepattributes Signature
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# ── Optimization Flags ──────────────────────────────────────

# Allow R8 to merge classes aggressively
-mergeinterfacesaggressively

# Allow access modification (package-private -> public where needed)
-allowaccessmodification

# Reuse matching code sequences
-repackageclasses 'ep'

# Flatten package hierarchy for smaller DEX
-flattenpackagehierarchy 'ep'

# ── Unused Code Removal ─────────────────────────────────────

# Remove verbose debug logging in release builds (keep info/warn/error)
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
}

# Remove Kotlin nullable assertions in release (safe: app won't crash on null)
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    static void checkNotNull(java.lang.Object);
    static void checkNotNullExpressionValue(java.lang.Object, java.lang.String);
    static void checkNotNullParameter(java.lang.Object, java.lang.String);
    static void checkExpressionValueIsNotNull(java.lang.Object, java.lang.String);
    static void checkReturnedValueIsNotNull(java.lang.Object, java.lang.String);
    static void checkFieldIsNotNull(java.lang.Object, java.lang.String);
}

# Remove Compose debug checks in release
-assumenosideeffects class androidx.compose.runtime.ComposerKt {
    static void sourceInformation(androidx.compose.runtime.Composer, java.lang.String);
    static void sourceInformationMarkerStart(androidx.compose.runtime.Composer, int, java.lang.String);
    static void sourceInformationMarkerEnd(androidx.compose.runtime.Composer);
}

# ── Keep Data Classes for Serialization ─────────────────────
-keepclassmembers class com.bitchat.android.model.** { *; }
-keepclassmembers class com.eventpulse.mesh.EventPulsePayload { *; }


