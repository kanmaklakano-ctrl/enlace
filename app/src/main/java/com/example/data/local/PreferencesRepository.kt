package com.example.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
        val SOURCES_SCHEMA_VERSION = intPreferencesKey("sources_schema_version")
        val GITHUB_UPDATE_REPO = stringPreferencesKey("github_update_repo")
    }

    companion object {
        // Bump whenever new live Canadian auction sources are added so upgraded installs auto-enable them.
        private const val CURRENT_SOURCES_SCHEMA = 2
        private val NEW_V2_SOURCES = setOf(
            AuctionSourceId.MAXX_AUCTIONS_ON,
            AuctionSourceId.AUCTION_403_ON,
            AuctionSourceId.ABLE_AUCTIONS_BC
        )
    }

    val selectedProvince: Flow<CanadianProvince> = context.encanDataStore.data.map { prefs ->
        CanadianProvince.fromCodeOrText(prefs[Keys.SELECTED_PROVINCE])
    }

    val enabledSources: Flow<Set<AuctionSourceId>> = context.encanDataStore.data.map { prefs ->
        val raw = prefs[Keys.ENABLED_SOURCES]
        val schema = prefs[Keys.SOURCES_SCHEMA_VERSION] ?: 1
        if (raw == null) {
            AuctionSourceId.defaultEnabledSources
        } else {
            val saved = raw.mapNotNull { name -> AuctionSourceId.entries.firstOrNull { it.name == name } }.toMutableSet()
            if (schema < CURRENT_SOURCES_SCHEMA) {
                saved.addAll(NEW_V2_SOURCES)
            }
            saved
        }
    }

    val githubUpdateRepo: Flow<String> = context.encanDataStore.data.map { prefs ->
        prefs[Keys.GITHUB_UPDATE_REPO].orEmpty()
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
            if ((prefs[Keys.SOURCES_SCHEMA_VERSION] ?: 1) < CURRENT_SOURCES_SCHEMA) {
                NEW_V2_SOURCES.forEach { current.add(it.name) }
            }
            if (enabled) current.add(sourceId.name) else current.remove(sourceId.name)
            prefs[Keys.ENABLED_SOURCES] = current
            prefs[Keys.SOURCES_SCHEMA_VERSION] = CURRENT_SOURCES_SCHEMA
        }
    }

    suspend fun enableAllSources() {
        context.encanDataStore.edit { prefs ->
            prefs[Keys.ENABLED_SOURCES] = AuctionSourceId.entries.map { it.name }.toSet()
            prefs[Keys.SOURCES_SCHEMA_VERSION] = CURRENT_SOURCES_SCHEMA
        }
    }

    suspend fun setGithubUpdateRepo(repoSlug: String) {
        context.encanDataStore.edit { prefs ->
            prefs[Keys.GITHUB_UPDATE_REPO] = repoSlug.trim()
        }
    }
}
