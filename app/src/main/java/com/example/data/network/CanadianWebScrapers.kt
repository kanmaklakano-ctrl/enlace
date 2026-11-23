package com.example.data.network

import com.example.model.AuctionCategory
import com.example.model.AuctionLot
import com.example.model.AuctionSourceId
import com.example.model.CanadianProvince
import com.example.model.ConnectorState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/**
 * Multi-strategy live Canadian web connector for secondary auction portals
 * (Rideau Auctions, Direct Auctions, MaxSold, Ritchie Bros, etc.).
 */
class GenericCanadianWebConnector(
    override val sourceId: AuctionSourceId,
    private val defaultProvince: CanadianProvince,
    private val supportedProvinces: Set<CanadianProvince>,
    private val urlBuilder: (query: String, province: CanadianProvince) -> String,
    private val linkPathHints: List<String>
) : AuctionConnector {

    override fun buildDirectSearchUrl(query: String, province: CanadianProvince): String =
        urlBuilder(BilingualQueryTranslator.toEnglishSearchQuery(query), province)

    override suspend fun search(
        query: String,
        province: CanadianProvince,
        category: AuctionCategory
    ): ConnectorResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val enQuery = BilingualQueryTranslator.toEnglishSearchQuery(query)
        val directUrl = urlBuilder(enQuery, province)

        if (province != CanadianProvince.ALL && supportedProvinces.isNotEmpty() && province !in supportedProvinces) {
            return@withContext ConnectorResult(
                sourceId = sourceId,
                state = ConnectorState.CONNECTED,
                lots = emptyList(),
                latencyMs = System.currentTimeMillis() - started,
                statusDetail = "Couverture principale : ${sourceId.coverageLabel}",
                directSearchUrl = directUrl
            )
        }

        val lots = runCatching { fetchLiveLots(directUrl, query, province) }.getOrDefault(emptyList())
        val latency = System.currentTimeMillis() - started

        if (lots.isNotEmpty()) {
            ConnectorResult(
                sourceId = sourceId,
                state = ConnectorState.CONNECTED,
                lots = lots,
                latencyMs = latency,
                statusDetail = "Connecté en direct (${lots.size} lots)",
                directSearchUrl = directUrl
            )
        } else {
            ConnectorResult(
                sourceId = sourceId,
                state = ConnectorState.WEB_DIRECT,
                lots = emptyList(),
                latencyMs = latency,
                statusDetail = "Portail prêt — recherche directe pré-filtrée",
                directSearchUrl = directUrl
            )
        }
    }

    private fun fetchLiveLots(
        url: String,
        rawQuery: String,
        province: CanadianProvince
    ): List<AuctionLot> {
        val origin = "https://${sourceId.domain}"
        val request = AuctionHttpClient.newGetRequest(url)
        return AuctionHttpClient.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use emptyList()
            val html = response.body?.string().orEmpty()
            if (html.isBlank()) return@use emptyList()

            val doc = Jsoup.parse(html, origin)
            val targetProv = if (province != CanadianProvince.ALL) province else defaultProvince

            val jsonLdLots = AuctionHttpClient.parseJsonLdLots(
                doc = doc,
                sourceId = sourceId,
                baseOrigin = origin,
                defaultProvince = targetProv,
                defaultCity = targetProv.displayNameFr
            )
            if (jsonLdLots.isNotEmpty()) {
                return@use filterBilingually(jsonLdLots, rawQuery)
            }

            val extracted = mutableListOf<AuctionLot>()
            val seen = mutableSetOf<String>()
            val selector = linkPathHints.joinToString(", ") { "a[href*=$it]" }
            val links = if (selector.isNotBlank()) doc.select(selector) else doc.select("article a[href], .card a[href]")

            for (link in links) {
                val absUrl = AuctionHttpClient.sanitizeHttpsUrl(link.attr("href"), origin) ?: continue
                if (absUrl.equals(origin, true) || absUrl.equals("$origin/", true)) continue
                if (!seen.add(absUrl)) continue

                val card = link.parents().firstOrNull {
                    it.tagName() == "article" ||
                        it.hasClass("card") ||
                        it.hasClass("lot") ||
                        it.hasClass("auction") ||
                        it.hasClass("product")
                } ?: continue

                // Require a real photo for generic cards so we never scrape text-only navigation links
                val img = card.select("img").first()
                val imgRaw = img?.attr("data-src").orEmpty()
                    .ifBlank { img?.attr("data-lazy-src").orEmpty() }
                    .ifBlank { img?.attr("src").orEmpty() }
                val imgUrl = AuctionHttpClient.sanitizeImageUrl(imgRaw, origin) ?: continue

                val title = card.select("h1, h2, h3, h4, .title, .name, .lot-title").first()?.text()?.trim()
                    .orEmpty()
                    .ifBlank { link.attr("title").trim() }
                    .ifBlank { link.text().trim() }
                if (title.length < 6 || title.length > 180) continue
                val lowerTitle = title.lowercase()
                if (lowerTitle.contains("sign in") || lowerTitle.contains("login") ||
                    lowerTitle.contains("english version") || lowerTitle.contains("ouvrir un compte") ||
                    lowerTitle.contains("contact") || lowerTitle.contains("privacy")
                ) {
                    continue
                }

                val cardText = card.text()
                val price = AuctionHttpClient.extractPrice(cardText)
                val detectedProv = CanadianProvince.fromCodeOrText(cardText)
                    .let { if (it == CanadianProvince.ALL) targetProv else it }

                extracted.add(
                    AuctionLot(
                        id = "${sourceId.name}_${absUrl.hashCode()}",
                        sourceId = sourceId,
                        title = title.take(160),
                        description = cardText.take(450),
                        currentBid = price,
                        currency = "CAD",
                        bidCount = AuctionHttpClient.extractInt(
                            Regex("""(\d+)\s*(?:bids?|mises?|ench[eè]res?)""", RegexOption.IGNORE_CASE)
                                .find(cardText)?.groupValues?.get(1)
                        ),
                        imageUrl = imgUrl,
                        auctionUrl = absUrl,
                        province = detectedProv,
                        city = detectedProv.displayNameFr,
                        auctionHouse = sourceId.displayName,
                        category = AuctionCategory.inferFromText("$title $cardText"),
                        closingLabel = "En cours"
                    )
                )
                if (extracted.size >= 20) break
            }
            filterBilingually(extracted, rawQuery)
        }
    }

    private fun filterBilingually(lots: List<AuctionLot>, rawQuery: String): List<AuctionLot> {
        val terms = BilingualQueryTranslator.expandMatchingTerms(rawQuery)
        if (terms.isEmpty()) return lots
        return lots.filter { lot ->
            val hay = BilingualQueryTranslator.normalize("${lot.title} ${lot.description} ${lot.city}")
            terms.any { hay.contains(it) }
        }
    }
}

object CanadianConnectorsRegistry {
    fun buildAllConnectors(): List<AuctionConnector> = listOf(
        HiBidCanadaConnector(),
        MichenerNetworkConnector(
            sourceId = AuctionSourceId.MICHENER_ALLEN_AB,
            baseOrigin = "https://www.maauctions.com",
            defaultProvince = CanadianProvince.AB,
            coveredProvinces = setOf(
                CanadianProvince.AB, CanadianProvince.ON, CanadianProvince.MB,
                CanadianProvince.NB, CanadianProvince.BC, CanadianProvince.SK
            )
        ),
        MichenerNetworkConnector(
            sourceId = AuctionSourceId.NORTH_TORONTO_ON,
            baseOrigin = "https://www.northtorontoauction.com",
            defaultProvince = CanadianProvince.ON,
            coveredProvinces = setOf(CanadianProvince.ON, CanadianProvince.QC)
        ),
        GcSurplusConnector(),
        McDougallConnector(),
        MichenerNetworkConnector(
            sourceId = AuctionSourceId.JARDINE_ATLANTIC,
            baseOrigin = "https://www.jardineauctioneers.com",
            defaultProvince = CanadianProvince.NB,
            coveredProvinces = setOf(
                CanadianProvince.NB, CanadianProvince.NS, CanadianProvince.PE, CanadianProvince.NL
            )
        ),
        PoliceAuctionsConnector(),
        GovDealsCanadaConnector(),
        BcAuctionConnector(),
        GenericCanadianWebConnector(
            sourceId = AuctionSourceId.RIDEAU_AUCTIONS_ON,
            defaultProvince = CanadianProvince.ON,
            supportedProvinces = setOf(CanadianProvince.ON, CanadianProvince.QC),
            urlBuilder = { query, _ ->
                if (query.isNotBlank()) {
                    "https://www.rideauauctions.com/?s=${AuctionHttpClient.urlEncode(query)}"
                } else {
                    "https://www.rideauauctions.com/auctions/"
                }
            },
            linkPathHints = listOf("/auction", "/lot", "/vehicle")
        ),
        GenericCanadianWebConnector(
            sourceId = AuctionSourceId.DIRECT_AUCTIONS_CA,
            defaultProvince = CanadianProvince.AB,
            supportedProvinces = setOf(CanadianProvince.AB, CanadianProvince.BC, CanadianProvince.SK, CanadianProvince.MB),
            urlBuilder = { _, _ -> "https://bid.directauctions.ca/auctionlist.aspx" },
            linkPathHints = listOf("/lot", "/auction", "auctionlist")
        ),
        GenericCanadianWebConnector(
            sourceId = AuctionSourceId.MAXSOLD_CA,
            defaultProvince = CanadianProvince.ON,
            supportedProvinces = setOf(
                CanadianProvince.ON, CanadianProvince.QC, CanadianProvince.BC,
                CanadianProvince.AB, CanadianProvince.NS, CanadianProvince.MB
            ),
            urlBuilder = { query, province ->
                val slug = when (province) {
                    CanadianProvince.ON -> "ontario"
                    CanadianProvince.QC -> "quebec"
                    CanadianProvince.BC -> "british-columbia"
                    CanadianProvince.AB -> "alberta"
                    CanadianProvince.NS -> "nova-scotia"
                    else -> ""
                }
                val base = if (slug.isNotBlank()) "https://maxsold.com/canada/$slug" else "https://maxsold.com/canada"
                if (query.isNotBlank()) "$base?q=${AuctionHttpClient.urlEncode(query)}" else base
            },
            linkPathHints = listOf("/auction/", "/item/", "/lot/")
        ),
        GenericCanadianWebConnector(
            sourceId = AuctionSourceId.RITCHIE_BROS_CA,
            defaultProvince = CanadianProvince.ALL,
            supportedProvinces = emptySet(),
            urlBuilder = { query, province ->
                val keywords = buildString {
                    if (query.isNotBlank()) append(query.trim())
                    if (province != CanadianProvince.ALL) {
                        if (isNotBlank()) append(" ")
                        append(province.displayNameEn)
                    }
                }
                if (keywords.isNotBlank()) {
                    "https://www.rbauction.com/search?keywords=${AuctionHttpClient.urlEncode(keywords)}&country=CAN"
                } else {
                    "https://www.rbauction.com/heavy-equipment-auctions/canada"
                }
            },
            linkPathHints = listOf("/pdp/", "/heavy-equipment-auctions/")
        )
    )
}
