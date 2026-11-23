package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.data.ai.AuctionStudyUiState
import com.example.model.AuctionLot
import com.example.model.CostBreakdown
import com.example.model.FavoriteLotItem
import com.example.ui.components.AuctionPhotoPlaceholder
import com.example.ui.components.AuctionStudyCard
import com.example.ui.components.BrowserLauncher
import com.example.ui.components.FormatUtils
import com.example.ui.theme.LiveEmerald
import com.example.ui.theme.MonoFontFamily
import kotlinx.coroutines.delay

@Composable
fun AuctionDetailScreen(
    lot: AuctionLot?,
    favoriteItem: FavoriteLotItem?,
    studyState: AuctionStudyUiState,
    onBack: () -> Unit,
    onStudyLot: (AuctionLot, Boolean) -> Unit,
    onToggleFavorite: (AuctionLot) -> Unit,
    onSaveFavoriteNotes: (lotId: String, maxBid: String, note: String) -> Unit,
    onOpenInCalculator: (AuctionLot) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    if (lot == null) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Enchère introuvable", style = MaterialTheme.typography.titleLarge)
                Button(onClick = onBack) { Text("Retour à la liste") }
            }
        }
        return
    }

    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lot.id, lot.closingTimeMillis) {
        if (lot.closingTimeMillis != null) {
            while (true) {
                nowMillis = System.currentTimeMillis()
                delay(1000L)
            }
        }
    }

    val isFavorite = favoriteItem != null
    val countdown = FormatUtils.formatCountdown(lot.closingTimeMillis, lot.closingLabel, nowMillis)
    val costEstimate = remember(lot.currentBid, lot.buyersPremiumPercent, lot.province) {
        CostBreakdown.calculate(
            bidAmount = lot.currentBid ?: 0.0,
            buyersPremiumPercent = lot.buyersPremiumPercent,
            province = lot.province
        )
    }

    var maxBidText by remember(favoriteItem?.lot?.id, favoriteItem?.maxBidNote) {
        mutableStateOf(favoriteItem?.maxBidNote?.let { String.format(java.util.Locale.US, "%.0f", it) } ?: "")
    }
    var personalNote by remember(favoriteItem?.lot?.id, favoriteItem?.personalNote) {
        mutableStateOf(favoriteItem?.personalNote ?: "")
    }

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Hero Photo + Overlay Top Controls
            item(key = "detail_hero") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(290.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    if (!lot.imageUrl.isNullOrBlank()) {
                        SubcomposeAsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(lot.imageUrl)
                                .crossfade(true)
                                .build(),
                            contentDescription = lot.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                            loading = { AuctionPhotoPlaceholder(lot) },
                            error = { AuctionPhotoPlaceholder(lot) }
                        )
                    } else {
                        AuctionPhotoPlaceholder(lot)
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                            .align(Alignment.TopCenter)
                            .background(
                                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent))
                            )
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.52f),
                            shape = CircleShape
                        ) {
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier.testTag("detail_back_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                    contentDescription = stringResource(R.string.cd_back),
                                    tint = Color.White
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(
                                color = Color.Black.copy(alpha = 0.52f),
                                shape = CircleShape
                            ) {
                                IconButton(
                                    onClick = { BrowserLauncher.shareAuction(context, lot) },
                                    modifier = Modifier.testTag("detail_share_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Share,
                                        contentDescription = stringResource(R.string.cd_share),
                                        tint = Color.White
                                    )
                                }
                            }
                            Surface(
                                color = Color.Black.copy(alpha = 0.52f),
                                shape = CircleShape
                            ) {
                                IconButton(
                                    onClick = { onToggleFavorite(lot) },
                                    modifier = Modifier.testTag("detail_favorite_button")
                                ) {
                                    Icon(
                                        imageVector = if (isFavorite) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                                        contentDescription = stringResource(R.string.cd_favorite),
                                        tint = if (isFavorite) Color(0xFFFFD66B) else Color.White
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Title + Source + Price Highlight Card
            item(key = "detail_header") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = lot.sourceId.displayName,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "${lot.province.code} • ${lot.province.displayNameFr}",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Text(
                        text = lot.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )

                    // Price & Countdown Metric Strip
                    Card(
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "MISE ACTUELLE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = FormatUtils.formatCad(lot.currentBid),
                                    style = MaterialTheme.typography.headlineMedium.copy(
                                        fontFamily = MonoFontFamily,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = if (lot.bidCount > 0) "${lot.bidCount} enchère(s) • ${lot.currency}" else "Devise : ${lot.currency}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = LiveEmerald
                                )
                            }

                            Column(horizontalAlignment = Alignment.End) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Schedule,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "CLÔTURE",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = countdown,
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontFamily = MonoFontFamily,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (lot.lotNumber.isNotBlank()) {
                                    Text(
                                        text = "Lot #${lot.lotNumber}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // AI Auction Analyst & CARFAX Study Card
            item(key = "detail_ai_study") {
                AuctionStudyCard(
                    studyState = studyState,
                    onStartStudy = { onStudyLot(lot, false) },
                    onRefreshStudy = { onStudyLot(lot, true) },
                    onOpenUrl = { url -> BrowserLauncher.openAuctionUrl(context, url) },
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // Metadata & Description Card
            item(key = "detail_info") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Informations du lot",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        DetailInfoRow(Icons.Outlined.LocationOn, "Localisation", "${lot.city} (${lot.province.displayNameFr})")
                        DetailInfoRow(Icons.Outlined.Business, "Maison d'encan", lot.auctionHouse)
                        DetailInfoRow(Icons.Outlined.Category, "Catégorie", lot.category.displayNameFr)

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        Text(
                            text = "Description",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = lot.description.ifBlank { "Consultez le portail officiel pour les photos complètes, conditions d'inspection et frais exacts." },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { BrowserLauncher.copyUrlToClipboard(context, lot.auctionUrl) },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.testTag("detail_copy_link_button")
                            ) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Copier le lien")
                            }
                            OutlinedButton(
                                onClick = { BrowserLauncher.shareAuction(context, lot) },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Partager")
                            }
                        }
                    }
                }
            }

            // Inline Canadian Total Cost Estimator
            item(key = "detail_cost_estimator") {
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
                        verticalArrangement = Arrangement.spacedBy(8.dp)
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
                                Icon(
                                    imageVector = Icons.Filled.Calculate,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary
                                )
                                Text(
                                    text = "Coût réel estimé (${lot.province.code})",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            OutlinedButton(
                                onClick = { onOpenInCalculator(lot) },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.testTag("detail_open_calculator_button")
                            ) {
                                Text("Ajuster", style = MaterialTheme.typography.labelLarge)
                            }
                        }

                        if (lot.currentBid != null && lot.currentBid > 0.0) {
                            CostLine("Mise actuelle", FormatUtils.formatCadPrecise(costEstimate.bidAmount))
                            CostLine(
                                "Frais d'acheteur (~${lot.buyersPremiumPercent}%)",
                                FormatUtils.formatCadPrecise(costEstimate.buyersPremiumAmount)
                            )
                            CostLine(
                                "Taxes (${lot.province.taxLabel})",
                                FormatUtils.formatCadPrecise(costEstimate.taxAmount)
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Total estimé",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = FormatUtils.formatCadPrecise(costEstimate.totalCost),
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontFamily = MonoFontFamily,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        } else {
                            Text(
                                text = "Taxes applicables en ${lot.province.displayNameFr} : ${lot.province.taxLabel}. Utilisez le calculateur pour estimer votre budget total.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Personal Watchlist Strategy Card (when bookmarked)
            if (isFavorite) {
                item(key = "detail_watchlist_notes") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = MaterialTheme.shapes.large,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Ma stratégie d'enchère (Favori)",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            OutlinedTextField(
                                value = maxBidText,
                                onValueChange = { maxBidText = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                                label = { Text("Ma mise maximale ($ CAD)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("favorite_max_bid_input")
                            )
                            OutlinedTextField(
                                value = personalNote,
                                onValueChange = { personalNote = it },
                                label = { Text("Note personnelle (inspection, transport…)") },
                                minLines = 2,
                                maxLines = 4,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("favorite_note_input")
                            )
                            Button(
                                onClick = { onSaveFavoriteNotes(lot.id, maxBidText, personalNote) },
                                modifier = Modifier
                                    .align(Alignment.End)
                                    .testTag("save_favorite_note_button")
                            ) {
                                Text("Enregistrer ma note")
                            }
                        }
                    }
                }
            }
        }

        // Floating Bottom Primary Action Bar
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 10.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { onStudyLot(lot, studyState is AuctionStudyUiState.Error) },
                    enabled = studyState !is AuctionStudyUiState.Loading,
                    modifier = Modifier
                        .height(52.dp)
                        .testTag("detail_bottom_study_cta"),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.25.dp, MaterialTheme.colorScheme.primary)
                ) {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = stringResource(R.string.cd_study_auction),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.study_auction),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Button(
                    onClick = { BrowserLauncher.openAuctionUrl(context, lot.auctionUrl) },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .testTag("detail_open_official_cta"),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(Icons.Filled.Gavel, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.open_official_auction),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(17.dp))
                }
            }
        }
    }
}

@Composable
private fun DetailInfoRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun CostLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = MonoFontFamily),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
