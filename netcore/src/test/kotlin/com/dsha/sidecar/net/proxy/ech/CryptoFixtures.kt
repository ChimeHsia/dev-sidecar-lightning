package com.dsha.sidecar.net.proxy.ech

import org.junit.Assert.assertArrayEquals
import java.util.Properties

internal fun hex(value: String): ByteArray {
    val text = value.filterNot { it.isWhitespace() }
    require(text.length % 2 == 0)
    return ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

internal fun fixture(name: String): Properties = Properties().apply {
    val stream = checkNotNull(HpkeTest::class.java.getResourceAsStream("/ech/$name.properties"))
    stream.use { load(it) }
}

internal fun Properties.bytes(name: String): ByteArray = hex(checkNotNull(getProperty(name)))

internal fun equalHex(expected: String, actual: ByteArray) = assertArrayEquals(hex(expected), actual)
