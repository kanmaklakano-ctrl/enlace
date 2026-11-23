package com.example.data.network

import com.example.model.AuctionCategory
import com.example.model.AuctionLot
import com.example.model.AuctionSourceId
import com.example.model.CanadianProvince
import com.example.model.ConnectorState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/**
 * Live HiBid Canada connector supporting all Canadian regional portals
 * (canada, ontario, quebec, alberta, bc, manitoba, saskatchewan, maritimes).
 * Decodes Angular SSR Apollo TransferState (`script#hibid-state`), resolves
 * `"Auction:XXXX"` `__ref` pointers for exact city/province/auctioneer, and
 * falls back to rendered HTML lot cards if needed.
 */
class HiBidCanadaConnector : AuctionConnector {
    override val sourceId: AuctionSourceId = AuctionSourceId.HIBID_CANADA

    override fun buildDirectSearchUrl(query: String, province: CanadianProvince): String {
        val sub = province.hibidSubdomain ?: "canada"
        val base = "https://$sub.hibid.com/lots"
        val englishQuery = BilingualQueryTranslator.toEnglishSearchQuery(query)
        val params = mutableListOf<String>()
        if (englishQuery.isNotBlank()) params.add("q=${AuctionHttpClient.urlEncode(englishQuery)}")
        if (province != CanadianProvince.ALL) {
            params.add("apage=1")
        }
        return if (params.isEmpty()) base else "$base?${params.joinToString("&")}"
    }

    override suspend fun search(
        query: String,
        province: CanadianProvince,
        category: AuctionCategory
    ): ConnectorResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val effectiveQuery = buildEffectiveQuery(query, category)
        val sub = province.hibidSubdomain ?: "canada"
        val origin = "https://$sub.hibid.com"
        val requestUrl = if (effectiveQuery.isNotBlank()) {
            "$origin/lots?q=${AuctionHttpClient.urlEncode(effectiveQuery)}"
        } else {
            "$origin/lots"
        }

        val lots = runCatching { fetchLotsFromRegionalPage(requestUrl, origin, province) }
            .getOrDefault(emptyList())
            .ifEmpty {
                if (sub != "canada" && effectiveQuery.isNotBlank()) {
                    val caUrl = "https://canada.hibid.com/lots?q=${AuctionHttpClient.urlEncode(effectiveQuery)}"
                    runCatching { fetchLotsFromRegionalPage(caUrl, "https://canada.hibid.com", province) }
                        .getOrDefault(emptyList())
                } else {
                    emptyList()
                }
            }

        val latency = System.currentTimeMillis() - started
        if (lots.isNotEmpty()) {
            ConnectorResult(
                sourceId = sourceId,
                state = ConnectorState.CONNECTED,
                lots = lots,
                latencyMs = latency,
                statusDetail = "Catalogue en direct (${lots.size} lots)",
                directSearchUrl = requestUrl
            )
        } else {
            ConnectorResult(
                sourceId = sourceId,
                state = ConnectorState.WEB_DIRECT,
                lots = emptyList(),
                latencyMs = latency,
                statusDetail = "Portail HiBid prêt — ouvrir la recherche directe",
                directSearchUrl = requestUrl
            )
        }
    }

    private fun buildEffectiveQuery(query: String, category: AuctionCategory): String {
        val translated = BilingualQueryTranslator.toEnglishSearchQuery(query)
        if (translated.isNotBlank()) return translated
        return when (category) {
            AuctionCategory.ALL -> ""
            AuctionCategory.VEHICLES -> "car"
            AuctionCategory.TOOLS_EQUIPMENT -> "tools"
            AuctionCategory.MACHINERY -> "tractor"
            AuctionCategory.ELECTRONICS -> "electronics"
            AuctionCategory.JEWELRY_COLLECTIBLES -> "gold"
            AuctionCategory.HOME_FURNITURE -> "furniture"
            AuctionCategory.STORAGE_LIQUIDATION -> "pallet"
        }
    }

    private fun fetchLotsFromRegionalPage(
        url: String,
        origin: String,
        requestedProvince: CanadianProvince
    ): List<AuctionLot> {
        val request = AuctionHttpClient.newGetRequest(url)
        AuctionHttpClient.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val html = response.body?.string().orEmpty()
            if (html.isBlank()) return emptyList()

            val doc = Jsoup.parse(html, origin)
            val stateLots = parseHibidTransferState(doc, origin, requestedProvince)
            if (stateLots.isNotEmpty()) return stateLots
            return parseHibidHtmlCards(doc, origin, requestedProvince)
        }
    }

    private fun parseHibidTransferState(
        doc: org.jsoup.nodes.Document,
        origin: String,
        requestedProvince: CanadianProvince
    ): List<AuctionLot> {
        val rawState = doc.selectFirst("script#hibid-state")?.data()?.trim().orEmpty()
        if (rawState.isBlank()) return emptyList()

        // Try direct JSON parse first so embedded &quot; inside HTML descriptions never breaks JSON syntax.
        val root = runCatching { JSONObject(rawState) }.getOrNull()
            ?: runCatching { JSONObject(Parser.unescapeEntities(rawState, false).trim()) }.getOrNull()
            ?: return emptyList()

        val apolloCache = findApolloCache(root, depth = 0) ?: root
        val collected = mutableListOf<AuctionLot>()
        val now = System.currentTimeMillis()
        val seenIds = mutableSetOf<String>()

        val keys = apolloCache.keys()
        while (keys.hasNext() && collected.size < 45) {
            val key = keys.next()
            if (key.startsWith("Lot:")) {
                val obj = apolloCache.optJSONObject(key) ?: continue
                parseHibidLotJson(obj, apolloCache, origin, requestedProvince, now)?.let { lot ->
                    if (seenIds.add(lot.id)) collected.add(lot)
                }
            }
        }

        if (collected.isEmpty()) {
            collectLotsRecursively(root, apolloCache, origin, requestedProvince, now, seenIds, collected)
        }
        return collected.take(45)
    }

    private fun findApolloCache(node: JSONObject, depth: Int): JSONObject? {
        if (depth > 4) return null
        val keys = node.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k.startsWith("Lot:") || k.startsWith("Auction:")) return node
        }
        val keys2 = node.keys()
        while (keys2.hasNext()) {
            val child = node.optJSONObject(keys2.next()) ?: continue
            val found = findApolloCache(child, depth + 1)
            if (found != null) return found
        }
        return null
    }

    private fun collectLotsRecursively(
        node: Any?,
        apolloCache: JSONObject,
        origin: String,
        requestedProvince: CanadianProvince,
        nowMillis: Long,
        seenIds: MutableSet<String>,
        out: MutableList<AuctionLot>
    ) {
        if (out.size >= 45) return
        when (node) {
            is JSONObject -> {
                if (node.has("lead") && (node.has("lotState") || node.has("featuredPicture") || node.has("lotNumber"))) {
                    parseHibidLotJson(node, apolloCache, origin, requestedProvince, nowMillis)?.let { lot ->
                        if (seenIds.add(lot.id)) out.add(lot)
                    }
                    return
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    collectLotsRecursively(node.opt(keys.next()), apolloCache, origin, requestedProvince, nowMillis, seenIds, out)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    collectLotsRecursively(node.opt(i), apolloCache, origin, requestedProvince, nowMillis, seenIds, out)
                }
            }
        }
    }

    private fun resolveRef(obj: JSONObject?, cache: JSONObject): JSONObject? {
        if (obj == null) return null
        val ref = obj.optString("__ref", "")
        return if (ref.isNotBlank()) cache.optJSONObject(ref) ?: obj else obj
    }

    private fun parseHibidLotJson(
        obj: JSONObject,
        apolloCache: JSONObject,
        origin: String,
        requestedProvince: CanadianProvince,
        nowMillis: Long
    ): AuctionLot? {
        val idNum = obj.optLong("id", 0L)
        if (idNum <= 0L) return null
        val title = obj.optString("lead", "").trim()
        if (title.length < 3) return null

        val desc = obj.optString("description", "").trim().ifBlank { title }
        val lotNum = obj.optString("lotNumber", "")

        val stateObj = resolveRef(obj.optJSONObject("lotState"), apolloCache)
        val isClosed = stateObj?.optBoolean("isClosed", false) ?: false
        if (isClosed) return null

        val highBid = stateObj?.optDouble("highBid")?.takeIf { !it.isNaN() && it > 0.0 }
        val minBid = stateObj?.optDouble("minBid")?.takeIf { !it.isNaN() && it > 0.0 }
        val buyNow = stateObj?.optDouble("buyNow")?.takeIf { !it.isNaN() && it > 0.0 }
        val displayBid = highBid ?: minBid ?: buyNow
        val bidCount = stateObj?.optInt("bidCount", 0) ?: 0
        val timeLeft = stateObj?.optString("timeLeft", "").orEmpty().trim()
            .ifBlank { stateObj?.optString("timeLeftLead", "").orEmpty().trim() }
            .ifBlank { stateObj?.optString("timeLeftTitle", "").orEmpty().trim() }
        val timeLeftSec = stateObj?.optDouble("timeLeftSeconds")?.takeIf { !it.isNaN() && it > 0.0 }?.toLong()

        val picObj = resolveRef(obj.optJSONObject("featuredPicture"), apolloCache)
        val rawImg = picObj?.optString("fullSizeLocation", "").orEmpty()
            .ifBlank { picObj?.optString("hdThumbnailLocation", "").orEmpty() }
            .ifBlank { picObj?.optString("thumbnailLocation", "").orEmpty() }
        val imageUrl = AuctionHttpClient.sanitizeImageUrl(rawImg, origin)

        val auctionObj = resolveRef(obj.optJSONObject("auction"), apolloCache)
        val eventCity = auctionObj?.optString("eventCity", "").orEmpty().trim()
        val eventState = auctionObj?.optString("eventState", "").orEmpty().trim()
        val auctioneerObj = resolveRef(auctionObj?.optJSONObject("auctioneer"), apolloCache)
        val auctioneer = auctioneerObj?.optString("name", "").orEmpty().trim()
            .ifBlank { auctionObj?.optString("eventName", "").orEmpty().trim() }
            .ifBlank { "HiBid Canada" }
        val currency = auctionObj?.optString("currencyCode", "CAD").orEmpty().ifBlank { "CAD" }
        val bpRate = auctionObj?.optDouble("buyerPremiumRate")?.takeIf { !it.isNaN() && it >= 0.0 }
            ?: sourceId.defaultBuyersPremium

        val siteObj = resolveRef(obj.optJSONObject("site"), apolloCache)
        val siteSubdomain = siteObj?.optString("subdomain", "").orEmpty().trim()
        val detectedProv = CanadianProvince.fromCodeOrText("$eventState $eventCity $siteSubdomain")
            .let { if (it == CanadianProvince.ALL) requestedProvince else it }

        val slug = title.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(60)
        val lotHost = if (siteSubdomain.isNotBlank()) "https://$siteSubdomain.hibid.com" else origin
        val lotUrl = "$lotHost/lot/$idNum/$slug"

        val cityDisplay = when {
            eventCity.isNotBlank() && detectedProv != CanadianProvince.ALL -> "$eventCity, ${detectedProv.code}"
            eventCity.isNotBlank() -> eventCity
            else -> detectedProv.displayNameFr
        }

        return AuctionLot(
            id = "HIBID_$idNum",
            sourceId = sourceId,
            title = title.take(160),
            description = desc.take(600),
            currentBid = displayBid,
            currency = currency,
            bidCount = bidCount,
            imageUrl = imageUrl,
            auctionUrl = lotUrl,
            province = detectedProv,
            city = cityDisplay,
            auctionHouse = auctioneer,
            category = AuctionCategory.inferFromText("$title $desc"),
            closingTimeMillis = timeLeftSec?.let { nowMillis + it * 1000L },
            closingLabel = timeLeft.ifBlank { "En cours" },
            lotNumber = lotNum,
            buyersPremiumPercent = bpRate
        )
    }

    private fun parseHibidHtmlCards(
        doc: org.jsoup.nodes.Document,
        origin: String,
        requestedProvince: CanadianProvince
    ): List<AuctionLot> {
        val groupedByUrl = linkedMapOf<String, MutableList<org.jsoup.nodes.Element>>()
        for (a in doc.select("a[href*=/lot/]")) {
            val absUrl = AuctionHttpClient.sanitizeHttpsUrl(a.attr("href"), origin) ?: continue
            groupedByUrl.getOrPut(absUrl) { mutableListOf() }.add(a)
        }

        val lots = mutableListOf<AuctionLot>()
        for ((absUrl, anchors) in groupedByUrl) {
            val title = anchors
                .map { it.text().trim() }
                .firstOrNull { it.length >= 5 && !it.startsWith("Lot ", ignoreCase = true) }
                ?: anchors.map { it.attr("title").trim() }.firstOrNull { it.length >= 5 }
                ?: continue

            val container = anchors.first().parents().firstOrNull {
                it.select("img[src*=cdn.hibid.com/img.axd], img[src*=sandhills]").isNotEmpty() && it.text().length > 20
            } ?: continue

            val imgEl = container.select("img[src*=cdn.hibid.com/img.axd], img[src*=sandhills]").first()
            val imgUrl = AuctionHttpClient.sanitizeImageUrl(imgEl?.attr("src"), origin)
            val fullText = container.text()
            val price = AuctionHttpClient.extractPrice(fullText)
            val bids = AuctionHttpClient.extractInt(
                Regex("""(\d+)\s*Bids?""", RegexOption.IGNORE_CASE).find(fullText)?.groupValues?.get(1)
            )
            val detectedProv = CanadianProvince.fromCodeOrText(fullText)
                .let { if (it == CanadianProvince.ALL) requestedProvince else it }
            val lotId = Regex("""/lot/(\d+)""").find(absUrl)?.groupValues?.get(1) ?: absUrl.hashCode().toString()

            lots.add(
                AuctionLot(
                    id = "HIBID_$lotId",
                    sourceId = sourceId,
                    title = title.take(160),
                    description = fullText.take(450),
                    currentBid = price,
                    currency = "CAD",
                    bidCount = bids,
                    imageUrl = imgUrl,
                    auctionUrl = absUrl,
                    province = detectedProv,
                    city = detectedProv.displayNameFr,
                    auctionHouse = "HiBid Canada",
                    category = AuctionCategory.inferFromText("$title $fullText"),
                    closingLabel = Regex("""\d+d\s+\d+[hm]|\d+h\s+\d+m""").find(fullText)?.value ?: "En cours"
                )
            )
            if (lots.size >= 35) break
        }
        return lots
    }
}
