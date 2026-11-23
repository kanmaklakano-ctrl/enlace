package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val EncanLightColorScheme = lightColorScheme(
    primary = MapleCrimson,
    onPrimary = Color.White,
    primaryContainer = MapleCrimsonContainer,
    onPrimaryContainer = OnMapleCrimsonContainer,
    secondary = CedarSlate,
    onSecondary = Color.White,
    secondaryContainer = CedarSlateContainer,
    onSecondaryContainer = OnCedarSlateContainer,
    tertiary = BurnishedBrass,
    onTertiary = Color.White,
    tertiaryContainer = BurnishedBrassContainer,
    onTertiaryContainer = OnBurnishedBrassContainer,
    background = WarmAlabaster,
    onBackground = InkCharcoal,
    surface = WarmPaperSurface,
    onSurface = InkCharcoal,
    surfaceVariant = WarmStoneContainer,
    onSurfaceVariant = InkMuted,
    surfaceContainerLowest = WarmPaperSurface,
    surfaceContainerLow = Color(0xFFF7F4EE),
    surfaceContainer = WarmStoneContainer,
    surfaceContainerHigh = WarmStoneHigh,
    outline = SubtleOutline,
    outlineVariant = Color(0xFFE5DFD5)
)

private val EncanDarkColorScheme = darkColorScheme(
    primary = MapleCrimsonDark,
    onPrimary = Color(0xFF54000C),
    primaryContainer = MapleCrimsonDarkContainer,
    onPrimaryContainer = Color(0xFFFFDAD9),
    secondary = Color(0xFFB5C7DC),
    onSecondary = Color(0xFF192B3E),
    secondaryContainer = Color(0xFF283A4E),
    onSecondaryContainer = Color(0xFFD7E3F1),
    tertiary = BurnishedBrassBright,
    onTertiary = Color(0xFF3E2600),
    tertiaryContainer = BrassDarkContainer,
    onTertiaryContainer = BurnishedBrassContainer,
    background = ObsidianBackground,
    onBackground = BoneWhite,
    surface = ObsidianSurface,
    onSurface = BoneWhite,
    surfaceVariant = ObsidianSurfaceHigh,
    onSurfaceVariant = MistMuted,
    surfaceContainerLowest = Color(0xFF0A0E12),
    surfaceContainerLow = ObsidianSurface,
    surfaceContainer = ObsidianSurfaceHigh,
    surfaceContainerHigh = ObsidianSurfaceHighest,
    outline = DarkOutline,
    outlineVariant = Color(0xFF242E39)
)

val EncanShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun EncanLiveTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) EncanDarkColorScheme else EncanLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = EncanTypography,
        shapes = EncanShapes,
        content = content
    )
}
