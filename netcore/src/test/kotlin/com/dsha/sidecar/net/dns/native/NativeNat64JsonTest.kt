// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.dns.native

import java.net.URI
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

internal object Nat64TestJson {
    fun answer(
        records: String = """{"name":"example.test.","type":1,"TTL":60,"data":"192.0.2.7"}""",
        type: Int = 1,
        status: Int = 0,
        name: String = "example.test.",
    ) = """{"Status":$status,"Question":[{"name":"$name","type":$type}],"Answer":[$records]}"""

    fun https(type: Int = 65, name: String = "example.test."): String {
        val ech = Base64.getEncoder().encodeToString(NativeEchTestData.config())
        return answer("""{"name":"$name","type":$type,"TTL":120,"data":"1 . ech=$ech"}""", type, name = name)
    }
}

class NativeNat64JsonTest {
    private fun parse(text: String, type: NativeNat64Question = NativeNat64Question.A) =
        NativeNat64Json.parse(text.toByteArray(), "example.test", type)
    private fun reject(text: String) {
        try { parse(text); fail("Expected invalid JSON DNS rejection") }
        catch (_: NativeDnsProtocolException) { }
    }

    @Test fun jsonAAnswersAreStrictDeduplicatedAndRetainMinimumTtl() {
        val reply = parse(Nat64TestJson.answer(
            """{"name":"example.test.","type":1,"TTL":60,"data":"192.0.2.7"},""" +
                """{"name":"example.test.","type":1,"TTL":3,"data":"192.0.2.7"},""" +
                """{"name":"injected.test.","type":1,"TTL":60,"data":"192.0.2.99"}""",
        )).value as NativeNat64DohAnswer.Addresses
        assertEquals(listOf(NativeDnsRecord("192.0.2.7", 3_000)), reply.answer.records)
    }

    @Test fun cnameChainAndFollowupCarrySmallestTtl() {
        val chain = """{"name":"example.test.","type":5,"TTL":2,"data":"edge.test."}"""
        val follow = parse(Nat64TestJson.answer(chain))
        assertEquals("edge.test", follow.followUp)
        assertEquals(listOf("edge.test"), follow.aliases)
        val full = parse(Nat64TestJson.answer(chain +
            """,{"name":"edge.test.","type":1,"TTL":120,"data":"192.0.2.9"}"""))
        assertEquals(2_000, (full.value as NativeNat64DohAnswer.Addresses).answer.records.single().ttlMillis)
    }

    @Test fun cnameConflictsCyclesAndOwnerCoexistenceFail() {
        val alias = """{"name":"example.test.","type":5,"TTL":2,"data":"edge.test."}"""
        reject(Nat64TestJson.answer(alias + """,{"name":"edge.test.","type":5,"TTL":3,"data":"example.test."}"""))
        reject(Nat64TestJson.answer(alias + """,{"name":"example.test.","type":5,"TTL":3,"data":"other.test."}"""))
        reject(Nat64TestJson.answer(alias + """,{"name":"example.test.","type":1,"TTL":3,"data":"192.0.2.1"}"""))
    }

    @Test fun nxdomainAndNodataHaveNoFabricatedIpAndAuthoritySoaCapsNegativeTtl() {
        for (status in listOf(0, 3)) {
            val json = Nat64TestJson.answer("", status = status).dropLast(1) +
                ""","Authority":[{"name":"test.","type":6,"TTL":40,"data":"ns.test. mail.test. 1 3600 600 86400 7"}]}"""
            val parsed = (parse(json).value as NativeNat64DohAnswer.Addresses).answer
            assertTrue(parsed.records.isEmpty())
            assertEquals(7_000, parsed.negativeTtlMillis)
            assertEquals(if (status == 3) NativeDnsAnswerCode.NXDOMAIN else NativeDnsAnswerCode.NODATA, parsed.code)
        }
    }

    @Test fun questionMismatchTruncationAndDnsFailuresAreExceptional() {
        reject(Nat64TestJson.answer(name = "wrong.test."))
        reject(Nat64TestJson.answer(type = 28))
        reject(Nat64TestJson.answer(status = 2))
        reject(Nat64TestJson.answer().dropLast(1) + ""","TC":true}""")
        reject("""{"Status":0,"Answer":[]}""")
    }

    @Test fun malformedAddressesTtlNumbersClassesAndNegativeWithAnswersAreRejected() {
        for (bad in listOf(
            Nat64TestJson.answer().replace("192.0.2.7", "999.0.2.7"),
            Nat64TestJson.answer().replace("\"TTL\":60", "\"TTL\":-1"),
            Nat64TestJson.answer().replace("\"TTL\":60", "\"TTL\":1.5"),
            Nat64TestJson.answer().replace("\"TTL\":60", "\"TTL\":4294967296"),
            Nat64TestJson.answer().replace("\"TTL\":60", "\"class\":3,\"TTL\":60"),
            Nat64TestJson.answer(status = 3),
        )) reject(bad)
    }

    @Test fun servicePresentationCarriesOriginalEchBytesForHttpsAndSvcb() {
        for (type in listOf(NativeNat64Question.HTTPS, NativeNat64Question.SVCB)) {
            val parsed = parse(Nat64TestJson.https(type.code), type).value as NativeNat64DohAnswer.Services
            assertArrayEquals(NativeEchTestData.config(), parsed.answer.records.single().service.ech)
            assertEquals(120_000, parsed.answer.records.single().ttlMillis)
        }
    }

    @Test fun aliasModeAndSvcParamsAreConvertedThroughFrozenCodec() {
        val alias = parse(Nat64TestJson.answer(
            """{"name":"example.test.","type":65,"TTL":5,"data":"0 alias.test."}""", type = 65),
            NativeNat64Question.HTTPS).value as NativeNat64DohAnswer.Services
        assertEquals(0, alias.answer.records.single().service.priority)
        assertEquals("alias.test.", alias.answer.records.single().service.target)
        assertNull(alias.answer.records.single().service.ech)
    }

    @Test fun duplicateKeysInvalidUtf8AndOversizedNestedJsonFail() {
        reject(Nat64TestJson.answer().replace("\"Status\":0", "\"Status\":0,\"Status\":0"))
        reject(Nat64TestJson.answer() + "tail")
        reject("[".repeat(18) + "0" + "]".repeat(18))
        reject(Nat64TestJson.answer().replace("\"TTL\":60", "\"TTL\":01"))
        try {
            NativeNat64Json.parse(byteArrayOf(0xff.toByte()), "example.test", NativeNat64Question.A)
            fail()
        } catch (_: java.nio.charset.CharacterCodingException) { }
    }

    @Test fun getTargetPreservesOtherParamsReplacesQuestionAndDoesNotDuplicateCt() {
        val query = listOf("mode" to "local", "name" to "old.test", "type" to "AAAA", "ct" to "application%2Fdns-json")
            .joinToString("&") { it.first + "=" + it.second }
        val target = NativeNat64Json.target(URI("https://resolver.test/resolve?" + query), "example.test", NativeNat64Question.A)
        val fields = target.substringAfter('?').split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals(4, fields.size)
        assertEquals("local", fields["mode"])
        assertEquals("example.test", fields["name"])
        assertEquals("A", fields["type"])
        assertEquals("application%2Fdns-json", fields["ct"])
        assertTrue(NativeNat64Json.target(URI("https://resolver.test/resolve"), "example.test",
            NativeNat64Question.HTTPS).substringAfter('?').split('&').any { it.substringBefore('=') == "ct" })
    }
    @Test fun boundedRecordCountRejectsLargeAnswerBeforeMapping() {
        try {
            NativeNat64Json.parse(Nat64TestJson.answer(
                """{"name":"example.test.","type":1,"TTL":60,"data":"192.0.2.7"},""" +
                    """{"name":"example.test.","type":1,"TTL":60,"data":"192.0.2.8"}""",
            ).toByteArray(), "example.test", NativeNat64Question.A, maxRecords = 1)
            fail()
        } catch (_: NativeDnsProtocolException) { }
    }

    @Test fun singularQuestionObjectIsValidatedAsStrictlyAsArrayForm() {
        val single = Nat64TestJson.answer().replace(
            """"Question":[{"name":"example.test.","type":1}]""",
            """"Question":{"name":"example.test.","type":1}""",
        )
        assertEquals(1, ((parse(single).value as NativeNat64DohAnswer.Addresses).answer.records.size))
        reject(single.replace("example.test.", "spoof.test."))
    }
}
