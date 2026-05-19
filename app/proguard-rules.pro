# Keep line numbers in release stack traces — model init failures only
# reproduce on real devices and the trimmed traces are useless without them.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# LiteRT-LM SDK is a native bridge: Engine, EngineConfig, SamplerConfig,
# ConversationConfig etc. are instantiated and accessed reflectively/JNI.
# Stripping or renaming them breaks model load with opaque NoSuchMethod errors.
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclassmembers class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# kotlinx.coroutines debug-agent probes — safe to ignore in release.
-dontwarn kotlinx.coroutines.debug.**
