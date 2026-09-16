# Obfuscation is on: it takes ~0.8 MB of identifier strings out of the dex. Every class read by name at
# runtime (Gson models, KeyMapper, the JNI bridge, the hidden-API bypass, BouncyCastle providers) is kept
# below. Keep out/mapping-<version>.txt of each release to read stack traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Our code

# KeyMapper - keep all VK_* fields for reflection
-keep class com.limelight.utils.KeyMapper {*;}

# KeyConfigHelper - keep classes and fields for Gson
-keep class com.limelight.utils.KeyConfigHelper {*;}
-keep class com.limelight.utils.KeyConfigHelper$ShortcutFile {*;}
-keep class com.limelight.utils.KeyConfigHelper$Shortcut {*;}


# Profiles
-keep class com.limelight.profiles.ProfilesManager$ProfilesData {*;}
-keep class com.limelight.profiles.SettingsProfile {*;}

# Moonlight common
-keep class com.limelight.nvstream.jni.* {*;}

# Okio
-keep class sun.misc.Unsafe {*;}
-dontwarn java.nio.file.*
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn okio.**

# BouncyCastle
-keep class org.bouncycastle.jcajce.provider.asymmetric.* {*;}
-keep class org.bouncycastle.jcajce.provider.asymmetric.util.* {*;}
-keep class org.bouncycastle.jcajce.provider.asymmetric.rsa.* {*;}
-keep class org.bouncycastle.jcajce.provider.digest.** {*;}
-keep class org.bouncycastle.jcajce.provider.symmetric.** {*;}
-keep class org.bouncycastle.jcajce.spec.* {*;}
-keep class org.bouncycastle.jce.** {*;}
# The JCA provider registers its algorithm classes by string name (Provider$Service.getImplClass), so
# whatever the shrinker keeps in these trees must also keep its name; the first obfuscated build died in
# CertificateFactory.getInstance("X.509", "BC") with ClassNotFoundException for asymmetric.x509.CertificateFactory
-keepnames class org.bouncycastle.jcajce.provider.** {*;}
-keepnames class org.bouncycastle.jce.provider.** {*;}
-keepnames class org.bouncycastle.pqc.jcajce.provider.** {*;}
-dontwarn javax.naming.**

# jMDNS
-dontwarn javax.jmdns.impl.DNSCache
-dontwarn org.slf4j.**

# HiddenApiBypass reflects on its own helper classes
-keep class org.lsposed.hiddenapibypass.** {*;}
