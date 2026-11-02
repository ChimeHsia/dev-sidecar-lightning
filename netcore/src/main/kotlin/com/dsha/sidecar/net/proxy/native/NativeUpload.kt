package com.dsha.sidecar.net.proxy.native

import com.dsha.sidecar.net.proxy.http.Http1Exception
import com.dsha.sidecar.net.proxy.http.Http1Reader
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Owns the request reader until completion. Only the response worker writes to the client. */
internal class NativeUpload(
    reader: Http1Reader,
    output: OutputStream,
    upstreamRaw: Socket,
    executor: ExecutorService,
    waitForContinue: Boolean,
    onFinished: () -> Unit,
) {
    val bodyComplete = AtomicBoolean()
    val failure = AtomicReference<IOException?>()
    private val cancelled = AtomicBoolean()
    private val permitted = CountDownLatch(if (waitForContinue) 1 else 0)
    private val task: Future<*> = executor.submit {
        try {
            permitted.await()
            val checked = object : FilterOutputStream(output) {
                override fun write(value: Int) = send { out.write(value) }
                override fun write(bytes: ByteArray, offset: Int, length: Int) =
                    send { out.write(bytes, offset, length) }
                override fun flush() = send { out.flush() }
                private fun send(write: () -> Unit) {
                    if (cancelled.get()) throw UploadCancelled()
                    try {
                        write()
                    } catch (error: IOException) {
                        throw NativeUpstreamFailure("Upstream request-body write failed", error)
                    }
                }
            }
            if (cancelled.get()) throw UploadCancelled()
            reader.transferBody(checked)
            bodyComplete.set(true)
            checked.flush()
        } catch (_: UploadCancelled) {
            // Early final response: no further request data may be forwarded.
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: IOException) {
            if (!cancelled.get()) {
                val typed = when (error) {
                    is NativeUpstreamFailure -> error
                    is Http1Exception -> NativeClientFailure(400, error)
                    is SocketTimeoutException -> NativeClientFailure(408, error)
                    else -> NativeClientFailure(400, error)
                }
                failure.set(typed)
                if (typed is NativeClientFailure) {
                    // Wake the response reader so it can report a client-side parse/read failure.
                    runCatching { upstreamRaw.close() }
                }
            }
        } finally {
            onFinished()
        }
    }

    fun allowBody() { permitted.countDown() }

    fun cancel() {
        cancelled.set(true)
        permitted.countDown()
    }

    fun await(timeoutMillis: Int) { task.get(timeoutMillis.toLong(), TimeUnit.MILLISECONDS) }

    fun settleFinalResponse() {
        // The origin can read the last write before transferBody publishes its completed state.
        if (!bodyComplete.get()) {
            try { task.get(10, TimeUnit.MILLISECONDS) } catch (_: TimeoutException) { }
        }
    }

    fun throwClientFailure() {
        (failure.get() as? NativeClientFailure)?.let { throw it }
    }

    private class UploadCancelled : IOException()
}
