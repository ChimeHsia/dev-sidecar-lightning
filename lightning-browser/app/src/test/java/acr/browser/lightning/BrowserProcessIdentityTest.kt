package acr.browser.lightning

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowApplication

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [SDK_VERSION])
class BrowserProcessIdentityTest {
    @Test
    fun `only exact main and incognito identities are accepted`() {
        val packageName = "top.panming.dsbrowser"
        assertEquals(BrowserProcess.MAIN, BrowserProcessIdentity.classify(packageName, packageName))
        assertEquals(BrowserProcess.INCOGNITO,
            BrowserProcessIdentity.classify(packageName, "$packageName:incognito"))
        for (name in listOf(null, "", "other.app", "$packageName:other", "$packageName:incognito2")) {
            assertNull(BrowserProcessIdentity.classify(packageName, name))
        }
    }

    @Test
    fun `pre P never calls the unavailable platform method`() {
        for (sdk in 24..27) {
            var platformCalls = 0
            assertEquals("browser:incognito", BrowserProcessIdentity.resolveName(
                sdk, { platformCalls++; "unexpected" }, { "browser:incognito" },
            ))
            assertEquals(0, platformCalls)
        }
    }

    @Test
    fun `modern process name wins and failures fall back`() {
        assertEquals("browser", BrowserProcessIdentity.resolveName(
            28, { "browser" }, { fail("unnecessary fallback"); null },
        ))
        for (name in listOf(null, "", "  ")) {
            assertEquals("browser:incognito", BrowserProcessIdentity.resolveName(
                28, { name }, { "browser:incognito" },
            ))
        }
        assertEquals("browser", BrowserProcessIdentity.resolveName(28, { error("unavailable") }, { "browser" }))
        assertNull(BrowserProcessIdentity.resolveName(27, { "unused" }, { error("unavailable") }))
    }

    @Test
    fun `legacy activity manager selects only current pid`() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(manager).setProcesses(listOf(
            ActivityManager.RunningAppProcessInfo("other", Process.myPid() + 1, emptyArray()),
            ActivityManager.RunningAppProcessInfo("browser:incognito", Process.myPid(), emptyArray()),
        ))
        assertEquals("browser:incognito", BrowserProcessIdentity.legacyName(context))
    }

    @Test
    fun `cmdline reads only the bounded first argument`() {
        assertEquals("browser:incognito", BrowserProcessIdentity.readCmdline(
            ByteArrayInputStream("browser:incognito\u0000other-argument".toByteArray()),
        ))
        assertEquals("browser", BrowserProcessIdentity.readCmdline(ByteArrayInputStream("browser".toByteArray())))
        assertEquals("", BrowserProcessIdentity.readCmdline(ByteArrayInputStream(byteArrayOf())))
        assertNull(BrowserProcessIdentity.readCmdline(ByteArrayInputStream(ByteArray(256) { 65 })))
    }

    @Test
    fun `Android adapter rejects unrecognized process before startup`() {
        val context = RuntimeEnvironment.getApplication()
        ShadowApplication.setProcessName("${context.packageName}:unrecognized")
        assertThrows(IllegalStateException::class.java) { BrowserProcessIdentity.current(context) }
        ShadowApplication.setProcessName("${context.packageName}:incognito")
        assertEquals(BrowserProcess.INCOGNITO, BrowserProcessIdentity.current(context))
        ShadowApplication.setProcessName(context.packageName)
        assertEquals(BrowserProcess.MAIN, BrowserProcessIdentity.current(context))
    }
}
