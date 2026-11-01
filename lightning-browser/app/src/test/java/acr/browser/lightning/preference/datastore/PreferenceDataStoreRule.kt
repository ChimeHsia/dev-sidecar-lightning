package acr.browser.lightning.preference.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.rules.ExternalResource
import org.junit.rules.TemporaryFolder

class PreferenceDataStoreRule : ExternalResource() {

    private val folder = TemporaryFolder()
    private val job = SupervisorJob()

    lateinit var dataStore: DataStore<Preferences>
        private set

    override fun before() {
        folder.create()
        val path = folder.root.resolve("test.preferences_pb").absolutePath.toPath()
        dataStore = PreferenceDataStoreFactory.create(
            // Android's File.renameTo cannot replace an existing file on Windows.
            // Okio keeps real serialization and atomic writes in these host tests.
            storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { path },
            scope = CoroutineScope(Dispatchers.IO + job),
        )
    }

    override fun after() {
        runBlocking { job.cancelAndJoin() }
        folder.delete()
    }
}
