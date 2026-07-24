# ScamShield release rules.
#
# Keep the domain model intact: :core:model types are reflected over by kotlinx.serialization
# in :core:data when a rule pack is parsed, and their names appear in Room's generated code.
-keep class com.scamshield.core.model.** { *; }

# kotlinx.serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# ONNX Runtime uses JNI and reflection over its own classes.
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# HuggingFace tokenizer bindings, likewise.
-keep class ai.djl.huggingface.** { *; }
-dontwarn ai.djl.**

# Do NOT keep line numbers or source file names in a way that could leak analysed text into
# a stack trace. Constraint C1: message content never reaches a crash report.
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable
