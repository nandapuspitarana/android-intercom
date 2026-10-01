# Release shrinker rules for Two Way.
# Concentus (pure-Java Opus) uses no reflection.
-keep class io.github.jaredmdobson.concentus.** { *; }
-dontwarn io.github.jaredmdobson.concentus.**
# Room generated code is kept by the Room library consumer rules.
