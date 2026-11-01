package com.dsha.sidecar.net.config.native

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.URLClassLoader

class LegacyFileCompatibilityTest {
    @Test fun fileReadAndRepositoryReloadWorkWithAllNioFileClassesUnavailable() {
        val root = File.createTempFile("native-config-legacy-", ".tmp")
        check(root.delete() && root.mkdir())
        try {
            File(root, "config.json").writeText("{server:{port:31181}}", Charsets.UTF_8)
            val jars = arrayOf(
                NativeConfigRepository::class.java.protectionDomain.codeSource.location,
                kotlin.Unit::class.java.protectionDomain.codeSource.location,
            )
            val hiddenNio = mutableSetOf<String>()
            val loader = object : URLClassLoader(jars, ClassLoader.getPlatformClassLoader()) {
                override fun loadClass(name: String, resolve: Boolean): Class<*> {
                    if (name.startsWith("java.nio.file.")) {
                        hiddenNio += name
                        throw ClassNotFoundException("API26 file API deliberately hidden")
                    }
                    return super.loadClass(name, resolve)
                }
            }
            loader.use {
                val limitsType = loader.loadClass(ConfigLimits::class.java.name)
                val limits = limitsType.getConstructor().newInstance()
                val filesType = loader.loadClass(ConfigFileStore::class.java.name)
                val files = filesType.getConstructor(File::class.java, limitsType).newInstance(root, limits)
                val bytes = filesType.getMethod("user").invoke(files) as ByteArray
                assertEquals("{server:{port:31181}}", bytes.toString(Charsets.UTF_8))
                assertTrue("The optional link detector was actually unavailable", hiddenNio.isNotEmpty())
                // API signatures contain File and cannot link java.nio.file.Path on class loading.
                val repositoryType = loader.loadClass(NativeConfigRepository::class.java.name)
                val repository = repositoryType.getConstructor().newInstance()
                val updated = repositoryType.getMethod("reloadFiles", String::class.java, File::class.java)
                    .invoke(repository, "{}", root)
                assertTrue(updated.javaClass.name.endsWith("ConfigUpdate\$Applied"))
                assertFalse(repositoryType.methods.any { method ->
                    method.parameterTypes.any { type -> type.name.startsWith("java.nio.file.") }
                })
            }
        } finally {
            root.listFiles()?.forEach { it.delete() }
            root.delete()
        }
    }
}
