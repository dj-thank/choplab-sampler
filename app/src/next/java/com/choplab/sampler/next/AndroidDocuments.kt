package com.choplab.sampler.next

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.choplab.core.Location
import com.choplab.jvm.HostDocuments
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Storage Access Framework documents behind opaque handles. URIs stay in this process and never enter a
 * Project. Only documents this app created may be discarded after a failed write.
 */
class AndroidDocuments(private val resolver: ContentResolver) : HostDocuments {
    private class Entry(val uri: Uri, val created: Boolean)
    private val entries = ConcurrentHashMap<String, Entry>()

    /** A document the user picked to read. */
    fun opened(uri: Uri): Location = register(Entry(uri, created = false))
    /** A document the picker has just created for this app to fill. */
    fun created(uri: Uri): Location = register(Entry(uri, created = true))
    fun uri(location: Location): Uri = entry(location).uri

    override fun openInput(location: Location): InputStream =
        requireNotNull(resolver.openInputStream(entry(location).uri)) { "Document is unavailable" }

    override fun openOutput(location: Location): OutputStream =
        // "wt" truncates, so a shorter file never keeps the tail of an earlier one.
        requireNotNull(resolver.openOutputStream(entry(location).uri, "wt")) { "Document is unavailable" }

    override fun displayName(location: Location): String? {
        val uri = entry(location).uri
        return runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
    }

    override fun discard(location: Location) {
        val entry = entries[location.handle] ?: return
        if (entry.created) runCatching { DocumentsContract.deleteDocument(resolver, entry.uri) }
    }

    private fun register(entry: Entry): Location = Location(UUID.randomUUID().toString()).also { entries[it.handle] = entry }
    private fun entry(location: Location): Entry = requireNotNull(entries[location.handle]) { "Unknown document" }
}
