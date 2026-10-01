package com.choplab.jvm.separation

import ai.onnxruntime.*
import com.choplab.core.separation.SeparationProblem
import com.choplab.jvm.PcmMemoryBudget
import kotlinx.coroutines.runBlocking
import java.io.Closeable
import java.nio.FloatBuffer
import java.nio.file.Path

/** The FloatBuffer is borrowed only during [consume]; it is never retained in document/UI state. */
interface FourStemInference : Closeable {
    fun infer(channelMajor: FloatArray, check: () -> Unit, consume: (FloatBuffer) -> Unit)
    fun cancel()
}

fun interface FourStemSessionFactory {
    fun open(memory: PcmMemoryBudget, available: SeparationMemory, allowDownload: Boolean, check: () -> Unit): FourStemInference
}

class OnnxFourStemFactory(private val models: FourStemModelStore) : FourStemSessionFactory {
    override fun open(memory: PcmMemoryBudget, available: SeparationMemory, allowDownload: Boolean, check: () -> Unit): FourStemInference {
        available.refusal()?.let { throw SeparationException(it) }
        return OnnxFourStemInference(models.ensure(allowDownload, check = check), memory, available, check)
    }
}

/** One real session for four genuine heads. Session close happens after the worker has left native inference. */
class OnnxFourStemInference(model: Path, private val memory: PcmMemoryBudget = PcmMemoryBudget.shared,
                            available: SeparationMemory, check: () -> Unit = {}) : FourStemInference {
    private val environment: OrtEnvironment
    private val session: OrtSession
    private val runLock = Any()
    private var running: OrtSession.RunOptions? = null
    @Volatile private var closed = false
    init {
        available.refusal()?.let { throw SeparationException(it) }
        FourStemModelStore.verify(model, check)
        check()
        // Hosts disable non-Windows 1DS before native initialization; the API also disables Windows ETW.
        environment = OrtEnvironment.getEnvironment().also { it.setTelemetry(false) }
        val opened = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(1); options.setInterOpNumThreads(1)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
            options.setCPUArenaAllocator(false); options.setMemoryPatternOptimization(false)
            options.addConfigEntry("session.disable_prepacking", "1")
            environment.createSession(model.toString(), options)
        }
        try {
            val input = opened.inputInfo["mix"]?.info as? TensorInfo
            val output = opened.outputInfo["stems"]?.info as? TensorInfo
            if (opened.inputNames != setOf("mix") || opened.outputNames != setOf("stems") ||
                input?.type != OnnxJavaType.FLOAT || output?.type != OnnxJavaType.FLOAT ||
                !declaredShape(input.shape, longArrayOf(1, 2, FourStemSpec.FRAMES.toLong())) ||
                !declaredShape(output.shape, longArrayOf(1, 4, 2, FourStemSpec.FRAMES.toLong())))
                throw SeparationException(SeparationProblem.MODEL_INVALID)
            check(); session = opened
        } catch (failure: Throwable) { opened.close(); throw failure }
    }
    override fun infer(channelMajor: FloatArray, check: () -> Unit, consume: (FloatBuffer) -> Unit) {
        check(!closed)
        require(channelMajor.size == FourStemSpec.FRAMES * 2 && channelMajor.all { it.isFinite() })
        check()
        // Native input, native output and Java output views/copies are charged separately from model activations.
        runBlocking { memory.reserve(FourStemSpec.NATIVE_IO_PCM_BYTES) }.use {
            OrtSession.RunOptions().use { options ->
                synchronized(runLock) { check(running == null && !closed); running = options }
                try {
                    check()
                    OnnxTensor.createTensor(environment, FloatBuffer.wrap(channelMajor), longArrayOf(1, 2, FourStemSpec.FRAMES.toLong())).use { input ->
                        session.run(mapOf("mix" to input), options).use { result ->
                            check()
                            val output = result.get("stems").orElse(null) as? OnnxTensor ?: throw SeparationException(SeparationProblem.INVALID_OUTPUT)
                            if (output.info.type != OnnxJavaType.FLOAT || !output.info.shape.contentEquals(longArrayOf(1, 4, 2, FourStemSpec.FRAMES.toLong())))
                                throw SeparationException(SeparationProblem.INVALID_OUTPUT)
                            val buffer = output.floatBuffer
                            if (buffer.remaining() != FourStemSpec.FRAMES * 8) throw SeparationException(SeparationProblem.INVALID_OUTPUT)
                            for (index in buffer.position() until buffer.limit()) {
                                if (index and 4095 == 0) check()
                                if (!buffer[index].isFinite()) throw SeparationException(SeparationProblem.INVALID_OUTPUT)
                            }
                            consume(buffer); check()
                        }
                    }
                } finally { synchronized(runLock) { running = null } }
            }
        }
    }
    override fun cancel() { synchronized(runLock) { running?.setTerminate(true) } }
    override fun close() {
        synchronized(runLock) { if (closed) return; check(running == null); closed = true }
        session.close()
    }
    companion object {
        internal fun declaredShape(actual: LongArray, expected: LongArray) = actual.size == expected.size && actual.indices.all { actual[it] == expected[it] || actual[it] == -1L }
    }
}
