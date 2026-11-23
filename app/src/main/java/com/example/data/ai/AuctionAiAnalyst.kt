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
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.Locale

private const val TAG = "AuctionAiAnalyst"

class AuctionAiAnalyst {

    private val studyResponseSchema: Schema = Schema.obj(
        properties = mapOf(
            "verdict" to Schema.enumeration(
                values = DealVerdict.entries.map { it.apiCode },
                description = "Verdict global de l'enchère au Canada"
            ),
            "dealScore" to Schema.integer(
                description = "Score d'opportunité de 0 (très mauvais) à 100 (affaire exceptionnelle)"
            ),
            "headline" to Schema.string(
                description = "Phrase verdict courte et directe en français (max 90 caractères)"
            ),
            "estimatedMarketValueCad" to Schema.string(
                description = "Valeur marchande réaliste au Canada en $ CAD (ex: 700 $ – 900 $ CAD)"
            ),
            "recommendedMaxBidCad" to Schema.string(
                description = "Mise maximale conseillée avant frais d'acheteur et taxes en $ CAD (ex: 520 $ CAD)"
            ),
            "estimatedProfitOrSavings" to Schema.string(
                description = "Économie ou marge potentielle par rapport au coût total estimé actuel (ex: ~240 $ CAD sous le marché)"
            ),
            "priceAnalysis" to Schema.string(
                description = "Comparaison chiffrée entre le coût total actuel (mise + frais d'acheteur + taxes provinciales) et le marché canadien (AutoTrader.ca, Kijiji, Marketplace, Amazon.ca, BestBuy.ca)"
            ),
            "conditionSummary" to Schema.string(
                description = "Synthèse honnête de l'état de l'objet d'après le texte complet de l'annonce officielle et la photo si fournie"
            ),
            "carfaxAnalysis" to Schema.string(
                description = "Pour un véhicule : analyse du rapport CARFAX, du NIV et du kilométrage (ou consignes précises si aucun CARFAX public n'est fourni). Pour un non-véhicule : chaîne vide"
            ),
            "pros" to Schema.array(
                items = Schema.string(),
                description = "2 à 4 points forts concrets de ce lot"
            ),
            "redFlags" to Schema.array(
                items = Schema.string(),
                description = "1 à 4 risques, coûts cachés ou zones d'ombre à surveiller"
            ),
            "verificationChecklist" to Schema.array(
                items = Schema.string(),
                description = "2 à 4 vérifications concrètes à faire avant de placer une mise"
            )
        )
    )

    private val model by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = studyResponseSchema
                temperature = 0.25f
            },
            systemInstruction = content {
                text(
                    """
                    Tu es un expert canadien en évaluation d'enchères en ligne, revente, électronique, outillage, machinerie et automobiles d'occasion au Canada.
                    Ton rôle est d'étudier une enchère réelle et d'aider l'acheteur à décider si c'est un bon deal ou non, en français clair, direct et honnête.
                    Règles strictes :
                    1. Compare toujours le COÛT RÉEL TOTAL (mise actuelle + frais d'acheteur + taxes de la province canadienne) par rapport au prix réaliste du marché canadien (Kijiji, Facebook Marketplace Canada, AutoTrader.ca, Amazon.ca, BestBuy.ca, HomeDepot.ca).
                    2. Calcule une « mise maximale conseillée » réaliste AVANT frais et taxes pour que l'acheteur conserve une bonne marge de sécurité après frais d'acheteur, taxes et risques d'encan (« tel quel / as-is », pas de retour).
                    3. Lis attentivement tous les détails extraits de la page officielle (défauts, pièces manquantes, boîte ouverte, retour client, état non testé, verrouillage iCloud/IMEI pour les téléphones, frais de manutention ou de ramassage).
                    4. Pour les véhicules : examine le NIV (VIN), le kilométrage et le rapport CARFAX s'il a été lu. N'invente JAMAIS un historique CARFAX qui n'est pas présent dans les données fournies ; si seul le lien CARFAX ou le NIV est disponible, dis-le clairement et explique exactement quoi vérifier (accidents, statut Salvage/Rebuilt/VGA, lien financier PPSA/RDPRM, kilométrage, rappels, inspection mécanique provinciale).
                    5. Si le prix actuel est absent ou très bas (début d'enchère), précise bien que le prix va probablement monter d'ici la clôture et base surtout ton conseil sur la mise maximale à ne pas dépasser.
                    6. Réponds UNIQUEMENT en JSON conforme au schéma fourni.
                    """.trimIndent()
                )
            }
        )
    }

    suspend fun analyzeLot(
        lot: AuctionLot,
        evidence: ScrapedLotEvidence,
        photoBitmap: Bitmap?
    ): AuctionStudyReport {
        val promptText = buildPrompt(lot, evidence)
        val response = withTimeout(60_000L) {
            if (photoBitmap != null) {
                model.generateContent(
                    content {
                        text(promptText)
                        image(photoBitmap)
                        text("Inspecte aussi visuellement cette photo du lot (état apparent, modèle, dommages visibles ou emballage) et intègre-la dans ton analyse.")
                    }
                )
            } else {
                model.generateContent(promptText)
            }
        }

        val jsonText = response.text
        if (jsonText.isNullOrBlank()) {
            Log.e(TAG, "Gemini returned empty response for lot ${lot.id}")
            throw IllegalStateException("L'agent IA n'a renvoyé aucune réponse pour cette enchère.")
        }

        return parseStudyReportJson(
            lotId = lot.id,
            jsonText = jsonText,
            evidence = evidence
        )
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
            Regex("""(?i)\b(car|truck|suv|sedan|van|pickup|ford|toyota|honda|hyundai|nissan|chevrolet|gmc|dodge|ram|mazda|subaru|bmw|mercedes|audi|volkswagen|kia|jeep|voiture|camion|auto)\b""")
                .containsMatchIn("${lot.title} ${lot.description}")

        return buildString {
            appendLine("=== DONNÉES DE L'ENCHÈRE CANADIENNE ===")
            appendLine("Titre : ${lot.title}")
            appendLine("Source : ${lot.sourceId.displayName} (${lot.auctionHouse})")
            appendLine("Province / Ville : ${lot.province.displayNameFr} (${lot.province.code}) — ${lot.city}")
            appendLine("Catégorie : ${lot.category.displayNameFr} (Véhicule détecté : ${if (isVehicle) "OUI" else "NON"})")
            appendLine("Mise actuelle : ${if (lot.currentBid != null) String.format(Locale.US, "%.2f $ %s", lot.currentBid, lot.currency) else "Non indiquée / début d'enchère"}")
            appendLine("Nombre d'enchères : ${lot.bidCount}")
            appendLine("Clôture : ${lot.closingLabel.ifBlank { "Non précisée" }}")
            appendLine("Frais d'acheteur estimés : ${lot.buyersPremiumPercent}%${evidence.pageBuyersPremiumHint?.let { " (trouvé sur page : $it)" } ?: ""}")
            appendLine("Taxes provinciales (${lot.province.taxLabel}) : ${String.format(Locale.US, "%.2f", breakdown.taxAmount)} $ CAD")
            if (currentBid > 0.0) {
                appendLine("COÛT RÉEL TOTAL ACTUEL (mise + frais + taxes) : ${String.format(Locale.US, "%.2f", breakdown.totalCost)} $ CAD")
            }
            appendLine("URL officielle : ${lot.auctionUrl}")
            appendLine()

            appendLine("=== DESCRIPTION INITIALE DU FLUX ===")
            appendLine(lot.description.ifBlank { "(Aucune description courte dans le flux)" })
            appendLine()

            appendLine("=== DONNÉES EXTRAITES EN DIRECT DE LA PAGE OFFICIELLE ===")
            appendLine("Page lue en direct : ${if (evidence.scrapedSuccessfully) "OUI" else "NON (site protégé ou hors ligne — analyser avec prudence)"}")
            if (evidence.pageTitle.isNotBlank()) appendLine("Titre page web : ${evidence.pageTitle}")
            evidence.retailPriceHint?.let { appendLine("Prix de détail / PDSF mentionné : $it") }
            evidence.detectedVin?.let { appendLine("NIV (VIN) détecté : $it") }
            evidence.detectedMileage?.let { appendLine("Kilométrage détecté : $it") }
            evidence.inspectionReportUrl?.let { appendLine("Lien rapport d'inspection trouvé : $it") }

            if (evidence.fullListingText.isNotBlank()) {
                appendLine("Texte détaillé de l'annonce :")
                appendLine(evidence.fullListingText)
            }
            appendLine()

            if (isVehicle || evidence.carfaxUrl != null) {
                appendLine("=== RAPPORT CARFAX / HISTORIQUE VÉHICULE ===")
                appendLine("Lien CARFAX trouvé sur l'annonce : ${evidence.carfaxUrl ?: "Aucun lien CARFAX public détecté"}")
                when {
                    !evidence.carfaxReportText.isNullOrBlank() -> {
                        appendLine("Contenu extrait du rapport CARFAX :")
                        appendLine(evidence.carfaxReportText)
                    }
                    !evidence.carfaxAccessNote.isNullOrBlank() -> {
                        appendLine("Note d'accès CARFAX : ${evidence.carfaxAccessNote}")
                    }
                    else -> {
                        appendLine("Aucun rapport CARFAX n'était joint publiquement à la page du lot.")
                    }
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
                pros = readStringList("pros"),
                redFlags = readStringList("redFlags"),
                verificationChecklist = readStringList("verificationChecklist")
            )
        }
    }
}
