# kotlinx.serialization generates serializer() companions that R8 cannot see are
# used, because they are reached reflectively from the polymorphic wire format.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keepclassmembers class org.droidmusic.** {
    *** Companion;
}
-keepclasseswithmembers class org.droidmusic.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class org.droidmusic.**$$serializer { *; }

# The sealed message hierarchy is resolved by name on the wire. Renaming any of
# it would mean two devices on different builds could not talk to each other.
-keep class org.droidmusic.session.** { *; }
