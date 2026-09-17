# Obfuscation and R8 optimizations are on (proguard-android-optimize.txt): identifiers leave the dex
# and code gets inlined, so every class reached by name at runtime (KeyMapper reflection, the JNI
# bridge, the hidden-API bypass) is kept below. Keep out/mapping-<version>.txt of each release to read
# stack traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Our code

# KeyMapper - keep all VK_* fields for reflection
-keep class com.limelight.utils.KeyMapper {*;}

# Moonlight common: the JNI side calls these by name
-keep class com.limelight.nvstream.jni.* {*;}

# Okio
-keep class sun.misc.Unsafe {*;}
-dontwarn java.nio.file.*
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn okio.**

# jMDNS
-dontwarn javax.jmdns.impl.DNSCache
-dontwarn org.slf4j.**

# HiddenApiBypass reflects on its own helper classes
-keep class org.lsposed.hiddenapibypass.** {*;}
