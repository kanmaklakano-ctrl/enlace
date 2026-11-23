package com.example.data.repository

import com.example.data.local.AuctionDao
import com.example.data.local.CachedAuctionEntity
import com.example.data.local.FavoriteAuctionEntity
import com.example.data.local.PreferencesRepository
import com.example.data.local.SavedSearchEntity
import com.example.data.network.AuctionConnector
import com.example.data.network.CanadianConnectorsRegistry
import com.example.data.network.ConnectorResult
import com.example.model.AuctionCategory
import com.example.model.AuctionLot
import com.example.model.AuctionSourceId
import com.example.model.CanadianProvince
import com.example.model.ConnectorState
import com.example.model.FavoriteLotItem
import com.example.model.SavedSearch
import com.example.model.SourceFetchStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.supervisorScope

data class AggregatedSearchResult(
    val liveLots: List<AuctionLot>,
    val sourceStatuses: List<SourceFetchStatus>,
    val fetchedAtMillis: Long
)

class AuctionRepository(
    private val dao: AuctionDao,
    private val preferences: PreferencesRepository,
    private val connectors: List<AuctionConnector> = CanadianConnectorsRegistry.buildAllConnectors()
) {
    val cachedAuctions: Flow<List<AuctionLot>> =
        dao.observeCachedAuctions().map { list -> list.map { it.toDomain() } }

    val favorites: Flow<List<FavoriteLotItem>> =
        dao.observeFavorites().map { list -> list.map { it.toDomain() } }

    val favoriteIds: Flow<Set<String>> =
        dao.observeFavoriteIds().map { it.toSet() }

    val savedSearches: Flow<List<SavedSearch>> =
        dao.observeSavedSearches().map { list -> list.map { it.toDomain() } }

    val selectedProvince: Flow<CanadianProvince> = preferences.selectedProvince
    val enabledSources: Flow<Set<AuctionSourceId>> = preferences.enabledSources

    fun directSearchUrlFor(
        sourceId: AuctionSourceId,
        query: String,
        province: CanadianProvince
    ): String {
        val connector = connectors.firstOrNull { it.sourceId == sourceId }
        return connector?.buildDirectSearchUrl(query, province) ?: "https://${sourceId.domain}"
    }

    suspend fun searchLiveAcrossCanada(
        query: String,
        province: CanadianProvince,
        category: AuctionCategory,
        enabledSources: Set<AuctionSourceId>,
        onSourceUpdate: (SourceFetchStatus) -> Unit
    ): AggregatedSearchResult = supervisorScope {
        val activeConnectors = connectors.filter { it.sourceId in enabledSources }
        val deferreds = activeConnectors.map { connector ->
            async {
                onSourceUpdate(
                    SourceFetchStatus(
                        sourceId = connector.sourceId,
                        state = ConnectorState.LOADING,
                        statusDetail = "Interrogation en direct…",
                        directSearchUrl = connector.buildDirectSearchUrl(query, province)
                    )
                )
                val result: ConnectorResult = runCatching {
                    connector.search(query, province, category)
                }.getOrElse { err ->
                    ConnectorResult(
                        sourceId = connector.sourceId,
                        state = ConnectorState.ERROR,
                        lots = emptyList(),
                        latencyMs = 0L,
                        statusDetail = err.localizedMessage?.take(60) ?: "Connexion impossible",
                        directSearchUrl = connector.buildDirectSearchUrl(query, province)
                    )
                }
                val status = SourceFetchStatus(
                    sourceId = result.sourceId,
                    state = result.state,
                    lotCount = result.lots.size,
                    latencyMs = result.latencyMs,
                    statusDetail = result.statusDetail,
                    directSearchUrl = result.directSearchUrl
                )
                onSourceUpdate(status)
                result to status
            }
        }

        val completed = deferreds.awaitAll()
        val now = System.currentTimeMillis()
        val mergedLots = completed
            .flatMap { it.first.lots }
            .distinctBy { it.auctionUrl }

        if (mergedLots.isNotEmpty()) {
            dao.upsertCachedAuctions(mergedLots.map { CachedAuctionEntity.fromDomain(it) })
            // Keep cache fresh (purge entries older than 7 days)
            dao.purgeOldCache(now - 7L * 24L * 60L * 60L * 1000L)
        }

        AggregatedSearchResult(
            liveLots = mergedLots,
            sourceStatuses = completed.map { it.second },
            fetchedAtMillis = now
        )
    }

    suspend fun setSelectedProvince(province: CanadianProvince) {
        preferences.setSelectedProvince(province)
    }

    suspend fun setSourceEnabled(sourceId: AuctionSourceId, enabled: Boolean) {
        preferences.setSourceEnabled(sourceId, enabled)
    }

    suspend fun enableAllSources() {
        preferences.enableAllSources()
    }

    suspend fun toggleFavorite(lot: AuctionLot, isCurrentlyFavorite: Boolean) {
        if (isCurrentlyFavorite) {
            dao.deleteFavorite(lot.id)
        } else {
            dao.upsertFavorite(FavoriteAuctionEntity.fromDomain(lot))
        }
    }

    suspend fun updateFavoriteNote(lotId: String, maxBid: Double?, note: String) {
        dao.updateFavoriteNotes(lotId, maxBid, note)
    }

    suspend fun saveSearchPreset(query: String, province: CanadianProvince, category: AuctionCategory) {
        val label = buildString {
            if (query.isNotBlank()) append(query.trim()) else append(category.displayNameFr)
            append(" • ")
            append(province.displayNameFr)
        }
        dao.insertSavedSearch(
            SavedSearchEntity(
                label = label,
                query = query.trim(),
                provinceCode = province.code,
                categoryName = category.name,
                createdAtMillis = System.currentTimeMillis()
            )
        )
    }

    suspend fun deleteSavedSearch(id: Long) {
        dao.deleteSavedSearch(id)
    }
}
