# SQLCipher is called through JNI; keep its classes and native method names.
-keep class net.zetetic.database.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
-dontwarn javax.annotation.**

# Sabou's own classes are kept whole (ADR-0048): commands are matched by their annotations, routes and drafts are
# Serializable and saved in the instance state, and documents are read back by name. Libraries are still shrunk.
-keep class ir.sabou.** { *; }
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
