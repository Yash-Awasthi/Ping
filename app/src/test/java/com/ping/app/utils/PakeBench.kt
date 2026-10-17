package com.ping.app.utils

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** Prints per-side PAKE cost; excluded by default, run with `./gradlew :app:testDebugUnitTest -Pbench --tests '*PakeBench'`. */
@Tag("bench")
class PakeBench {
    @Test
    fun bench() {
        val ctx = Pake.context("a|n1", "a|n2", byteArrayOf(1, 2, 3))
        val g = Pake.generator("F31A0")
        val peer = Pake.Session(g).share
        repeat(300) { Pake.Session(Pake.generator("F31A0")).finish(peer, ctx) }
        val n = 500
        var t = System.nanoTime()
        val sessions = List(n) { Pake.Session(Pake.generator("F31A0")) }
        val atLock = (System.nanoTime() - t) / 1e6 / n
        t = System.nanoTime()
        sessions.forEach { it.finish(peer, ctx) }
        val afterShare = (System.nanoTime() - t) / 1e6 / n
        println("PAKE per side: at lock %.2f ms, after peer share %.2f ms".format(atLock, afterShare))
    }
}
