// SPDX-License-Identifier: MPL-2.0
package com.dsha.sidecar.net.http.native.script

/**
 * Conservative byte scanner for an explicit head closing tag. Not a general HTML DOM parser.
 * Ignores comments, quoted attributes and raw-text elements so literal "</head>" is not a target.
 */
internal object HtmlHeadBoundary {
    fun find(bytes: ByteArray): Int {
        var index = 0
        var seenHead = false
        var raw: String? = null
        while (index < bytes.size) {
            if (bytes[index] != '<'.code.toByte()) { index++; continue }
            if (starts(bytes, index, "<!--")) {
                val end = (index + 4 until bytes.size - 2).firstOrNull { starts(bytes, it, "-->") } ?: return -1
                index = end + 3
                continue
            }
            var cursor = index + 1
            val closing = bytes.getOrNull(cursor) == '/'.code.toByte()
            if (closing) cursor++
            val start = cursor
            while (cursor < bytes.size && character(bytes[cursor]).let { it in 'a'..'z' || it in '0'..'9' || it == '-' }) cursor++
            val name = (start until cursor).map { character(bytes[it]) }.joinToString("")
            if (name.isEmpty()) {
                if (raw != null) { index++; continue }
                // Only declarations are skipped; malformed tag starts are refused.
                if (bytes.getOrNull(start) !in listOf('!'.code.toByte(), '?'.code.toByte())) return -1
            }
            if (raw != null && !(closing && name == raw)) { index++; continue }
            if (name.isNotEmpty() && bytes.getOrNull(cursor)?.let {
                    character(it) !in listOf(' ', '\t', '\r', '\n', '\u000c', '/', '>')
                } == true) { index++; continue }
            var quote: Byte? = null
            while (cursor < bytes.size) {
                val current = bytes[cursor]
                if (quote != null) {
                    if (current == quote) quote = null
                } else if (current == '\''.code.toByte() || current == '"'.code.toByte()) quote = current
                else if (current == '>'.code.toByte()) break
                cursor++
            }
            if (cursor >= bytes.size) return -1
            if (raw != null) raw = null
            else when {
                closing && name == "head" -> return if (seenHead) index else -1
                !closing && name == "head" -> { if (seenHead) return -1; seenHead = true }
                !closing && name == "body" -> return -1
                !closing && name in listOf("script", "style", "title", "textarea") -> raw = name
            }
            index = cursor + 1
        }
        return -1
    }

    private fun character(byte: Byte): Char = (byte.toInt() and 255).toChar().let { if (it in 'A'..'Z') it + 32 else it }
    private fun starts(bytes: ByteArray, offset: Int, text: String): Boolean =
        offset + text.length <= bytes.size && text.indices.all { bytes[offset + it] == text[it].code.toByte() }
}
