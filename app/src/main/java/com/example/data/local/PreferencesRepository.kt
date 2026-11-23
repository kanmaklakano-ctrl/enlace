package com.example.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.AuctionSourceId
import com.example.model.CanadianProvince
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.encanDataStore by preferencesDataStore(name = "encanlive_prefs")

class PreferencesRepository(private val context: Context) {
    private object Keys {
        val SELECTED_PROVINCE = stringPreferencesKey("selected_province")
        val ENABLED_SOURCES = stringSetPreferencesKey("enabled_sources")
    }

    val selectedProvince: Flow<CanadianProvince> = context.encanDataStore.data.map { prefs ->
        CanadianProvince.fromCodeOrText(prefs[Keys.SELECTED_PROVINCE])
    }

    val enabledSources: Flow<Set<AuctionSourceId>> = context.encanDataStore.data.map { prefs ->
        val raw = prefs[Keys.ENABLED_SOURCES]
        if (raw == null) {
            AuctionSourceId.defaultEnabledSources
        } else {
            raw.mapNotNull { name -> AuctionSourceId.entries.firstOrNull { it.name == name } }.toSet()
        }
    }

    suspend fun setSelectedProvince(province: CanadianProvince) {
        context.encanDataStore.edit { prefs ->
            prefs[Keys.SELECTED_PROVINCE] = province.code
        }
    }

    suspend fun setSourceEnabled(sourceId: AuctionSourceId, enabled: Boolean) {
        context.encanDataStore.edit { prefs ->
            val current = prefs[Keys.ENABLED_SOURCES]?.toMutableSet()
                ?: AuctionSourceId.defaultEnabledSources.map { it.name }.toMutableSet()
            if (enabled) current.add(sourceId.name) else current.remove(sourceId.name)
            prefs[Keys.ENABLED_SOURCES] = current
        }
    }

    suspend fun enableAllSources() {
        context.encanDataStore.edit { prefs ->
            prefs[Keys.ENABLED_SOURCES] = AuctionSourceId.entries.map { it.name }.toSet()
        }
    }
}
