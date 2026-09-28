package com.choplab.ui.source

import com.choplab.core.Location
import kotlinx.coroutines.CoroutineScope

/** A fresh dialog session. Only its current saved receipt resolves to a host-private import handle. */
fun interface OnlineSourceHost {
    fun open(scope: CoroutineScope, stopAll: () -> Unit): OnlineImportSession
}

class OnlineImportSession(
    val port: OnlineSourcePort,
    val resolve: (savedId: String) -> OnlineImportSelection?,
)

data class OnlineImportSelection(val location: Location, val assetHash: String)
