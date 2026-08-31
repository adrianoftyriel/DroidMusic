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

# The music and library modules are kept whole, and this is not caution for its
# own sake - it is the fix for a bug that broke every text chart in the app.
#
# The Companion rule above comes from the kotlinx.serialization documentation,
# where it is meant for @Serializable classes. Applied to every class in
# org.droidmusic it pins a Companion *field* on classes that have nothing to do
# with serialization, while R8 in full mode stays free to shrink the companion
# *class* that field points at. Interval is one of those: Interval.Companion
# survived as a field and t2.l - the class - did not, so Transposer.transpose
# died on NoClassDefFoundError before any chart could be laid out. The release
# APK only; nothing minified is under test, so every suite stayed green.
#
# These two modules are the whole of the app's correctness - the parsers, the
# transposer, key detection, layout and pagination - and they are about forty
# small classes between them. Obfuscating them saves a trivial amount of space
# and has now cost the feature entirely, twice over. The same reasoning as the
# session rule below, for a different reason.
-keep class org.droidmusic.music.** { *; }
-keep class org.droidmusic.library.** { *; }

# The sealed message hierarchy is resolved by name on the wire. Renaming any of
# it would mean two devices on different builds could not talk to each other.
-keep class org.droidmusic.session.** { *; }

# Kotlin's enum `entries` is not plain bytecode: it is compiled into a call on
# the kotlin.enums runtime, so it is a class this app depends on without ever
# naming one.
#
# Kept as a precaution, not as a fix for anything observed. It was added while
# chasing the NoClassDefFoundError above on the theory that the enum-entries
# runtime was the missing class, and that theory was wrong - the missing class
# was Interval$Companion. The rule stays because the hazard it describes is
# real and the cost is a few hundred bytes, but nothing here has ever been seen
# to fail for this reason.
-keep class kotlin.enums.** { *; }
