// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RequestRetryPolicyTest {
    @Test
    fun upstreamRetryFixtures() {
        for (case in HttpPolicyFixtures.cases("retry")) {
            val input = case.input
            @Suppress("UNCHECKED_CAST")
            val result = RequestRetryPolicy.canRetryWithBody(
                input["method"],
                input["headers"] as Map<String, Any?>,
                input["methods"] as List<String>,
                (input["threshold"] as Number).toInt(),
            )
            assertEquals(case.name, case.expected, result)
        }
    }

    @Test
    fun rejectsUnsupportedInputEvenWhenMethodWouldBeIneligible() {
        assertThrows(IllegalArgumentException::class.java) {
            RequestRetryPolicy.canRetryWithBody("UNKNOWN", mapOf("x" to Any()), listOf("GET"), 10)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RequestRetryPolicy.canRetryWithBody("GET", emptyMap(), listOf("GET"), -1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RequestRetryPolicy.canRetryWithBody("GET", mapOf("expect" to listOf("continue")), listOf("GET"), 10)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RequestRetryPolicy.canRetryWithBody(true, emptyMap(), listOf("GET"), 10)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RequestRetryPolicy.canRetryWithBody("POST", mapOf("content-length" to 1e21), listOf("POST"), 10)
        }
    }

    @Test
    fun doesNotMutateHeadersOrMethodList() {
        val nested = mutableListOf<Any?>("10")
        val headers = linkedMapOf<String, Any?>("content-length" to nested)
        val methods = mutableListOf("POST")
        assertEquals(true, RequestRetryPolicy.canRetryWithBody("post", headers, methods, 10))
        assertEquals(mapOf("content-length" to listOf("10")), headers)
        assertEquals(listOf("POST"), methods)
    }
}
