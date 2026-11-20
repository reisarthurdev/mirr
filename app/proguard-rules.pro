# youtubedl-android: chama o python por processo e usa Jackson/commons-compress por reflexão
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keep class org.apache.commons.compress.** { *; }
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# classes opcionais referenciadas por essas libs que não existem no Android
-dontwarn java.beans.**
-dontwarn javax.**
-dontwarn org.w3c.dom.bootstrap.**
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
-dontwarn org.osgi.**
-dontwarn com.fasterxml.jackson.**
-dontwarn org.apache.commons.**
