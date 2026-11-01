package acr.browser.lightning.browser.tab

import android.os.Bundle
import android.os.Parcel

/**
 * An immutable, application-level navigation snapshot, not a copy of a live page runtime.
 *
 * Only encoded state is retained. A caller can never mutate another tab's nested Bundle values,
 * arrays or Parcelables through a state returned by [newBundle].
 */
class TabSnapshot private constructor(
    private val encodedState: ByteArray,
    private val stateClassLoader: ClassLoader?,
    val title: String,
    val url: String,
    val searchQuery: String,
    val searchQuerySelection: Pair<Int, Int>,
    val hasSavedState: Boolean,
) {

    internal fun shareStorage(pool: TabSnapshotPool): TabSnapshot {
        val shared = pool.intern(encodedState)
        return if (shared === encodedState) {
            this
        } else {
            TabSnapshot(
                encodedState = shared,
                stateClassLoader = stateClassLoader,
                title = title,
                url = url,
                searchQuery = searchQuery,
                searchQuerySelection = searchQuerySelection,
                hasSavedState = hasSavedState,
            )
        }
    }

    internal fun sharesStorageWith(other: TabSnapshot): Boolean =
        encodedState === other.encodedState

    /**
     * Reuse the encoded history when only independently owned tab metadata has changed.
     */
    fun withTabState(
        title: String = this.title,
        url: String = this.url,
        searchQuery: String = this.searchQuery,
        searchQuerySelection: Pair<Int, Int> = this.searchQuerySelection,
    ): TabSnapshot = if (
        title == this.title && url == this.url && searchQuery == this.searchQuery &&
        searchQuerySelection == this.searchQuerySelection
    ) {
        this
    } else {
        TabSnapshot(
            encodedState = encodedState,
            stateClassLoader = stateClassLoader,
            title = title,
            url = url,
            searchQuery = searchQuery,
            searchQuerySelection = searchQuerySelection,
            hasSavedState = hasSavedState,
        )
    }

    fun newBundle(): Bundle {
        val parcel = Parcel.obtain()
        return try {
            parcel.unmarshall(encodedState, 0, encodedState.size)
            parcel.setDataPosition(0)
            requireNotNull(parcel.readBundle(stateClassLoader)).apply {
                putBundle(METADATA_KEY, Bundle().apply {
                    putString(TITLE_KEY, title)
                    putString(URL_KEY, url)
                    putString(QUERY_KEY, searchQuery)
                    putInt(SELECTION_START_KEY, searchQuerySelection.first)
                    putInt(SELECTION_END_KEY, searchQuerySelection.second)
                })
            }
        } finally {
            parcel.recycle()
        }
    }

    internal suspend fun initialize(tab: TabModel) {
        tab.searchQuery = searchQuery
        tab.searchQuerySelection = searchQuerySelection
        if (hasSavedState) {
            // Keep fallback metadata available to TabAdapter if WebView rejects saved history.
            tab.restore(newBundle())
        } else {
            // No synthetic request replay: without history, only navigate to the saved URL.
            tab.loadUrl(url.ifBlank { "about:blank" })
        }
    }

    companion object {
        private const val METADATA_KEY = "acr.browser.lightning.TAB_SNAPSHOT"
        private const val TITLE_KEY = "title"
        private const val URL_KEY = "url"
        private const val QUERY_KEY = "query"
        private const val SELECTION_START_KEY = "selectionStart"
        private const val SELECTION_END_KEY = "selectionEnd"
        private const val LEGACY_URL_KEY = "URL_KEY"

        /**
         * Read fallback navigation without capturing or encoding the mutable state again.
         */
        fun savedUrl(bundle: Bundle): String? =
            bundle.getBundle(METADATA_KEY)?.getString(URL_KEY) ?: bundle.getString(LEGACY_URL_KEY)

        fun capture(
            bundle: Bundle,
            title: String? = null,
            url: String? = null,
            searchQuery: String? = null,
            searchQuerySelection: Pair<Int, Int>? = null,
            statePool: TabSnapshotPool? = null,
        ): TabSnapshot {
            val metadata = bundle.getBundle(METADATA_KEY)
            // This shallow top-level copy is used only for synchronous serialization. No mutable
            // value from it escapes, and removing our metadata must not change the caller's Bundle.
            val state = Bundle(bundle).apply { remove(METADATA_KEY) }
            val parcel = Parcel.obtain()
            val encodedState = try {
                parcel.writeBundle(state)
                parcel.marshall()
            } finally {
                parcel.recycle()
            }
            return TabSnapshot(
                encodedState = statePool?.intern(encodedState) ?: encodedState,
                stateClassLoader = bundle.classLoader ?: TabSnapshot::class.java.classLoader,
                title = title ?: metadata?.getString(TITLE_KEY).orEmpty(),
                url = url ?: metadata?.getString(URL_KEY)
                    ?: bundle.getString(LEGACY_URL_KEY).orEmpty(),
                searchQuery = searchQuery ?: metadata?.getString(QUERY_KEY).orEmpty(),
                searchQuerySelection = searchQuerySelection ?: (
                    (metadata?.getInt(SELECTION_START_KEY) ?: 0) to
                        (metadata?.getInt(SELECTION_END_KEY) ?: 0)
                    ),
                hasSavedState = state.keySet().any { it != LEGACY_URL_KEY },
            )
        }
    }
}
