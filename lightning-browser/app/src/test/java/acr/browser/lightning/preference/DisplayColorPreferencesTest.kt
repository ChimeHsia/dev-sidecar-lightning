package acr.browser.lightning.preference

import acr.browser.lightning.preference.datastore.PreferenceDataStoreRule
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import app.cash.turbine.turbineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Rule
import org.junit.Test

class DisplayColorPreferencesTest {

    @get:Rule
    val dataStoreRule = PreferenceDataStoreRule()

    @Test
    fun `new preference preserves the previously enabled manifest behavior`() = runTest {
        val preferences = DisplayColorPreferences(dataStoreRule.dataStore)

        assertThat(preferences.wideColorGamutEnabled.get()).isTrue()
    }

    @Test
    fun `explicit standard gamut is respected instead of the enabled default`() = runTest {
        dataStoreRule.dataStore.edit {
            it[booleanPreferencesKey("wideColorGamut")] = false
        }

        val preferences = DisplayColorPreferences(dataStoreRule.dataStore)

        assertThat(preferences.wideColorGamutEnabled.get()).isFalse()
    }

    @Test
    fun `standard selection is stored explicitly and survives a new preference wrapper`() = runTest {
        val preferences = DisplayColorPreferences(dataStoreRule.dataStore)

        preferences.wideColorGamutEnabled.set(false)

        val restored = DisplayColorPreferences(dataStoreRule.dataStore)
        assertThat(restored.wideColorGamutEnabled.get()).isFalse()
        assertThat(dataStoreRule.dataStore.data.first()[booleanPreferencesKey("wideColorGamut")])
            .isFalse()
    }

    @Test
    fun `observers receive changes made through a separate preference wrapper`() = runTest {
        val browserPreferences = DisplayColorPreferences(dataStoreRule.dataStore)
        val settingsPreferences = DisplayColorPreferences(dataStoreRule.dataStore)

        turbineScope {
            val changes = browserPreferences.wideColorGamutEnabled.values().testIn(backgroundScope)
            assertThat(changes.awaitItem()).isTrue()

            settingsPreferences.wideColorGamutEnabled.set(false)
            assertThat(changes.awaitItem()).isFalse()

            settingsPreferences.wideColorGamutEnabled.set(true)
            assertThat(changes.awaitItem()).isTrue()
            changes.cancelAndIgnoreRemainingEvents()
        }
    }
}
