package com.choplab.sampler.audio

import java.io.File

/** Owns only ChopLab's transient capture WAVs under one app-private cache directory. */
internal class CaptureTempFileStore(private val directory: File) {
    fun create(kind: String, nowMillis: Long = System.currentTimeMillis()): File {
        require(kind in OWNED_KINDS) { "未対応の一時録音種別です" }
        require(directory.exists() || directory.mkdirs()) { "一時録音フォルダーを作成できません" }
        return File(directory, "${kind}_${nowMillis}.wav")
    }

    suspend fun <T> consume(file: File, action: suspend () -> T): T {
        require(isOwned(file)) { "アプリ所有外の音声は一時録音として削除できません" }
        return try {
            action()
        } finally {
            runCatching { file.delete() }
        }
    }

    fun deleteOwned(file: File): Boolean = isOwned(file) && (!file.exists() || file.delete())

    fun cleanupStale(nowMillis: Long, maxAgeMillis: Long): Int {
        val cutoff = nowMillis - maxAgeMillis.coerceAtLeast(0L)
        return directory.listFiles().orEmpty().count { file ->
            file.isFile && isOwned(file) && file.lastModified() <= cutoff && file.delete()
        }
    }

    private fun isOwned(file: File): Boolean {
        val expectedParent = runCatching { directory.canonicalFile }.getOrNull() ?: return false
        val actualParent = runCatching { file.canonicalFile.parentFile }.getOrNull() ?: return false
        return actualParent == expectedParent && OWNED_NAME.matches(file.name)
    }

    private companion object {
        val OWNED_KINDS = setOf("microphone", "system", "vocal")
        val OWNED_NAME = Regex("(?:microphone|system|vocal)_[0-9]+\\.wav")
    }
}
