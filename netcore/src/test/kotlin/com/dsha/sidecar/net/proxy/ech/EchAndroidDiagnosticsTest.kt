package com.dsha.sidecar.net.proxy.ech

import org.conscrypt.Conscrypt
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.security.NoSuchAlgorithmException
import java.security.Security
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class EchAndroidDiagnosticsTest {
    @Test
    fun androidDefaultProviderChoiceReproducesMissingPkixServiceWithoutChangingTrust() {
        val androidStyle = Conscrypt.newProviderBuilder().provideTrustManager(false).build()
        assertNull(androidStyle.getService("TrustManagerFactory", "PKIX"))
        assertThrows(NoSuchAlgorithmException::class.java) {
            TrustManagerFactory.getInstance("PKIX", androidStyle)
        }
    }

    @Test
    fun explicitLocalProviderEnablesRealPkixTrustManagerWithoutGlobalRegistration() {
        val before = Security.getProviders().toList()
        val provider = Conscrypt.newProviderBuilder().provideTrustManager(true).build()
        assertNotNull(provider.getService("TrustManagerFactory", "PKIX"))
        val factory = TrustManagerFactory.getInstance("PKIX", provider)
        factory.init(null as java.security.KeyStore?)
        assertTrue(factory.trustManagers.single() is X509TrustManager)
        assertTrue(Conscrypt.isConscrypt(EchPeerTrust.SystemRoots.manager()))
        assertEquals(before, Security.getProviders().toList())
    }

    @Test
    fun connectorSuppliesExactInputValidationStageAndClosesRaw() {
        val raw = Socket()
        fun u16(value: Int) = byteArrayOf((value ushr 8).toByte(), value.toByte())
        val name = "cover.example".toByteArray(Charsets.US_ASCII)
        val contents = byteArrayOf(7, 0, 32) + u16(32) + ByteArray(32) { 1 } +
            byteArrayOf(0, 4, 0, 1, 0, 1, 64, name.size.toByte()) + name + byteArrayOf(0, 0)
        val config = TypedEchConfigList.parse(
            u16(contents.size + 4) + byteArrayOf(0xfe.toByte(), 0x0d) + u16(contents.size) + contents
        )
        val error = assertThrows(EchConnectionException::class.java) {
            ConscryptEchConnector().connect(raw, config, "inner.example")
        }
        assertEquals(EchFailure.INVALID_REQUEST, error.failure)
        assertEquals(EchStage.INPUT_VALIDATION, error.stage)
        assertTrue(raw.isClosed)
    }

    @Test
    fun diagnosticKeepsExactConnectorStageAndClassNames() {
        val exception = EchConnectionException(
            EchFailure.HANDSHAKE, NoSuchAlgorithmException("not logged"), EchStage.TRUST_MANAGER_CREATION
        )
        val result = EchDiagnostics.capture(exception, "CONNECTOR")
        assertEquals("TRUST_MANAGER_CREATION", result.stage)
        assertEquals("EchConnectionException", result.causeClasses[0].simpleName)
        assertEquals("java.security.NoSuchAlgorithmException", result.causeClasses[1].fullName)
        assertFalse(result.causeChainTruncated)
    }

    @Test
    fun unknownConnectorStageUsesCallersStaticStage() {
        val result = EchDiagnostics.capture(EchConnectionException(EchFailure.HANDSHAKE), "NATIVE_AVAILABILITY")
        assertEquals("NATIVE_AVAILABILITY", result.stage)
        assertEquals(1, result.causeClasses.size)
    }

    @Test
    fun causeChainIsBoundedAtEight() {
        var exception: Throwable = IllegalStateException("private input")
        repeat(12) { exception = RuntimeException("private input", exception) }
        val result = EchDiagnostics.capture(exception, "APPLICATION_READ")
        assertEquals(8, result.causeClasses.size)
        assertTrue(result.causeChainTruncated)
    }

    @Test
    fun cyclicCauseChainTerminates() {
        val cyclic = object : RuntimeException() {
            override val cause: Throwable get() = this
        }
        val result = EchDiagnostics.capture(cyclic, "TEST")
        assertEquals(1, result.causeClasses.size)
        assertTrue(result.causeChainTruncated)
    }

    @Test
    fun diagnosticsNeverReadMessagesToStringOrStackTrace() {
        val sensitive = object : RuntimeException() {
            override val message: String get() = error("Message must never be read")
            override fun toString(): String = error("toString must never be read")
            override fun getStackTrace(): Array<StackTraceElement> = error("Stack trace must never be read")
        }
        val result = EchDiagnostics.capture(sensitive, "FIXTURE_VALIDATION")
        assertEquals(1, result.causeClasses.size)
        assertFalse(result.causeChainTruncated)
    }
}
