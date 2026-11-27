# Shrink unused dependency code only. Preserve Freedom's own classes, members,
# runtime names, stack traces, and existing OCR IPC consumer rules.
-dontoptimize
-dontobfuscate
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*
-keep class com.freedom.** { *; }
