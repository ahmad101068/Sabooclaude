# SQLCipher is called through JNI; keep its classes and native method names.
-keep class net.zetetic.database.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
-dontwarn javax.annotation.**
