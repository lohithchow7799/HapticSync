# HapticSync release shrinking rules.
# kotlinx.serialization ships its own consumer rules; keep our serializable model classes'
# generated serializers reachable by name for safety across R8 versions.
-keepclassmembers @kotlinx.serialization.Serializable class com.hapticsync.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.hapticsync.**$$serializer { *; }
