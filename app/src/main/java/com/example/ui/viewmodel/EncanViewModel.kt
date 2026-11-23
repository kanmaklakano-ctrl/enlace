package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.EncanDatabase
import com.example.data.local.PreferencesRepository
import com.example.data.network.BilingualQueryTranslator
import com.example.data.repository.AuctionRepository
import com.example.model.AuctionCategory
import com.example.model.AuctionLot
import com.example.model.AuctionSourceId
import com.example.model.CanadianProvince
import com.example.model.ConnectorState
import com.example.model.CostBreakdown
import com.example.model.FavoriteLotItem
import com.example.model.SavedSearch
import com.example.model.SortOrder
import com.example.model.SourceFetchStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ExploreUiState(
    val query: String = "",
    val translationHint: String? = null,
    val selectedProvince: CanadianProvince = CanadianProvince.ALL,
    val selectedCategory: AuctionCategory = AuctionCategory.ALL,
    val sortOrder: SortOrder = SortOrder.CLOSING_SOON,
    val maxPriceFilter: Double? = null,
    val selectedSourceFilter: AuctionSourceId? = null,
    val hideAccessories: Boolean = false,
    val hiddenAccessoriesCount: Int = 0,
    val isDeviceSearch: Boolean = false,
    val isRefreshing: Boolean = false,
    val hasCompletedInitialSearch: Boolean = false,
    val showingCachedFallback: Boolean = false,
    val lastUpdatedMillis: Long? = null,
    val liveLots: List<AuctionLot> = emptyList(),
    val filteredLots: List<AuctionLot> = emptyList(),
    val sourceStatuses: List<SourceFetchStatus> = AuctionSourceId.entries.map {
        SourceFetchStatus(sourceId = it)
    },
    val enabledSources: Set<AuctionSourceId> = AuctionSourceId.defaultEnabledSources,
    val favoriteIds: Set<String> = emptySet(),
    val snackbarMessage: String? = null
)

data class CalculatorUiState(
    val bidInput: String = "500",
    val buyersPremiumPercent: Double = 15.0,
    val extraFeesInput: String = "0",
    val selectedProvince: CanadianProvince = CanadianProvince.ON
) {
    val breakdown: CostBreakdown
        get() = CostBreakdown.calculate(
            bidAmount = bidInput.replace(",", ".").toDoubleOrNull() ?: 0.0,
            buyersPremiumPercent = buyersPremiumPercent,
            province = selectedProvince,
            extraFees = extraFeesInput.replace(",", ".").toDoubleOrNull() ?: 0.0
        )
}

class EncanViewModel(application: Application) : AndroidViewModel(application) {
    private val database = EncanDatabase.getInstance(application)
    private val preferences = PreferencesRepository(application)
    private val repository = AuctionRepository(database.auctionDao(), preferences)

    private val _query = MutableStateFlow("")
    private val _lastFetchedQuery = MutableStateFlow("")
    private val _selectedProvince = MutableStateFlow(CanadianProvince.ALL)
    private val _selectedCategory = MutableStateFlow(AuctionCategory.ALL)
    private val _sortOrder = MutableStateFlow(SortOrder.CLOSING_SOON)
    private val _maxPriceFilter = MutableStateFlow<Double?>(null)
    private val _selectedSourceFilter = MutableStateFlow<AuctionSourceId?>(null)
    private val _hideAccessories = MutableStateFlow(false)
    private val _isRefreshing = MutableStateFlow(false)
    private val _hasCompletedInitial = MutableStateFlow(false)
    private val _lastUpdatedMillis = MutableStateFlow<Long?>(null)
    private val _liveLots = MutableStateFlow<List<AuctionLot>>(emptyList())
    private val _sourceStatuses = MutableStateFlow<Map<AuctionSourceId, SourceFetchStatus>>(
        AuctionSourceId.entries.associateWith { SourceFetchStatus(sourceId = it) }
    )
    private val _snackbarMessage = MutableStateFlow<String?>(null)
    private val _selectedLotId = MutableStateFlow<String?>(null)

    private val _calculatorState = MutableStateFlow(CalculatorUiState())
    val calculatorState: StateFlow<CalculatorUiState> = _calculatorState.asStateFlow()

    val favorites: StateFlow<List<FavoriteLotItem>> = repository.favorites
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val savedSearches: StateFlow<List<SavedSearch>> = repository.savedSearches
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val cachedLotsFlow = repository.cachedAuctions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val favoriteIdsFlow = repository.favoriteIds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private val enabledSourcesFlow = repository.enabledSources
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AuctionSourceId.defaultEnabledSources)

    private data class FilterSnapshot(
        val query: String,
        val lastFetchedQuery: String,
        val province: CanadianProvince,
        val category: AuctionCategory,
        val sortOrder: SortOrder,
        val maxPrice: Double?,
        val sourceFilter: AuctionSourceId?,
        val hideAccessories: Boolean
    )

    private val filtersFlow = combine(
        combine(_query, _lastFetchedQuery) { q, fq -> q to fq },
        _selectedProvince,
        _selectedCategory,
        _sortOrder,
        combine(_maxPriceFilter, _selectedSourceFilter, _hideAccessories) { maxP, src, hideAcc ->
            Triple(maxP, src, hideAcc)
        }
    ) { qPair, prov, cat, sort, triple ->
        FilterSnapshot(qPair.first, qPair.second, prov, cat, sort, triple.first, triple.second, triple.third)
    }

    private data class RuntimeSnapshot(
        val isRefreshing: Boolean,
        val hasInitial: Boolean,
        val lastUpdated: Long?,
        val liveLots: List<AuctionLot>,
        val cachedLots: List<AuctionLot>,
        val statuses: Map<AuctionSourceId, SourceFetchStatus>,
        val enabledSources: Set<AuctionSourceId>,
        val favoriteIds: Set<String>,
        val snackbar: String?
    )

    private val runtimeFlow = combine(
        combine(_isRefreshing, _hasCompletedInitial, _lastUpdatedMillis) { r, i, l -> Triple(r, i, l) },
        combine(_liveLots, cachedLotsFlow, _sourceStatuses) { live, cache, st -> Triple(live, cache, st) },
        combine(enabledSourcesFlow, favoriteIdsFlow, _snackbarMessage) { en, fav, msg -> Triple(en, fav, msg) }
    ) { a, b, c ->
        RuntimeSnapshot(
            isRefreshing = a.first,
            hasInitial = a.second,
            lastUpdated = a.third,
            liveLots = b.first,
            cachedLots = b.second,
            statuses = b.third,
            enabledSources = c.first,
            favoriteIds = c.second,
            snackbar = c.third
        )
    }

    val exploreState: StateFlow<ExploreUiState> = combine(filtersFlow, runtimeFlow) { f, r ->
        val showingCache = r.liveLots.isEmpty() && r.cachedLots.isNotEmpty()
        val baseLots = if (r.liveLots.isNotEmpty()) r.liveLots else r.cachedLots
        val plan = BilingualQueryTranslator.analyzeQuery(f.query)
        val filterResult = applyFiltersAndSort(
            lots = baseLots,
            f = f,
            plan = plan,
            enabledSources = r.enabledSources,
            isServerMatchedLive = !showingCache && f.query.trim().equals(f.lastFetchedQuery.trim(), ignoreCase = true)
        )
        val orderedStatuses = AuctionSourceId.entries.map { sid ->
            r.statuses[sid] ?: SourceFetchStatus(
                sourceId = sid,
                directSearchUrl = repository.directSearchUrlFor(sid, f.query, f.province)
            )
        }
        ExploreUiState(
            query = f.query,
            translationHint = BilingualQueryTranslator.translationHint(f.query),
            selectedProvince = f.province,
            selectedCategory = f.category,
            sortOrder = f.sortOrder,
            maxPriceFilter = f.maxPrice,
            selectedSourceFilter = f.sourceFilter,
            hideAccessories = f.hideAccessories,
            hiddenAccessoriesCount = filterResult.hiddenAccessoriesCount,
            isDeviceSearch = plan.isDeviceSearch,
            isRefreshing = r.isRefreshing,
            hasCompletedInitialSearch = r.hasInitial,
            showingCachedFallback = showingCache,
            lastUpdatedMillis = r.lastUpdated,
            liveLots = baseLots,
            filteredLots = filterResult.lots,
            sourceStatuses = orderedStatuses,
            enabledSources = r.enabledSources,
            favoriteIds = r.favoriteIds,
            snackbarMessage = r.snackbar
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ExploreUiState())

    val selectedLot: StateFlow<AuctionLot?> = combine(
        _selectedLotId,
        _liveLots,
        cachedLotsFlow,
        favorites
    ) { id, live, cached, favs ->
        if (id == null) return@combine null
        live.firstOrNull { it.id == id }
            ?: favs.firstOrNull { it.lot.id == id }?.lot
            ?: cached.firstOrNull { it.id == id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val selectedFavoriteItem: StateFlow<FavoriteLotItem?> = combine(
        _selectedLotId,
        favorites
    ) { id, favs ->
        if (id == null) null else favs.firstOrNull { it.lot.id == id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private var activeSearchJob: Job? = null

    init {
        viewModelScope.launch {
            val savedProv = repository.selectedProvince.first()
            _selectedProvince.value = savedProv
            if (savedProv != CanadianProvince.ALL) {
                _calculatorState.update { it.copy(selectedProvince = savedProv) }
            }
            refreshLiveAuctions()
        }
    }

    fun onQueryChange(newQuery: String) {
        _query.value = newQuery
    }

    fun submitSearch() {
        val plan = BilingualQueryTranslator.analyzeQuery(_query.value)
        _selectedSourceFilter.value = null
        _hideAccessories.value = plan.shouldFilterAccessoriesByDefault
        _sortOrder.value = if (plan.rawQuery.isNotBlank()) SortOrder.RELEVANCE else SortOrder.CLOSING_SOON
        refreshLiveAuctions()
    }

    fun runQuickKeyword(keyword: String) {
        _query.value = keyword
        _selectedCategory.value = AuctionCategory.ALL
        _selectedSourceFilter.value = null
        val plan = BilingualQueryTranslator.analyzeQuery(keyword)
        _hideAccessories.value = plan.shouldFilterAccessoriesByDefault
        _sortOrder.value = if (plan.rawQuery.isNotBlank()) SortOrder.RELEVANCE else SortOrder.CLOSING_SOON
        refreshLiveAuctions()
    }

    fun toggleHideAccessories() {
        _hideAccessories.update { !it }
    }

    fun selectProvince(province: CanadianProvince) {
        _selectedProvince.value = province
        _selectedSourceFilter.value = null
        if (province != CanadianProvince.ALL) {
            _calculatorState.update { it.copy(selectedProvince = province) }
        }
        viewModelScope.launch {
            repository.setSelectedProvince(province)
        }
        refreshLiveAuctions()
    }

    fun selectCategory(category: AuctionCategory) {
        _selectedCategory.value = category
        refreshLiveAuctions()
    }

    fun selectSortOrder(sortOrder: SortOrder) {
        _sortOrder.value = sortOrder
    }

    fun setMaxPriceFilter(maxPrice: Double?) {
        _maxPriceFilter.value = maxPrice
    }

    fun selectSourceFilter(sourceId: AuctionSourceId?) {
        _selectedSourceFilter.value = if (_selectedSourceFilter.value == sourceId) null else sourceId
    }

    fun toggleSourceEnabled(sourceId: AuctionSourceId, enabled: Boolean) {
        viewModelScope.launch {
            repository.setSourceEnabled(sourceId, enabled)
        }
    }

    fun enableAllSources() {
        viewModelScope.launch {
            repository.enableAllSources()
            _snackbarMessage.value = "Toutes les sources canadiennes ont été activées."
        }
    }

    fun refreshLiveAuctions() {
        activeSearchJob?.cancel()
        activeSearchJob = viewModelScope.launch {
            _isRefreshing.value = true
            val q = _query.value.trim()
            val prov = _selectedProvince.value
            val cat = _selectedCategory.value
            val enabled = repository.enabledSources.first()

            if (enabled.isEmpty()) {
                _isRefreshing.value = false
                _hasCompletedInitial.value = true
                _snackbarMessage.value = "Activez au moins une source dans l'onglet Sources."
                return@launch
            }

            _sourceStatuses.value = AuctionSourceId.entries.associateWith { sid ->
                SourceFetchStatus(
                    sourceId = sid,
                    state = if (sid in enabled) ConnectorState.LOADING else ConnectorState.IDLE,
                    statusDetail = if (sid in enabled) "Connexion en cours…" else "Désactivée",
                    directSearchUrl = repository.directSearchUrlFor(sid, q, prov)
                )
            }

            val aggregated = repository.searchLiveAcrossCanada(
                query = q,
                province = prov,
                category = cat,
                enabledSources = enabled,
                onSourceUpdate = { status ->
                    _sourceStatuses.update { map -> map + (status.sourceId to status) }
                }
            )

            _lastFetchedQuery.value = q
            _liveLots.value = aggregated.liveLots
            _lastUpdatedMillis.value = aggregated.fetchedAtMillis
            _hasCompletedInitial.value = true
            _isRefreshing.value = false
        }
    }

    fun openLotDetail(lotId: String) {
        _selectedLotId.value = lotId
    }

    fun toggleFavorite(lot: AuctionLot) {
        viewModelScope.launch {
            val isFav = lot.id in favoriteIdsFlow.value
            repository.toggleFavorite(lot, isFav)
            _snackbarMessage.value = if (isFav) "Retiré des favoris" else "Ajouté aux favoris"
        }
    }

    fun updateFavoriteNotes(lotId: String, maxBidText: String, note: String) {
        viewModelScope.launch {
            val parsedMax = maxBidText.replace(",", ".").toDoubleOrNull()
            repository.updateFavoriteNote(lotId, parsedMax, note.trim())
            _snackbarMessage.value = "Note et mise maximale enregistrées"
        }
    }

    fun saveCurrentSearch() {
        viewModelScope.launch {
            repository.saveSearchPreset(
                query = _query.value,
                province = _selectedProvince.value,
                category = _selectedCategory.value
            )
            _snackbarMessage.value = "Recherche sauvegardée dans vos favoris"
        }
    }

    fun applySavedSearch(saved: SavedSearch) {
        _query.value = saved.query
        _selectedProvince.value = saved.province
        _selectedCategory.value = saved.category
        _selectedSourceFilter.value = null
        val plan = BilingualQueryTranslator.analyzeQuery(saved.query)
        _hideAccessories.value = plan.shouldFilterAccessoriesByDefault
        _sortOrder.value = if (plan.rawQuery.isNotBlank()) SortOrder.RELEVANCE else SortOrder.CLOSING_SOON
        refreshLiveAuctions()
    }

    fun deleteSavedSearch(id: Long) {
        viewModelScope.launch {
            repository.deleteSavedSearch(id)
        }
    }

    fun clearSnackbar() {
        _snackbarMessage.value = null
    }

    fun updateCalculatorBid(input: String) {
        if (input.length <= 12) {
            _calculatorState.update { it.copy(bidInput = input.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }) }
        }
    }

    fun updateCalculatorPremium(percent: Double) {
        _calculatorState.update { it.copy(buyersPremiumPercent = percent.coerceIn(0.0, 35.0)) }
    }

    fun updateCalculatorExtraFees(input: String) {
        if (input.length <= 10) {
            _calculatorState.update { it.copy(extraFeesInput = input.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }) }
        }
    }

    fun updateCalculatorProvince(province: CanadianProvince) {
        val target = if (province == CanadianProvince.ALL) CanadianProvince.ON else province
        _calculatorState.update { it.copy(selectedProvince = target) }
    }

    fun prefillCalculatorFromLot(lot: AuctionLot) {
        _calculatorState.value = CalculatorUiState(
            bidInput = lot.currentBid?.let { String.format(java.util.Locale.US, "%.0f", it) } ?: "250",
            buyersPremiumPercent = lot.buyersPremiumPercent,
            extraFeesInput = "0",
            selectedProvince = if (lot.province == CanadianProvince.ALL) CanadianProvince.ON else lot.province
        )
    }

    private data class FilteredResult(
        val lots: List<AuctionLot>,
        val hiddenAccessoriesCount: Int
    )

    private fun applyFiltersAndSort(
        lots: List<AuctionLot>,
        f: FilterSnapshot,
        plan: com.example.data.network.SmartQueryPlan,
        enabledSources: Set<AuctionSourceId>,
        isServerMatchedLive: Boolean
    ): FilteredResult {
        val expandedTerms = plan.expandedTerms

        val baseFiltered = lots.filter { lot ->
            if (lot.sourceId !in enabledSources) return@filter false
            if (f.sourceFilter != null && lot.sourceId != f.sourceFilter) return@filter false
            if (f.province != CanadianProvince.ALL &&
                lot.province != CanadianProvince.ALL &&
                lot.province != f.province
            ) {
                return@filter false
            }
            if (f.category != AuctionCategory.ALL &&
                lot.category != f.category &&
                !isServerMatchedLive
            ) {
                return@filter false
            }
            if (f.maxPrice != null && lot.currentBid != null && lot.currentBid > f.maxPrice) {
                return@filter false
            }
            if (!isServerMatchedLive && expandedTerms.isNotEmpty()) {
                val hay = BilingualQueryTranslator.normalize(
                    "${lot.title} ${lot.description} ${lot.city} ${lot.auctionHouse} ${lot.province.displayNameFr} ${lot.province.displayNameEn}"
                )
                if (expandedTerms.none { hay.contains(it) }) return@filter false
            }
            true
        }

        // Smart accessory filtering (only hides accessories if real non-accessory lots exist, so we never show 0 results unnecessarily)
        val nonAccessories = if (plan.isDeviceSearch) {
            baseFiltered.filterNot { BilingualQueryTranslator.isLikelyAccessory(it, plan) }
        } else {
            baseFiltered
        }
        val accessoryCount = (baseFiltered.size - nonAccessories.size).coerceAtLeast(0)
        val finalPool = if (f.hideAccessories && nonAccessories.isNotEmpty()) {
            nonAccessories
        } else {
            baseFiltered
        }
        val actuallyHidden = if (f.hideAccessories && nonAccessories.isNotEmpty()) accessoryCount else 0

        val sorted = when (f.sortOrder) {
            SortOrder.RELEVANCE -> finalPool.sortedWith(
                compareByDescending<AuctionLot> { BilingualQueryTranslator.scoreRelevance(it, plan) }
                    .thenByDescending { it.currentBid ?: -1.0 }
                    .thenBy { it.closingTimeMillis ?: Long.MAX_VALUE }
            )
            SortOrder.CLOSING_SOON -> finalPool.sortedWith(
                compareByDescending<AuctionLot> { !it.imageUrl.isNullOrBlank() }
                    .thenBy { it.closingTimeMillis ?: Long.MAX_VALUE }
                    .thenByDescending { it.bidCount }
            )
            SortOrder.PRICE_ASC -> finalPool.sortedBy { it.currentBid ?: Double.MAX_VALUE }
            SortOrder.PRICE_DESC -> finalPool.sortedByDescending { it.currentBid ?: -1.0 }
            SortOrder.MOST_BIDS -> finalPool.sortedByDescending { it.bidCount }
            SortOrder.RECENT -> finalPool.sortedWith(
                compareByDescending<AuctionLot> { !it.imageUrl.isNullOrBlank() }
                    .thenByDescending { it.fetchedAtMillis }
            )
        }
        return FilteredResult(lots = sorted, hiddenAccessoriesCount = actuallyHidden)
    }
}
