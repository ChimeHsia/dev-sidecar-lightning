/*
 * SPDX-License-Identifier: MPL-2.0
 * Regression tests for semantics adapted from
 * @blue-frontier/dev-sidecar/src/merge.js (docmirror.cn).
 */
package com.dsha.sidecar.net.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class ConfigMergeTest {

    @Test
    fun `Node cleanup preserves deleted array slots until the adapter restores holes`() {
        val input = mapOf(
            "list" to listOf(1, null, "[delete]", mapOf("gone" to null), listOf("[delete]", 2)),
        )
        assertEquals(
            mapOf("list" to listOf(1, null, null, emptyMap<String, Any?>(), listOf(null, 2))),
            ConfigMerge.sanitize(input, preserveArraySlots = true),
        )
    }

    @Test
    fun `nested objects override retain add and delete fields`() {
        val base = mapOf(
            "proxy" to mapOf(
                "host" to "old.example",
                "port" to 31181,
                "auth" to mapOf("user" to "old", "password" to "secret", "realm" to "local"),
            ),
            "keep" to true,
        )
        val overlay = mapOf(
            "proxy" to mapOf(
                "host" to "new.example",
                "auth" to mapOf("user" to "new", "password" to null, "realm" to "[delete]"),
                "enabled" to false,
            ),
        )

        assertEquals(
            mapOf(
                "proxy" to mapOf(
                    "host" to "new.example",
                    "port" to 31181,
                    "auth" to mapOf("user" to "new"),
                    "enabled" to false,
                ),
                "keep" to true,
            ),
            ConfigMerge.mergeAndSanitize(base, overlay),
        )
    }

    @Test
    fun `merge preserves deletion markers until sanitize runs`() {
        val base = mapOf(
            "nullTarget" to mapOf("inherited" to true),
            "markerTarget" to listOf("inherited"),
            "nested" to mapOf("remove" to 1, "keep" to 2),
        )
        val overlay = mapOf(
            "nullTarget" to null,
            "markerTarget" to "[delete]",
            "nested" to mapOf("remove" to null),
        )

        val merged = ConfigMerge.merge(base, overlay)

        assertTrue(merged.containsKey("nullTarget"))
        assertEquals(null, merged["nullTarget"])
        assertEquals("[delete]", merged["markerTarget"])
        assertEquals(mapOf("remove" to null, "keep" to 2), merged["nested"])
        assertEquals(
            mapOf("nested" to mapOf("keep" to 2)),
            ConfigMerge.sanitize(merged),
        )
        assertEquals(ConfigMerge.sanitize(merged), ConfigMerge.mergeAndSanitize(base, overlay))
    }

    @Test
    fun `lists replace completely without merging elements or retaining a tail`() {
        val base = mapOf(
            "items" to listOf(mapOf("old" to 1, "keep" to 2), "tail"),
            "clear" to listOf(1, 2),
        )
        val overlay = mapOf(
            "items" to listOf(mapOf("new" to 3)),
            "clear" to emptyList<Any?>(),
        )

        assertEquals(overlay, ConfigMerge.mergeAndSanitize(base, overlay))
    }

    @Test
    fun `different container and scalar types replace each other`() {
        val base = mapOf(
            "listToObject" to listOf(1, 2),
            "objectToList" to mapOf("old" to 1),
            "scalarToObject" to 42,
            "objectToScalar" to mapOf("old" to 1),
            "nullToObject" to null,
        )
        val overlay = mapOf(
            "listToObject" to mapOf("fresh" to true),
            "objectToList" to listOf("fresh"),
            "scalarToObject" to mapOf("fresh" to true),
            "objectToScalar" to false,
            "nullToObject" to mapOf("fresh" to true),
        )

        assertEquals(overlay, ConfigMerge.mergeAndSanitize(base, overlay))
    }

    @Test
    fun `false zero and empty strings survive merge and cleanup`() {
        val overlay = mapOf(
            "enabled" to false,
            "count" to 0,
            "label" to "",
            "items" to listOf(false, 0, ""),
        )
        val base = mapOf("enabled" to true, "count" to 10, "label" to "old")

        assertEquals(overlay, ConfigMerge.mergeAndSanitize(base, overlay))
    }

    @Test
    fun `sanitize removes markers recursively and compacts lists`() {
        val config = mapOf(
            "removeNull" to null,
            "removeMarker" to "[delete]",
            "nested" to mapOf("gone" to null, "kept" to "ok"),
            "items" to listOf(
                null,
                "first",
                "[delete]",
                mapOf("gone" to "[delete]", "kept" to 1),
                listOf(null, false, "[delete]", 0, ""),
                null,
            ),
        )
        val expectedItems = listOf("first", mapOf("kept" to 1), listOf(false, 0, ""))

        assertEquals(
            mapOf("nested" to mapOf("kept" to "ok"), "items" to expectedItems),
            ConfigMerge.sanitize(config),
        )
        assertEquals(expectedItems, ConfigMerge.sanitize(config["items"] as List<*>))
    }

    @Test
    fun `only exact marker values are deleted and empty containers remain`() {
        val config = mapOf(
            "[delete]" to "a key",
            "spaced" to " [delete] ",
            "case" to "[DELETE]",
            "object" to mapOf("gone" to null),
            "list" to listOf(null, "[delete]"),
            "nestedLists" to listOf(listOf(null), mapOf("gone" to "[delete]")),
        )

        assertEquals(
            mapOf(
                "[delete]" to "a key",
                "spaced" to " [delete] ",
                "case" to "[DELETE]",
                "object" to emptyMap<String, Any?>(),
                "list" to emptyList<Any?>(),
                "nestedLists" to listOf(emptyList<Any?>(), emptyMap<String, Any?>()),
            ),
            ConfigMerge.sanitize(config),
        )
    }

    @Test
    fun `empty overlay still cleans inherited markers`() {
        val base = mapOf(
            "gone" to null,
            "nested" to mapOf("gone" to "[delete]", "kept" to true),
            "items" to listOf(null, 1, "[delete]"),
        )

        assertEquals(base, ConfigMerge.merge(base, emptyMap()))
        assertEquals(
            mapOf("nested" to mapOf("kept" to true), "items" to listOf(1)),
            ConfigMerge.mergeAndSanitize(base, emptyMap()),
        )
    }

    @Test
    fun `empty configurations are supported`() {
        val empty = emptyMap<String, Any?>()
        val config = mapOf("nested" to mapOf("kept" to true))

        assertEquals(empty, ConfigMerge.mergeAndSanitize(empty, empty))
        assertEquals(empty, ConfigMerge.sanitize(empty))
        assertEquals(emptyList(), ConfigMerge.sanitize(emptyList()))
        assertEquals(config, ConfigMerge.mergeAndSanitize(empty, config))
        assertEquals(config, ConfigMerge.mergeAndSanitize(config, empty))
        assertEquals(empty, ConfigMerge.mergeAndSanitize(empty, mapOf("gone" to null)))
    }

    @Test
    fun `merge deep copies retained replaced and recursively merged containers`() {
        val retainedChild = linkedMapOf<String, Any?>("value" to 1)
        val retainedList = mutableListOf<Any?>(retainedChild)
        val baseNested = linkedMapOf<String, Any?>("keep" to retainedList, "replace" to "old")
        val replacementChild = linkedMapOf<String, Any?>("value" to 2)
        val replacementList = mutableListOf<Any?>(replacementChild)
        val base = linkedMapOf<String, Any?>(
            "nested" to baseNested,
            "untouched" to retainedChild,
            "items" to listOf("old"),
        )
        val overlayNested = linkedMapOf<String, Any?>("replace" to "new")
        val overlay = linkedMapOf<String, Any?>(
            "nested" to overlayNested,
            "items" to replacementList,
        )

        val merged = ConfigMerge.merge(base, overlay)
        val nested = merged["nested"] as Map<*, *>
        val retained = nested["keep"] as List<*>
        val replaced = merged["items"] as List<*>

        assertEquals("old", baseNested["replace"])
        assertEquals<Map<String, Any?>>(mapOf("replace" to "new"), overlayNested)
        assertNotSame(base, merged)
        assertNotSame(baseNested, nested)
        assertNotSame(overlayNested, nested)
        assertNotSame(retainedList, retained)
        assertNotSame(retainedChild, retained[0])
        assertNotSame(retainedChild, merged["untouched"])
        assertNotSame(replacementList, replaced)
        assertNotSame(replacementChild, replaced[0])

        retainedChild["value"] = 10
        retainedList.add("later")
        replacementChild["value"] = 20
        replacementList.clear()
        overlayNested["replace"] = "later"
        assertEquals(listOf(mapOf("value" to 1)), retained)
        assertEquals(mapOf("value" to 1), merged["untouched"])
        assertEquals(listOf(mapOf("value" to 2)), replaced)
        assertEquals("new", nested["replace"])
    }

    @Test
    fun `sanitize deep copies without mutating input maps or lists`() {
        val child = linkedMapOf<String, Any?>("gone" to null, "kept" to 1)
        val items = mutableListOf<Any?>(null, child, "[delete]")
        val input = linkedMapOf<String, Any?>("items" to items, "gone" to "[delete]")

        val sanitized = ConfigMerge.sanitize(input)
        val sanitizedItems = sanitized["items"] as List<*>
        val sanitizedList = ConfigMerge.sanitize(items)

        assertEquals(mapOf("items" to listOf(mapOf("kept" to 1))), sanitized)
        assertEquals(listOf(mapOf("kept" to 1)), sanitizedList)
        assertEquals<Map<String, Any?>>(mapOf("gone" to null, "kept" to 1), child)
        assertEquals<List<Any?>>(listOf(null, child, "[delete]"), items)
        assertEquals("[delete]", input["gone"])
        assertNotSame(input, sanitized)
        assertNotSame(items, sanitizedItems)
        assertNotSame(child, sanitizedItems[0])
        assertNotSame(items, sanitizedList)
        assertNotSame(child, sanitizedList[0])

        child["kept"] = 2
        items.clear()
        assertEquals(listOf(mapOf("kept" to 1)), sanitizedItems)
        assertEquals(listOf(mapOf("kept" to 1)), sanitizedList)
    }

    @Test
    fun `combined operation leaves deletion markers in both inputs`() {
        val base = linkedMapOf<String, Any?>(
            "nested" to linkedMapOf<String, Any?>("keep" to 1, "remove" to 2),
            "items" to mutableListOf<Any?>(null, "old"),
        )
        val overlay = linkedMapOf<String, Any?>(
            "nested" to linkedMapOf<String, Any?>("remove" to null),
            "items" to mutableListOf<Any?>("[delete]", "new"),
        )

        assertEquals(
            mapOf("nested" to mapOf("keep" to 1), "items" to listOf("new")),
            ConfigMerge.mergeAndSanitize(base, overlay),
        )
        assertEquals<Map<String, Any?>>(
            mapOf("nested" to mapOf("keep" to 1, "remove" to 2), "items" to listOf(null, "old")),
            base,
        )
        assertEquals<Map<String, Any?>>(
            mapOf("nested" to mapOf("remove" to null), "items" to listOf("[delete]", "new")),
            overlay,
        )
    }

    @Test
    fun `non JSON values and non string object keys are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            ConfigMerge.merge(emptyMap(), mapOf("unsupported" to Any()))
        }
        assertFailsWith<IllegalArgumentException> {
            ConfigMerge.sanitize(mapOf("nested" to mapOf(1 to "invalid key")))
        }
        assertFailsWith<IllegalArgumentException> {
            ConfigMerge.sanitize(listOf(setOf(1)))
        }
    }
}
