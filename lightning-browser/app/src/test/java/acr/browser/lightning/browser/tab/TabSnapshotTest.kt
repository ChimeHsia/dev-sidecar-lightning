package acr.browser.lightning.browser.tab

import acr.browser.lightning.SDK_VERSION
import acr.browser.lightning.TestApplication
import android.graphics.Rect
import android.os.Bundle
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [SDK_VERSION])
class TabSnapshotTest {

    @Test
    fun `capture detaches nested bundles arrays lists and parcelables from source`() {
        val bytes = byteArrayOf(1, 2)
        val numbers = intArrayOf(3, 4)
        val strings = arrayListOf("original")
        val bounds = Rect(1, 2, 3, 4)
        val nested = Bundle().apply {
            putByteArray("bytes", bytes)
            putIntArray("numbers", numbers)
            putStringArrayList("strings", strings)
            putParcelable("bounds", bounds)
        }
        val source = Bundle().apply { putBundle("nested", nested) }
        val snapshot = TabSnapshot.capture(source)

        bytes[0] = 99
        numbers[0] = 99
        strings[0] = "changed"
        bounds.left = 99
        nested.putString("extra", "changed")
        source.clear()

        val restored = requireNotNull(snapshot.newBundle().getBundle("nested"))
        assertThat(restored.getByteArray("bytes")).containsExactly(1.toByte(), 2.toByte())
        assertThat(restored.getIntArray("numbers")).containsExactly(3, 4)
        assertThat(restored.getStringArrayList("strings")).containsExactly("original")
        @Suppress("DEPRECATION")
        assertThat(restored.getParcelable<Rect>("bounds")).isEqualTo(Rect(1, 2, 3, 4))
        assertThat(restored.containsKey("extra")).isFalse()
    }

    @Test
    fun `each mutable export has independent nested values`() {
        val snapshot = TabSnapshot.capture(Bundle().apply {
            putBundle("nested", Bundle().apply { putByteArray("bytes", byteArrayOf(1, 2)) })
        })
        val first = snapshot.newBundle()
        val second = snapshot.newBundle()
        requireNotNull(first.getBundle("nested")?.getByteArray("bytes"))[0] = 99
        first.clear()

        assertThat(second.getBundle("nested")?.getByteArray("bytes"))
            .containsExactly(1.toByte(), 2.toByte())
        assertThat(snapshot.newBundle().getBundle("nested")?.getByteArray("bytes"))
            .containsExactly(1.toByte(), 2.toByte())
    }

    @Test
    fun `uninitialized duplicate initializers share snapshot without loading anything`() {
        val snapshot = TabSnapshot.capture(
            Bundle(),
            title = "Saved title",
            url = "https://example.test/page",
            searchQuery = "independent query",
            searchQuerySelection = 2 to 5,
        )
        val duplicate = FreezableInitializer(snapshot)
        val another = FreezableInitializer(duplicate.snapshot)

        assertThat(duplicate.snapshot).isSameAs(another.snapshot)
        assertThat(duplicate.id).isEqualTo(-1)
        assertThat(another.id).isEqualTo(-1)
        assertThat(another.initialTitle).isEqualTo("Saved title")
        assertThat(another.initialUrl).isEqualTo("https://example.test/page")
        assertThat(another.initialPreview).isEqualTo(TabModel.Preview.None)
    }

    @Test
    fun `changing small state separates metadata without changing earlier snapshot`() {
        val original = TabSnapshot.capture(
            Bundle().apply { putByteArray("history", byteArrayOf(1)) },
            title = "Original",
            url = "https://example.test/",
            searchQuery = "old",
        )
        assertThat(original.withTabState()).isSameAs(original)

        val changed = original.withTabState(searchQuery = "new", searchQuerySelection = 1 to 2)
        assertThat(changed).isNotSameAs(original)
        assertThat(original.searchQuery).isEqualTo("old")
        assertThat(changed.searchQuery).isEqualTo("new")
        assertThat(changed.searchQuerySelection).isEqualTo(1 to 2)
        requireNotNull(changed.newBundle().getByteArray("history"))[0] = 99
        assertThat(original.newBundle().getByteArray("history")).containsExactly(1.toByte())
    }

    @Test
    fun `empty history navigates only when the duplicate is activated`() = runTest {
        val tab = RecordingTab()
        val snapshot = TabSnapshot.capture(
            Bundle(),
            url = "https://example.test/fallback",
            searchQuery = "saved query",
            searchQuerySelection = 1 to 3,
        )
        val initializer = FreezableInitializer(snapshot)
        assertThat(snapshot.hasSavedState).isFalse()
        assertThat(tab.loadedUrls).isEmpty()
        assertThat(tab.restoredBundles).isEmpty()

        initializer.initialize(tab)

        assertThat(tab.loadedUrls).containsExactly("https://example.test/fallback")
        assertThat(tab.restoredBundles).isEmpty()
        assertThat(tab.searchQuery).isEqualTo("saved query")
        assertThat(tab.searchQuerySelection).isEqualTo(1 to 3)
    }

    @Test
    fun `restore consumers cannot mutate history shared by another duplicate`() = runTest {
        val snapshot = TabSnapshot.capture(Bundle().apply {
            putBundle("history", Bundle().apply { putIntArray("entries", intArrayOf(7, 8)) })
        })
        val first = RecordingTab()
        val second = RecordingTab()
        FreezableInitializer(snapshot).initialize(first)
        requireNotNull(first.restoredBundles.single().getBundle("history")?.getIntArray("entries"))[0] = 99
        FreezableInitializer(snapshot).initialize(second)

        assertThat(second.restoredBundles.single().getBundle("history")?.getIntArray("entries"))
            .containsExactly(7, 8)
        assertThat(first.loadedUrls).isEmpty()
        assertThat(second.loadedUrls).isEmpty()
    }

    @Test
    fun `saved uninitialized duplicate preserves fallback and metadata on reopen`() = runTest {
        val initial = FreezableInitializer(TabSnapshot.capture(
            Bundle(),
            title = "Original title",
            url = "https://example.test/fallback",
            searchQuery = "query",
            searchQuerySelection = 0 to 5,
        ))
        val saved = initial.bundle
        val reopened = FreezableInitializer(TabSnapshot.capture(saved))
        saved.clear()
        val tab = RecordingTab()

        assertThat(reopened.initialTitle).isEqualTo("Original title")
        assertThat(reopened.initialUrl).isEqualTo("https://example.test/fallback")
        assertThat(reopened.snapshot.hasSavedState).isFalse()
        assertThat(tab.loadedUrls).isEmpty()
        reopened.initialize(tab)
        assertThat(tab.loadedUrls).containsExactly("https://example.test/fallback")
        assertThat(tab.searchQuerySelection).isEqualTo(0 to 5)
    }

    @Test
    fun `legacy internal page state uses its saved URL without restoring nonexistent history`() = runTest {
        val snapshot = TabSnapshot.capture(Bundle().apply {
            putString("URL_KEY", "https://appassets.androidplatform.net/start.html")
        })
        val tab = RecordingTab()
        FreezableInitializer(snapshot).initialize(tab)

        assertThat(snapshot.hasSavedState).isFalse()
        assertThat(tab.loadedUrls).containsExactly("https://appassets.androidplatform.net/start.html")
        assertThat(tab.restoredBundles).isEmpty()
    }

    @Test
    fun `missing history and URL safely opens a blank page`() = runTest {
        val tab = RecordingTab()
        BundleInitializer(Bundle()).initialize(tab)
        assertThat(tab.loadedUrls).containsExactly("about:blank")
        assertThat(tab.restoredBundles).isEmpty()
    }

    @Test
    fun `legacy constructor delegate uses wrapper snapshot fallback metadata`() = runTest {
        val bundle = Bundle()
        val originalDelegate = BundleInitializer(bundle)
        val initializer = FreezableInitializer(
            bundle = bundle,
            delegate = originalDelegate,
            initialTitle = "Wrapper title",
            initialUrl = "https://example.test/wrapper",
            id = 42,
        )
        val tab = RecordingTab()

        initializer.initialize(tab)

        assertThat(initializer.snapshot)
            .isSameAs((initializer.delegate as BundleInitializer).snapshot)
        assertThat(tab.loadedUrls).containsExactly("https://example.test/wrapper")
    }

    @Test
    fun `saved history carries fallback URL to restore consumer`() = runTest {
        val snapshot = TabSnapshot.capture(
            Bundle().apply { putByteArray("history", byteArrayOf(1)) },
            url = "https://example.test/fallback",
        )
        val tab = RecordingTab()
        FreezableInitializer(snapshot).initialize(tab)

        assertThat(TabSnapshot.savedUrl(tab.restoredBundles.single()))
            .isEqualTo("https://example.test/fallback")
    }

    @Test
    fun `repeated captures of identical large history share storage and isolate mutable values`() {
        val pool = TabSnapshotPool()
        val bytes = ByteArray(128 * 1024) { (it % 127).toByte() }
        val source = Bundle().apply {
            putBundle("history", Bundle().apply { putByteArray("bytes", bytes) })
        }
        val first = TabSnapshot.capture(source, statePool = pool)
        val second = TabSnapshot.capture(source, statePool = pool)

        assertThat(first.sharesStorageWith(second)).isTrue()
        bytes[0] = 99
        val exported = first.newBundle()
        requireNotNull(exported.getBundle("history")?.getByteArray("bytes"))[0] = 98
        assertThat(requireNotNull(second.newBundle().getBundle("history")?.getByteArray("bytes"))[0])
            .isEqualTo(0.toByte())
    }

    @Test
    fun `different metadata shares large history and capture leaves source metadata untouched`() {
        val pool = TabSnapshotPool()
        val original = TabSnapshot.capture(
            Bundle().apply { putByteArray("history", ByteArray(128 * 1024) { 7 }) },
            title = "Original title",
            url = "https://example.test/",
            searchQuery = "old",
        )
        val firstState = original.newBundle()
        val secondState = original.withTabState(
            title = "Different title",
            searchQuery = "new",
            searchQuerySelection = 1 to 2,
        ).newBundle()
        val first = TabSnapshot.capture(firstState, statePool = pool)
        val second = TabSnapshot.capture(secondState, statePool = pool)

        assertThat(first.sharesStorageWith(second)).isTrue()
        assertThat(first.searchQuery).isEqualTo("old")
        assertThat(second.searchQuery).isEqualTo("new")
        assertThat(second.title).isEqualTo("Different title")
        assertThat(second.searchQuerySelection).isEqualTo(1 to 2)
        assertThat(TabSnapshot.capture(firstState).searchQuery).isEqualTo("old")
        assertThat(TabSnapshot.capture(secondState).searchQuery).isEqualTo("new")
    }

    @Test
    fun `presenter style interning shares repeated captures without crossing session pools`() {
        val regularPool = TabSnapshotPool()
        val incognitoPool = TabSnapshotPool()
        val source = Bundle().apply { putByteArray("history", ByteArray(128 * 1024) { 7 }) }
        val first = TabSnapshot.capture(source).shareStorage(regularPool)
        val second = TabSnapshot.capture(source).shareStorage(regularPool)
        val incognito = TabSnapshot.capture(source).shareStorage(incognitoPool)

        assertThat(first.sharesStorageWith(second)).isTrue()
        assertThat(first.sharesStorageWith(incognito)).isFalse()
    }

    @Test
    fun `same size different histories never merge`() {
        val pool = TabSnapshotPool()
        val first = TabSnapshot.capture(
            Bundle().apply { putByteArray("history", ByteArray(128 * 1024) { 7 }) },
            statePool = pool,
        )
        val second = TabSnapshot.capture(
            Bundle().apply { putByteArray("history", ByteArray(128 * 1024) { 8 }) },
            statePool = pool,
        )

        assertThat(first.sharesStorageWith(second)).isFalse()
        assertThat(requireNotNull(first.newBundle().getByteArray("history"))[0]).isEqualTo(7.toByte())
        assertThat(requireNotNull(second.newBundle().getByteArray("history"))[0]).isEqualTo(8.toByte())
    }

    @Test
    fun `pool does not retain entries beyond configured bound or intern small histories`() {
        val pool = TabSnapshotPool(minimumBytes = 64 * 1024, maximumEntries = 1)
        val source = Bundle().apply { putByteArray("history", ByteArray(128 * 1024) { 7 }) }
        val first = TabSnapshot.capture(source, statePool = pool)
        val other = TabSnapshot.capture(
            Bundle().apply { putByteArray("history", ByteArray(128 * 1024) { 8 }) },
            statePool = pool,
        )
        val recaptured = TabSnapshot.capture(source, statePool = pool)
        val small = Bundle().apply { putInt("history", 7) }
        val smallFirst = TabSnapshot.capture(small, statePool = pool)
        val smallSecond = TabSnapshot.capture(small, statePool = pool)

        assertThat(first.sharesStorageWith(other)).isFalse()
        assertThat(first.sharesStorageWith(recaptured)).isFalse()
        assertThat(smallFirst.sharesStorageWith(smallSecond)).isFalse()
    }

    private class RecordingTab : TabModel by unsupportedTab() {
        override var searchQuery: String = ""
        override var searchQuerySelection: Pair<Int, Int> = 0 to 0
        val loadedUrls = mutableListOf<String>()
        val restoredBundles = mutableListOf<Bundle>()

        override suspend fun loadUrl(url: String) {
            loadedUrls += url
        }

        override suspend fun restore(bundle: Bundle) {
            restoredBundles += bundle
        }
    }

    private companion object {
        fun unsupportedTab(): TabModel = Proxy.newProxyInstance(
            TabModel::class.java.classLoader,
            arrayOf(TabModel::class.java),
        ) { _, method, _ ->
            error("Unexpected TabModel call: ${method.name}")
        } as TabModel
    }
}
