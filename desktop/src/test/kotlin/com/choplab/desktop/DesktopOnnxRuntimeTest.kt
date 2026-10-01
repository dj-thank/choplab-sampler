package com.choplab.desktop

import ai.onnxruntime.OrtEnvironment
import com.sun.jna.Native
import com.sun.jna.Platform
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopOnnxRuntimeTest {
    @Test fun directJavaLaunchDisablesNativeTelemetryBeforeOrtAndExitsNormally() {
        for (inherited in listOf(null, "0")) {
            val child = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path,
                "-cp", System.getProperty("java.class.path"), OrtLifecycleChild::class.java.name,
                inherited ?: "absent").redirectErrorStream(true)
            if (inherited == null) child.environment().remove("ORT_DISABLE_TELEMETRY")
            else child.environment()["ORT_DISABLE_TELEMETRY"] = inherited
            val process = child.start()
            try {
                assertTrue(process.waitFor(30, TimeUnit.SECONDS), "ORT child must exit naturally")
                val output = process.inputStream.bufferedReader().readText()
                assertEquals(0, process.exitValue(), output)
                assertTrue(output.contains("ORT_LIFECYCLE_PASS"), output)
            } finally { if (process.isAlive) process.destroyForcibly() }
        }
    }
}

/** A new native runtime per case: neither Gradle's environment nor a previously initialized ORT can mask ordering. */
object OrtLifecycleChild {
    @JvmStatic fun main(args: Array<String>) {
        val inherited = args.single().takeUnless { it == "absent" }
        check(System.getenv("ORT_DISABLE_TELEMETRY") == inherited)
        prepareDesktopOnnxRuntime()
        if (Platform.isWindows()) {
            check(System.getenv("ORT_DISABLE_TELEMETRY") == inherited)
        } else {
            check(Native.load(Platform.C_LIBRARY_NAME, OrtProcessEnvironment::class.java)
                .getenv("ORT_DISABLE_TELEMETRY") == "1")
        }
        // This is deliberately the first ORT access, after checking the real C getenv value.
        val environment = OrtEnvironment.getEnvironment().also { it.setTelemetry(false) }
        check(environment.version == "1.30.0")
        environment.close()
        println("ORT_LIFECYCLE_PASS version=${environment.version}")
    }
}
