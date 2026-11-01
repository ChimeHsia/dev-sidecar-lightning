package acr.browser.lightning.preference

import acr.browser.lightning.preference.datastore.NonNullPreferenceStore
import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.core.MultiProcessDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The browser and incognito windows live in different processes.
 */
@Singleton
class DisplayColorPreferences internal constructor(dataStore: DataStore<Preferences>) {

    @Inject
    constructor(application: Application) : this(
        MultiProcessDataStoreFactory.create(
            serializer = PreferencesFileSerializer,
            produceFile = { application.preferencesDataStoreFile("display_color") },
        )
    )

    val wideColorGamutEnabled = NonNullPreferenceStore(
        key = booleanPreferencesKey("wideColorGamut"),
        dataStore = dataStore,
        defaultValue = true,
    )
}
