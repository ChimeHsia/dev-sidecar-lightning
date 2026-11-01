package acr.browser.lightning.accel

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Keeps one completed version per archive; stale markers never authorize a rollback skip. */
internal object PayloadExtractor {
    fun extract(
        name: String,
        version: String,
        destination: File,
        openArchive: () -> InputStream,
    ): Int? {
        require(name.matches(Regex("[A-Za-z0-9_-]+\\.zip")))
        require(version.matches(Regex("[A-Za-z0-9._-]+")))
        if (!destination.isDirectory && !destination.mkdirs()) {
            throw IOException("Cannot create payload directory")
        }
        val marker = File(destination, ".$name.v$version.done")
        val previous = destination.listFiles()?.filter {
            it.name.startsWith(".$name.v") && it.name.endsWith(".done")
        } ?: throw IOException("Cannot inspect payload markers")
        if (marker.isFile && previous.size == 1 && previous.single() == marker) return null

        // Old APKs also use these marker names. Removing stale versions makes an
        // old APK re-extract on rollback, and lets this code detect a later return.
        for (file in previous) {
            if (!file.isFile || !file.delete()) throw IOException("Cannot invalidate payload marker")
        }
        val root = destination.canonicalFile
        val prefix = root.path + File.separator
        val written = HashSet<String>()
        var count = 0
        openArchive().use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val output = File(root, entry.name).canonicalFile
                    if (!output.path.startsWith(prefix)) throw IOException("Payload entry escapes its directory")
                    if (output.parentFile == root && output.name.startsWith(".$name.v") &&
                        output.name.endsWith(".done")
                    ) throw IOException("Payload archive cannot provide its completion marker")
                    if (entry.isDirectory) {
                        if (!output.isDirectory && !output.mkdirs()) throw IOException("Cannot create payload subdirectory")
                    } else {
                        if (!written.add(output.path)) throw IOException("Duplicate payload file")
                        val parent = output.parentFile
                        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create payload parent")
                        output.outputStream().use { zip.copyTo(it) }
                        count++
                    }
                    zip.closeEntry()
                }
            }
        }
        if (count == 0) throw IOException("Payload contains no files")
        if (!marker.createNewFile()) throw IOException("Cannot mark payload extraction complete")
        return count
    }
}
