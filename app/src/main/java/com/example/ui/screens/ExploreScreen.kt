package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.AuctionCategory
import com.example.model.AuctionLot
import com.example.model.AuctionSourceId
import com.example.model.CanadianProvince
import com.example.model.ConnectorState
import com.example.model.SortOrder
import com.example.model.SourceFetchStatus
import com.example.ui.components.AuctionLotCard
import com.example.ui.components.BrowserLauncher
import com.example.ui.components.FormatUtils
import com.example.ui.components.ProvincePickerSheet
import com.example.ui.theme.BurnishedBrassBright
import com.example.ui.theme.LiveEmerald
import com.example.ui.theme.LiveEmeraldLight
import com.example.ui.theme.MonoFontFamily
import com.example.ui.viewmodel.ExploreUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    uiState: ExploreUiState,
    onQueryChange: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    onQuickKeyword: (String) -> Unit,
    onSelectProvince: (CanadianProvince) -> Unit,
    onSelectCategory: (AuctionCategory) -> Unit,
    onSelectSortOrder: (SortOrder) -> Unit,
    onSetMaxPrice: (Double?) -> Unit,
    onSelectSourceFilter: (AuctionSourceId?) -> Unit,
    onToggleHideAccessories: () -> Unit,
    onRefresh: () -> Unit,
    onSaveSearch: () -> Unit,
    onLotClick: (AuctionLot) -> Unit,
    onStudyLotClick: (AuctionLot) -> Unit,
    onFavoriteToggle: (AuctionLot) -> Unit,
    onOpenSourcesTab: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var showProvinceSheet by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showPriceMenu by remember { mutableStateOf(false) }

    val connectedSourcesCount = remember(uiState.sourceStatuses) {
        uiState.sourceStatuses.count { it.state == ConnectorState.CONNECTED && it.lotCount > 0 }
    }
    val readySourcesCount = remember(uiState.sourceStatuses) {
        uiState.sourceStatuses.count { it.state == ConnectorState.CONNECTED || it.state == ConnectorState.WEB_DIRECT }
    }

    PullToRefreshBox(
        isRefreshing = uiState.isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Editorial Hero Masthead with Canadian Province Selector
            item(key = "hero_header") {
                HeroMasthead(
                    selectedProvince = uiState.selectedProvince,
                    connectedCount = connectedSourcesCount,
                    readyCount = readySourcesCount,
                    totalEnabled = uiState.enabledSources.size,
                    isRefreshing = uiState.isRefreshing,
                    lastUpdatedMillis = uiState.lastUpdatedMillis,
                    onProvinceClick = { showProvinceSheet = true },
                    onRefreshClick = onRefresh
                )
            }

            // 2. Search Bar + Save Search Button
            item(key = "search_bar") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = uiState.query,
                        onValueChange = onQueryChange,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("auction_search_input"),
                        placeholder = {
                            Text(
                                text = stringResource(R.string.search_hint),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        },
                        trailingIcon = {
                            if (uiState.query.isNotBlank()) {
                                IconButton(
                                    onClick = {
                                        onQueryChange("")
                                        onSubmitSearch()
                                    },
                                    modifier = Modifier.testTag("clear_search_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.cd_clear_search)
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                keyboardController?.hide()
                                onSubmitSearch()
                            }
                        )
                    )

                    Surface(
                        onClick = {
                            keyboardController?.hide()
                            onSubmitSearch()
                        },
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(54.dp)
                            .testTag("submit_search_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = "Lancer la recherche en direct",
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }

                    Surface(
                        onClick = onSaveSearch,
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .size(54.dp)
                            .testTag("save_search_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.BookmarkAdd,
                                contentDescription = "Sauvegarder cette recherche",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            // 2b. Bilingual query indicator + quick popular searches
            item(key = "quick_keywords_strip") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (uiState.translationHint != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.65f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        ) {
                            Text(
                                text = "Recherche intelligente : « ${uiState.query.trim()} » → « ${uiState.translationHint} »",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }

                    val quickTerms = listOf("iPhone", "Samsung", "Voiture", "Outils", "MacBook", "Palette", "Tracteur", "Bijoux")
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(quickTerms, key = { it }) { term ->
                            val isSelected = uiState.query.equals(term, ignoreCase = true)
                            Surface(
                                onClick = { onQuickKeyword(term) },
                                shape = RoundedCornerShape(50),
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                                ),
                                modifier = Modifier.testTag("quick_term_$term")
                            ) {
                                Text(
                                    text = term,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 3. Quick Province Filter Strip
            item(key = "province_strip") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(CanadianProvince.quickFilterProvinces, key = { it.code }) { prov ->
                        val selected = uiState.selectedProvince == prov
                        FilterChip(
                            selected = selected,
                            onClick = { onSelectProvince(prov) },
                            label = {
                                Text(
                                    text = if (prov == CanadianProvince.ALL) "Canada (Tous)" else "${prov.code} • ${prov.displayNameFr}",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            modifier = Modifier.testTag("province_chip_${prov.code}")
                        )
                    }
                    item(key = "more_provinces") {
                        FilterChip(
                            selected = uiState.selectedProvince !in CanadianProvince.quickFilterProvinces,
                            onClick = { showProvinceSheet = true },
                            label = { Text("13 provinces…") },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.ExpandMore,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            modifier = Modifier.testTag("more_provinces_chip")
                        )
                    }
                }
            }

            // 4. Category Pills
            item(key = "category_strip") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(AuctionCategory.entries, key = { it.name }) { cat ->
                        val selected = uiState.selectedCategory == cat
                        Surface(
                            onClick = { onSelectCategory(cat) },
                            shape = RoundedCornerShape(12.dp),
                            color = if (selected) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                            border = BorderStroke(
                                1.dp,
                                if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("category_chip_${cat.name}")
                        ) {
                            Text(
                                text = cat.displayNameFr,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (selected) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }

            // 5. Live Multi-Source Telemetry Ribbon
            item(key = "live_sources_ribbon") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Sites canadiens en direct",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Gérer (${uiState.enabledSources.size})",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .testTag("manage_sources_link")
                                .clickable(onClick = onOpenSourcesTab)
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        )
                    }

                    AnimatedVisibility(visible = uiState.isRefreshing) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .height(3.dp)
                                .clip(RoundedCornerShape(50)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }

                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val activeStatuses = uiState.sourceStatuses.filter { it.sourceId in uiState.enabledSources }
                        items(activeStatuses, key = { it.sourceId.name }) { status ->
                            SourceStatusChip(
                                status = status,
                                isSelectedFilter = uiState.selectedSourceFilter == status.sourceId,
                                onClick = {
                                    if (status.lotCount > 0) {
                                        onSelectSourceFilter(status.sourceId)
                                    } else {
                                        BrowserLauncher.openAuctionUrl(context, status.directSearchUrl)
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // 6. Results Summary + Sort / Price Bar
            item(key = "results_toolbar") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${uiState.filteredLots.size} enchère${if (uiState.filteredLots.size > 1) "s" else ""} trouvée${if (uiState.filteredLots.size > 1) "s" else ""}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (uiState.showingCachedFallback) {
                            Text(
                                text = "Affichage du cache local récent",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        } else if (uiState.selectedSourceFilter != null) {
                            Text(
                                text = "Filtre : ${uiState.selectedSourceFilter.displayName} (appuyez pour tout voir)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable { onSelectSourceFilter(null) }
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box {
                            Surface(
                                onClick = { showPriceMenu = true },
                                shape = RoundedCornerShape(10.dp),
                                color = if (uiState.maxPriceFilter != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.testTag("max_price_filter_button")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.FilterList,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = uiState.maxPriceFilter?.let { "≤ ${FormatUtils.formatCad(it)}" } ?: "Prix",
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                            }
                            DropdownMenu(expanded = showPriceMenu, onDismissRequest = { showPriceMenu = false }) {
                                listOf(null to "Tous les prix", 250.0 to "≤ 250 $", 1000.0 to "≤ 1 000 $", 5000.0 to "≤ 5 000 $", 20000.0 to "≤ 20 000 $").forEach { (limit, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            onSetMaxPrice(limit)
                                            showPriceMenu = false
                                        }
                                    )
                                }
                            }
                        }

                        Box {
                            Surface(
                                onClick = { showSortMenu = true },
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.testTag("sort_order_button")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.SwapVert,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = uiState.sortOrder.displayNameFr,
                                        style = MaterialTheme.typography.labelLarge,
                                        maxLines = 1
                                    )
                                }
                            }
                            DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                                SortOrder.entries.forEach { order ->
                                    DropdownMenuItem(
                                        text = { Text(order.displayNameFr) },
                                        onClick = {
                                            onSelectSortOrder(order)
                                            showSortMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 6b. Smart Accessory Filter Pill (auto-shown for iPhone, Samsung, MacBook, iPad, etc.)
            if (uiState.isDeviceSearch || uiState.hiddenAccessoriesCount > 0 || uiState.hideAccessories) {
                item(key = "smart_accessory_filter") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = uiState.hideAccessories,
                            onClick = onToggleHideAccessories,
                            label = {
                                val labelText = when {
                                    uiState.hideAccessories && uiState.hiddenAccessoriesCount > 0 ->
                                        "Sans accessoires (${uiState.hiddenAccessoriesCount} coques/câbles masqués)"
                                    uiState.hideAccessories ->
                                        "Sans accessoires (appareils prioritaires)"
                                    else ->
                                        "Masquer coques & accessoires"
                                }
                                Text(
                                    text = labelText,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onTertiaryContainer
                            ),
                            modifier = Modifier.testTag("hide_accessories_chip")
                        )
                    }
                }
            }

            // 7. Live Auction Cards OR Smart Empty / Direct Portals Panel
            if (uiState.filteredLots.isNotEmpty()) {
                items(uiState.filteredLots, key = { it.id }) { lot ->
                    AuctionLotCard(
                        lot = lot,
                        isFavorite = lot.id in uiState.favoriteIds,
                        onCardClick = { onLotClick(lot) },
                        onStudyClick = { onStudyLotClick(lot) },
                        onFavoriteClick = { onFavoriteToggle(lot) },
                        onOpenUrlClick = { BrowserLauncher.openAuctionUrl(context, lot.auctionUrl) },
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }

                // Bottom Direct Portals Quick Strip for sites behind Cloudflare/JS walls
                val directOnly = uiState.sourceStatuses.filter {
                    it.sourceId in uiState.enabledSources && it.state == ConnectorState.WEB_DIRECT
                }
                if (directOnly.isNotEmpty()) {
                    item(key = "direct_portals_footer") {
                        DirectPortalsBanner(
                            province = uiState.selectedProvince,
                            query = uiState.query,
                            statuses = directOnly,
                            onOpenPortal = { url -> BrowserLauncher.openAuctionUrl(context, url) }
                        )
                    }
                }
            } else if (!uiState.isRefreshing && uiState.hasCompletedInitialSearch) {
                item(key = "empty_state_portals") {
                    EmptyResultsWithDirectPortals(
                        province = uiState.selectedProvince,
                        query = uiState.query,
                        statuses = uiState.sourceStatuses.filter { it.sourceId in uiState.enabledSources },
                        onRetry = onRefresh,
                        onClearFilters = {
                            onQueryChange("")
                            onSelectCategory(AuctionCategory.ALL)
                            onSetMaxPrice(null)
                            onSelectSourceFilter(null)
                            onSelectProvince(CanadianProvince.ALL)
                        },
                        onOpenPortal = { url -> BrowserLauncher.openAuctionUrl(context, url) }
                    )
                }
            } else {
                item(key = "initial_loading_card") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            Text(
                                text = "Interrogation en direct des sites d'enchères canadiens…",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "HiBid Canada, Maxx Auctions, 403 Auction, Able Auctions, GCSurplus, McDougall et plus",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    if (showProvinceSheet) {
        ProvincePickerSheet(
            selectedProvince = uiState.selectedProvince,
            includeAllCanada = true,
            onSelect = onSelectProvince,
            onDismiss = { showProvinceSheet = false }
        )
    }
}

@Composable
private fun HeroMasthead(
    selectedProvince: CanadianProvince,
    connectedCount: Int,
    readyCount: Int,
    totalEnabled: Int,
    isRefreshing: Boolean,
    lastUpdatedMillis: Long?,
    onProvinceClick: () -> Unit,
    onRefreshClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(216.dp)
    ) {
        Image(
            painter = painterResource(id = R.drawable.img_hero_canada_auctions_1795387238743),
            contentDescription = stringResource(R.string.cd_hero_banner),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xD90E1318),
                            Color(0xA6121921),
                            Color(0xEB0E1318)
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(if (isRefreshing) BurnishedBrassBright else LiveEmeraldLight)
                    )
                    Text(
                        text = "ENCANLIVE • CANADA",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFFFFD699)
                    )
                }

                IconButton(
                    onClick = onRefreshClick,
                    modifier = Modifier.testTag("hero_refresh_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.cd_refresh),
                        tint = Color.White
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Enchères en direct au Canada",
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = onProvinceClick,
                        shape = RoundedCornerShape(50),
                        color = Color.White.copy(alpha = 0.14f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.28f)),
                        modifier = Modifier.testTag("hero_province_selector")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.LocationOn,
                                contentDescription = null,
                                tint = Color(0xFFFF898E),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = selectedProvince.displayNameFr,
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White
                            )
                            Icon(
                                imageVector = Icons.Filled.ExpandMore,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    val statusSummary = when {
                        isRefreshing -> "Scan live ($totalEnabled sites)…"
                        connectedCount > 0 -> "$connectedCount flux live • $readyCount/$totalEnabled prêts"
                        else -> "$readyCount/$totalEnabled portails prêts"
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = statusSummary,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.9f)
                        )
                        if (lastUpdatedMillis != null) {
                            Text(
                                text = "MAJ ${FormatUtils.formatRelativeAge(lastUpdatedMillis)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.65f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceStatusChip(
    status: SourceFetchStatus,
    isSelectedFilter: Boolean,
    onClick: () -> Unit
) {
    val dotColor = when (status.state) {
        ConnectorState.LOADING -> BurnishedBrassBright
        ConnectorState.CONNECTED -> LiveEmerald
        ConnectorState.WEB_DIRECT -> MaterialTheme.colorScheme.tertiary
        ConnectorState.ERROR -> MaterialTheme.colorScheme.error
        ConnectorState.IDLE -> MaterialTheme.colorScheme.outline
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (isSelectedFilter) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = BorderStroke(
            width = if (isSelectedFilter) 1.5.dp else 1.dp,
            color = if (isSelectedFilter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .testTag("source_status_chip_${status.sourceId.name}")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            if (status.state == ConnectorState.LOADING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 2.dp,
                    color = dotColor
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
            }
            Text(
                text = status.sourceId.displayName,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (status.lotCount > 0) {
                Text(
                    text = "${status.lotCount}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = MonoFontFamily,
                        fontWeight = FontWeight.Bold
                    ),
                    color = LiveEmerald
                )
            } else if (status.state == ConnectorState.WEB_DIRECT) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                    contentDescription = "Ouvrir sur le portail web",
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(13.dp)
                )
            }
        }
    }
}

@Composable
private fun DirectPortalsBanner(
    province: CanadianProvince,
    query: String,
    statuses: List<SourceFetchStatus>,
    onOpenPortal: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Language,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Explorer aussi en 1 clic (${province.displayNameFr})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = if (query.isBlank()) {
                    "Ces sites utilisent une protection navigateur : ouvrez-les déjà filtrés sur ${province.displayNameFr}."
                } else {
                    "Ouvrez votre recherche « $query » directement pré-filtrée sur ces portails canadiens."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(statuses, key = { it.sourceId.name }) { st ->
                    OutlinedButton(
                        onClick = { onOpenPortal(st.directSearchUrl) },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(st.sourceId.displayName)
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyResultsWithDirectPortals(
    province: CanadianProvince,
    query: String,
    statuses: List<SourceFetchStatus>,
    onRetry: () -> Unit,
    onClearFilters: () -> Unit,
    onOpenPortal: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_empty_auction_state_1795387238333),
                contentDescription = stringResource(R.string.cd_empty_illustration),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .clip(RoundedCornerShape(14.dp))
            )

            Text(
                text = "Aucun lot direct pour ces filtres",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Certains sites canadiens filtrent le trafic automatisé ou n'ont aucun lot correspondant à « ${query.ifBlank { province.displayNameFr }} » en ce moment. Vous pouvez réessayer ou ouvrir directement les portails pré-filtrés :",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onRetry,
                    modifier = Modifier.testTag("empty_retry_button")
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Réessayer")
                }
                OutlinedButton(
                    onClick = onClearFilters,
                    modifier = Modifier.testTag("empty_reset_filters_button")
                ) {
                    Text("Réinitialiser")
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CloudDone,
                        contentDescription = null,
                        tint = LiveEmerald,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Recherche directe pré-filtrée (${province.displayNameFr})",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                statuses.take(8).forEach { st ->
                    Surface(
                        onClick = { onOpenPortal(st.directSearchUrl) },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = st.sourceId.displayName,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = st.sourceId.coverageLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = "Ouvrir ${st.sourceId.displayName}",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
