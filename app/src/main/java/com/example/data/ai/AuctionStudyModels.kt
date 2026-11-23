package com.example.data.ai

enum class DealVerdict(
    val apiCode: String,
    val labelFr: String,
    val shortBadgeFr: String
) {
    EXCELLENT_DEAL("EXCELLENT_DEAL", "Excellent deal", "EXCELLENT DEAL"),
    BON_DEAL("BON_DEAL", "Bon deal", "BON DEAL"),
    PRIX_CORRECT("PRIX_CORRECT", "Prix correct", "PRIX CORRECT"),
    RISQUE("RISQUE", "Risqué — vérifier avant", "RISQUÉ"),
    TROP_CHER("TROP_CHER", "Trop cher / peu rentable", "TROP CHER");

    companion object {
        fun fromCode(raw: String?): DealVerdict {
            val normalized = raw.orEmpty().trim().uppercase()
            return entries.firstOrNull { it.apiCode == normalized } ?: PRIX_CORRECT
        }
    }
}

data class ScrapedLotEvidence(
    val scrapedSuccessfully: Boolean = false,
    val pageTitle: String = "",
    val fullListingText: String = "",
    val detectedVin: String? = null,
    val detectedMileage: String? = null,
    val retailPriceHint: String? = null,
    val carfaxUrl: String? = null,
    val carfaxReportText: String? = null,
    val carfaxAccessNote: String? = null,
    val inspectionReportUrl: String? = null,
    val pageBuyersPremiumHint: String? = null
)

data class AuctionStudyReport(
    val lotId: String,
    val verdict: DealVerdict,
    val dealScore: Int,
    val headline: String,
    val estimatedMarketValueCad: String,
    val recommendedMaxBidCad: String,
    val estimatedProfitOrSavings: String,
    val priceAnalysis: String,
    val conditionSummary: String,
    val carfaxAnalysis: String,
    val carfaxUrl: String?,
    val inspectionReportUrl: String?,
    val detectedVin: String?,
    val detectedMileage: String?,
    val scrapedOfficialPage: Boolean,
    val pros: List<String>,
    val redFlags: List<String>,
    val verificationChecklist: List<String>,
    val analyzedAtMillis: Long = System.currentTimeMillis()
)

sealed interface AuctionStudyUiState {
    data object Idle : AuctionStudyUiState
    data class Loading(val stageLabel: String) : AuctionStudyUiState
    data class Success(val report: AuctionStudyReport) : AuctionStudyUiState
    data class Error(val message: String) : AuctionStudyUiState
}
