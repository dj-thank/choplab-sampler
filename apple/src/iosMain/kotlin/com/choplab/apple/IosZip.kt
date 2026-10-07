package com.choplab.apple

import kotlinx.cinterop.*
import platform.zlib.*

/**
 * The ZIP subset `.choplab` archives use: DEFLATE (JVM ZipOutputStream, data descriptors) or STORED entries,
 * no encryption, no ZIP64, read through the central directory. Writes DEFLATE entries with data descriptors,
 * which the JVM hosts' ZipInputStream reads.
 */
internal object IosZip {
    const val MAX_ENTRIES = 4_096
    private const val LOCAL = 0x04034b50L
    private const val CENTRAL = 0x02014b50L
    private const val END = 0x06054b50L
    private const val DESCRIPTOR = 0x08074b50L

    class Entry(val name: String, val method: Int, val crc: Long, val compressedSize: Long, val size: Long,
                internal val localHeaderOffset: Long, internal val flags: Int)

    /** Central-directory entries in their stored order. */
    fun entries(path: String): List<Entry> {
        val fileSize = requireNotNull(regularFileSize(path)) { "Archive unavailable" }
        require(fileSize >= 22) { "Not a ZIP archive" }
        return FileReader(path).use { reader ->
            val tailLength = minOf(fileSize, 22L + 65_535).toInt()
            val tail = ByteArray(tailLength)
            reader.seek(fileSize - tailLength); reader.readFully(tail)
            var end = -1
            for (at in tailLength - 22 downTo 0) if (tail.u32(at) == END) { end = at; break }
            require(end >= 0) { "ZIP end record missing" }
            require(tail.u16(end + 4) == 0 && tail.u16(end + 6) == 0) { "Multi-disk ZIP is not supported" }
            val count = tail.u16(end + 10)
            val directorySize = tail.u32(end + 12)
            val directoryOffset = tail.u32(end + 16)
            require(count == tail.u16(end + 8) && count in 1..MAX_ENTRIES) { "ZIP entry count outside limits" }
            require(directoryOffset + directorySize <= fileSize - tailLength + end) { "ZIP directory outside the file" }
            require(directorySize <= 4L * 1024 * 1024) { "ZIP directory too large" }
            val directory = ByteArray(directorySize.toInt())
            reader.seek(directoryOffset); reader.readFully(directory)
            var at = 0
            List(count) {
                require(at + 46 <= directory.size && directory.u32(at) == CENTRAL) { "Corrupt ZIP directory" }
                val flags = directory.u16(at + 8)
                val method = directory.u16(at + 10)
                val nameLength = directory.u16(at + 28)
                val extraLength = directory.u16(at + 30)
                val commentLength = directory.u16(at + 32)
                require(flags and 1 == 0) { "Encrypted ZIP entries are not supported" }
                require(method == 0 || method == 8) { "Unsupported ZIP compression" }
                require(at + 46 + nameLength + extraLength + commentLength <= directory.size) { "Corrupt ZIP directory" }
                val name = directory.ascii(at + 46, nameLength)
                val entry = Entry(name, method, directory.u32(at + 16), directory.u32(at + 20), directory.u32(at + 24),
                    directory.u32(at + 42), flags)
                require(entry.compressedSize != 0xFFFFFFFFL && entry.size != 0xFFFFFFFFL && entry.localHeaderOffset != 0xFFFFFFFFL) { "ZIP64 is not supported" }
                at += 46 + nameLength + extraLength + commentLength
                entry
            }
        }
    }

    /**
     * Streams [entry]'s uncompressed bytes to [sink] in order, checking its size and CRC-32. [maxBytes] bounds the
     * expanded size before any byte is accepted, so a small archive cannot expand without limit.
     */
    fun extract(path: String, entry: Entry, maxBytes: Long, cancelled: () -> Boolean, sink: (ByteArray, Int) -> Unit) {
        require(entry.size <= maxBytes) { "ZIP entry exceeds limit" }
        FileReader(path).use { reader ->
            reader.seek(entry.localHeaderOffset)
            val local = ByteArray(30)
            reader.readFully(local)
            require(local.u32(0) == LOCAL && local.u16(8) == entry.method) { "Corrupt ZIP entry" }
            val nameLength = local.u16(26)
            val extraLength = local.u16(28)
            val localName = ByteArray(nameLength).also { reader.readFully(it) }
            require(localName.ascii(0, nameLength) == entry.name) { "ZIP entry names disagree" }
            if (extraLength > 0) reader.readFully(ByteArray(extraLength))
            val input = ByteArray(64 * 1024)
            var crc = crc32(0u, null, 0u)
            var produced = 0L
            fun emit(bytes: ByteArray, count: Int) {
                produced += count
                require(produced <= entry.size && produced <= maxBytes) { "ZIP entry larger than declared" }
                crc = bytes.usePinned { crc32(crc, it.addressOf(0).reinterpret(), count.convert()) }
                sink(bytes, count)
            }
            if (entry.method == 0) {
                require(entry.compressedSize == entry.size) { "Corrupt stored entry" }
                var left = entry.size
                while (left > 0) {
                    require(!cancelled()) { "Archive cancelled" }
                    val count = minOf(left, input.size.toLong()).toInt()
                    reader.readFully(input, 0, count)
                    emit(input, count); left -= count
                }
            } else inflate(reader, entry.compressedSize, input, cancelled, ::emit)
            require(produced == entry.size && crc.toLong() == entry.crc) { "ZIP entry size or CRC mismatch" }
        }
    }

    private fun inflate(reader: FileReader, compressedSize: Long, input: ByteArray, cancelled: () -> Boolean, emit: (ByteArray, Int) -> Unit) {
        memScoped {
            val stream = alloc<z_stream>()
            require(inflateInit2_(stream.ptr, -MAX_WBITS, ZLIB_VERSION, sizeOf<z_stream>().toInt()) == Z_OK) { "zlib unavailable" }
            val output = ByteArray(64 * 1024)
            try {
                var left = compressedSize
                var finished = false
                input.usePinned { inPinned ->
                    output.usePinned { outPinned ->
                        while (!finished) {
                            require(!cancelled()) { "Archive cancelled" }
                            if (stream.avail_in == 0u) {
                                require(left > 0) { "Truncated DEFLATE data" }
                                val count = minOf(left, input.size.toLong()).toInt()
                                reader.readFully(input, 0, count)
                                left -= count
                                stream.next_in = inPinned.addressOf(0).reinterpret()
                                stream.avail_in = count.convert()
                            }
                            stream.next_out = outPinned.addressOf(0).reinterpret()
                            stream.avail_out = output.size.convert()
                            val status = inflate(stream.ptr, Z_NO_FLUSH)
                            require(status == Z_OK || status == Z_STREAM_END) { "Corrupt DEFLATE data" }
                            val count = output.size - stream.avail_out.toInt()
                            if (count > 0) emit(output, count)
                            if (status == Z_STREAM_END) finished = true
                            else require(count > 0 || stream.avail_in == 0u || left > 0) { "Stalled DEFLATE data" }
                        }
                    }
                }
                require(left == 0L && stream.avail_in == 0u) { "Trailing DEFLATE data" }
            } finally { inflateEnd(stream.ptr) }
        }
    }

    /** Writes entries in call order: DEFLATE (level 6, like the JVM hosts) with data descriptors. */
    class Writer(private val output: FileWriter) : AutoCloseable {
        private class Written(val name: String, val crc: Long, val compressedSize: Long, val size: Long, val offset: Long)
        private val written = mutableListOf<Written>()
        private var finished = false

        fun add(name: String, cancelled: () -> Boolean, source: ((ByteArray, Int) -> Unit) -> Unit) {
            check(!finished)
            require(written.size < MAX_ENTRIES && written.none { it.name.equals(name, ignoreCase = true) })
            val nameBytes = name.encodeToByteArray()
            require(nameBytes.size == name.length) { "ZIP names are ASCII" }
            val offset = output.written
            val local = ByteArray(30 + nameBytes.size)
            local.put32(0, LOCAL); local.put16(4, 20); local.put16(6, 8); local.put16(8, 8)
            local.put16(10, 0); local.put16(12, 0x21) // 1980-01-01 00:00, as the JVM hosts write time 0
            local.put16(26, nameBytes.size); nameBytes.copyInto(local, 30)
            output.write(local)
            var crc = crc32(0u, null, 0u)
            var size = 0L
            var compressed = 0L
            memScoped {
                val stream = alloc<z_stream>()
                require(deflateInit2_(stream.ptr, 6, Z_DEFLATED, -MAX_WBITS, 8, Z_DEFAULT_STRATEGY, ZLIB_VERSION, sizeOf<z_stream>().toInt()) == Z_OK)
                val out = ByteArray(64 * 1024)
                try {
                    out.usePinned { outPinned ->
                        fun drain(flush: Int) {
                            do {
                                stream.next_out = outPinned.addressOf(0).reinterpret()
                                stream.avail_out = out.size.convert()
                                val status = deflate(stream.ptr, flush)
                                require(status == Z_OK || status == Z_STREAM_END || status == Z_BUF_ERROR) { "DEFLATE failed" }
                                val count = out.size - stream.avail_out.toInt()
                                if (count > 0) { output.write(out, 0, count); compressed += count }
                            } while (stream.avail_out == 0u || (flush == Z_FINISH && status != Z_STREAM_END))
                        }
                        source { bytes, count ->
                            require(!cancelled()) { "Archive cancelled" }
                            if (count > 0) bytes.usePinned { pinned ->
                                crc = crc32(crc, pinned.addressOf(0).reinterpret(), count.convert())
                                stream.next_in = pinned.addressOf(0).reinterpret()
                                stream.avail_in = count.convert()
                                drain(Z_NO_FLUSH)
                                require(stream.avail_in == 0u)
                            }
                            size += count
                        }
                        drain(Z_FINISH)
                    }
                } finally { deflateEnd(stream.ptr) }
            }
            require(size < 0xFFFFFFFFL && compressed < 0xFFFFFFFFL && output.written < 0xFFFFFFFFL) { "ZIP64 is not supported" }
            val descriptor = ByteArray(16)
            descriptor.put32(0, DESCRIPTOR); descriptor.put32(4, crc.toLong()); descriptor.put32(8, compressed); descriptor.put32(12, size)
            output.write(descriptor)
            written += Written(name, crc.toLong(), compressed, size, offset)
        }

        fun finish() {
            check(!finished)
            finished = true
            val directoryOffset = output.written
            for (entry in written) {
                val name = entry.name.encodeToByteArray()
                val central = ByteArray(46 + name.size)
                central.put32(0, CENTRAL); central.put16(4, 20); central.put16(6, 20); central.put16(8, 8); central.put16(10, 8)
                central.put16(12, 0); central.put16(14, 0x21)
                central.put32(16, entry.crc); central.put32(20, entry.compressedSize); central.put32(24, entry.size)
                central.put16(28, name.size); central.put32(42, entry.offset); name.copyInto(central, 46)
                output.write(central)
            }
            val directorySize = output.written - directoryOffset
            val end = ByteArray(22)
            end.put32(0, END); end.put16(8, written.size); end.put16(10, written.size)
            end.put32(12, directorySize); end.put32(16, directoryOffset)
            output.write(end)
        }

        override fun close() {}
    }
}

private fun ByteArray.put16(at: Int, value: Int) { this[at] = value.toByte(); this[at + 1] = (value ushr 8).toByte() }
private fun ByteArray.put32(at: Int, value: Long) { for (i in 0 until 4) this[at + i] = (value ushr (i * 8)).toByte() }
