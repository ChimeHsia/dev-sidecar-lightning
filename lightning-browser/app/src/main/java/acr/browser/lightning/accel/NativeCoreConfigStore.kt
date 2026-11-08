package acr.browser.lightning.accel

import com.dsha.sidecar.net.config.native.*
import com.dsha.sidecar.net.runtime.NativeRuntimeConfig
import java.io.File

/** Copies only bounded configuration documents; old Node files remain available for rollback. */
internal class NativeCoreConfigStore(private val directory: File, private val legacy: File?) {
    init {
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create native configuration directory" }
        if (legacy?.isDirectory == true) {
            val old = ConfigFileStore(legacy)
            for (name in NAMES) {
                if (!File(directory, name).exists()) {
                    old.read(name)?.let { bytes -> write(name, bytes) }
                }
            }
        }
    }

    fun documents(defaults: String): ConfigDocuments {
        val files = ConfigFileStore(directory)
        return ConfigDocuments(defaults, files.user()?.toString(Charsets.UTF_8),
            files.read("remote_config_personal.json5")?.toString(Charsets.UTF_8),
            files.read("remote_config.json5")?.toString(Charsets.UTF_8))
    }

    fun repository() = NativeConfigRepository(
        remoteFetchDefaults = RemoteFetchDefaults("https://cloudflare-dns.com/dns-query", "cf-DoH", "cloudflare-dns.com"),
        validate = { NativeRuntimeConfig.decode(it.configuration) })

    fun load(defaults: String): NativeRuntimeConfig =
        when (val result = repository().reload(documents(defaults))) {
            is ConfigUpdate.Applied -> NativeRuntimeConfig.decode(result.snapshot.configuration)
            is ConfigUpdate.Rejected -> throw IllegalArgumentException("Native configuration rejected: ${result.error}")
        }

    fun write(name: String, bytes: ByteArray) {
        require(name in NAMES && bytes.size <= 1_048_576)
        val destination = File(directory, name)
        val staged = File.createTempFile("config-", ".tmp", directory)
        try {
            staged.outputStream().use { it.write(bytes); it.fd.sync() }
            java.nio.file.Files.move(staged.toPath(), destination.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { staged.delete() }
    }

    companion object {
        private val NAMES = setOf("config.json", "config.json5", "remote_config_personal.json5", "remote_config.json5")
    }
}
