// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.dns

/** Every input rejection from SvcbCodec uses this boundary; offsets are bytes or text indices. */
class SvcbFormatException(message: String, val offset: Int? = null) :
    IllegalArgumentException(if (offset == null) message else "$message at $offset")

sealed class SvcbValue {
    data class Strings(val items: List<String>) : SvcbValue()
    data class Number(val number: Int) : SvcbValue()
    object Flag : SvcbValue()
    data class Text(val text: String) : SvcbValue()
    /** ECH and unknown wire parameters stay opaque. Arrays are owned by this result. */
    data class Bytes(val bytes: ByteArray) : SvcbValue()
}

data class SvcbParam(val key: Int, val name: String, val raw: ByteArray, val value: SvcbValue)

data class SvcbRecord(val priority: Int, val target: String, val params: List<SvcbParam>) {
    /** First occurrence wins, as in upstream; params retains every duplicate in wire order. */
    val paramMap: Map<String, SvcbValue> get() = firstValues(params.map { it.name to it.value })
    val ech: ByteArray? get() = (paramMap["ech"] as? SvcbValue.Bytes)?.bytes
}

/** Presentation values are textual, so they do not pretend to have original wire bytes. */
data class SvcbPresentationParam(val key: Int, val name: String, val value: SvcbValue)

data class SvcbPresentation(
    val priority: Int,
    val target: String,
    val params: List<SvcbPresentationParam>,
) {
    val paramMap: Map<String, SvcbValue> get() = firstValues(params.map { it.name to it.value })
    val ech: ByteArray? get() = (paramMap["ech"] as? SvcbValue.Bytes)?.bytes
}

data class SvcbDecodedName(val name: String, val offset: Int, val labels: List<ByteArray>)
data class SvcbQuestion(val name: String, val typeCode: Int, val klass: Int) {
    val type: String get() = SvcbCodec.typeName(typeCode)
}

enum class SvcbSection { ANSWER, AUTHORITY, ADDITIONAL }

data class SvcbResourceRecord(
    val name: String,
    val typeCode: Int,
    val klass: Int,
    /** Unsigned DNS TTL, including values above Int.MAX_VALUE. */
    val ttl: Long,
    val section: SvcbSection,
    val raw: ByteArray,
    val service: SvcbRecord? = null,
    val cname: String? = null,
) {
    val type: String get() = SvcbCodec.typeName(typeCode)
}

data class SvcbResponse(
    val id: Int,
    val flags: Int,
    val questions: List<SvcbQuestion>,
    /** All three RR sections in packet order, matching upstream's combined answers array. */
    val answers: List<SvcbResourceRecord>,
    val raw: ByteArray,
) {
    val rcode: Int get() = flags and 15
    val truncated: Boolean get() = flags and 0x0200 != 0
}

private fun firstValues(pairs: List<Pair<String, SvcbValue>>): Map<String, SvcbValue> {
    val result = linkedMapOf<String, SvcbValue>()
    for ((name, value) in pairs) if (name !in result) result[name] = value
    return result
}
