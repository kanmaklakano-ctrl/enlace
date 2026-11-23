package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.model.CanadianProvince
import com.example.model.CostBreakdown
import com.example.ui.components.FormatUtils
import com.example.ui.components.ProvincePickerSheet
import com.example.ui.theme.MonoFontFamily
import com.example.ui.viewmodel.CalculatorUiState

@Composable
fun CalculatorScreen(
    uiState: CalculatorUiState,
    onBidChange: (String) -> Unit,
    onPremiumChange: (Double) -> Unit,
    onExtraFeesChange: (String) -> Unit,
    onProvinceChange: (CanadianProvince) -> Unit,
    modifier: Modifier = Modifier
) {
    var showProvinceSheet by remember { mutableStateOf(false) }
    val breakdown = uiState.breakdown
    val comparisonProvinces = remember {
        listOf(
            CanadianProvince.AB,
            CanadianProvince.SK,
            CanadianProvince.BC,
            CanadianProvince.ON,
            CanadianProvince.NS,
            CanadianProvince.QC,
            CanadianProvince.NB
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "calc_header") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Calculateur d'enchère",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Calculez votre coût total réel au Canada avec les frais d'acheteur (Buyer's Premium) et taxes provinciales exactes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Inputs Card
        item(key = "calc_inputs") {
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Province Selector Row
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("calculator_province_selector")
                            .clickable { showProvinceSheet = true }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.LocationOn,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Column {
                                    Text(
                                        text = "${uiState.selectedProvince.displayNameFr} (${uiState.selectedProvince.code})",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = uiState.selectedProvince.taxLabel,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                }
                            }
                            Icon(
                                imageVector = Icons.Filled.ExpandMore,
                                contentDescription = "Changer de province",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    OutlinedTextField(
                        value = uiState.bidInput,
                        onValueChange = onBidChange,
                        label = { Text("Montant de votre mise ($ CAD)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("calculator_bid_input")
                    )

                    // Quick bid presets
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(listOf("100", "500", "1500", "5000", "12000")) { preset ->
                            FilterChip(
                                selected = uiState.bidInput == preset,
                                onClick = { onBidChange(preset) },
                                label = { Text("$preset $") }
                            )
                        }
                    }

                    // Buyer's premium slider + presets
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Frais d'acheteur (Buyer's Premium)",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${String.format(java.util.Locale.CANADA_FRENCH, "%.1f", uiState.buyersPremiumPercent)} %",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontFamily = MonoFontFamily,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value = uiState.buyersPremiumPercent.toFloat(),
                            onValueChange = { onPremiumChange((it * 2).toInt() / 2.0) },
                            valueRange = 0f..25f,
                            modifier = Modifier.testTag("calculator_premium_slider")
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(listOf(0.0, 10.0, 12.5, 15.0, 18.0)) { rate ->
                                FilterChip(
                                    selected = uiState.buyersPremiumPercent == rate,
                                    onClick = { onPremiumChange(rate) },
                                    label = { Text("${if (rate % 1.0 == 0.0) rate.toInt().toString() else rate.toString()} %") }
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = uiState.extraFeesInput,
                        onValueChange = onExtraFeesChange,
                        label = { Text("Frais annexes / Livraison ($ CAD)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("calculator_extra_fees_input")
                    )
                }
            }
        }

        // Receipt Breakdown Summary Card
        item(key = "calc_summary") {
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondary),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "TOTAL ESTIMÉ À PAYER (${breakdown.province.code})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondary.copy(alpha = 0.78f)
                    )
                    Text(
                        text = FormatUtils.formatCadPrecise(breakdown.totalCost),
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontFamily = MonoFontFamily,
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.onSecondary,
                        modifier = Modifier.testTag("calculator_total_output")
                    )

                    HorizontalDivider(color = MaterialTheme.colorScheme.onSecondary.copy(alpha = 0.18f))

                    SummaryRow("Mise gagnante", FormatUtils.formatCadPrecise(breakdown.bidAmount))
                    SummaryRow(
                        "Frais d'acheteur (${breakdown.buyersPremiumPercent}%)",
                        FormatUtils.formatCadPrecise(breakdown.buyersPremiumAmount)
                    )
                    if (breakdown.extraFees > 0.0) {
                        SummaryRow("Frais annexes", FormatUtils.formatCadPrecise(breakdown.extraFees))
                    }
                    SummaryRow("Sous-total taxable", FormatUtils.formatCadPrecise(breakdown.subtotal))
                    SummaryRow(
                        "Taxes (${breakdown.province.taxLabel})",
                        FormatUtils.formatCadPrecise(breakdown.taxAmount)
                    )
                }
            }
        }

        // Quick Cross-Province Comparison
        item(key = "calc_province_comparison") {
            Card(
                shape = MaterialTheme.shapes.large,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Comparaison par province",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    comparisonProvinces.forEach { prov ->
                        val cmp = CostBreakdown.calculate(
                            bidAmount = breakdown.bidAmount,
                            buyersPremiumPercent = breakdown.buyersPremiumPercent,
                            province = prov,
                            extraFees = breakdown.extraFees
                        )
                        val isCurrent = prov == uiState.selectedProvince
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onProvinceChange(prov) }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "${prov.displayNameFr} (${prov.code})",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = prov.taxLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = FormatUtils.formatCadPrecise(cmp.totalCost),
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontFamily = MonoFontFamily,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium
                                ),
                                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                        if (prov != comparisonProvinces.last()) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                        }
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(12.dp)) }
    }

    if (showProvinceSheet) {
        ProvincePickerSheet(
            selectedProvince = uiState.selectedProvince,
            includeAllCanada = false,
            onSelect = onProvinceChange,
            onDismiss = { showProvinceSheet = false }
        )
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondary.copy(alpha = 0.85f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = MonoFontFamily,
                fontWeight = FontWeight.SemiBold
            ),
            color = MaterialTheme.colorScheme.onSecondary
        )
    }
}
