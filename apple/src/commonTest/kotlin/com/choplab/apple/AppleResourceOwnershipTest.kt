package com.choplab.apple

import kotlinx.coroutines.*
import kotlin.test.*

class AppleResourceOwnershipTest {
    @Test fun closeBeforeRecompositionStillOwnsThePublishedResource() {
        val owner = ResourceOwner<Any>()
        val value = Any()
        assertTrue(owner.install(value))
        assertSame(value, owner.close())
        assertNull(owner.close())
        assertFalse(owner.install(Any()))
        val disposedBeforePublication = ResourceOwner<Any>()
        assertNull(disposedBeforePublication.close())
        assertFalse(disposedBeforePublication.install(Any()))
    }
    @Test fun cancelledConstructionReleasesExactlyOnceAndNeverPublishes(): Unit = runBlocking {
        withTimeout(10_000) {
            val entered = CompletableDeferred<Unit>()
            val finishConstruction = CompletableDeferred<Unit>()
            var released = 0
            var published = 0
            val task = launch {
                createAndPublishOwnedResource(
                    create = { runBlocking { entered.complete(Unit); finishConstruction.await(); Any() } },
                    release = { delay(1); released++ },
                    publish = { published++ },
                )
            }
            entered.await()
            task.cancel()
            finishConstruction.complete(Unit)
            task.join()
            assertEquals(1, released)
            assertEquals(0, published)
        }
    }
    @Test fun successfulPublicationTransfersOwnership(): Unit = runBlocking {
        val value = Any()
        var published: Any? = null
        var released = 0
        createAndPublishOwnedResource({ value }, { released++ }, { published = it })
        assertSame(value, published)
        assertEquals(0, released)
    }
    @Test fun failedPublicationReleasesTheCompletedResource(): Unit = runBlocking {
        var released = 0
        assertFailsWith<IllegalStateException> {
            createAndPublishOwnedResource({ Any() }, { released++ }, { error("Publication failed") })
        }
        assertEquals(1, released)
    }
}
