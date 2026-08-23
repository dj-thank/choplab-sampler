package com.choplab.sampler.model

import com.choplab.sampler.format.portableStringFormat

/** Locale-independent common replacement for the JVM-only String.format extension. */
internal fun String.format(vararg arguments: Any?): String = portableStringFormat(this, arguments)
