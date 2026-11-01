package acr.browser.lightning.browser

import acr.browser.lightning.SDK_VERSION
import acr.browser.lightning.TestApplication
import acr.browser.lightning.browser.tab.TabModel
import acr.browser.lightning.browser.tab.TabSnapshot
import android.os.Bundle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [SDK_VERSION])
class BrowserSessionLifecycleTest {

    @Test
    fun `pause during initialization pauses partial tabs without overwriting disk history`() = runTest {
        val fixture = Fixture()
        fixture.tabs += RecordingTab(7, fixture.events)

        fixture.lifecycle.pause()

        assertThat(fixture.events).containsExactly("pause:7")
        assertThat(fixture.saves).isEmpty()
        fixture.lifecycle.initialized()
        fixture.lifecycle.pause()
        assertThat(fixture.saves).containsExactly(listOf(7))
    }

    @Test
    fun `cold start recreation waits for all restored tabs before handing off`() = runTest {
        val fixture = Fixture()
        val prepare = launch { fixture.lifecycle.prepareForRecreation(true) }
        runCurrent()
        fixture.lifecycle.pause()
        assertThat(fixture.handoff.take()).isNull()
        assertThat(fixture.saves).isEmpty()
        assertThat(prepare.isCompleted).isFalse()

        fixture.tabs += RecordingTab(41, fixture.events)
        fixture.tabs += RecordingTab(12, fixture.events, TabModel.Type.POP_UP)
        fixture.selectedId = 41
        fixture.lifecycle.initialized()
        runCurrent()

        val session = requireNotNull(fixture.handoff.take())
        assertThat(session.tabs.map { it.id }).containsExactly(41, 12)
        assertThat(session.tabs.map { it.type })
            .containsExactly(TabModel.Type.NORMAL, TabModel.Type.POP_UP)
        assertThat(session.selectedId).isEqualTo(41)
        assertThat(session.isSearchBarExpanded).isTrue()
        assertThat(prepare.isCompleted).isTrue()
    }

    @Test
    fun `recreation preserves every tab query and history independently of disk restoration`() = runTest {
        val fixture = Fixture(saveEnabled = false)
        fixture.tabs += RecordingTab(9, fixture.events, TabModel.Type.EPHEMERAL).apply {
            searchQuery = "unfinished query"
            searchQuerySelection = 3 to 8
        }
        fixture.tabs += RecordingTab(2, fixture.events)
        fixture.selectedId = 9
        fixture.lifecycle.initialized()

        fixture.lifecycle.prepareForRecreation(true)
        fixture.lifecycle.pause()
        fixture.lifecycle.destroy()
        val session = requireNotNull(fixture.handoff.take())

        assertThat(fixture.saves).isEmpty()
        assertThat(session.tabs.first().type).isEqualTo(TabModel.Type.EPHEMERAL)
        assertThat(session.tabs.first().snapshot.searchQuery).isEqualTo("unfinished query")
        assertThat(session.tabs.first().snapshot.searchQuerySelection).isEqualTo(3 to 8)
        assertThat(session.tabs.first().snapshot.newBundle().getIntArray("history"))
            .containsExactly(9, 10)
        assertThat(session.tabs.last().snapshot.searchQuery).isEqualTo("query-2")
        assertThat(fixture.events.indexOf("snapshot:2"))
            .isLessThan(fixture.events.indexOf("destroy:9"))
        assertThat(fixture.events).endsWith("destroy:9", "destroy:2")
    }

    @Test
    fun `prepared recreation still pauses views but never saves to disk again`() = runTest {
        val fixture = Fixture()
        fixture.tabs += RecordingTab(3, fixture.events)
        fixture.lifecycle.initialized()
        fixture.lifecycle.prepareForRecreation(false)
        fixture.events.clear()

        fixture.lifecycle.pause()

        assertThat(fixture.events).containsExactly("pause:3")
        assertThat(fixture.saves).isEmpty()
    }

    @Test
    fun `in flight persistence exits before publishing recreation state`() = runTest {
        val fixture = Fixture()
        fixture.tabs += RecordingTab(5, fixture.events)
        fixture.lifecycle.initialized()
        val saved = CompletableDeferred<Unit>()
        fixture.saveBarrier = saved
        val pause = launch { fixture.lifecycle.pause() }
        runCurrent()
        assertThat(fixture.events).contains("save:start")

        val prepare = launch { fixture.lifecycle.prepareForRecreation(false) }
        runCurrent()
        assertThat(fixture.handoff.take()).isNull()
        assertThat(prepare.isCompleted).isFalse()
        saved.complete(Unit)
        pause.join()
        prepare.join()

        assertThat(fixture.events.indexOf("save:done"))
            .isLessThan(fixture.events.indexOf("snapshot:5"))
        assertThat(fixture.handoff.take()).isNotNull()
    }

    @Test
    fun `cancelled visibility save does not cancel the independently awaited handoff`() = runTest {
        val fixture = Fixture()
        fixture.tabs += RecordingTab(6, fixture.events)
        fixture.lifecycle.initialized()
        fixture.saveBarrier = CompletableDeferred()
        val visibility = BrowserVisibilityController(
            scope = this,
            pause = { fixture.lifecycle.pause() },
            resume = {},
        )
        visibility.hide()
        runCurrent()
        assertThat(fixture.events).contains("save:start")
        visibility.show()
        runCurrent()

        fixture.lifecycle.prepareForRecreation(false)
        fixture.lifecycle.destroy()

        assertThat(fixture.handoff.take()?.tabs?.map { it.id }).containsExactly(6)
        assertThat(fixture.events).doesNotContain("save:done")
        assertThat(fixture.events).endsWith("destroy:6")
    }

    @Test
    fun `destroy is idempotent and continues after its caller is cancelled`() = runTest {
        val fixture = Fixture()
        val released = CompletableDeferred<Unit>()
        fixture.tabs += RecordingTab(1, fixture.events).apply { destroyBarrier = released }
        fixture.tabs += RecordingTab(2, fixture.events)
        val disposal = launch { fixture.lifecycle.destroy() }
        runCurrent()
        assertThat(fixture.events).containsExactly("destroy:start:1")
        disposal.cancel()
        runCurrent()
        released.complete(Unit)
        disposal.join()
        fixture.lifecycle.destroy()
        fixture.lifecycle.pause()

        assertThat(fixture.events)
            .containsExactly("destroy:start:1", "destroy:1", "destroy:2")
        assertThat(fixture.saves).isEmpty()
    }

    @Test
    fun `one teardown failure does not strand the remaining tabs`() = runTest {
        val fixture = Fixture()
        fixture.tabs += RecordingTab(1, fixture.events).apply { failOnDestroy = true }
        fixture.tabs += RecordingTab(2, fixture.events)

        val failure = runCatching { fixture.lifecycle.destroy() }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IllegalStateException::class.java)
        assertThat(fixture.events).containsExactly("destroy:1", "destroy:2")
    }

    private class Fixture(saveEnabled: Boolean = true) {
        val events = mutableListOf<String>()
        val tabs = mutableListOf<RecordingTab>()
        val saves = mutableListOf<List<Int>>()
        val handoff = BrowserSessionHandoff()
        var selectedId: Int? = null
        var saveBarrier: CompletableDeferred<Unit>? = null
        val lifecycle = BrowserSessionLifecycle(
            tabs = { tabs },
            selectedId = { selectedId },
            save = {
                if (saveEnabled) {
                    events += "save:start"
                    saveBarrier?.await()
                    saves += it.map(TabModel::id)
                    events += "save:done"
                }
            },
            publish = { handoff.put(it) },
        )
    }

    private class RecordingTab(
        override val id: Int,
        private val events: MutableList<String>,
        override var tabType: TabModel.Type = TabModel.Type.NORMAL,
    ) : TabModel by unsupportedTab() {
        override var searchQuery: String = "query-$id"
        override var searchQuerySelection: Pair<Int, Int> = 1 to 2
        var destroyBarrier: CompletableDeferred<Unit>? = null
        var failOnDestroy = false

        override suspend fun background(backgroundAll: Boolean) {
            check(backgroundAll)
            events += "pause:$id"
        }

        override suspend fun duplicationSnapshot(): TabSnapshot {
            events += "snapshot:$id"
            return TabSnapshot.capture(
                bundle = Bundle().apply { putIntArray("history", intArrayOf(id, id + 1)) },
                title = "Title $id",
                url = "https://example.test/$id",
                searchQuery = searchQuery,
                searchQuerySelection = searchQuerySelection,
            )
        }

        override suspend fun destroy() {
            destroyBarrier?.let {
                events += "destroy:start:$id"
                it.await()
            }
            events += "destroy:$id"
            check(!failOnDestroy)
        }
    }

    private companion object {
        fun unsupportedTab(): TabModel = Proxy.newProxyInstance(
            TabModel::class.java.classLoader,
            arrayOf(TabModel::class.java),
        ) { _, method, _ -> error("Unexpected TabModel call: ${method.name}") } as TabModel
    }
}
