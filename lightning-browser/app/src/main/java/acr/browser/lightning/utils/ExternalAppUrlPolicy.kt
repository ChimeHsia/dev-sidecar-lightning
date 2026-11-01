package acr.browser.lightning.utils

import java.net.URI
import java.net.URISyntaxException

/** GitHub's app sends these web entries back to an external-app chooser. */
object ExternalAppUrlPolicy {

    private val webOnlyGitHubEntries = setOf(
        "search", "trending", "explore", "marketplace", "settings",
        "login", "logout", "signup", "join",
    )

    fun shouldStayInBrowser(url: String): Boolean {
        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return false
        }
        if (!uri.scheme.equals("http", ignoreCase = true) &&
            !uri.scheme.equals("https", ignoreCase = true)
        ) {
            return false
        }
        if (!uri.host.equals("github.com", ignoreCase = true) &&
            !uri.host.equals("www.github.com", ignoreCase = true)
        ) {
            return false
        }

        val firstSegment = uri.path.orEmpty().split('/').firstOrNull { it.isNotEmpty() }
        return firstSegment == null ||
            webOnlyGitHubEntries.any { it.equals(firstSegment, ignoreCase = true) }
    }
}
