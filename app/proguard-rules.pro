# Keep annotations and signatures needed by kotlinx.serialization and reflection-free codegen.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

# --- kotlinx.serialization (our @Serializable domain model) ---
# The compiler plugin + runtime ship most rules; keep our model's generated serializers
# and companion factories explicitly so cache/secret (de)serialization survives R8.
-keepclassmembers class it.allard.multistream.core.model.** {
    *** Companion;
}
-keepclasseswithmembers class it.allard.multistream.core.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class it.allard.multistream.core.model.**$$serializer { *; }
# Sealed TitleKey hierarchy (polymorphic serialization).
-keep class it.allard.multistream.core.model.TitleKey { *; }
-keep class it.allard.multistream.core.model.TitleKey$* { *; }

# Enums are persisted by name (enum.name <-> valueOf); keep them un-renamed.
-keep enum it.allard.multistream.** { *; }

# Tink (via androidx.security-crypto) references compile-only errorprone annotations.
-dontwarn com.google.errorprone.annotations.**

# Networking stacks normally ship their own consumer rules; silence optional refs.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
