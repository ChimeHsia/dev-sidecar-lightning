package com.dsha.sidecar.net.config.native

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class NativeConfigRepositoryTest {
    private val defaults = "{server:{setting:{verifySsl:true},dns:{providers:{},mapping:{}},intercepts:{}}}"

    @Test fun sixPassMergePreservesOrderAndHighPriorityValues() {
        val snapshot = applied(NativeConfigRepository().reload(ConfigDocuments(
            defaults = "{server:{intercepts:{last:{'.*':{sni:'default'}},both:{'.*':{sni:'default'}}}}}",
            user = "{server:{intercepts:{first:{'.*':{sni:'user'}},both:{'.*':{sni:'user'}}}}}",
            personal = "{server:{intercepts:{personal:{'.*':{sni:'personal'}},both:{'.*':{sni:'personal'}}}}}",
            shared = "{server:{intercepts:{shared:{'.*':{sni:'shared'}},both:{'.*':{sni:'shared'}}}}}",
        )))
        assertEquals(listOf("first", "both", "personal", "shared", "last"), snapshot.intercepts.keys.toList())
        assertEquals("user", (((snapshot.intercepts["both"] as Map<*, *>)[".*"]) as Map<*, *>)["sni"])
        assertEquals(listOf(ConfigLayer.USER, ConfigLayer.PERSONAL, ConfigLayer.SHARED, ConfigLayer.DEFAULT),
            snapshot.provenance.map { it.layer })
    }

    @Test fun finalDeletionAndArraySlotBehaviorUseAcceptedConfigMerge() {
        val snapshot = applied(NativeConfigRepository().reload(ConfigDocuments(
            defaults = "{server:{intercepts:{gone:{a:1},keep:{a:1}},items:[1,2,3]}}",
            user = "{server:{intercepts:{gone:null},items:[1,null,'[delete]',{a:null}]}}",
        )))
        assertFalse(snapshot.intercepts.containsKey("gone"))
        assertEquals(listOf(1.0, null, null, emptyMap<String, Any?>()), snapshot.server["items"])
    }

    @Test fun noUserLayerGetsTheOriginalAndroidSeedAndNoDesktopActivation() {
        val snapshot = applied(NativeConfigRepository().reload(ConfigDocuments(defaults)))
        assertEquals(31181.0, snapshot.server["port"])
        assertEquals(true, snapshot.server["enabled"])
        assertTrue(snapshot.provenance.first().androidSeed)
        val plugins = snapshot.configuration["plugin"] as Map<*, *>
        for (name in listOf("node", "git", "pip", "share", "free_eye", "overwall")) {
            assertEquals(false, (plugins[name] as Map<*, *>)["enabled"])
        }
    }

    @Test fun remoteOrUserOverridesCannotActivatePluginsOrSystemProxy() {
        val snapshot = applied(NativeConfigRepository().reload(ConfigDocuments(
            defaults, "{plugin:{custom:{enabled:true}},proxy:{enabled:true}}",
            shared = "{plugin:{node:{enabled:true}}}",
        )))
        assertEquals(false, (snapshot.configuration["proxy"] as Map<*, *>)["enabled"])
        val plugins = snapshot.configuration["plugin"] as Map<*, *>
        assertEquals(false, (plugins["custom"] as Map<*, *>)["enabled"])
        assertEquals(false, (plugins["node"] as Map<*, *>)["enabled"])
    }

    @Test fun invalidReloadRetainsExactPreviousSnapshotAndRevision() {
        val repository = NativeConfigRepository()
        val before = applied(repository.reload(ConfigDocuments(defaults, "{}")))
        val failure = repository.reload(ConfigDocuments(defaults, "{bad:'private-token' garbage}")) as ConfigUpdate.Rejected
        assertSame(before, failure.previous)
        assertSame(before, repository.current())
        assertEquals(1L, before.revision)
        assertFalse(failure.toString().contains("private-token"))
        assertEquals(2L, applied(repository.reload(ConfigDocuments(defaults, "{}"))).revision)
    }

    @Test fun firstInvalidLoadDoesNotPublishAnEmptySuccess() {
        val repository = NativeConfigRepository()
        assertNull((repository.reload(ConfigDocuments("garbage")) as ConfigUpdate.Rejected).previous)
        assertNull(repository.current())
    }

    @Test fun everyNestedContainerAndMetadataListIsReadOnly() {
        val snapshot = applied(NativeConfigRepository().reload(ConfigDocuments(
            defaults, "{server:{items:[{x:1}]}}",
        )))
        unsupportedMutation { (snapshot.configuration as MutableMap)["server"] = emptyMap<String, Any?>() }
        unsupportedMutation { (snapshot.server as MutableMap)["x"] = 1 }
        unsupportedMutation { (snapshot.server["items"] as MutableList<Any?>).add(2) }
        unsupportedMutation { ((snapshot.server["items"] as List<*>)[0] as MutableMap<String, Any?>)["x"] = 2 }
        unsupportedMutation { (snapshot.provenance as MutableList).clear() }
    }

    @Test fun totalBytesAndAggregateKeyBudgetsRejectWholeTransaction() {
        val repository = NativeConfigRepository(ConfigLimits(maxDocumentBytes = 64, maxTotalBytes = 64))
        assertTrue(repository.reload(ConfigDocuments(
            "{a:'123456789012345678901234567890'}",
            "{b:'123456789012345678901234567890'}")) is ConfigUpdate.Rejected)
        val keyLimited = NativeConfigRepository(ConfigLimits(maxKeys = 3))
        assertEquals(ConfigError.LIMIT, (keyLimited.reload(ConfigDocuments(
            "{a:1,b:2}", "{c:3,d:4}")) as ConfigUpdate.Rejected).error)
    }

    @Test fun byteAndDepthBudgetFailuresKeepTheLastUsableEpoch() {
        val repository = NativeConfigRepository(ConfigLimits(
            maxDocumentBytes = 256, maxTotalBytes = 1024, maxDepth = 4,
        ))
        val before = applied(repository.reload(ConfigDocuments(defaults, "{}")))
        for (bad in listOf(
            "{server:{deep:{a:{b:{c:1}}}}}",
            "{server:{value:'" + "x".repeat(257) + "'}}",
        )) {
            val failure = repository.reload(ConfigDocuments(bad, "{}")) as ConfigUpdate.Rejected
            assertEquals(ConfigError.LIMIT, failure.error)
            assertSame(before, failure.previous)
            assertSame(before, repository.current())
        }
    }

    @Test fun consumerValidationRejectsWithoutPublishingAndRedactsExceptions() {
        var allow = true
        val repository = NativeConfigRepository(validate = {
            if (!allow) throw IllegalArgumentException("secret URL must not escape")
        })
        val previous = applied(repository.reload(ConfigDocuments(defaults, "{}")))
        allow = false
        val failure = repository.reload(ConfigDocuments(defaults, "{}")) as ConfigUpdate.Rejected
        assertEquals(ConfigError.VALIDATION, failure.error)
        assertSame(previous, failure.previous)
        assertFalse(failure.toString().contains("secret"))
    }

    @Test fun reentrantWriterCannotPublishAnEpochDuringValidation() {
        lateinit var repository: NativeConfigRepository
        var nested: ConfigUpdate? = null
        repository = NativeConfigRepository(validate = {
            nested = repository.reload(ConfigDocuments(defaults, "{}"))
        })
        assertEquals(1L, applied(repository.reload(ConfigDocuments(defaults, "{}"))).revision)
        assertTrue(nested is ConfigUpdate.Rejected)
        assertEquals(1L, repository.current()!!.revision)
    }

    @Test fun invalidServerShapeAndInvalidUtf16FailRatherThanCoercing() {
        val repository = NativeConfigRepository()
        assertEquals(ConfigError.SHAPE,
            (repository.reload(ConfigDocuments("{server:1}", "{}")) as ConfigUpdate.Rejected).error)
        assertEquals(ConfigError.UTF8,
            (repository.reload(ConfigDocuments("{a:'\uD800'}", "{}")) as ConfigUpdate.Rejected).error)
    }

    @Test fun cacheFilesPreferNewUserFormatAndCorruptNewFileNeverUsesLegacy() {
        withDirectory { directory ->
            Files.writeString(directory.resolve("config.json5"), "{server:{port:1234}}")
            val repository = NativeConfigRepository()
            assertEquals(1234.0, applied(repository.reloadFiles(defaults, directory.toFile())).server["port"])
            Files.writeString(directory.resolve("config.json"), "{server:{port:5678}}")
            val before = applied(repository.reloadFiles(defaults, directory.toFile()))
            assertEquals(5678.0, before.server["port"])
            Files.writeString(directory.resolve("config.json"), "bad")
            assertSame(before, (repository.reloadFiles(defaults, directory.toFile()) as ConfigUpdate.Rejected).previous)
        }
    }

    @Test fun localSharedAndPersonalCacheFilesHaveCorrectPrecedence() {
        withDirectory { directory ->
            Files.writeString(directory.resolve("remote_config.json5"), "{server:{port:2000}}")
            Files.writeString(directory.resolve("remote_config_personal.json5"), "{server:{port:3000}}")
            Files.writeString(directory.resolve("config.json"), "{}")
            assertEquals(3000.0, applied(NativeConfigRepository().reloadFiles(defaults, directory.toFile())).server["port"])
        }
    }

    @Test fun optionalMissingFilesDifferFromInvalidExistingOrDirectoryFiles() {
        withDirectory { directory ->
            assertNull(ConfigFileStore(directory.toFile()).read("missing.json"))
            val repository = NativeConfigRepository()
            val before = applied(repository.reloadFiles(defaults, directory.toFile()))
            Files.createDirectory(directory.resolve("remote_config.json5"))
            assertSame(before, (repository.reloadFiles(defaults, directory.toFile()) as ConfigUpdate.Rejected).previous)
        }
    }

    @Test fun pathsCannotTraverseOrReadSymlinkOrOversizedFile() {
        withDirectory { directory ->
            val files = ConfigFileStore(directory.toFile(), ConfigLimits(maxDocumentBytes = 16))
            for (name in listOf("../config.json", "/config.json", "C:\\config.json", "nested/config.json",
                "config.json:stream", "config..json", "config.json/other")) {
                assertEquals(ConfigError.PATH, rejected { files.read(name) }.code)
            }
            Files.write(directory.resolve("large.json"), ByteArray(17))
            assertEquals(ConfigError.LIMIT, rejected { files.read("large.json") }.code)
            // Windows Developer Mode/admin permission is required for symlink creation.
            val target = directory.resolve("target.json")
            Files.writeString(target, "{}")
            val link = directory.resolve("link.json")
            try {
                Files.createSymbolicLink(link, target)
                assertEquals(ConfigError.PATH, rejected { files.read("link.json") }.code)
            } catch (failure: java.nio.file.FileSystemException) {
                assertFalse(Files.isSymbolicLink(link))
            }
        }
    }

    @Test fun writersSerializeAndReadersNeverSeeHalfBuiltSnapshots() {
        val repository = NativeConfigRepository()
        val first = applied(repository.reload(ConfigDocuments(defaults, "{}")))
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val jobs = (1..3).map { writer ->
            pool.submit {
                try {
                    start.await()
                    repeat(15) { n ->
                        applied(repository.reload(ConfigDocuments(
                            "{server:{epoch:${writer * 100 + n},copy:${writer * 100 + n}}}", "{}")))
                    }
                } catch (error: Throwable) { failure.set(error) }
            }
        }
        start.countDown()
        repeat(2000) {
            val seen = repository.current()!!
            if (seen !== first) assertEquals(seen.server["epoch"], seen.server["copy"])
        }
        jobs.forEach { it.get(10, TimeUnit.SECONDS) }
        pool.shutdownNow()
        failure.get()?.let { throw AssertionError(it) }
        assertEquals(46L, repository.current()!!.revision)
    }
}

internal fun applied(result: ConfigUpdate): DecodedServerSnapshot =
    (result as? ConfigUpdate.Applied)?.snapshot ?: throw AssertionError("Unexpected rejection: $result")

internal fun unsupportedMutation(block: () -> Unit) {
    try { block() } catch (_: UnsupportedOperationException) { return }
    throw AssertionError("A mutable snapshot container escaped")
}

internal fun withDirectory(block: (java.nio.file.Path) -> Unit) {
    val directory = Files.createTempDirectory("synthetic-native-config-")
    try { block(directory) } finally {
        Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
}
