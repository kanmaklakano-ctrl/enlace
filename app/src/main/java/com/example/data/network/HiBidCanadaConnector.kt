package com.example.data.network

import com.example.model.AuctionCategory
import com.example.model.AuctionLot
import com.example.model.AuctionSourceId
import com.example.model.CanadianProvince
import com.example.model.ConnectorState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/**
 * Supercharged live HiBid Canada connector powered primarily by HiBid's live GraphQL API
 * (`POST https://canada.hibid.com/graphql`) with exact Canadian province filtering (`state`)
 * and smart multi-query ranking (e.g., querying both "apple iphone" and "iphone" sorted by
 * `BID_AMOUNT_HIGH_TO_LOW` so real phones/devices immediately surface ahead of $1 accessories).
 *
 * Falls back transparently to HiBid regional SSR (`script#hibid-state`) and HTML cards.
 */
class HiBidCanadaConnector : AuctionConnector {
    override val sourceId: AuctionSourceId = AuctionSourceId.HIBID_CANADA

    companion object {
        private const val GRAPHQL_ENDPOINT = "https://canada.hibid.com/graphql"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private const val LOT_SEARCH_QUERY = """
            query LotSearch(${'$'}input: LotSearchInput!, ${'$'}pageNumber: Int!, ${'$'}pageLength: Int!) {
              lotSearch(input: ${'$'}input, pageNumber: ${'$'}pageNumber, pageLength: ${'$'}pageLength) {
                pagedResults {
                  totalCount
                  results {
                    id
                    lead
                    description
                    lotNumber
                    lotState {
                      highBid
                      minBid
                      buyNow
                      bidCount
                      timeLeft
                      timeLeftLead
                      timeLeftTitle
                      timeLeftSeconds
                      isClosed
                    }
                    featuredPicture {
                      fullSizeLocation
                      hdThumbnailLocation
                      thumbnailLocation
                    }
                    site {
                      subdomain
                    }
                    auction {
                      eventName
                      eventCity
                      eventState
                      buyerPremiumRate
                      auctioneer {
                        name
                      }
                    }
                  }
                }
              }
            }
        """
    }

    override fun buildDirectSearchUrl(query: String, province: CanadianProvince): String {
        val plan = BilingualQueryTranslator.analyzeQuery(query)
        val searchTerm = plan.apiQueries.firstOrNull() ?: plan.englishQuery
        val base = if (province == CanadianProvince.ALL) {
            "https://canada.hibid.com/lots"
        } else {
            val slug = province.hibidSubdomain ?: "canada"
            if (slug == "canada") "https://canada.hibid.com/lots" else "https://hibid.com/$slug/lots"
        }
        return if (searchTerm.isNotBlank()) {
            val sortParam = if (plan.isDeviceSearch) "&s=BID_AMOUNT_HIGH_TO_LOW" else ""
            "$base?q=${AuctionHttpClient.urlEncode(searchTerm)}$sortParam"
        } else {
            base
        }
    }

    override suspend fun search(
        query: String,
        province: CanadianProvince,
        category: AuctionCategory
    ): ConnectorResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val plan = BilingualQueryTranslator.analyzeQuery(query)
        val directUrl = buildDirectSearchUrl(query, province)

        val apiQueries = buildApiQueries(plan, category)

        // 1. Primary path: parallel live GraphQL searches against HiBid Canada
        val graphqlLots = runCatching {
            fetchViaGraphQlSmart(
                apiQueries = apiQueries,
                province = province,
                isDeviceSearch = plan.isDeviceSearch,
                hasUserQuery = plan.englishQuery.isNotBlank()
            )
        }.getOrDefault(emptyList())

        // 2. Fallback path: regional SSR HTML page if GraphQL returns empty
        val finalLots = if (graphqlLots.isNotEmpty()) {
            graphqlLots
        } else {
            val fallbackQuery = apiQueries.firstOrNull().orEmpty()
            val fallbackUrl = buildSsrUrl(fallbackQuery, province, plan.isDeviceSearch)
            runCatching { fetchLotsFromRegionalPage(fallbackUrl, "https://canada.hibid.com", province) }
                .getOrDefault(emptyList())
                .ifEmpty {
                    if (province != CanadianProvince.ALL && fallbackQuery.isNotBlank()) {
                        val caUrl = "https://canada.hibid.com/lots?q=${AuctionHttpClient.urlEncode(fallbackQuery)}"
                        runCatching { fetchLotsFromRegionalPage(caUrl, "https://canada.hibid.com", province) }
                            .getOrDefault(emptyList())
                    } else {
                        emptyList()
                    }
                }
        }

        val latency = System.currentTimeMillis() - started
        if (finalLots.isNotEmpty()) {
            ConnectorResult(
                sourceId = sourceId,
                state = ConnectorState.CONNECTED,
                lots = finalLots,
                latencyMs = latency,
                statusDetail = "API HiBid en direct (${finalLots.size} lots)",
                directSearchUrl = directUrl
            )
        } else {
            ConnectorResult(
                sourceId = sourceId,
                state = ConnectorState.WEB_DIRECT,
                lots = emptyList(),
                latencyMs = latency,
                statusDetail = "Portail HiBid prêt — ouvrir la recherche directe",
                directSearchUrl = directUrl
            )
        }
    }

    private fun buildApiQueries(plan: SmartQueryPlan, category: AuctionCategory): List<String> {
        if (plan.apiQueries.isNotEmpty()) return plan.apiQueries
        val catTerm = when (category) {
            AuctionCategory.ALL -> ""
            AuctionCategory.VEHICLES -> "car"
            AuctionCategory.TOOLS_EQUIPMENT -> "tools"
            AuctionCategory.MACHINERY -> "tractor"
            AuctionCategory.ELECTRONICS -> "apple iphone"
            AuctionCategory.JEWELRY_COLLECTIBLES -> "gold"
            AuctionCategory.HOME_FURNITURE -> "furniture"
            AuctionCategory.STORAGE_LIQUIDATION -> "pallet"
        }
        return listOf(catTerm)
    }

    private fun buildSsrUrl(searchTerm: String, province: CanadianProvince, highToLow: Boolean): String {
        val base = if (province == CanadianProvince.ALL) {
            "https://canada.hibid.com/lots"
        } else {
            val slug = province.hibidSubdomain ?: "canada"
            if (slug == "canada") "https://canada.hibid.com/lots" else "https://hibid.com/$slug/lots"
        }
        if (searchTerm.isBlank()) return base
        val sort = if (highToLow) "&s=BID_AMOUNT_HIGH_TO_LOW" else ""
        return "$base?q=${AuctionHttpClient.urlEncode(searchTerm)}$sort"
    }

    private suspend fun fetchViaGraphQlSmart(
        apiQueries: List<String>,
        province: CanadianProvince,
        isDeviceSearch: Boolean,
        hasUserQuery: Boolean
    ): List<AuctionLot> = coroutineScope {
        val primarySort = when {
            isDeviceSearch -> "BID_AMOUNT_HIGH_TO_LOW"
            hasUserQuery -> "BID_AMOUNT_HIGH_TO_LOW"
            else -> "TIME_REMAINING"
        }

        // Run up to 2 smart query variants in parallel (e.g. "apple iphone" + "iphone", or "samsung galaxy" + "samsung")
        val tasks = mutableListOf<Pair<String, String>>()
        apiQueries.take(2).forEach { q ->
            tasks.add(q to primarySort)
        }
        // If only 1 query and it's a user search, also fetch TIME_REMAINING to capture fresh $0 opening-bid items
        if (tasks.size == 1 && hasUserQuery && primarySort == "BID_AMOUNT_HIGH_TO_LOW") {
            tasks.add(tasks.first().first to "TIME_REMAINING")
        }

        val batches = tasks.map { (q, sort) ->
            async(Dispatchers.IO) {
                runCatching {
                    executeGraphQlSearch(
                        searchText = q,
                        province = province,
                        sortOrder = sort,
                        pageLength = if (tasks.size > 1) 40 else 50
                    )
                }.getOrDefault(emptyList())
            }
        }.awaitAll()

        val merged = linkedMapOf<String, AuctionLot>()
        for (batch in batches) {
            for (lot in batch) {
                merged.putIfAbsent(lot.id, lot)
            }
        }
        merged.values.toList().take(80)
    }

    private fun executeGraphQlSearch(
        searchText: String,
        province: CanadianProvince,
        sortOrder: String,
        pageLength: Int
    ): List<AuctionLot> {
        val inputObj = JSONObject().apply {
            if (searchText.isNotBlank()) put("searchText", searchText)
            put("status", "OPEN")
            put("filter", "ALL")
            put("isArchive", false)
            put("countryName", "Canada")
            put("sortOrder", sortOrder)
            if (province != CanadianProvince.ALL) {
                put("state", province.code)
            }
        }

        val payload = JSONObject().apply {
            put("operationName", "LotSearch")
            put(
                "variables",
                JSONObject().apply {
                    put("input", inputObj)
                    put("pageNumber", 1)
                    put("pageLength", pageLength)
                }
            )
            put("query", LOT_SEARCH_QUERY)
        }.toString()

        val request = Request.Builder()
            .url(GRAPHQL_ENDPOINT)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
            )
            .header("Accept", "application/json")
            .header("Origin", "https://canada.hibid.com")
            .header("Referer", "https://canada.hibid.com/lots")
            .header("Site_Subdomain", "canada")
            .build()

        AuctionHttpClient.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return emptyList()

            val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
            val results = root
                .optJSONObject("data")
                ?.optJSONObject("lotSearch")
                ?.optJSONObject("pagedResults")
                ?.optJSONArray("results")
                ?: return emptyList()

            val emptyCache = JSONObject()
            val now = System.currentTimeMillis()
            val out = mutableListOf<AuctionLot>()
            for (i in 0 until results.length()) {
                val obj = results.optJSONObject(i) ?: continue
                parseHibidLotJson(obj, emptyCache, "https://canada.hibid.com", province, now)?.let {
                    out.add(it)
                }
            }
            return out
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

        val root = runCatching { JSONObject(rawState) }.getOrNull()
            ?: runCatching { JSONObject(Parser.unescapeEntities(rawState, false).trim()) }.getOrNull()
            ?: return emptyList()

        val apolloCache = findApolloCache(root, depth = 0) ?: root
        val collected = mutableListOf<AuctionLot>()
        val now = System.currentTimeMillis()
        val seenIds = mutableSetOf<String>()

        val keys = apolloCache.keys()
        while (keys.hasNext() && collected.size < 50) {
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
        return collected.take(50)
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
        if (out.size >= 50) return
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
        val bpRate = auctionObj?.optDouble("buyerPremiumRate")?.takeIf { !it.isNaN() && it >= 0.0 }
            ?: sourceId.defaultBuyersPremium

        val siteObj = resolveRef(obj.optJSONObject("site"), apolloCache)
        val siteSubdomain = siteObj?.optString("subdomain", "").orEmpty().trim().lowercase()
        val detectedProv = CanadianProvince.fromCodeOrText("$eventState $eventCity $siteSubdomain")
            .let { if (it == CanadianProvince.ALL) requestedProvince else it }

        val slug = title.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(60)
        val lotHost = if (siteSubdomain.isNotBlank() && siteSubdomain != "null") {
            "https://$siteSubdomain.hibid.com"
        } else {
            origin
        }
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
            currency = "CAD",
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
