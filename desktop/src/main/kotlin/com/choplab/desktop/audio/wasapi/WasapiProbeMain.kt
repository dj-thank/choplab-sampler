package com.choplab.desktop.audio.wasapi

import kotlin.system.exitProcess

fun main() {
    val receipt = WasapiEndpointProbe().probe()
    println(receipt.toJson())
    if (!receipt.successful) exitProcess(2)
}
