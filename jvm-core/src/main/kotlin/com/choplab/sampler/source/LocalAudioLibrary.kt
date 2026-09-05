package com.choplab.sampler.source

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

/** Content-addressed private library. No paths or audio are stored in project source/assets. */
class LocalAudioLibrary(val directory: File, private val validateAudio: (File)->Unit) {
    companion object {
        val extensions = setOf("wav", "mp3", "m4a", "aac", "flac", "ogg", "opus", "mp4", "webm", "aiff", "aif")
        const val MAX_FILE_BYTES = 256L * 1024 * 1024
        private val idPattern = Regex("[a-f0-9]{64}")
    }
    init { check(directory.isDirectory || directory.mkdirs()) { "音源ライブラリを作成できません" } }

    fun list(): List<AudioLibraryItem> = directory.listFiles().orEmpty().asSequence()
        .filter { it.extension == "properties" && idPattern.matches(it.nameWithoutExtension) }
        .sortedByDescending(File::lastModified).take(2000).mapNotNull { meta ->
            runCatching {
                val properties = readProperties(meta)
                val file = resolve(meta.nameWithoutExtension)
                AudioLibraryItem(meta.nameWithoutExtension, properties.getProperty("title").take(240),
                    properties.getProperty("origin", "ファイル"), file.length())
            }.getOrNull()
        }.toList()

    fun resolve(id: String): File {
        require(idPattern.matches(id)) { "音源IDが不正です" }
        val meta = readProperties(File(directory, "$id.properties"))
        val extension = meta.getProperty("extension")
        require(extension in extensions) { "未対応の音源形式です" }
        return File(directory, "$id.$extension").also { check(it.isFile) { "音源ファイルが見つかりません" } }
    }

    fun importFile(file: File, title: String = file.nameWithoutExtension, origin: String = "ファイル"): AudioLibraryItem {
        require(file.isFile && file.extension.lowercase() in extensions) { "対応する音声・動画ファイルを選んでください" }
        require(file.length() in 1..MAX_FILE_BYTES) { "音源ファイルが大きすぎるか空です" }
        return file.inputStream().use { importStream(it, file.extension.lowercase(), title, origin) }
    }

    @Synchronized fun importStream(input: InputStream, extension: String, title: String, origin: String): AudioLibraryItem {
        require(extension in extensions) { "未対応の音源形式です" }
        val temp = File(directory, ".import-${UUID.randomUUID()}.$extension")
        val hash = MessageDigest.getInstance("SHA-256")
        var count = 0L
        try {
            temp.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val length = input.read(buffer)
                    if (length < 0) break
                    count += length
                    require(count <= MAX_FILE_BYTES) { "音源は256MiBまでです" }
                    hash.update(buffer, 0, length)
                    output.write(buffer, 0, length)
                }
                output.fd.sync()
            }
            require(count > 0) { "音源が空です" }
            val id = hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            val metadata = File(directory, "$id.properties")
            if (metadata.isFile) {
                runCatching {
                    val known = readProperties(metadata)
                    AudioLibraryItem(id, known.getProperty("title"), known.getProperty("origin", "ファイル"), resolve(id).length())
                }.getOrNull()?.let { return it }
            }
            validateAudio(temp)
            if(Thread.currentThread().isInterrupted) throw InterruptedException()
            val destination = File(directory, "$id.$extension")
            Files.move(temp.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            val cleanTitle = title.filter { it.code >= 32 }.take(240).ifBlank { "音源" }
            val properties = Properties().apply {
                setProperty("title", cleanTitle); setProperty("extension", extension)
                setProperty("origin", origin.filter { it.code >= 32 }.take(1024))
            }
            val metaTemp = File(directory, ".$id-${UUID.randomUUID()}.metadata")
            try {
                metaTemp.writer(Charsets.UTF_8).use { properties.store(it, "ChopLab personal audio") }
                Files.move(metaTemp.toPath(), metadata.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally { metaTemp.delete() }
            return AudioLibraryItem(id, cleanTitle, properties.getProperty("origin"), count)
        } finally { temp.delete() }
    }

    fun importBundle(file: File): List<AudioLibraryItem> = file.inputStream().use { importBundle(it) }

    @Synchronized fun importBundle(input: InputStream): List<AudioLibraryItem> {
        val root=directory.canonicalFile
        val staging=File(root,".bundle-${UUID.randomUUID()}").apply { check(mkdir()) }
        check(staging.canonicalFile.parentFile==root)
        try {
            val prepared=LocalAudioLibrary(staging,validateAudio)
            var total=0L;var entries=0;var audioEntries=0
            ZipInputStream(input.buffered()).use { zip ->
                while(true) {
                    if(Thread.currentThread().isInterrupted)throw InterruptedException()
                    val entry=zip.nextEntry?:break
                    require(++entries<=128) { "音源セット内のファイルが多すぎます" }
                    val leaf=entry.name.replace('\\','/').substringAfterLast('/')
                    val extension=leaf.substringAfterLast('.',"").lowercase()
                    val bounded=object:InputStream() {
                        override fun read():Int { val n=zip.read();if(n>=0)checkTotal(1);return n }
                        override fun read(b:ByteArray,off:Int,len:Int):Int { val n=zip.read(b,off,len);if(n>0)checkTotal(n);return n }
                        private fun checkTotal(n:Int) {
                            if(Thread.currentThread().isInterrupted)throw InterruptedException()
                            total+=n;require(total<=1024L*1024*1024) { "音源セットは1GiBまでです" }
                        }
                    }
                    if(!entry.isDirectory && extension in extensions) {
                        require(++audioEntries<=32) { "音源セットは一度に32件までです" }
                        prepared.importStream(bounded,extension,leaf.substringBeforeLast('.'),"音源セット")
                    } else {
                        val discard=ByteArray(8192)
                        while(bounded.read(discard)>=0) { }
                    }
                }
            }
            val items=prepared.list()
            require(items.isNotEmpty()) { "セット内に対応音源がありません" }
            // Validation/ZIP failures cannot publish any of the staged items.
            return items.map { importFile(prepared.resolve(it.id),it.title,it.origin) }
        } finally {
            check(staging.canonicalFile.parentFile==root)
            staging.deleteRecursively()
        }
    }

    fun exportBundle(target: File) {
        val entries=list()
        require(entries.size<=32) { "音源セットは32件までです。ライブラリを分けて書き出してください" }
        require(entries.sumOf { it.bytes }<=1024L*1024*1024) { "音源セットは1GiBまでです" }
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            val usedNames=mutableSetOf<String>()
            entries.forEach { item ->
                val file = resolve(item.id)
                val safeTitle = item.title.replace(Regex("""[^\p{L}\p{N} ._()-]"""), "_").take(100)
                var name="$safeTitle.${file.extension}"
                var suffix=2
                while(!usedNames.add(name)) { name="$safeTitle (${suffix++}).${file.extension}" }
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            }
        }
    }

    private fun readProperties(file: File): Properties {
        require(file.length() in 1..16384) { "音源情報が不正です" }
        return Properties().apply { file.reader(Charsets.UTF_8).use(::load) }
    }
}
