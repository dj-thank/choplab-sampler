# AndroidX runner/monitor carry Error Prone build-time annotations without the
# annotation artifact. Both annotations have CLASS retention (explicit/default),
# so the instrumentation runtime does not load them. No runtime class is ignored.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.MustBeClosed
