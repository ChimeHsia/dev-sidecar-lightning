package com.dsha.sidecar.net.runtime

import com.dsha.sidecar.net.proxy.native.*
import com.dsha.sidecar.net.proxy.tls.EphemeralTestCa
import java.net.BindException
import java.net.InetAddress
import java.net.ServerSocket
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class NativePortFallbackTest {
    @Test fun occupiedPreferredPortAdvancesAndConfigRefreshKeepsTheBoundPort() {
        val loopback = InetAddress.getByName("127.0.0.1")
        ServerSocket(0, 8, loopback).use { occupied ->
            val preferred = occupied.localPort
            assumeTrue(preferred < 65535)
            val nextAvailable = runCatching { ServerSocket(preferred + 1, 8, loopback).close() }.isSuccess
            assumeTrue(nextAvailable)
            val config = NativeRuntimeConfig.decode(mapOf("server" to mapOf("port" to preferred)))
            NativeRuntime(config, EphemeralTestCa.create().authority(), NativeRuntimeOptions(
                proxy = NativeProxyOptions(port = preferred, portFallbackCount = 1))).use { runtime ->
                assertEquals(preferred + 1, runtime.start().port)
                runtime.reconfigure(config)
                assertEquals(preferred + 1, runtime.localAddress!!.port)
                assertEquals(NativeRuntimeState.LISTENING, runtime.state)
            }
        }
    }

    @Test fun exhaustedRangeFailsRatherThanUsingAnotherProgramsListener() {
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { occupied ->
            NativeProxyServer(RouteResolver { error("No request expected") }, null,
                NativeProxyOptions(port = occupied.localPort)).use { proxy ->
                assertThrows(BindException::class.java) { proxy.start() }
                assertEquals(NativeProxyState.FAILED, proxy.state)
                assertNull(proxy.localAddress)
            }
        }
    }
}
