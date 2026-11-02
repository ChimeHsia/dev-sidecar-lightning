// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.dns.native

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext

internal val dnsLoopback: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

internal class NativeDnsStreamServer(
    count: Int = 1,
    tls: SSLContext? = null,
    private val allowIoFailure: Boolean = false,
    handler: (Socket) -> Unit,
) : Closeable {
    private val server: ServerSocket = (tls?.serverSocketFactory?.createServerSocket() ?: ServerSocket()).apply {
        bind(InetSocketAddress(dnsLoopback, 0))
        soTimeout = 5_000
    }
    val port: Int get() = server.localPort
    @Volatile private var active: Socket? = null
    @Volatile private var closing = false
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "dns-test-stream").apply { isDaemon = true } }
    private val task = worker.submit {
        repeat(count) {
            if (!closing) {
                try {
                    server.accept().use { socket ->
                        active = socket
                        socket.soTimeout = 5_000
                        handler(socket)
                    }
                } catch (error: IOException) {
                    if (!closing && !allowIoFailure) throw error
                } finally {
                    active = null
                }
            }
        }
    }

    fun finish() { task.get(5, TimeUnit.SECONDS) }

    override fun close() {
        closing = true
        server.close()
        active?.close()
        worker.shutdownNow()
        check(worker.awaitTermination(5, TimeUnit.SECONDS)) { "Test stream server did not terminate" }
        task.get(5, TimeUnit.SECONDS)
    }
}

internal class NativeDnsUdpServer(
    count: Int = 1,
    handler: (DatagramSocket, DatagramPacket) -> Unit,
) : Closeable {
    private val socket = DatagramSocket(InetSocketAddress(dnsLoopback, 0)).apply { soTimeout = 5_000 }
    val port: Int get() = socket.localPort
    @Volatile private var closing = false
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "dns-test-udp").apply { isDaemon = true } }
    private val task = worker.submit {
        repeat(count) {
            try {
                val request = DatagramPacket(ByteArray(65535), 65535)
                socket.receive(request)
                handler(socket, request)
            } catch (error: IOException) {
                if (!closing) throw error
            }
        }
    }
    fun finish() { task.get(5, TimeUnit.SECONDS) }
    override fun close() {
        closing = true
        socket.close()
        worker.shutdownNow()
        check(worker.awaitTermination(5, TimeUnit.SECONDS))
        task.get(5, TimeUnit.SECONDS)
    }
}

internal fun DatagramPacket.bytes(): ByteArray = data.copyOfRange(offset, offset + length)
internal fun DatagramSocket.reply(request: DatagramPacket, bytes: ByteArray) =
    send(DatagramPacket(bytes, bytes.size, request.socketAddress))

internal fun Socket.readDns(): ByteArray {
    val input = DataInputStream(getInputStream())
    val length = input.readUnsignedShort()
    check(length in 12..65535)
    return ByteArray(length).also(input::readFully)
}

internal fun Socket.writeDns(bytes: ByteArray, fragmented: Boolean = false) {
    val output = DataOutputStream(getOutputStream())
    if (fragmented) {
        output.writeByte(bytes.size ushr 8)
        output.flush()
        output.writeByte(bytes.size)
        for (byte in bytes) {
            output.writeByte(byte.toInt())
            output.flush()
        }
    } else {
        output.writeShort(bytes.size)
        output.write(bytes)
        output.flush()
    }
}
