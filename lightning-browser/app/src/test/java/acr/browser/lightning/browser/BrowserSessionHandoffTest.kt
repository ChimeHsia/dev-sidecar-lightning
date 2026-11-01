package acr.browser.lightning.browser

import acr.browser.lightning.SDK_VERSION
import acr.browser.lightning.TestApplication
import acr.browser.lightning.browser.tab.TabModel
import acr.browser.lightning.browser.tab.TabSnapshot
import android.os.Bundle
import android.os.Parcel
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [SDK_VERSION])
class BrowserSessionHandoffTest {

    @Test
    fun `actual Activity recreation retains and consumes the session exactly once`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        try {
            val originalActivity = controller.get()
            val handoff = BrowserSessionHandoff.from(originalActivity)
            val session = session()
            handoff.put(session)

            controller.recreate()

            assertThat(controller.get()).isNotSameAs(originalActivity)
            val replacement = BrowserSessionHandoff.from(controller.get())
            assertThat(replacement).isSameAs(handoff)
            assertThat(replacement.take()).isSameAs(session)
            assertThat(replacement.take()).isNull()
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `separate windows never consume each others private session`() {
        val regular = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val incognito = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        try {
            val privateSession = session()
            BrowserSessionHandoff.from(incognito.get()).put(privateSession)

            assertThat(BrowserSessionHandoff.from(regular.get()).take()).isNull()
            assertThat(BrowserSessionHandoff.from(incognito.get()).take()).isSameAs(privateSession)
        } finally {
            regular.pause().stop().destroy()
            incognito.pause().stop().destroy()
        }
    }

    @Test
    fun `large histories stay out of the Activity saved instance bundle`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        try {
            val state = TabSnapshot.capture(
                Bundle().apply { putByteArray("history", ByteArray(2 * 1024 * 1024)) },
                url = "https://private.example.test/",
            )
            BrowserSessionHandoff.from(controller.get()).put(
                BrowserSession(
                    listOf(BrowserSession.Tab(17, TabModel.Type.NORMAL, state)),
                    selectedId = 17,
                    isSearchBarExpanded = true,
                )
            )
            val saved = Bundle()

            controller.saveInstanceState(saved)
            val parcel = Parcel.obtain()
            try {
                parcel.writeBundle(saved)
                assertThat(parcel.marshall().size).isLessThan(128 * 1024)
            } finally {
                parcel.recycle()
            }
            assertThat(BrowserSessionHandoff.from(controller.get()).take()?.tabs).hasSize(1)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `restored initializers retain id type query selection and lazy independent history`() = runTest {
        val session = session()
        val first = session.tabs.first()
        val second = session.tabs.last()
        val firstInitializer = first.initializer()
        val secondInitializer = second.initializer()
        val firstTab = RecordingTab()
        val secondTab = RecordingTab()

        assertThat(session.tabs.map { it.id }).containsExactly(81, 4)
        assertThat(session.selectedId).isEqualTo(81)
        assertThat(firstInitializer.id).isEqualTo(81)
        assertThat(first.type).isEqualTo(TabModel.Type.EPHEMERAL)
        assertThat(secondInitializer.id).isEqualTo(4)
        assertThat(second.type).isEqualTo(TabModel.Type.POP_UP)
        assertThat(firstTab.restores).isEmpty()
        assertThat(secondTab.restores).isEmpty()

        firstInitializer.initialize(firstTab)
        secondInitializer.initialize(secondTab)

        assertThat(firstTab.searchQuery).isEqualTo("pending first query")
        assertThat(firstTab.searchQuerySelection).isEqualTo(2 to 7)
        assertThat(secondTab.searchQuery).isEqualTo("second query")
        assertThat(secondTab.searchQuerySelection).isEqualTo(0 to 3)
        assertThat(firstTab.restores.single().getIntArray("history")).containsExactly(1, 2)
        assertThat(secondTab.restores.single().getIntArray("history")).containsExactly(8, 9)
    }

    @Test
    fun `finishing the owner clears an unconsumed private session`() {
        val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val handoff = BrowserSessionHandoff.from(controller.get())
        handoff.put(session())

        controller.get().finish()
        controller.pause().stop().destroy()

        assertThat(handoff.take()).isNull()
    }

    private fun session() = BrowserSession(
        tabs = listOf(
            BrowserSession.Tab(
                id = 81,
                type = TabModel.Type.EPHEMERAL,
                snapshot = TabSnapshot.capture(
                    Bundle().apply { putIntArray("history", intArrayOf(1, 2)) },
                    url = "https://example.test/first",
                    searchQuery = "pending first query",
                    searchQuerySelection = 2 to 7,
                ),
            ),
            BrowserSession.Tab(
                id = 4,
                type = TabModel.Type.POP_UP,
                snapshot = TabSnapshot.capture(
                    Bundle().apply { putIntArray("history", intArrayOf(8, 9)) },
                    url = "https://example.test/second",
                    searchQuery = "second query",
                    searchQuerySelection = 0 to 3,
                ),
            ),
        ),
        selectedId = 81,
        isSearchBarExpanded = true,
    )

    private class RecordingTab : TabModel by unsupportedTab() {
        override var searchQuery: String = ""
        override var searchQuerySelection: Pair<Int, Int> = 0 to 0
        val restores = mutableListOf<Bundle>()

        override suspend fun restore(bundle: Bundle) {
            restores += bundle
        }
    }

    private companion object {
        fun unsupportedTab(): TabModel = Proxy.newProxyInstance(
            TabModel::class.java.classLoader,
            arrayOf(TabModel::class.java),
        ) { _, method, _ -> error("Unexpected TabModel call: ${method.name}") } as TabModel
    }
}
