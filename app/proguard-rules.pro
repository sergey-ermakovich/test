# Conservative rules: shrink unused code (mainly the huge material-icons-extended
# set) but keep everything we touch, and don't obfuscate — our ECARX access uses
# reflection into system classes, and we'd rather not chase name-mangling bugs
# on a car we can't easily reinstall.
-dontobfuscate

# Our own code (small; keeps reflection targets and manifest components safe).
-keep class com.zeekrprobe.** { *; }

# Libraries we call, some via reflection internally.
-keep class dadb.** { *; }
-keep class okio.** { *; }
-keep class org.lsposed.hiddenapibypass.** { *; }

-dontwarn dadb.**
-dontwarn okio.**
-dontwarn org.bouncycastle.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
