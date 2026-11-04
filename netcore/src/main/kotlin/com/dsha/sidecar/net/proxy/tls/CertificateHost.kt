package com.dsha.sidecar.net.proxy.tls

import java.net.Inet6Address
import java.util.Locale
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.util.IPAddress

/** Parses certificate identities without DNS lookups, URL parsing, or wildcard expansion. */
internal class CertificateHost private constructor(
    val canonicalName: String,
    val subjectAlternativeName: GeneralName,
) {
    companion object {
        fun parse(host: String): CertificateHost {
            require(host.isNotEmpty() && host.length <= 254 && host.all { it.code in 33..126 }) {
                "Expected an ASCII DNS name or an unbracketed IP literal"
            }
            if (':' in host) {
                require(host.all { it in "0123456789abcdefABCDEF:." } && IPAddress.isValidIPv6(host)) {
                    "Invalid IPv6 literal"
                }
                if ('.' in host) ipv4(host.substringAfterLast(':'))
                val bytes = (GeneralName(GeneralName.iPAddress, host).name as ASN1OctetString).octets
                val canonical = Inet6Address.getByAddress(null, bytes, -1).hostAddress
                return CertificateHost(canonical, GeneralName(GeneralName.iPAddress, DEROctetString(bytes)))
            }
            if (host.all { it in "0123456789." }) {
                val bytes = ipv4(host)
                return CertificateHost(host, GeneralName(GeneralName.iPAddress, DEROctetString(bytes)))
            }
            val canonical = host.removeSuffix(".").lowercase(Locale.ROOT)
            require(canonical.length in 1..253 && canonical.split('.').all { label ->
                label.length in 1..63 && label.first().isLetterOrDigit() &&
                    label.last().isLetterOrDigit() && label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
            }) { "Invalid DNS name" }
            return CertificateHost(canonical, GeneralName(GeneralName.dNSName, canonical))
        }

        private fun ipv4(host: String): ByteArray {
            val parts = host.split('.')
            require(parts.size == 4 && parts.all {
                it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() <= 255
            }) { "Invalid or ambiguous IPv4 literal" }
            return ByteArray(4) { parts[it].toInt().toByte() }
        }
    }
}
