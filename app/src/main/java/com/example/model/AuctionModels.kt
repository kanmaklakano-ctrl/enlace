package com.example.model

enum class AuctionSourceId(
    val displayName: String,
    val domain: String,
    val taglineFr: String,
    val coverageLabel: String,
    val defaultBuyersPremium: Double,
    val supportsLiveApiOrScrape: Boolean
) {
    HIBID_CANADA(
        displayName = "HiBid Canada",
        domain = "canada.hibid.com",
        taglineFr = "Réseau national (API GraphQL live) : tech, autos, outils, bijoux & lots",
        coverageLabel = "Toutes les provinces",
        defaultBuyersPremium = 15.0,
        supportsLiveApiOrScrape = true
    ),
    MAXX_AUCTIONS_ON(
        displayName = "Maxx Auctions",
        domain = "www.maxx.ca",
        taglineFr = "Liquidations de détail, électronique, Apple/Samsung & électroménagers",
        coverageLabel = "Ontario (GTA / Toronto)",
        defaultBuyersPremium = 15.0,
        supportsLiveApiOrScrape = true
    ),
    AUCTION_403_ON(
        displayName = "403 Auction",
        domain = "www.403auction.com",
        taglineFr = "Palettes revendeurs, électronique, retours & fret non réclamé",
        coverageLabel = "Ontario (Burlington / GTA)",
        defaultBuyersPremium = 15.0,
        supportsLiveApiOrScrape = true
    ),
    ABLE_AUCTIONS_BC(
        displayName = "Able Auctions",
        domain = "www.ableauctions.ca",
        taglineFr = "Encans en ligne majeurs en Colombie-Britannique (tech, véhicules, commerce)",
        coverageLabel = "Colombie-Britannique (Vancouver / Langley)",
        defaultBuyersPremium = 17.0,
        supportsLiveApiOrScrape = true
    ),
    MICHENER_ALLEN_AB(
        displayName = "Michener Allen",
        domain = "www.maauctions.com",
        taglineFr = "Véhicules, camions, VR et équipements lourds au Canada",
        coverageLabel = "AB, ON, MB, NB, BC, SK",
        defaultBuyersPremium = 12.0,
        supportsLiveApiOrScrape = true
    ),
    NORTH_TORONTO_ON(
        displayName = "North Toronto Auction",
        domain = "www.northtorontoauction.com",
        taglineFr = "Encans publics d'automobiles, VUS et camions en Ontario",
        coverageLabel = "Ontario (Toronto / Barrie)",
        defaultBuyersPremium = 12.0,
        supportsLiveApiOrScrape = true
    ),
    GCSURPLUS_CA(
        displayName = "GCSurplus Canada",
        domain = "www.gcsurplus.ca",
        taglineFr = "Biens excédentaires fédéraux : véhicules, outils & équipement",
        coverageLabel = "Toutes les provinces",
        defaultBuyersPremium = 0.0,
        supportsLiveApiOrScrape = true
    ),
    MCDOUGALL_CA(
        displayName = "McDougall Auction",
        domain = "mcdougallauction.com",
        taglineFr = "Véhicules, machinerie lourde, agricole et liquidation",
        coverageLabel = "SK, AB, MB, ON",
        defaultBuyersPremium = 12.0,
        supportsLiveApiOrScrape = true
    ),
    JARDINE_ATLANTIC(
        displayName = "Jardine Auctioneers",
        domain = "www.jardineauctioneers.com",
        taglineFr = "Véhicules, VR et équipements dans les Maritimes",
        coverageLabel = "NB, NS, PE, NL",
        defaultBuyersPremium = 12.0,
        supportsLiveApiOrScrape = true
    ),
    RIDEAU_AUCTIONS_ON(
        displayName = "Rideau Auctions",
        domain = "www.rideauauctions.com",
        taglineFr = "Véhicules, matériel commercial et liquidations en Ontario",
        coverageLabel = "Ontario (Winchester / Ottawa)",
        defaultBuyersPremium = 15.0,
        supportsLiveApiOrScrape = true
    ),
    DIRECT_AUCTIONS_CA(
        displayName = "Direct Auctions",
        domain = "www.directauctions.ca",
        taglineFr = "Encans en ligne d'équipements, autos et liquidations",
        coverageLabel = "Alberta & Ouest canadien",
        defaultBuyersPremium = 12.0,
        supportsLiveApiOrScrape = true
    ),
    POLICE_AUCTIONS_CA(
        displayName = "Police Auctions Canada",
        domain = "www.policeauctionscanada.com",
        taglineFr = "Objets saisis, bijoux, électronique, vélos & outils",
        coverageLabel = "Ontario & livraison Canada",
        defaultBuyersPremium = 15.0,
        supportsLiveApiOrScrape = true
    ),
    GOVDEALS_CA(
        displayName = "GovDeals Canada",
        domain = "www.govdeals.ca",
        taglineFr = "Surplus municipaux, flottes publiques et équipements",
        coverageLabel = "ON, QC, AB, BC, MB, NS…",
        defaultBuyersPremium = 12.5,
        supportsLiveApiOrScrape = true
    ),
    BC_AUCTION(
        displayName = "BC Auction",
        domain = "www.bcauction.ca",
        taglineFr = "Surplus publics de la Colombie-Britannique",
        coverageLabel = "Colombie-Britannique",
        defaultBuyersPremium = 0.0,
        supportsLiveApiOrScrape = true
    ),
    MAXSOLD_CA(
        displayName = "MaxSold Canada",
        domain = "maxsold.com",
        taglineFr = "Ventes de succession, meubles, outils & collections locales",
        coverageLabel = "ON, QC, BC, AB, NS",
        defaultBuyersPremium = 15.0,
        supportsLiveApiOrScrape = true
    ),
    RITCHIE_BROS_CA(
        displayName = "Ritchie Bros. Canada",
        domain = "www.rbauction.com",
        taglineFr = "Machinerie lourde, camions commerciaux & agricole",
        coverageLabel = "Toutes les provinces",
        defaultBuyersPremium = 10.0,
        supportsLiveApiOrScrape = true
    );

    companion object {
        val defaultEnabledSources: Set<AuctionSourceId> = entries.toSet()

        fun fromNameOrDefault(name: String): AuctionSourceId =
            entries.firstOrNull { it.name == name } ?: HIBID_CANADA
    }
}

data class AuctionLot(
    val id: String,
    val sourceId: AuctionSourceId,
    val title: String,
    val description: String,
    val currentBid: Double?,
    val currency: String = "CAD",
    val bidCount: Int = 0,
    val imageUrl: String?,
    val auctionUrl: String,
    val province: CanadianProvince,
    val city: String,
    val auctionHouse: String,
    val category: AuctionCategory,
    val closingTimeMillis: Long? = null,
    val closingLabel: String = "",
    val lotNumber: String = "",
    val buyersPremiumPercent: Double = sourceId.defaultBuyersPremium,
    val fetchedAtMillis: Long = System.currentTimeMillis()
)

enum class ConnectorState {
    IDLE,
    LOADING,
    CONNECTED,
    WEB_DIRECT,
    ERROR
}

data class SourceFetchStatus(
    val sourceId: AuctionSourceId,
    val state: ConnectorState = ConnectorState.IDLE,
    val lotCount: Int = 0,
    val latencyMs: Long = 0L,
    val statusDetail: String = "",
    val directSearchUrl: String = "https://${sourceId.domain}"
)

enum class SortOrder(val displayNameFr: String) {
    RELEVANCE("Pertinence"),
    CLOSING_SOON("Fin imminente"),
    PRICE_ASC("Prix croissant"),
    PRICE_DESC("Prix décroissant"),
    MOST_BIDS("Plus d'enchères"),
    RECENT("Plus récents")
}

data class SavedSearch(
    val id: Long = 0L,
    val label: String,
    val query: String,
    val province: CanadianProvince,
    val category: AuctionCategory,
    val createdAtMillis: Long = System.currentTimeMillis()
)

data class FavoriteLotItem(
    val lot: AuctionLot,
    val maxBidNote: Double? = null,
    val personalNote: String = "",
    val savedAtMillis: Long = System.currentTimeMillis()
)

data class CostBreakdown(
    val bidAmount: Double,
    val buyersPremiumPercent: Double,
    val buyersPremiumAmount: Double,
    val extraFees: Double,
    val subtotal: Double,
    val province: CanadianProvince,
    val taxAmount: Double,
    val totalCost: Double
) {
    companion object {
        fun calculate(
            bidAmount: Double,
            buyersPremiumPercent: Double,
            province: CanadianProvince,
            extraFees: Double = 0.0
        ): CostBreakdown {
            val safeBid = bidAmount.coerceAtLeast(0.0)
            val safePremium = buyersPremiumPercent.coerceIn(0.0, 50.0)
            val safeExtra = extraFees.coerceAtLeast(0.0)
            val premiumAmount = safeBid * (safePremium / 100.0)
            val subtotal = safeBid + premiumAmount + safeExtra
            val taxAmount = subtotal * province.taxRate
            return CostBreakdown(
                bidAmount = safeBid,
                buyersPremiumPercent = safePremium,
                buyersPremiumAmount = premiumAmount,
                extraFees = safeExtra,
                subtotal = subtotal,
                province = province,
                taxAmount = taxAmount,
                totalCost = subtotal + taxAmount
            )
        }
    }
}
