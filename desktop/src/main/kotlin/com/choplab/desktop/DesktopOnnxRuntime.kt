package com.choplab.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform

/** Run before any ORT class initializes, including direct execution of the packaged launcher. */
internal fun prepareDesktopOnnxRuntime() {
    if (Platform.isWindows()) return // Windows uses ORT's per-environment ETW API, not 1DS.
    val process = Native.load(Platform.C_LIBRARY_NAME, OrtProcessEnvironment::class.java)
    // Java's cached System.getenv map does not change native getenv. ORT reads the latter.
    check(process.setenv("ORT_DISABLE_TELEMETRY", "1", 1) == 0 &&
        process.getenv("ORT_DISABLE_TELEMETRY") == "1") { "Cannot disable ONNX Runtime telemetry before initialization" }
}

internal interface OrtProcessEnvironment : Library {
    fun setenv(name: String, value: String, overwrite: Int): Int
    fun getenv(name: String): String?
}
