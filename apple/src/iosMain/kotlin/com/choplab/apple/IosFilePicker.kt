package com.choplab.apple

import kotlinx.coroutines.*
import platform.Foundation.NSURL
import platform.UIKit.*
import platform.UniformTypeIdentifiers.*
import platform.darwin.NSObject
import kotlin.coroutines.resume

/** UIKit makes an app-owned copy; security-scoped external URLs never outlive the picker. */
internal class IosFilePicker(private val parent: () -> UIViewController) {
    private var active: UIDocumentPickerViewController? = null
    private var delegate: UIDocumentPickerDelegateProtocol? = null
    suspend fun openAudio() = open(listOf(UTTypeAudio))
    suspend fun openProject() = open(listOf(UTTypeData))
    private suspend fun open(types: List<UTType>): HostFile? = withContext(Dispatchers.Main) {
        if (active != null) return@withContext null
        suspendCancellableCoroutine { continuation ->
            val picker = UIDocumentPickerViewController(forOpeningContentTypes = types, asCopy = true)
            fun finish(value: HostFile?) {
                active = null; delegate = null
                if (continuation.isActive) continuation.resume(value)
            }
            val owner = object : NSObject(), UIDocumentPickerDelegateProtocol {
                override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
                    val url = didPickDocumentsAtURLs.singleOrNull() as? NSURL
                    finish(url?.path?.let { path -> HostFile(path, url.lastPathComponent ?: "audio") })
                }
                override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) { finish(null) }
            }
            picker.delegate = owner
            picker.allowsMultipleSelection = false
            delegate = owner; active = picker
            continuation.invokeOnCancellation { dispatchMain { picker.dismissViewControllerAnimated(true, null); active = null; delegate = null } }
            if (continuation.isActive) parent().presentViewController(picker, true, null)
        }
    }
    suspend fun share(url: NSURL): Boolean = withContext(Dispatchers.Main) {
        val presenter = parent()
        if (presenter.presentedViewController != null) return@withContext false
        val sheet = UIActivityViewController(listOf(url), null)
        sheet.popoverPresentationController?.sourceView = presenter.view
        sheet.popoverPresentationController?.sourceRect = presenter.view.bounds
        presenter.presentViewController(sheet, true, null)
        true
    }
}

private fun dispatchMain(block: () -> Unit) = platform.darwin.dispatch_async(platform.darwin.dispatch_get_main_queue(), block)
