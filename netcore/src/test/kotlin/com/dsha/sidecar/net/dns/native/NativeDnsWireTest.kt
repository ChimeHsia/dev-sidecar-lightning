// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.dns.native

import org.junit.Assert.*
import org.junit.Test
import com.dsha.sidecar.net.dns.native.NativeDnsTestPackets as Packets

class NativeDnsWireTest {
    private val query = NativeDnsWire.question("example.test", NativeDnsWire.A, 0x7139)

    private fun reject(bytes: ByteArray) {
        try {
            NativeDnsWire.parse(bytes, query)
            fail("Expected malformed/unmatched DNS response rejection")
        } catch (_: NativeDnsProtocolException) { }
    }

    @Test fun queryContainsRandomnessInputSingleInQuestionAndRecursionDesired() {
        assertEquals(0x7139, Packets.u16(query.bytes, 0))
        assertEquals(0x0100, Packets.u16(query.bytes, 2))
        assertEquals(1, Packets.u16(query.bytes, 4))
        val question = Packets.question(query.bytes)
        assertEquals("example.test", question.host)
        assertEquals(1, question.type)
    }

    @Test fun addressAnswersPreserveOrderAndUnsignedTtl() {
        val reply = NativeDnsWire.parse(Packets.response(query.bytes, listOf(
            Packets.a("example.test", 0xffffffffL, 1), Packets.a("example.test", 0, 2),
        )), query)
        assertEquals(listOf("192.0.2.1", "192.0.2.2"), reply.answer.records.map { it.address })
        assertEquals(listOf(4_294_967_295_000, 0L), reply.answer.records.map { it.ttlMillis })
    }

    @Test fun aaaaAnswersAreLiteralOnlyAndClassFiltered() {
        val v6 = NativeDnsWire.question("example.test", NativeDnsWire.AAAA, 41)
        val reply = NativeDnsWire.parse(Packets.response(v6.bytes, listOf(
            Packets.aaaa("example.test").copy(klass = 3), Packets.aaaa("example.test"),
        )), v6)
        assertEquals(listOf("2001:db8:0:0:0:0:0:9"), reply.answer.records.map { it.address })
    }

    @Test fun cnameChainCarriesMinimumTtlAndRejectsUnrelatedOrAdditionalAddresses() {
        val reply = NativeDnsWire.parse(Packets.response(query.bytes, listOf(
            Packets.a("injected.test", last = 99),
            Packets.cname("example.test", "first.test", 7),
            Packets.cname("first.test", "edge.test", 2),
            Packets.a("edge.test", 100, 3),
        ), additional = listOf(Packets.a("edge.test", last = 99))), query)
        assertEquals(listOf(NativeDnsRecord("192.0.2.3", 2_000)), reply.answer.records)
        assertEquals(listOf("first.test", "edge.test"), reply.aliases)
    }

    @Test fun cnameWithoutTerminalAnswerRequestsBoundedFollowup() {
        val reply = NativeDnsWire.parse(Packets.response(query.bytes,
            listOf(Packets.cname("example.test", "edge.test", 4))), query)
        assertEquals("edge.test", reply.followUp)
        assertEquals(4_000, reply.aliasTtlMillis)
        assertTrue(reply.answer.records.isEmpty())
    }

    @Test fun cnameCyclesConflictsAndOwnerAddressCoexistenceAreRejected() {
        for (records in listOf(
            listOf(Packets.cname("example.test", "edge.test"), Packets.cname("edge.test", "example.test")),
            listOf(Packets.cname("example.test", "one.test"), Packets.cname("example.test", "two.test")),
            listOf(Packets.cname("example.test", "edge.test"), Packets.a("example.test")),
        )) reject(Packets.response(query.bytes, records))
    }

    @Test fun nxdomainAndNodataUseAuthoritySoaMinimumAndNotAdditionalSoa() {
        for (flags in listOf(0x8183, 0x8180)) {
            val bytes = Packets.response(query.bytes, authority = listOf(Packets.soa("test")),
                additional = listOf(Packets.soa("test", minimum = 1)), flags = flags)
            val reply = NativeDnsWire.parse(bytes, query)
            assertEquals(if (flags == 0x8183) NativeDnsAnswerCode.NXDOMAIN else NativeDnsAnswerCode.NODATA,
                reply.answer.code)
            assertEquals(20_000L, reply.answer.negativeTtlMillis)
            assertTrue(reply.answer.records.isEmpty())
        }
    }

    @Test fun unrelatedSoaDoesNotAuthorizeNegativeCaching() {
        val reply = NativeDnsWire.parse(Packets.response(query.bytes,
            authority = listOf(Packets.soa("unrelated.test")), flags = 0x8183), query)
        assertEquals(0L, reply.answer.negativeTtlMillis)
    }

    @Test fun wrongIdNameTypeClassAndQuestionCountAreRejected() {
        reject(Packets.response(query.bytes, id = query.id xor 1))
        reject(Packets.response(query.bytes, hostname = "spoof.test"))
        reject(Packets.response(query.bytes, type = 28))
        reject(Packets.response(query.bytes, klass = 3))
        reject(Packets.response(query.bytes).apply { this[5] = 2 })
    }

    @Test fun responseFlagsAndErrorCodesAreNotTreatedAsSuccessfulEmptyAnswers() {
        for (flags in listOf(0x0100, 0x8980, 0x81c0, 0x8182, 0x8185)) {
            reject(Packets.response(query.bytes, flags = flags))
        }
        reject(Packets.response(query.bytes, listOf(Packets.a("example.test")), flags = 0x8183))
    }

    @Test fun everyTruncatedLengthInPositiveMessageIsRejected() {
        val bytes = Packets.response(query.bytes, listOf(Packets.a("example.test")))
        for (size in bytes.indices) reject(bytes.copyOf(size))
        reject(bytes + byteArrayOf(0))
    }

    @Test fun malformedAddressAndCnameRdataLengthsAreRejected() {
        reject(Packets.response(query.bytes, listOf(Packets.a("example.test").copy(data = ByteArray(3)))))
        reject(Packets.response(query.bytes, listOf(Packets.aaaa("example.test").copy(data = ByteArray(15)))))
        reject(Packets.response(query.bytes, listOf(
            Packets.cname("example.test", "edge.test").let { it.copy(data = it.data + byteArrayOf(0)) },
        )))
    }

    @Test fun compressionCycleAndOutOfPacketPointersAreRejected() {
        val prefix = Packets.response(query.bytes)
        val cycle = prefix.copyOf().apply { this[12] = 0xc0.toByte(); this[13] = 12 }
        val outside = prefix.copyOf().apply { this[12] = 0xff.toByte(); this[13] = 0xff.toByte() }
        reject(cycle)
        reject(outside)
    }

    @Test fun compressedOwnerNamesAreAcceptedWithCompleteRdataValidation() {
        val head = Packets.response(query.bytes).apply { this[7] = 1 }
        val rr = byteArrayOf(0xc0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 9, 0, 4,
            192.toByte(), 0, 2, 8)
        assertEquals(listOf(NativeDnsRecord("192.0.2.8", 9_000)),
            NativeDnsWire.parse(head + rr, query).answer.records)
    }

    @Test fun udpTruncationStillRequiresCorrectTransactionAndQuestion() {
        val truncated = Packets.response(query.bytes, flags = 0x8380).apply { this[7] = 9 }
        assertTrue(NativeDnsWire.parse(truncated, query).truncated)
        reject(truncated.copyOf().apply { this[0] = 0 })
        reject(truncated.copyOf(15))
    }

    @Test fun recordAddressAndAliasLimitsRejectCompleteOversizeAnswers() {
        val bytes = Packets.response(query.bytes, listOf(
            Packets.a("example.test", last = 1), Packets.a("example.test", last = 2),
        ))
        try {
            NativeDnsWire.parse(bytes, query, maxAddresses = 1)
            fail()
        } catch (_: NativeDnsProtocolException) { }
        try {
            NativeDnsWire.parse(bytes, query, maxRecords = 1)
            fail()
        } catch (_: NativeDnsProtocolException) { }
        val chain = Packets.response(query.bytes, listOf(
            Packets.cname("example.test", "one.test"), Packets.cname("one.test", "two.test"),
        ))
        try {
            NativeDnsWire.parse(chain, query, maxAliases = 1)
            fail()
        } catch (_: NativeDnsProtocolException) { }
    }

    @Test fun duplicateCnameAndAddressRecordsKeepShortestLifetime() {
        val reply = NativeDnsWire.parse(Packets.response(query.bytes, listOf(
            Packets.cname("example.test", "edge.test", 2),
            Packets.cname("example.test", "edge.test", 30),
            Packets.a("edge.test", 60), Packets.a("edge.test", 1),
        )), query)
        assertEquals(listOf(NativeDnsRecord("192.0.2.7", 1_000)), reply.answer.records)
        assertEquals(2_000, reply.aliasTtlMillis)
    }

    @Test fun negativeCanonicalTargetUsesItsOwnAuthorityZoneAndAliasTtl() {
        val reply = NativeDnsWire.parse(Packets.response(query.bytes,
            listOf(Packets.cname("example.test", "missing.other", 3)),
            authority = listOf(Packets.soa("other")), flags = 0x8183), query)
        assertEquals(NativeDnsAnswerCode.NXDOMAIN, reply.answer.code)
        assertEquals(3_000L, reply.answer.negativeTtlMillis)
        assertNull(reply.followUp)
    }
}
