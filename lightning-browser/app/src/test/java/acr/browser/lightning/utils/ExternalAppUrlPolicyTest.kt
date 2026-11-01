package acr.browser.lightning.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalAppUrlPolicyTest {

    @Test
    fun `root URLs stay regardless of query fragment or casing`() {
        listOf(
            "https://github.com",
            "https://github.com/",
            "https://github.com?tab=repositories",
            "https://github.com/#readme",
            "https://github.com/?q=kotlin#results",
            "HTTP://GITHUB.COM/",
            "HtTpS://WwW.GitHub.Com:443/",
            "https://github.com///",
            "https://github.com/%2F",
        ).forEach { url ->
            assertTrue(url, ExternalAppUrlPolicy.shouldStayInBrowser(url))
        }
    }

    @Test
    fun `every web only entry and its descendants stay`() {
        listOf(
            "search", "trending", "explore", "marketplace", "settings",
            "login", "logout", "signup", "join",
        ).forEach { entry ->
            listOf(
                "https://github.com/$entry",
                "https://www.github.com/$entry/",
                "http://github.com/$entry/details?q=kotlin#section",
            ).forEach { url ->
                assertTrue(url, ExternalAppUrlPolicy.shouldStayInBrowser(url))
            }
        }
    }

    @Test
    fun `matching uses decoded path with case insensitive nonempty segments`() {
        listOf(
            "https://github.com/SeArCh?q=kotlin",
            "https://github.com/%73earch",
            "https://github.com/%53%45%54%54%49%4E%47%53/profile",
            "https://github.com/%2FTrEnDiNg%2Fdevelopers",
            "https://github.com///EXPLORE//collections",
        ).forEach { url ->
            assertTrue(url, ExternalAppUrlPolicy.shouldStayInBrowser(url))
        }
    }

    @Test
    fun `profiles repositories and entry name prefixes are left alone`() {
        listOf(
            "https://github.com/JetBrains",
            "https://github.com/JetBrains/kotlin",
            "https://www.github.com/owner/repository/issues/1",
            "https://github.com/owner/search",
            "https://github.com/searchlight",
            "https://github.com/settings-backup",
            "https://github.com/search%20",
            "https://github.com/search+",
            "https://github.com/%2573earch",
            "https://github.com/owner?next=/search#settings",
        ).forEach { url ->
            assertFalse(url, ExternalAppUrlPolicy.shouldStayInBrowser(url))
        }
    }

    @Test
    fun `only exact GitHub web hosts and HTTP schemes qualify`() {
        listOf(
            "https://github.com.evil/search",
            "https://www.github.com.evil/",
            "https://evilgithub.com/",
            "https://api.github.com/search",
            "https://github.com@evil.example/search",
            "https://example.com/?next=https://github.com/",
            "https://github.com./search",
            "ftp://github.com/search",
            "github://github.com/",
            "javascript:github.com/search",
            "//github.com/search",
            "/search",
        ).forEach { url ->
            assertFalse(url, ExternalAppUrlPolicy.shouldStayInBrowser(url))
        }
    }

    @Test
    fun `malformed inputs return false without throwing`() {
        listOf(
            "",
            "not a URL",
            "https://",
            "https:///search",
            "https://github.com/has space",
            "https://github.com/%",
            "https://github.com/%GG",
            "https://github.com:invalid/search",
            "https://[github.com]/search",
            "https://github.com/\u0000",
        ).forEach { url ->
            assertFalse(url, ExternalAppUrlPolicy.shouldStayInBrowser(url))
        }
    }
}
