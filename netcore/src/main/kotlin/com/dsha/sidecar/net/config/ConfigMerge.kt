/*
 * SPDX-License-Identifier: MPL-2.0
 * Adapted from @blue-frontier/dev-sidecar/src/merge.js:
 * doMerge and deleteNullItems. Upstream author: docmirror.cn.
 */
package com.dsha.sidecar.net.config

/**
 * Copies and merges JSON values represented by string-keyed maps, lists,
 * strings, numbers, booleans, and null. Other value types are rejected.
 * Inputs must be acyclic. Returned maps and lists never alias input containers.
 *
 * Only objects merge recursively; arrays and scalar values replace old values.
 * Deletion markers must survive [merge] until [sanitize] runs, otherwise an
 * overlay null would incorrectly leave an inherited value in place.
 */
object ConfigMerge {

    /**
     * Recursively merges [overlay] over [base], retaining null and "[delete]".
     * A map replacing a non-map starts fresh; lists always replace as a whole.
     */
    fun merge(base: Map<String, Any?>, overlay: Map<String, Any?>): Map<String, Any?> =
        mergeObjects(base, overlay)

    /**
     * Returns a deep copy without null or exact "[delete]" values, recursively.
     * Empty objects and lists are retained.
     */
    fun sanitize(
        config: Map<String, Any?>,
        preserveArraySlots: Boolean = false,
    ): Map<String, Any?> = sanitizeObject(config, preserveArraySlots)

    /**
     * Cleans and copies a list, compacting deleted elements. Unlike upstream
     * JavaScript `delete array[index]`, this does not preserve sparse holes.
     * Set [preserveArraySlots] for the Node adapter: null slots are restored
     * to JavaScript holes after JSON transport.
     */
    fun sanitize(values: List<Any?>, preserveArraySlots: Boolean = false): List<Any?> =
        if (preserveArraySlots) {
            values.map { if (isDeletion(it)) null else sanitizeValue(it, true) }
        } else {
            values.filterNot(::isDeletion).map { sanitizeValue(it, false) }
        }

    /** Merges first, then removes deletion markers from the complete result. */
    fun mergeAndSanitize(
        base: Map<String, Any?>,
        overlay: Map<String, Any?>,
    ): Map<String, Any?> = sanitize(merge(base, overlay))

    private fun mergeObjects(base: Map<*, *>, overlay: Map<*, *>): Map<String, Any?> {
        val result = copyObject(base)
        for ((key, value) in overlay) {
            require(key is String) { "JSON object keys must be strings" }
            val previous = result[key]
            result[key] = if (previous is Map<*, *> && value is Map<*, *>) {
                mergeObjects(previous, value)
            } else {
                copyValue(value)
            }
        }
        return result
    }

    private fun copyObject(value: Map<*, *>): MutableMap<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        for ((key, item) in value) {
            require(key is String) { "JSON object keys must be strings" }
            result[key] = copyValue(item)
        }
        return result
    }

    private fun copyValue(value: Any?): Any? = when (value) {
        is Map<*, *> -> copyObject(value)
        is List<*> -> value.map(::copyValue)
        null, is String, is Boolean, is Number -> value
        else -> throw IllegalArgumentException("Unsupported JSON value type")
    }

    private fun sanitizeObject(value: Map<*, *>, preserveArraySlots: Boolean): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        for ((key, item) in value) {
            require(key is String) { "JSON object keys must be strings" }
            if (!isDeletion(item)) {
                result[key] = sanitizeValue(item, preserveArraySlots)
            }
        }
        return result
    }

    private fun sanitizeValue(value: Any?, preserveArraySlots: Boolean): Any? = when (value) {
        is Map<*, *> -> sanitizeObject(value, preserveArraySlots)
        is List<*> -> sanitize(value, preserveArraySlots)
        else -> copyValue(value)
    }

    private fun isDeletion(value: Any?): Boolean = value == null || value == "[delete]"
}
