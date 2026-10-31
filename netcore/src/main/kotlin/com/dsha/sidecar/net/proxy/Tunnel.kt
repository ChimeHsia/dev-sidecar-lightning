package com.dsha.sidecar.net.proxy

import java.io.InputStream
import java.io.OutputStream
import java.net.Socket

/** 双向字节转发：两个方向各一个线程，正常 EOF 走半关闭，异常则关闭双方以解除阻塞。 */
object Tunnel {

    private const val BUF = 16 * 1024

    /**
     * @param clientIn 客户端输入流（可能是已缓冲过的流，必须由调用方传入，
     *                 否则会丢掉缓冲区里的字节）
     */
    /** @return (客户端→服务端字节数, 服务端→客户端字节数) */
    fun relay(client: Socket, clientIn: InputStream, upstream: Socket): Pair<Long, Long> {
        val sent = java.util.concurrent.atomic.AtomicLong()
        val up = Thread({ sent.set(pump(clientIn, upstream.getOutputStream(), client, upstream)) }, "tunnel-c2s")
        up.isDaemon = true
        up.start()
        val received = pump(upstream.getInputStream(), client.getOutputStream(), upstream, client)
        up.join(2000)
        closeQuietly(client)
        closeQuietly(upstream)
        return sent.get() to received
    }

    fun relay(client: Socket, upstream: Socket): Pair<Long, Long> =
        relay(client, client.getInputStream(), upstream)

    /** 从 [from] 读到 [to]；正常结束时半关闭 [to] 的写方向。 */
    fun pump(from: InputStream, to: OutputStream, fromSocket: Socket, toSocket: Socket): Long {
        var normalEof = false
        var total = 0L
        try {
            total = copy(from, to)
            normalEof = true
        } catch (_: Exception) {
            // 任一端异常，直接收尾
        } finally {
            try {
                if (normalEof) {
                    toSocket.shutdownOutput()
                } else {
                    closeQuietly(fromSocket)
                    closeQuietly(toSocket)
                }
            } catch (_: Exception) {
            }
        }
        return total
    }

    fun copy(input: InputStream, output: OutputStream): Long {
        val buf = ByteArray(BUF)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) return total
            if (n == 0) continue
            output.write(buf, 0, n)
            output.flush()
            total += n
        }
    }

    fun closeQuietly(s: Socket?) {
        try {
            s?.close()
        } catch (_: Exception) {
        }
    }

    fun close(s: java.io.Closeable?) {
        try {
            s?.close()
        } catch (_: Exception) {
        }
    }
}
