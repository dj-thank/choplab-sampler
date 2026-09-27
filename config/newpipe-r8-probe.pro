# Retain the real entry while host adoption is pending, so R8 cannot pass by deleting the adapter.
-keep,allowoptimization class com.choplab.sampler.source.newpipe.NewPipeSourceBackend { *; }
