package com.example.data.ai

import android.graphics.Bitmap
import android.util.Log
import com.example.model.AuctionCategory
import com.example.model.AuctionLot
import com.example.model.CostBreakdown
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.appcheck.FirebaseAppCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "AuctionAiAnalyst"

class AuctionAiAnalyst {

    private val studyResponseSchema: Schema = Schema.obj(
        properties = mapOf(
            "verdict" to Schema.enumeration(
                values = DealVerdict.entries.map { it.apiCode },
                description = "Verdict global de l'enchère au Canada"
            ),
            "dealScore" to Schema.integer(
                description = "Score d'opportunité de 0 à 100"
            ),
            "headline" to Schema.string(
                description = "Verdict court en français (max 90 caractères)"
            ),
            "estimatedMarketValueCad" to Schema.string(
                description = "Valeur marchande au Canada en $ CAD (ex: 700 $ – 900 $ CAD)"
            ),
            "recommendedMaxBidCad" to Schema.string(
                description = "Mise maximale conseillée avant frais/taxes en $ CAD (ex: 520 $ CAD)"
            ),
            "estimatedProfitOrSavings" to Schema.string(
                description = "Économie ou marge potentielle (ex: ~240 $ CAD sous le marché)"
            ),
            "priceAnalysis" to Schema.string(
                description = "Comparaison chiffrée entre le coût total actuel (mise + frais + taxes) et le marché canadien"
            ),
            "conditionSummary" to Schema.string(
                description = "Synthèse de l'état d'après la page officielle et la photo si fournie"
            ),
            "carfaxAnalysis" to Schema.string(
                description = "Pour un véhicule : analyse CARFAX, NIV et kilométrage (ou consignes précises). Sinon chaîne vide"
            ),
            "pros" to Schema.array(
                items = Schema.string(),
                description = "2 à 4 points forts concrets"
            ),
            "redFlags" to Schema.array(
                items = Schema.string(),
                description = "1 à 4 risques ou points de vigilance"
            ),
            "verificationChecklist" to Schema.array(
                items = Schema.string(),
                description = "2 à 4 vérifications avant d'enchérir"
            )
        )
    )

    private val model by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.5-flash-lite",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = studyResponseSchema
                temperature = 0.25f
            },
            systemInstruction = content {
                text(
                    """
                    Tu es un expert canadien en évaluation d'enchères en ligne, électronique, outillage, machinerie et véhicules d'occasion au Canada.
                    Réponds en français clair, concis et honnête.
                    1. Compare le COÛT RÉEL TOTAL (mise actuelle + frais d'acheteur + taxes provinciales) aux prix réalistes du marché canadien (AutoTrader.ca, Kijiji, Facebook Marketplace Canada, Amazon.ca, BestBuy.ca).
                    2. Calcule une mise maximale conseillée AVANT frais et taxes en gardant une marge pour les risques « tel quel / as-is ».
                    3. Lis les détails de la page officielle (défauts, pièces manquantes, boîte ouverte, verrouillage iCloud/IMEI, frais).
                    4. Pour un véhicule : si le rapport CARFAX (texte complet ou PDF joint) a été lu, fais une synthèse PRÉCISE et CHIFFRÉE du rapport : nombre d'accidents/sinistres avec dates et montants en $ CAD, statut du titre/branding (Normal, Salvage, Rebuilt, VGA, Volé), liens financiers (Lien Check), évolution du kilométrage (cohérence / risque de recul), dossiers d'entretien, historique USA/importation et rappels ouverts. N'invente jamais de données CARFAX absentes.
                    5. Réponds uniquement en JSON conforme au schéma fourni.
                    """.trimIndent()
                )
            }
        )
    }

    suspend fun analyzeLot(
        lot: AuctionLot,
        evidence: ScrapedLotEvidence,
        photoBitmap: Bitmap?,
        onProgress: (String) -> Unit = {}
    ): AuctionStudyReport = withContext(Dispatchers.IO) {
        onProgress("3/4 — Vérification App Check…")
        verifyAppCheckToken()

        val aiStage = if (evidence.carfaxReadDirectly) {
            "4/4 — Analyse IA du rapport CARFAX, du prix Canada et des risques…"
        } else {
            "4/4 — Connexion à Gemini (analyse prix Canada & détails)…"
        }
        onProgress(aiStage)
        val promptText = buildPrompt(lot, evidence)
        val inputContent = content {
            text(promptText)
            if (evidence.carfaxPdfBytes != null && evidence.carfaxPdfBytes.isNotEmpty()) {
                inlineData(evidence.carfaxPdfBytes, "application/pdf")
                text("Lis intégralement ce rapport PDF officiel (CARFAX / inspection) ci-joint et intègre tous les accidents, montants, kilométrages, statuts et entretiens dans carfaxAnalysis et le verdict.")
            }
            if (photoBitmap != null) {
                image(photoBitmap)
                text("Inspecte aussi visuellement cette photo du lot (état apparent, modèle, dommages visibles).")
            }
        }

        val accumulatedJson = StringBuilder()
        var chunksReceived = 0

        withTimeout(60_000L) {
            model.generateContentStream(inputContent).collect { chunk ->
                val part = chunk.text.orEmpty()
                if (part.isNotEmpty()) {
                    accumulatedJson.append(part)
                    chunksReceived++
                    if (chunksReceived == 1 || chunksReceived % 4 == 0) {
                        onProgress("4/4 — Rédaction du verdict IA (${accumulatedJson.length} car.)…")
                    }
                }
            }
        }

        val jsonText = accumulatedJson.toString().trim()
        if (jsonText.isBlank()) {
            Log.e(TAG, "Gemini returned empty stream for lot ${lot.id}")
            throw IllegalStateException("L'agent IA n'a renvoyé aucune réponse pour cette enchère.")
        }

        parseStudyReportJson(
            lotId = lot.id,
            jsonText = jsonText,
            evidence = evidence
        )
    }

    private suspend fun verifyAppCheckToken() {
        try {
            withTimeout(15_000L) {
                suspendCancellableCoroutine<Unit> { cont ->
                    FirebaseAppCheck.getInstance()
                        .getAppCheckToken(false)
                        .addOnSuccessListener { token ->
                            Log.d(TAG, "App Check token acquired (expires=${token.expireTimeMillis}).")
                            if (cont.isActive) cont.resume(Unit)
                        }
                        .addOnFailureListener { error ->
                            Log.e(TAG, "App Check token failed", error)
                            if (cont.isActive) cont.resumeWithException(error)
                        }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.e(TAG, "App Check token request timed out after 15s", e)
            throw IllegalStateException(
                "Attestation Firebase App Check bloquée (>15 s). Vérifiez que l'émulateur ou le téléphone dispose d'un accès réseau Google.",
                e
            )
        }
    }

    private fun buildPrompt(lot: AuctionLot, evidence: ScrapedLotEvidence): String {
        val currentBid = lot.currentBid ?: 0.0
        val breakdown = CostBreakdown.calculate(
            bidAmount = currentBid,
            buyersPremiumPercent = lot.buyersPremiumPercent,
            province = lot.province
        )
        val isVehicle = lot.category == AuctionCategory.VEHICLES ||
            evidence.detectedVin != null ||
            evidence.carfaxUrl != null ||
            Regex("""(?i)\b(car|truck|suv|sedan|van|pickup|ford|toyota|honda|hyundai|nissan|chevrolet|gmc|dodge|ram|mazda|subaru|bmw|mercedes|audi|volkswagen|kia|jeep|chrysler|porsche|voiture|camion|auto)\b""")
                .containsMatchIn("${lot.title} ${lot.description}")

        return buildString {
            appendLine("=== ENCHÈRE CANADIENNE ===")
            appendLine("Titre : ${lot.title}")
            appendLine("Source : ${lot.sourceId.displayName} (${lot.auctionHouse})")
            appendLine("Province / Ville : ${lot.province.displayNameFr} (${lot.province.code}) — ${lot.city}")
            appendLine("Catégorie : ${lot.category.displayNameFr} | Véhicule : ${if (isVehicle) "OUI" else "NON"}")
            appendLine("Mise actuelle : ${if (lot.currentBid != null) String.format(Locale.US, "%.2f $ %s", lot.currentBid, lot.currency) else "Non indiquée"}")
            appendLine("Enchères : ${lot.bidCount} | Clôture : ${lot.closingLabel.ifBlank { "Non précisée" }}")
            appendLine("Frais d'acheteur : ${lot.buyersPremiumPercent}%${evidence.pageBuyersPremiumHint?.let { " (page : $it)" } ?: ""}")
            appendLine("Taxes (${lot.province.taxLabel}) : ${String.format(Locale.US, "%.2f", breakdown.taxAmount)} $ CAD")
            if (currentBid > 0.0) {
                appendLine("COÛT TOTAL ACTUEL ESTIMÉ : ${String.format(Locale.US, "%.2f", breakdown.totalCost)} $ CAD")
            }
            appendLine("Description : ${lot.description.take(400).ifBlank { "(Courte)" }}")
            appendLine()

            appendLine("=== PAGE OFFICIELLE ===")
            appendLine("Lue en direct : ${if (evidence.scrapedSuccessfully) "OUI" else "NON"}")
            evidence.retailPriceHint?.let { appendLine("Prix détail / PDSF : $it") }
            evidence.detectedVin?.let { appendLine("NIV (VIN) : $it") }
            evidence.detectedMileage?.let { appendLine("Kilométrage : $it") }
            evidence.inspectionReportUrl?.let { appendLine("Rapport inspection : $it") }
            if (evidence.fullListingText.isNotBlank()) {
                appendLine(evidence.fullListingText.take(1500))
            }

            if (isVehicle || evidence.carfaxUrl != null || evidence.carfaxReadDirectly) {
                appendLine()
                appendLine("=== RAPPORT CARFAX / HISTORIQUE VÉHICULE ===")
                appendLine("Lien CARFAX : ${evidence.carfaxUrl ?: "Aucun lien public trouvé"}")
                appendLine("Rapport lu directement : ${if (evidence.carfaxReadDirectly) "OUI (${evidence.carfaxSourceMethod ?: "Direct"})" else "NON"}")
                when {
                    !evidence.carfaxReportText.isNullOrBlank() -> {
                        appendLine("CONTENU COMPLET DU RAPPORT CARFAX LU EN DIRECT :")
                        appendLine(evidence.carfaxReportText.take(5_500))
                    }
                    evidence.carfaxPdfBytes != null -> {
                        appendLine("Le rapport CARFAX / d'inspection officiel au format PDF est joint directement à ce message — lis-le intégralement.")
                    }
                    !evidence.carfaxAccessNote.isNullOrBlank() -> appendLine(evidence.carfaxAccessNote)
                    else -> appendLine("Aucun rapport CARFAX public joint à l'annonce.")
                }
            }
        }
    }

    companion object {
        internal fun parseStudyReportJson(
            lotId: String,
            jsonText: String,
            evidence: ScrapedLotEvidence
        ): AuctionStudyReport {
            val json = JSONObject(jsonText)
            val verdict = DealVerdict.fromCode(json.optString("verdict"))
            val dealScore = json.optInt("dealScore", 50).coerceIn(0, 100)
            val headline = json.optString("headline").ifBlank { verdict.labelFr }
            val marketValue = json.optString("estimatedMarketValueCad").ifBlank { "Estimation indisponible" }
            val maxBid = json.optString("recommendedMaxBidCad").ifBlank { "À évaluer selon inspection" }
            val savings = json.optString("estimatedProfitOrSavings").ifBlank { "Voir analyse ci-dessous" }
            val priceAnalysis = json.optString("priceAnalysis").ifBlank { "Analyse de prix non fournie." }
            val conditionSummary = json.optString("conditionSummary").ifBlank { "Vérifiez les détails sur l'annonce officielle." }
            val carfaxAnalysis = json.optString("carfaxAnalysis").trim()

            fun readStringList(key: String): List<String> {
                val arr = json.optJSONArray(key) ?: return emptyList()
                return (0 until arr.length())
                    .mapNotNull { idx -> arr.optString(idx)?.trim()?.takeIf { it.isNotBlank() } }
            }

            return AuctionStudyReport(
                lotId = lotId,
                verdict = verdict,
                dealScore = dealScore,
                headline = headline,
                estimatedMarketValueCad = marketValue,
                recommendedMaxBidCad = maxBid,
                estimatedProfitOrSavings = savings,
                priceAnalysis = priceAnalysis,
                conditionSummary = conditionSummary,
                carfaxAnalysis = carfaxAnalysis,
                carfaxUrl = evidence.carfaxUrl,
                inspectionReportUrl = evidence.inspectionReportUrl,
                detectedVin = evidence.detectedVin,
                detectedMileage = evidence.detectedMileage,
                scrapedOfficialPage = evidence.scrapedSuccessfully,
                carfaxReadDirectly = evidence.carfaxReadDirectly,
                pros = readStringList("pros"),
                redFlags = readStringList("redFlags"),
                verificationChecklist = readStringList("verificationChecklist")
            )
        }
    }
}
