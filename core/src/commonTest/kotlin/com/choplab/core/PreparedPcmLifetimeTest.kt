package com.choplab.core

import com.choplab.core.model.Asset
import com.choplab.core.model.Project
import com.choplab.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PreparedPcmLifetimeTest {
    @Test fun appliedRejectedCancelledLateAndClosedPreparationsAlwaysReleaseTheirHandoff() = runTest {
        for (route in 0..4) {
            val source = PcmAsset.fromInterleaved(FloatArray(8) { .25f })
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val engine = object : EnginePort {
                override suspend fun prepare(project: Project, patternId: String, revision: Long): EngineProgram {
                    entered.complete(Unit)
                    if (route >= 2) withContext(NonCancellable) { release.await() }
                    return EngineProgram(listOf(Pad(0, source)), preparedPcm = PcmLeaseGroup(listOf(source.acquire())))
                }
                override suspend fun apply(command: EngineCommand) = route != 1 || command !is EngineCommand.SwapProgram
                override fun snapshot() = TransportState()
            }
            val assets = object : AssetStore {
                override suspend fun containsVerified(asset: Asset) = true
                override suspend fun write(asset: Asset, bytes: ByteArray) = Unit
                override suspend fun read(asset: Asset) = ByteArray(0)
            }
            val services = Services(assets, object : ImportPort {
                override suspend fun import(location: Location): Asset = error("Unused")
            }, object : ProjectPort {
                override suspend fun save(project: Project, revision: Long, location: Location) = Unit
                override suspend fun open(location: Location) = Project()
            }, object : ExportPort {
                override suspend fun export(project: Project, patternId: String, request: ExportRequest): ExportReceipt = error("Unused")
            }, engine)
            val studio = Studio(this, services, preparationDispatcher = StandardTestDispatcher(testScheduler))
            val preparing = async { studio.dispatch(Action.SelectPattern("pattern-1")) }
            entered.await()
            when (route) {
                2 -> studio.dispatch(Action.CancelWork)
                3 -> preparing.cancel()
                4 -> studio.dispatch(Action.Close)
            }
            release.complete(Unit)
            advanceUntilIdle()
            if (route < 2) assertEquals(route == 0, preparing.await().accepted)
            assertEquals(0, source.leaseCount, "Preparation route $route leaked the PCM handoff")
            assertEquals(.25f, source.sample(0, 0))
            if (route != 4) studio.dispatch(Action.Close)
        }
    }
}
