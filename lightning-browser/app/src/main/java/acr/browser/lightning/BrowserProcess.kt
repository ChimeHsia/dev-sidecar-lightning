package acr.browser.lightning

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.FileInputStream
import java.io.InputStream

internal enum class BrowserProcess {
    MAIN,
    INCOGNITO,
}

internal object BrowserProcessIdentity {
    fun current(context: Context): BrowserProcess {
        val name = resolveName(
            sdk = Build.VERSION.SDK_INT,
            platform = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Application.getProcessName() else null
            },
            legacy = { legacyName(context) },
        )
        return classify(context.packageName, name)
            ?: throw IllegalStateException("Cannot identify browser process: ${name ?: "unavailable"}")
    }

    internal fun resolveName(sdk: Int, platform: () -> String?, legacy: () -> String?): String? {
        val modern = if (sdk >= Build.VERSION_CODES.P) runCatching(platform).getOrNull() else null
        return modern?.takeIf { it.isNotBlank() }
            ?: runCatching(legacy).getOrNull()?.takeIf { it.isNotBlank() }
    }

    internal fun classify(packageName: String, processName: String?): BrowserProcess? = when (processName) {
        packageName -> BrowserProcess.MAIN
        "$packageName:incognito" -> BrowserProcess.INCOGNITO
        else -> null
    }

    internal fun legacyName(context: Context): String? {
        val fromManager = runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            manager?.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.processName
        }.getOrNull()
        if (!fromManager.isNullOrBlank()) return fromManager
        return runCatching {
            FileInputStream("/proc/self/cmdline").use(::readCmdline)
        }.getOrNull()
    }

    internal fun readCmdline(input: InputStream): String? {
        val bytes = ByteArray(256)
        var count = 0
        while (count < bytes.size) {
            val next = input.read()
            if (next <= 0) break
            bytes[count++] = next.toByte()
        }
        return if (count == bytes.size) null else String(bytes, 0, count, Charsets.UTF_8)
    }
}
