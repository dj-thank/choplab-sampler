package com.choplab.sampler.source

/**
 * An import failure whose message is written for the person importing audio and is shown as is.
 * Other exceptions keep the generic status text, so internal messages never reach the screen.
 */
class SourceImportUserError(message: String) : IllegalStateException(message)
