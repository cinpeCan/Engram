# Engram release R8 rules

# Preserve Kotlinx Serialization descriptors, serializers, and polymorphic Nav3 keys.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keep,includedescriptorclasses class io.github.cinpecan.engram.ui.navigation.EngramNavKey { *; }
-keep,includedescriptorclasses class io.github.cinpecan.engram.ui.navigation.EngramNavKey$** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keep,includedescriptorclasses class io.github.cinpecan.engram.data.remote.** {
    *** Companion;
    *** serializer(...);
}
-keep,includedescriptorclasses class io.github.cinpecan.engram.domain.model.** {
    *** Companion;
    *** serializer(...);
}
-keepclasseswithmembers class io.github.cinpecan.engram.data.remote.**$$serializer { *; }
-keepclasseswithmembers class io.github.cinpecan.engram.domain.model.**$$serializer { *; }

# Keep Kotlin stdlib multifile sequence facade hierarchy together to prevent cross-package IllegalAccessError.
-keep class kotlin.sequences.** { *; }

# Keep Koin ViewModel and Android entry constructors invoked at runtime.
-keep class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
