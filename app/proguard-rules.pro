# OkHttp 4 — platform shim warnings (these classes are conditionally present
# at runtime; safe to suppress so R8 doesn't fail the build).
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Tink (pulled in transitively by androidx.security:security-crypto) references
# errorprone annotations that aren't on the runtime classpath. They're compile-
# time-only metadata so suppressing the warnings is safe.
-dontwarn com.google.errorprone.annotations.**

# Kotlin's intrinsic null-check helpers can be stripped from a release build.
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkNotNull(java.lang.Object);
    public static void checkNotNull(java.lang.Object, java.lang.String);
    public static void checkNotNullParameter(java.lang.Object, java.lang.String);
    public static void checkNotNullExpressionValue(java.lang.Object, java.lang.String);
    public static void checkParameterIsNotNull(java.lang.Object, java.lang.String);
    public static void checkExpressionValueIsNotNull(java.lang.Object, java.lang.String);
}
