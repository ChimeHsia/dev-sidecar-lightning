package com.dsha.sidecar.net.proxy.native

import com.dsha.sidecar.net.proxy.http.Http1Reader
import com.dsha.sidecar.net.proxy.http.Http1Response
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Owns the buffered reader through final body transfer; discarding closes without draining. */
internal class NativeUpstreamAttempt(
    val reader: Http1Reader,
    val wait: NativeResponseWait,
    private val release: () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean()

    fun readHead(method: String): Http1Response {
        check(!closed.get())
        wait.beginHead()
        return try {
            reader.readResponse(method) ?: throw IOException("Missing response")
        } finally {
            wait.finishHead()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try {
                wait.close()
            } finally {
                release()
            }
        }
    }
}
