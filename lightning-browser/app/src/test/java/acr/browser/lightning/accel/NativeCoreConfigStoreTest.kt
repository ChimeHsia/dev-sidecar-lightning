package acr.browser.lightning.accel

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NativeCoreConfigStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun migratesOnlyConfigurationAndPreservesNativeUserChanges() {
        val legacy = temporary.newFolder("legacy")
        File(legacy, "config.json").writeText("""{"server":{"port":31181}}""")
        File(legacy, "dev-sidecar.ca.key.pem").writeText("not a real key")
        File(legacy, "running.json").writeText("""{"not":"a user configuration"}""")
        val directory = temporary.newFolder("native")
        val store = NativeCoreConfigStore(directory, legacy)
        assertEquals("""{"server":{"port":31181}}""", store.documents("{}").user)
        assertEquals(setOf("config.json"), directory.listFiles()!!.map { it.name }.toSet())
        store.write("config.json", """{"server":{"port":31182}}""".toByteArray())
        assertEquals("""{"server":{"port":31182}}""",
            NativeCoreConfigStore(directory, legacy).documents("{}").user)
        assertTrue(File(legacy, "dev-sidecar.ca.key.pem").exists())
    }

    @Test fun refusesOversizedOrUnrecognizedCacheWrites() {
        val store = NativeCoreConfigStore(temporary.newFolder(), null)
        assertThrows(IllegalArgumentException::class.java) { store.write("../config.json", byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { store.write("running.json", byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { store.write("config.json", ByteArray(1_048_577)) }
    }

    @Test fun legacyRootCaPathsNeverReplaceTheBundledAuthority() {
        val legacy = temporary.newFolder()
        File(legacy, "config.json").writeText(
            """{"server":{"setting":{"rootCaFile":{"keyPath":"untrusted.pem"}}}}""")
        val store = NativeCoreConfigStore(temporary.newFolder(), legacy)
        val config = store.load("""{"server":{"enabled":true,"port":31181}}""")
        assertEquals(31181, config.listenPort)
    }
}
