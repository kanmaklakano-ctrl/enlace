package com.example.ui.components

import java.text.NumberFormat
import java.util.Locale

object FormatUtils {
    private val cadLocale = Locale("fr", "CA")

    fun formatCad(amount: Double?): String {
        if (amount == null) return "Voir mise"
        val formatter = NumberFormat.getNumberInstance(cadLocale).apply {
            minimumFractionDigits = if (amount % 1.0 == 0.0) 0 else 2
            maximumFractionDigits = 2
        }
        return "${formatter.format(amount)} $"
    }

    fun formatCadPrecise(amount: Double): String {
        val formatter = NumberFormat.getNumberInstance(cadLocale).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }
        return "${formatter.format(amount)} $ CAD"
    }

    fun formatCountdown(closingTimeMillis: Long?, fallbackLabel: String, nowMillis: Long = System.currentTimeMillis()): String {
        if (closingTimeMillis == null) return fallbackLabel.ifBlank { "En cours" }
        val diffMs = closingTimeMillis - nowMillis
        if (diffMs <= 0L) return "Terminée"
        val totalSeconds = diffMs / 1000L
        val days = totalSeconds / 86400L
        val hours = (totalSeconds % 86400L) / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return when {
            days > 0L -> "${days}j ${hours}h"
            hours > 0L -> "${hours}h ${minutes}m"
            minutes > 0L -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    fun isClosingUrgent(closingTimeMillis: Long?, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (closingTimeMillis == null) return false
        val diff = closingTimeMillis - nowMillis
        return diff in 1L..(6L * 60L * 60L * 1000L)
    }

    fun formatRelativeAge(timestampMillis: Long?): String {
        if (timestampMillis == null) return ""
        val elapsedMin = ((System.currentTimeMillis() - timestampMillis) / 60_000L).coerceAtLeast(0L)
        return when {
            elapsedMin < 1L -> "à l'instant"
            elapsedMin < 60L -> "il y a $elapsedMin min"
            elapsedMin < 1440L -> "il y a ${elapsedMin / 60L} h"
            else -> "il y a ${elapsedMin / 1440L} j"
        }
    }
}
