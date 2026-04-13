# NetPopUp ProGuard / R8 rules

# ── Modèles de données Firestore ──────────────────────────────────────────────
# Firestore utilise la réflexion pour sérialiser/désérialiser les documents.
# Sans ces règles, les champs peuvent être supprimés par R8 → données vides.
-keep class com.netpopup.data.model.** { *; }
-keepclassmembers class com.netpopup.data.model.** { <fields>; }

# ── Firebase SDK ──────────────────────────────────────────────────────────────
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# ── Hilt / Dagger ─────────────────────────────────────────────────────────────
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class dagger.** { *; }
-dontwarn dagger.hilt.**

# ── Kotlin ────────────────────────────────────────────────────────────────────
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings { <fields>; }
-keepclassmembers class kotlin.Lazy { <fields>; }

# ── Kotlin Coroutines ─────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepnames class kotlinx.coroutines.android.AndroidExceptionPreHandler {}
-keepclassmembernames class kotlinx.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# ── DataStore / Protobuf ──────────────────────────────────────────────────────
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }

# ── Jetpack Compose ───────────────────────────────────────────────────────────
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ── Jetpack Navigation ────────────────────────────────────────────────────────
-keepnames class androidx.navigation.** { *; }

# ── OkHttp (Firebase networking) ─────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**

# ── Enumérations (utilisées dans Room.type) ───────────────────────────────────
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── Supprime les traces de stack Kotlin en production ─────────────────────────
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    static void checkParameterIsNotNull(java.lang.Object, java.lang.String);
    static void checkNotNullParameter(java.lang.Object, java.lang.String);
    static void checkExpressionValueIsNotNull(java.lang.Object, java.lang.String);
    static void checkNotNullExpressionValue(java.lang.Object, java.lang.String);
}
