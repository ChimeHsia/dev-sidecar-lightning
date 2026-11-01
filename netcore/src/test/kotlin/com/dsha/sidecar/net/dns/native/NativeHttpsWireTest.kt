// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.dns.native

import org.junit.Assert.*
import org.junit.Test
import com.dsha.sidecar.net.dns.native.NativeDnsTestPackets as Packets

class NativeHttpsWireTest {
    private val question = NativeHttpsWire.question("example.test", HttpsRecordType.HTTPS, 19)
    private fun service(owner: String = "example.test", type: Int = 65, ttl: Long = 10) =
        Packets.Rr(owner, type, NativeEchTestData.rdata(), ttl)
    private fun reject(bytes: ByteArray) {
        try { NativeHttpsWire.parse(bytes, question); fail("Expected invalid HTTPS response") }
        catch (_: NativeDnsProtocolException) { }
    }

    @Test fun actualHttpsAndSvcbQuestionsHaveOwnTypesAndReturnFullRawEchParameter() {
        for (type in HttpsRecordType.entries) {
            val query = NativeHttpsWire.question("example.test", type, 19)
            assertEquals(type.code, Packets.question(query.bytes).type)
            val parsed = NativeHttpsWire.parse(Packets.response(query.bytes,
                listOf(service(type = type.code))), query)
            assertEquals(10_000L, parsed.answer.records.single().ttlMillis)
            assertArrayEquals(NativeEchTestData.config(), parsed.answer.records.single().service.ech)
        }
    }

    @Test fun wrongIdQuestionTypeNameAndClassAreRejected() {
        reject(Packets.response(question.bytes, id = 20))
        reject(Packets.response(question.bytes, type = 64))
        reject(Packets.response(question.bytes, hostname = "wrong.test"))
        reject(Packets.response(question.bytes, klass = 3))
        reject(Packets.response(question.bytes, flags = 0x8182))
    }

    @Test fun allTruncatedLengthsAndTrailingBytesAreRejected() {
        val full = Packets.response(question.bytes, listOf(service()))
        for (length in full.indices) reject(full.copyOf(length))
        reject(full + byteArrayOf(0))
    }

    @Test fun cnameMinimumTtlAppliesOnlyToMatchingInAnswerRecords() {
        val bytes = Packets.response(question.bytes, listOf(
            service("unrelated.test"), Packets.cname("example.test", "edge.test", 2),
            service("edge.test", ttl = 50), service("edge.test").copy(klass = 3),
        ), additional = listOf(service("edge.test")))
        val reply = NativeHttpsWire.parse(bytes, question)
        assertEquals(1, reply.answer.records.size)
        assertEquals(2_000, reply.answer.records.single().ttlMillis)
        assertEquals(listOf("edge.test"), reply.answer.canonicalNames)
    }

    @Test fun cnameOnlyAndNegativeSoaProduceDistinctFollowupDecisions() {
        val alias = Packets.cname("example.test", "edge.test")
        val follow = NativeHttpsWire.parse(Packets.response(question.bytes, listOf(alias)), question)
        assertEquals("edge.test", follow.followUp)
        val negative = NativeHttpsWire.parse(Packets.response(question.bytes, listOf(alias),
            authority = listOf(Packets.soa("test"))), question)
        assertNull(negative.followUp)
        assertEquals(NativeDnsAnswerCode.NODATA, negative.answer.code)
        assertEquals(10_000L, negative.answer.negativeTtlMillis)
    }

    @Test fun truncatedUdpResponseNeedsMatchingQuestionBeforeTcpRetry() {
        val bytes = Packets.response(question.bytes, flags = 0x8380).apply { this[7] = 3 }
        assertTrue(NativeHttpsWire.parse(bytes, question).truncated)
        reject(bytes.copyOf().apply { this[1] = 20 })
        reject(bytes.copyOf(14))
    }

    @Test fun duplicateUnorderedOrShortSvcParamsAreRejected() {
        val valid = NativeEchTestData.rdata()
        val duplicate = valid + valid.copyOfRange(3, valid.size)
        reject(Packets.response(question.bytes, listOf(Packets.Rr("example.test", 65, duplicate))))
        reject(Packets.response(question.bytes, listOf(Packets.Rr("example.test", 65, valid.copyOf(valid.size - 1)))))
        reject(Packets.response(question.bytes, listOf(Packets.Rr("example.test", 65, byteArrayOf(0, 1)))))
    }

    @Test fun serviceAndCnameCyclesConflictsAndLimitsAreRejected() {
        reject(Packets.response(question.bytes, listOf(service(), Packets.cname("example.test", "edge.test"))))
        reject(Packets.response(question.bytes, listOf(
            Packets.cname("example.test", "edge.test"), Packets.cname("edge.test", "example.test"),
        )))
        try {
            NativeHttpsWire.parse(Packets.response(question.bytes, listOf(service(), service())), question,
                maxServices = 1)
            fail()
        } catch (_: NativeDnsProtocolException) { }
    }

    @Test fun recordsAndParamsAreDefensivelyDetached() {
        val answer = NativeHttpsWire.parse(Packets.response(question.bytes, listOf(service())), question).answer
        val original = answer.records.single().service.ech!!
        original.fill(0)
        answer.records.single().service.params.single().raw.fill(0)
        assertArrayEquals(NativeEchTestData.config(), answer.records.single().service.ech)
    }
}
