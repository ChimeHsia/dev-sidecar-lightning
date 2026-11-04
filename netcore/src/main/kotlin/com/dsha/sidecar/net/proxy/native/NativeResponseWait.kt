package com.dsha.sidecar.net.proxy.native

import java.io.Closeable
import java.net.Socket
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * An origin may legitimately wait for a continuously progressing upload before sending its head.
 * Apply the response-head deadline after the body finishes, or while awaiting an explicit 100.
 * The enclosing exchange deadline still bounds the complete upload and response.
 */
internal class NativeResponseWait(
    private val socket: Socket,
    private val raw: Socket,
    private val timer: ScheduledExecutorService,
    private val readTimeoutMillis: Int,
    private val timedOut: AtomicBoolean,
    bodyPending: Boolean,
    expectContinue: Boolean,
) : Closeable {
    private val lock = Any()
    private var bodyFinished = !bodyPending
    private var awaitingContinue = expectContinue
    private var awaitingHead = false
    private var closed = false
    private var epoch = 0L
    private var task: ScheduledFuture<*>? = null

    fun beginHead() = synchronized(lock) {
        awaitingHead = true
        // A deadline can be armed later by the upload helper, even during a blocked read.
        socket.soTimeout = 0
        armIfReady()
    }

    fun finishHead() = synchronized(lock) {
        awaitingHead = false
        epoch++
        task?.cancel(false)
        task = null
        socket.soTimeout = readTimeoutMillis
    }

    fun continued() = synchronized(lock) { awaitingContinue = false }

    fun bodyFinished() = synchronized(lock) {
        bodyFinished = true
        armIfReady()
    }

    private fun armIfReady() {
        if (closed || !awaitingHead || (!bodyFinished && !awaitingContinue) || task != null) return
        try {
            val generation = epoch
            task = timer.schedule({
                synchronized(lock) {
                    if (!closed && awaitingHead && epoch == generation) {
                        timedOut.set(true)
                        runCatching { raw.close() }
                    }
                }
            }, readTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            runCatching { raw.close() }
        }
    }

    override fun close() = synchronized(lock) {
        closed = true
        epoch++
        task?.cancel(false)
        task = null
    }
}
