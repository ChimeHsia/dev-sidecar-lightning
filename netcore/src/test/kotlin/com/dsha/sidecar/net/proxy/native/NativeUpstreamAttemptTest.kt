package com.dsha.sidecar.net.proxy.native

import com.dsha.sidecar.net.proxy.http.Http1Reader
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class NativeUpstreamAttemptTest {
    @Test fun retainedReaderTransfersTheSelectedResponseBody() {
        withAttempt("HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nbody") { attempt, releases, _ ->
            assertEquals(200, attempt.readHead("GET").head.status)
            val body = ByteArrayOutputStream()
            attempt.reader.transferBody(body)
            assertEquals("body", body.toString("UTF-8"))
            assertEquals(0, releases.get())
            attempt.close()
            attempt.close()
            assertEquals(1, releases.get())
            assertThrows(IllegalStateException::class.java) { attempt.readHead("GET") }
        }
    }

    @Test fun discardedResponseClosesWithoutWaitingForItsBody() {
        withAttempt("HTTP/1.1 500 Error\r\nContent-Length: 1000000\r\n\r\n") { attempt, releases, peer ->
            assertEquals(500, attempt.readHead("GET").head.status)
            attempt.close()
            assertEquals(1, releases.get())
            assertEquals(-1, peer.getInputStream().read())
        }
    }

    private fun withAttempt(
        response: String,
        check: (NativeUpstreamAttempt, AtomicInteger, Socket) -> Unit,
    ) {
        val timer = Executors.newSingleThreadScheduledExecutor()
        try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
                Socket("127.0.0.1", listener.localPort).use { client ->
                    listener.accept().use { peer ->
                        peer.soTimeout = 2_000
                        peer.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
                        peer.getOutputStream().flush()
                        val releases = AtomicInteger()
                        val wait = NativeResponseWait(client, client, timer, 2_000, AtomicBoolean(), false, false)
                        NativeUpstreamAttempt(Http1Reader(client.getInputStream()), wait) {
                            releases.incrementAndGet()
                            client.close()
                        }.use { attempt -> check(attempt, releases, peer) }
                        assertEquals(1, releases.get())
                    }
                }
            }
        } finally {
            timer.shutdownNow()
        }
    }
}
