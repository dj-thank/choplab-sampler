package com.choplab.sampler.next

import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.choplab.core.*
import com.choplab.core.model.*
import com.choplab.core.separation.SeparationMemoryReceipt
import com.choplab.core.separation.SeparationMemorySource
import com.choplab.jvm.*
import com.choplab.jvm.separation.SeparationMemory
import com.choplab.sampler.BuildConfig
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.AssumptionViolatedException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/** Owned synthetic fixtures only. No activity, permission request, native audio device or network. */
internal class OfflineRuntimeFixture private constructor(val context: Context, val directory: Path, private val scope: String) {
    private val locations = mutableMapOf<String, Path>()
    val backend = EditorBackend.create(directory.resolve("profile"),
        { StreamingEnginePort(it, { error("No audio endpoint in offline runtime acceptance") }) }, { assets, compiler ->
            HostFileServices(WavImportPort(assets, { locations.getValue(it.handle) }),
                FileProjectPort(assets, { locations.getValue(it.handle) }), WavExportPort(compiler, { locations.getValue(it.handle) }))
        })

    fun original(): Asset {
        val file = directory.resolve("synthetic-original.wav")
        Files.newOutputStream(file).use { output ->
            WavCodec.writePcm(output, FloatArray(24_000 * 2) { index ->
                val frame = index / 2
                (.3 * exp(-(frame % 6_000) / 48_000.0 * 35) *
                    sin(frame / 48_000.0 * Math.PI * 2 * if (index % 2 == 0) 110.0 else 173.0)).toFloat()
            }, bits = 24, dither = false)
        }
        val bytes = Files.readAllBytes(file)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        val asset = Asset(hash, "wav", bytes.size.toLong(), 48_000, 2, 24_000, "Synthetic original.wav")
        backend.assets.adopt(asset, file)
        return asset
    }

    suspend fun exportAndReopen(project: Project, original: Asset, originalBytes: ByteArray, frames: Int) {
        val path = directory.resolve("export.wav"); locations["export"] = path
        check(backend.studio.dispatch(Action.Export(ExportRequest(Location("export"), frames, tailFrames = 0, bits = 24),
            PlaybackTarget.Arrangement())).accepted)
        withTimeout(15_000) { while (backend.studio.work.value.jobId != null) delay(5) }
        val rendered = Files.newInputStream(path).use(WavCodec::read)
        check(rendered.info.bits == 24 && rendered.info.channels == 2 && rendered.info.frames == frames.toLong())
        check(rendered.samples.all { it.isFinite() } && rendered.samples.any { abs(it) > .00001f })
        val archive = ByteArrayOutputStream().also { ArchiveCodec().write(project, backend.assets, it) }.toByteArray()
        val fresh = FileAssetStore(directory.resolve("fresh"))
        check(ArchiveCodec().read(ByteArrayInputStream(archive), fresh) == project)
        project.assets.forEach { check(fresh.read(it).contentEquals(backend.assets.read(it))) }
        backend.flushAutosave()
        check(AutosaveStore(directory.resolve("profile/autosave"), fresh).recover()?.project == project)
        check(backend.assets.read(original).contentEquals(originalBytes))
    }

    fun memory(): SeparationMemory {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return SeparationMemory(info.totalMem, info.availMem, info.lowMemory, SeparationMemoryReceipt(
            SeparationMemorySource.ANDROID_ACTIVITY_MANAGER, info.totalMem, info.availMem, info.lowMemory, System.currentTimeMillis()))
    }

    fun externalAcceptance(): Path {
        val external = requireNotNull(context.getExternalFilesDir(null)).toPath()
        check(!Files.isSymbolicLink(external))
        val acceptance = external.resolve("acceptance")
        Files.createDirectories(acceptance)
        check(Files.isDirectory(acceptance, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(acceptance))
        check(acceptance.toRealPath().parent == external.toRealPath())
        return acceptance
    }

    fun receipt(status: String, fields: Map<String, Any> = emptyMap()) {
        val json = JSONObject(linkedMapOf<String, Any>("status" to status, "scope" to scope,
            "syntheticInput" to true, "modelDownload" to false, "audioEndpoint" to false,
            "humanAcceptance" to false) + fields).toString()
        val report = Files.createTempFile(externalAcceptance(), "$scope-", ".json")
        Files.write(report, json.toByteArray(Charsets.UTF_8))
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("choplabOfflineRuntimeReceipt", json) })
    }

    fun unavailable(problem: String): Nothing {
        receipt("UNAVAILABLE", mapOf("problem" to problem))
        assumeTrue("UNAVAILABLE: $scope / $problem; this skip is not runtime proof", false)
        error("Unreachable")
    }

    companion object {
        suspend fun run(flag: String, scope: String, body: suspend (OfflineRuntimeFixture) -> Map<String, Any>) {
            assumeTrue("NOT_RUN: explicit $flag=true required; this skip is not runtime proof",
                InstrumentationRegistry.getArguments().getString(flag) == "true")
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            check(context.packageName == "com.choplab.sampler.preview" && BuildConfig.BUILD_TYPE == "preview" && !BuildConfig.DEBUG)
            val memory = PcmMemoryBudget.shared
            check(memory.statistics().usedBytes == 0L) { "Run each fixture in an isolated idle instrumentation process" }
            val directory = Files.createTempDirectory(context.cacheDir.toPath(), "offline-runtime-")
            val fixture = OfflineRuntimeFixture(context, directory, scope)
            val result: Map<String, Any>
            try {
                try { result = body(fixture) }
                finally {
                    try { fixture.backend.shutdown() }
                    finally {
                        val deleted = directory.toFile().deleteRecursively()
                        withTimeout(10_000) { while (memory.statistics().usedBytes != 0L) delay(5) }
                        check(deleted) { "Owned fixture cleanup failed" }
                    }
                }
            } catch (failure: Throwable) {
                if (failure !is AssumptionViolatedException) fixture.receipt("FAILED", mapOf("failureType" to failure.javaClass.simpleName))
                throw failure
            }
            val stats = memory.statistics()
            check(stats.peakBytes <= stats.limitBytes)
            fixture.receipt("LOCAL_PASS", result + mapOf("pcmRetainedBytes" to 0, "ownedTemporaryRemaining" to 0,
                "pcmPeakBytes" to stats.peakBytes, "pcmLimitBytes" to stats.limitBytes))
        }
    }
}
