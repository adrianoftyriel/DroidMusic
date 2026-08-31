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

# Kotlin's enum `entries` is not plain bytecode: it is compiled into a call on
# kotlin.enums, and R8 removed that from the release build. The symptom is the
# worst kind - a NoClassDefFoundError under an obfuscated name, in release only,
# on a code path every text chart goes through, with every test passing because
# tests are not minified.
#
# The one use of `entries` in this app has been written as `values()` instead,
# which needs no runtime at all. This rule is the guard for the next one, since
# `entries` is what the IDE suggests and what the language now prefers.
-keep class kotlin.enums.** { *; }
