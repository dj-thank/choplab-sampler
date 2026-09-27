package com.choplab.desktop.next

import com.choplab.sampler.source.*
import java.io.File
import java.nio.file.Path

/** Retained desktop entry point backed by the same owned worker used by Android. */
internal class NextOnline(directory: Path, validate: (File) -> Unit, backend: YoutubeSourceBackend) : AutoCloseable {
    internal val session = OnlineSourceSession(directory, validate, backend)
    val state = session.state
    val detailed get() = session.detailed
    fun search(query: String, kind: YoutubeSearchKind = YoutubeSearchKind.VIDEOS) = session.search(query, kind)
    fun inspect(id: String) = session.inspect(id)
    fun selectFormat(id: String) = session.selectFormat(id)
    fun acquire(id: String) = session.acquire(id)
    fun cancel() = session.cancel()
    override fun close() = session.close()
}
