# ChopLab currently keeps release shrinking disabled. Add project-specific rules here when enabled.

# NewPipeExtractor's upstream rules, needed when the release size slice enables R8.
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
