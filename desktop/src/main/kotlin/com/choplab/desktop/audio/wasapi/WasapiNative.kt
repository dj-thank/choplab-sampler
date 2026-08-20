package com.choplab.desktop.audio.wasapi

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.COM.COMUtils
import com.sun.jna.platform.win32.COM.Unknown
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.WinNT

internal val CLSID_MM_DEVICE_ENUMERATOR = Guid.CLSID("{BCDE0395-E52F-467C-8E3D-C4579291692E}")
internal val IID_MM_DEVICE_ENUMERATOR = Guid.IID("{A95664D2-9614-4F35-A746-DE8DB63617E6}")
internal val IID_AUDIO_CLIENT = Guid.IID("{1CB9AD4C-DBFA-4C32-B178-C2F568A703B2}")

internal const val CLSCTX_ALL = 0x17
internal const val E_RENDER = 0
internal const val E_CAPTURE = 1
internal const val E_CONSOLE = 0
internal const val E_MULTIMEDIA = 1
internal const val E_COMMUNICATIONS = 2
internal const val DEVICE_STATE_ACTIVE = 0x00000001
internal const val DEVICE_STATE_ALL = 0x0000000F
internal val HRESULT_NOT_FOUND: Int = 0x80070490u.toInt()

internal class WasapiException(
    operation: String,
    val hresult: Int,
) : IllegalStateException("$operation failed with HRESULT 0x${hresult.toUInt().toString(16).uppercase().padStart(8, '0')}")

internal fun checkHResult(operation: String, result: WinNT.HRESULT) {
    if (COMUtils.FAILED(result)) throw WasapiException(operation, result.toInt())
}

internal open class WasapiComObject(pointer: Pointer) : Unknown(pointer) {
    protected fun invokeHResult(vtableIndex: Int, vararg arguments: Any?): WinNT.HRESULT {
        val nativeArguments = arrayOfNulls<Any>(arguments.size + 1)
        nativeArguments[0] = pointer
        arguments.copyInto(nativeArguments, destinationOffset = 1)
        return _invokeNativeObject(vtableIndex, nativeArguments, WinNT.HRESULT::class.java) as WinNT.HRESULT
    }
}

internal inline fun <T> withSta(block: () -> T): T {
    val result = Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, Ole32.COINIT_APARTMENTTHREADED)
    checkHResult("CoInitializeEx(STA)", result)
    return try {
        block()
    } finally {
        Ole32.INSTANCE.CoUninitialize()
    }
}

internal fun isWindows(): Boolean =
    System.getProperty("os.name").contains("Windows", ignoreCase = true)
