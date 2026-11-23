package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.graphics.toColorInt
import com.example.data.network.AuctionHttpClient
import com.example.model.AuctionLot

object BrowserLauncher {
    fun openAuctionUrl(context: Context, rawUrl: String) {
        val safeUrl = AuctionHttpClient.sanitizeHttpsUrl(rawUrl)
        if (safeUrl == null) {
            Toast.makeText(context, "Lien d'enchère invalide ou non sécurisé.", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = Uri.parse(safeUrl)
        runCatching {
            val toolbarColor = "#B91C2E".toColorInt()
            val params = CustomTabColorSchemeParams.Builder()
                .setToolbarColor(toolbarColor)
                .build()
            val customTabsIntent = CustomTabsIntent.Builder()
                .setDefaultColorSchemeParams(params)
                .setShowTitle(true)
                .setUrlBarHidingEnabled(true)
                .build()
            customTabsIntent.launchUrl(context, uri)
        }.onFailure {
            runCatching {
                val fallback = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallback)
            }.onFailure {
                Toast.makeText(context, "Aucun navigateur disponible pour ouvrir le lien.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun shareAuction(context: Context, lot: AuctionLot) {
        val priceText = lot.currentBid?.let { FormatUtils.formatCad(it) } ?: "Voir sur le site"
        val shareBody = buildString {
            append("${lot.title}\n")
            append("Mise actuelle : $priceText\n")
            append("Province : ${lot.province.displayNameFr} (${lot.city})\n")
            append("Source : ${lot.sourceId.displayName}\n")
            append(lot.auctionUrl)
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Enchère Canada : ${lot.title}")
            putExtra(Intent.EXTRA_TEXT, shareBody)
        }
        context.startActivity(Intent.createChooser(intent, "Partager l'enchère"))
    }

    fun copyUrlToClipboard(context: Context, url: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Lien enchère", url))
        Toast.makeText(context, "Lien copié dans le presse-papiers", Toast.LENGTH_SHORT).show()
    }
}
