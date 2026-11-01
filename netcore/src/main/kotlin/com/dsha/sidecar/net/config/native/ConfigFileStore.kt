package com.dsha.sidecar.net.config.native

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream

/**
 * Reads only simple relative filenames beneath an existing, caller-owned directory.
 * Android API24 File API; no statically linked java.nio.file/desugaring dependency.
 * No creation, migration, deletion or URL persistence. File aliases, non-files and
 * ambiguous paths are rejected; bounded reads recheck canonical path/size/mtime.
 * This is not openat/O_NOFOLLOW: the caller must own and protect the root against
 * hostile concurrent replacement. Canonical root aliases used by Android itself
 * are resolved once; file aliases below that root are rejected.
 */
class ConfigFileStore(root: File, private val limits: ConfigLimits = ConfigLimits()) {
    private val directory: File

    init {
        try {
            directory = root.canonicalFile
            if (!directory.isDirectory) reject(ConfigError.PATH)
        } catch (failure: ConfigRejected) {
            throw failure
        } catch (_: Exception) {
            reject(ConfigError.PATH)
        }
    }

    fun read(name: String): ByteArray? {
        if (!name.matches(Regex("[A-Za-z0-9_-]+\\.(json|json5)"))) reject(ConfigError.PATH)
        val file = File(directory, name)
        try {
            if (optionalSymbolicLink(file) || file.canonicalFile != file ||
                file.canonicalFile.parentFile != directory
            ) reject(ConfigError.PATH)
            if (!file.exists()) {
                if (!directory.isDirectory || !directory.canRead()) reject(ConfigError.FILE)
                return null
            }
            if (!file.isFile) reject(ConfigError.PATH)
            val beforeSize = file.length()
            val beforeModified = file.lastModified()
            if (beforeSize > limits.maxDocumentBytes) reject(ConfigError.LIMIT)
            val output = ByteArrayOutputStream()
            FileInputStream(file).use { stream ->
                val buffer = ByteArray(4096)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (output.size().toLong() + count > limits.maxDocumentBytes) reject(ConfigError.LIMIT)
                    output.write(buffer, 0, count)
                }
            }
            if (optionalSymbolicLink(file) || file.canonicalFile != file ||
                !file.isFile || file.lastModified() != beforeModified ||
                file.length() != beforeSize || beforeSize != output.size().toLong()
            ) reject(ConfigError.FILE)
            return output.toByteArray()
        } catch (failure: ConfigRejected) {
            throw failure
        } catch (_: Exception) {
            reject(ConfigError.FILE)
        }
    }

    /** config.json wins; an invalid present new file never silently falls back to config.json5. */
    fun user(): ByteArray? = read("config.json") ?: read("config.json5")
}

// Windows File.canonicalFile does not resolve symbolic file aliases. On JVMs
// exposing NIO, use its link probe through reflection only; Android API24 has no
// static linkage to those API26 types and uses Unix canonical-path validation.
private val optionalLinkProbe: Pair<java.lang.reflect.Method, java.lang.reflect.Method>? = try {
    File::class.java.getMethod("toPath") to Class.forName("java.nio.file.Files")
        .getMethod("isSymbolicLink", Class.forName("java.nio.file.Path"))
} catch (_: ReflectiveOperationException) {
    null
}

private fun optionalSymbolicLink(file: File): Boolean {
    val probe = optionalLinkProbe ?: return false
    return try {
        probe.second.invoke(null, probe.first.invoke(file)) as Boolean
    } catch (_: Exception) {
        reject(ConfigError.PATH)
    }
}
