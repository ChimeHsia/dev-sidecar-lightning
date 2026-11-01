package com.dsha.sidecar.net.config.native

import org.junit.Assert.*
import org.junit.Test

class BoundedJson5Test {
    private val parser = BoundedJson5()

    @Test fun commentsQuotesNumbersAndTrailingCommasAreActuallyDecoded() {
        val result = parser.parseObject("""/*start*/{
            unquoted:'value', "number":+0x2a, fractional:.5, trailing:5.,
            exponent:1e-2, enabled:true, none:null, array:[1,2,], // end
        }""")
        assertEquals("value", result["unquoted"])
        assertEquals(42.0, result["number"])
        assertEquals(.5, result["fractional"])
        assertEquals(5.0, result["trailing"])
        assertEquals(.01, result["exponent"])
        assertEquals(listOf(1.0, 2.0), result["array"])
        assertEquals(true, result["enabled"])
        assertTrue(result.containsKey("none"))
    }

    @Test fun quotedUnicodeEscapesAndContinuationRoundTrip() {
        val input = "{'quoted\\u004bey':'\\x41\\u4e2d\\uD83D\\uDE00', continuation:'a\\\r\nb', escaped:'\\v\\0\\q'}"
        val value = parser.parseObject(input)
        assertEquals("A\u4e2d\uD83D\uDE00", value["quotedKey"])
        assertEquals("ab", value["continuation"])
        assertEquals("\u000b\u0000q", value["escaped"])
    }

    @Test fun unicodeWhitespaceAndEscapedAsciiIdentifiersWork() {
        assertEquals(mapOf("key" to true), parser.parseObject("\ufeff{\u00a0\\u006bey:true,\u2028}"))
    }

    @Test fun objectKeyOrderMatchesJsArrayIndexRules() {
        val result = parser.parseObject("{z:0,'10':10,'2':2,'01':1,'4294967295':3,a:1}")
        assertEquals(listOf("2", "10", "z", "01", "4294967295", "a"), result.keys.toList())
    }

    @Test fun ambiguousOrUnsupportedJson5IsRejectedExplicitly() {
        for (source in listOf(
            "{a:Infinity}", "{a:NaN}", "{a:1e999}", "{a:1,a:2}",
            "{__proto__:{x:1}}", "{constructor:0}", "{prototype:0}",
            "{\u4e2d:1}", "{\\u4e2d:1}", "{a:'\\uD800'}", "{a:'\\uDC00'}",
        )) {
            val failure = rejected { parser.parseObject(source) }
            assertEquals(source, ConfigError.UNSUPPORTED, failure.code)
            assertFalse(failure.message!!.contains(source))
        }
    }

    @Test fun malformedAndExpressionInputsFailInsteadOfEvaluating() {
        for (source in listOf(
            "{a:undefined}", "{a:function(){}}", "{a:(1+1)}", "{a:new Date()}",
            "{a:01}", "{a:0x}", "{a:'\\1'}", "{a:'\\09'}", "{a:1_000}",
            "{a:[,1]}", "{a:1,,}", "{a:1} trailing", "{/* unclosed", "{a:'raw\nline'}",
            "[]", "null", "", "{a:'unterminated}", "{a:0b10}",
        )) rejected { parser.parseObject(source) }
    }

    @Test fun malformedUtf8AndOversizedBytesFailBeforeParsing() {
        assertEquals(ConfigError.UTF8, rejected {
            parser.parseObject(byteArrayOf(0xc3.toByte(), 0x28))
        }.code)
        val limited = BoundedJson5(ConfigLimits(maxDocumentBytes = 32))
        assertEquals(ConfigError.LIMIT, rejected { limited.parseObject(ByteArray(33)) }.code)
    }

    @Test fun depthKeyNodeStringAndNumberBudgetsAreEnforced() {
        val cases = listOf(
            ConfigLimits(maxDepth = 2) to "{a:{b:{c:1}}}",
            ConfigLimits(maxKeys = 1) to "{a:1,b:2}",
            ConfigLimits(maxNodes = 2) to "{a:[1]}",
            ConfigLimits(maxStringChars = 2) to "{a:'long'}",
            ConfigLimits(maxNumberChars = 2) to "{a:123}",
        )
        for ((limits, text) in cases) {
            assertEquals(ConfigError.LIMIT, rejected { BoundedJson5(limits).parseObject(text) }.code)
        }
    }

    @Test fun numericAndDepthBoundariesAcceptExactlyTheirBudget() {
        assertEquals(mapOf("a" to mapOf("b" to 1.0)),
            BoundedJson5(ConfigLimits(maxDepth = 2, maxKeys = 2, maxNodes = 3))
                .parseObject("{a:{b:1}}"))
        assertEquals(-0.0, parser.parseObject("{zero:-0}")["zero"])
    }

    @Test fun limitsCannotDisableTheHardRecursionOrAllocationCaps() {
        rejectedArgument { ConfigLimits(maxDepth = 100_000) }
        rejectedArgument { ConfigLimits(maxKeys = 0) }
        rejectedArgument { ConfigLimits(maxDocumentBytes = Int.MAX_VALUE) }
    }
}

internal fun rejected(block: () -> Unit): ConfigRejected {
    try { block() } catch (failure: ConfigRejected) { return failure }
    throw AssertionError("Expected explicit rejection")
}

internal fun rejectedArgument(block: () -> Unit) {
    try { block() } catch (_: IllegalArgumentException) { return }
    throw AssertionError("Expected argument validation")
}
