package acr.browser.lightning.accel

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PayloadExtractorTest {
    @get:Rule val temporary = TemporaryFolder()
    private val name = "nodejs-project.zip"

    private fun archive(vararg files: Pair<String, String>): ByteArray =
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                for ((path, text) in files) {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(text.toByteArray())
                    zip.closeEntry()
                }
            }
        }.toByteArray()

    private fun extract(root: File, version: String, text: String) =
        PayloadExtractor.extract(name, version, root) { archive("main.js" to text).inputStream() }

    private fun markers(root: File) = root.listFiles()!!.filter { it.name.endsWith(".done") }.map { it.name }

    @Test
    fun `upgrade replaces old markers and an old APK must extract on rollback`() {
        val root = temporary.newFolder()
        File(root, ".$name.v8.done").createNewFile()
        File(root, "main.js").writeText("v9 left behind")
        File(root, ".$name.v9.done").createNewFile()
        assertEquals(1, extract(root, "10", "v10"))
        assertEquals(listOf(".$name.v10.done"), markers(root))
        assertFalse(File(root, ".$name.v8.done").exists())
        assertEquals("v10", File(root, "main.js").readText())
    }

    @Test
    fun `old APK rollback followed by returning to the new APK re-extracts`() {
        val root = temporary.newFolder()
        extract(root, "10", "v10")
        // Reproduce old APK behavior: it writes files and its own marker, leaving v10's marker.
        File(root, "main.js").writeText("v8")
        File(root, ".$name.v8.done").createNewFile()
        assertEquals(1, extract(root, "10", "v10 again"))
        assertEquals("v10 again", File(root, "main.js").readText())
        assertEquals(listOf(".$name.v10.done"), markers(root))
    }

    @Test
    fun `one matching marker skips archive opening`() {
        val root = temporary.newFolder()
        extract(root, "10", "v10")
        assertNull(PayloadExtractor.extract(name, "10", root) { error("Archive must not open") })
    }

    @Test
    fun `failed extraction cannot leave an old success marker and can retry`() {
        val root = temporary.newFolder()
        extract(root, "8", "v8")
        assertThrows(IOException::class.java) {
            PayloadExtractor.extract(name, "10", root) { throw IOException("Fixture read failure") }
        }
        assertTrue(markers(root).isEmpty())
        assertEquals(1, extract(root, "10", "recovered"))
        assertEquals("recovered", File(root, "main.js").readText())
    }

    @Test
    fun `unlisted user configuration and navigation data survive extraction`() {
        val root = temporary.newFolder()
        val config = File(root, "home/.dev-sidecar/config.json")
        config.parentFile.mkdirs()
        config.writeText("{\"retained\":true}")
        extract(root, "8", "v8")
        extract(root, "10", "v10")
        assertEquals("{\"retained\":true}", config.readText())
    }

    @Test
    fun `invalid or empty archive is never marked complete`() {
        val root = temporary.newFolder()
        for (bytes in listOf(byteArrayOf(), "not a ZIP".toByteArray(), archive())) {
            assertThrows(IOException::class.java) {
                PayloadExtractor.extract(name, "10", root) { bytes.inputStream() }
            }
            assertTrue(markers(root).isEmpty())
        }
    }

    @Test
    fun `archive traversal is rejected without writing outside the payload directory`() {
        val root = temporary.newFolder("payload")
        assertThrows(IOException::class.java) {
            PayloadExtractor.extract(name, "10", root) {
                archive("../escaped.txt" to "must not appear").inputStream()
            }
        }
        assertFalse(File(root.parentFile, "escaped.txt").exists())
        assertTrue(markers(root).isEmpty())
    }

    @Test
    fun `another archive marker does not invalidate the matching version`() {
        val root = temporary.newFolder()
        extract(root, "10", "v10")
        File(root, ".node-libs.zip.v8.done").createNewFile()
        assertNull(PayloadExtractor.extract(name, "10", root) { error("Different archive marker") })
    }

    @Test
    fun `archive content cannot forge a completed extraction`() {
        val root = temporary.newFolder()
        assertThrows(IOException::class.java) {
            PayloadExtractor.extract(name, "10", root) {
                archive(".$name.v10.done" to "not a valid marker").inputStream()
            }
        }
        assertTrue(markers(root).isEmpty())
        assertEquals(1, extract(root, "10", "valid retry"))
    }
}
