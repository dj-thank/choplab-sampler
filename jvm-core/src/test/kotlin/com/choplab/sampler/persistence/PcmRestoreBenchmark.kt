package com.choplab.sampler.persistence
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Random
@Volatile private var sink: Any? = null
/** Synthetic host timing/allocation only; never a physical app-start or peak-RSS claim. */
object PcmRestoreBenchmark {
@JvmStatic
fun main(args: Array<String>) {
    require(args.size == 2) { "Usage: <label> raw | <label> <existing-recovery-fixture-directory>" }
    require(args[0].matches(Regex("[A-Za-z0-9_-]+"))) { "Use a CSV-safe label" }
    val label=args[0]
    val bean=ManagementFactory.getThreadMXBean() as ThreadMXBean
    check(bean.isThreadAllocatedMemorySupported)
    bean.isThreadAllocatedMemoryEnabled=true
    val tid=Thread.currentThread().id
    fun run(case: String, block: ()->Any) {
        repeat(8) { sink=block(); sink=null }
        repeat(12) { iteration ->
            val before=bean.getThreadAllocatedBytes(tid)
            val start=System.nanoTime()
            sink=block()
            val elapsed=System.nanoTime()-start
            val allocated=bean.getThreadAllocatedBytes(tid)-before
            println("$label,$case,$iteration,${elapsed/1e6},$allocated")
            sink=null
        }
    }
    if(args[1]=="raw") {
        for(seconds in listOf(1,30,300)) {
            val count=seconds*48_000*2
            val data=ByteArrayOutputStream().also { output ->
                val random=Random(20260910L)
                Pcm16WavCodec.write(output, ShortArray(count) { random.nextInt(65_536).toShort() }, 48_000, 2)
            }.toByteArray()
            run("wav_${seconds}s_stereo") {
                Pcm16WavCodec.read(ByteArrayInputStream(data),count/2,48_000,2)
            }
        }
    } else {
        val dir=File(args[1])
        require(dir.isDirectory) { "Use an existing synthetic fixture; do not benchmark user projects" }
        run("recovery_30s_4generations") {
            val recovered=requireNotNull(AtomicProjectStore(dir).loadWithRevision())
            check(recovered.revision==4L && recovered.state.currentAudio?.samples?.size==2_880_000)
            recovered
        }
    }
}

}
