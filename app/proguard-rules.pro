# JNI resolves Java classes, methods and fields by their original names. The 1.30.0
# AAR consumer rules cover telemetry only; the inference Java API must also survive R8.
-keep class ai.onnxruntime.** { *; }

# YoutubeDL.getInfo uses Jackson ObjectMapper.readValue(VideoInfo.class). Its mapper
# beans are reflective, including nested formats/subtitles/thumbnails and annotation names.
-keepattributes Signature,*Annotation*
-keep class com.yausername.youtubedl_android.mapper.** { *; }

# YoutubeDL.init opens its Python ZIP through commons-compress:1.12. ExtraFieldUtils
# registers exactly these 13 types via Class.newInstance() (and uses it again when
# parsing). Keep their concrete classes/public no-arg constructors; names may change.
# Source: commons-compress-1.12-sources.jar, ExtraFieldUtils.java:40-54,64-89.
# SHA-256: ae08ac41171c3805f5e8ff04c29a95163a1bf8fb25ccff49581f7af6c902606d
-keep,allowobfuscation class org.apache.commons.compress.archivers.zip.AsiExtraField { public <init>(); }
-keep,allowobfuscation class org.apache.commons.compress.archivers.zip.X5455_ExtendedTimestamp { public <init>(); }
-keep,allowobfuscation class org.apache.commons.compress.archivers.zip.X7875_NewUnix { public <init>(); }
-keep,allowobfuscation class org.apache.commons.compress.archivers.zip.JarMarker { public <init>(); }
-keep,allowobfuscation class org.apache.commons.compress.archivers.zip.UnicodePathExtraField { public <init>(); }
-keep,allowobfuscation class org.apache.commons.compress.archivers.zip.UnicodeCommentExtraField { public <init>(); }
-keep,allowobfuscation class org.apache.commons.compress.archivers.zip.Zip64ExtendedInformationExtraField { public <init>(); }
-keep,allowobfuscation class org.apache.commons.compress.archivers.zip.X000A_NTFS { public <init>(); }
-keep,allowobfuscation class org.apache.commons.compress.archivers.zip.X0014_X509Certificates { public <init>(); }
-keep,allowobfuscation class org.apache.commons.compress.archivers.zip.X0015_CertificateIdForFile { public <init>(); }
-keep,allowobfuscation class org.apache.commons.compress.archivers.zip.X0016_CertificateIdForCentralDirectory { public <init>(); }
-keep,allowobfuscation class org.apache.commons.compress.archivers.zip.X0017_StrongEncryptionHeader { public <init>(); }
-keep,allowobfuscation class org.apache.commons.compress.archivers.zip.X0019_EncryptionRecipientCertificateList { public <init>(); }

# NewPipeExtractor's upstream rules. Do not remove its reflective Rhino/protobuf entry paths.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
# NewPipe's JavaScript entry uses Rhino's interpreter and safe standard objects, not
# JVM bytecode generation, JSR-223, or JavaBeans. Keep these exclusions confined to
# the upstream optional bridges (see NewPipeRhinoCompatibilityTest).
# Upstream rules: TeamNewPipe/NewPipe@7e5df38aad4b2c035332b3f71aee3064d4fdaae4.
-dontwarn org.mozilla.javascript.JavaToJSONConverters
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
-keep class org.schabi.newpipe.extractor.timeago.patterns.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
# The new adapter is retained even while the formal release still exposes the legacy launcher.
-keep,allowoptimization class com.choplab.sampler.source.newpipe.NewPipeSourceBackend { *; }
