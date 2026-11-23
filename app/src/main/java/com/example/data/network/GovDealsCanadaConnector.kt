package com.example.data.network

import com.example.model.AuctionCategory
import com.example.model.AuctionLot
import com.example.model.AuctionSourceId
import com.example.model.CanadianProvince
import com.example.model.ConnectorState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class GovDealsCanadaConnector : AuctionConnector {
    override val sourceId: AuctionSourceId = AuctionSourceId.GOVDEALS_CA

    private val baseOrigin = "https://www.govdeals.ca"

    override fun buildDirectSearchUrl(query: String, province: CanadianProvince): String {
        val params = mutableListOf<String>()
        if (query.isNotBlank()) params.add("kWord=${AuctionHttpClient.urlEncode(query)}")
        if (province != CanadianProvince.ALL) {
            params.add("state=${province.code}")
        }
        return if (params.isEmpty()) "$baseOrigin/en/search" else "$baseOrigin/en/search?${params.joinToString("&")}"
    }

    override suspend fun search(
        query: String,
        province: CanadianProvince,
        category: AuctionCategory
    ): ConnectorResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val directUrl = buildDirectSearchUrl(query, province)

        // 1) Primary strategy: Liquidity Services / GovDeals public search JSON API filtered to Canada
        val apiLots = runCatching { fetchFromMaestroApi(query, province) }.getOrNull().orEmpty()
        if (apiLots.isNotEmpty()) {
            return@withContext ConnectorResult(
                sourceId = sourceId,
                state = ConnectorState.CONNECTED,
                lots = apiLots,
                latencyMs = System.currentTimeMillis() - started,
                statusDetail = "API GovDeals Canada (${apiLots.size} lots)",
                directSearchUrl = directUrl
            )
        }

        // 2) Secondary strategy: HTML + JSON-LD from govdeals.ca
        val htmlLots = runCatching { fetchFromHtml(directUrl, province) }.getOrNull().orEmpty()
        val latency = System.currentTimeMillis() - started

        if (htmlLots.isNotEmpty()) {
            ConnectorResult(
                sourceId = sourceId,
                state = ConnectorState.CONNECTED,
                lots = htmlLots,
                latencyMs = latency,
                statusDetail = "Catalogue GovDeals (${htmlLots.size} lots)",
                directSearchUrl = directUrl
            )
        } else {
            ConnectorResult(
                sourceId = sourceId,
                state = ConnectorState.WEB_DIRECT,
                lots = emptyList(),
                latencyMs = latency,
                statusDetail = "Accès direct GovDeals.ca filtré (${province.displayNameFr})",
                directSearchUrl = directUrl
            )
        }
    }

    private fun fetchFromMaestroApi(query: String, province: CanadianProvince): List<AuctionLot> {
        val payload = JSONObject().apply {
            put("categoryIds", "")
            put("businessId", "GD")
            put("searchText", query.ifBlank { "*" })
            put("isQAL", false)
            put("locationId", JSONObject.NULL)
            put("model", "")
            put("makebrand", "")
            put("auctionTypeId", JSONObject.NULL)
            put("page", 1)
            put("displayRows", 24)
            put("sortField", "auctionenddate")
            put("sortOrder", "asc")
            put("sessionId", "")
            put("requestType", "search")
            put("responseStyle", "productsOnly")
            put("facets", JSONArray())
            put(
                "facetsFilter",
                JSONArray().apply {
                    put("country:\"Canada\"")
                    if (province != CanadianProvince.ALL) {
                        put("state:\"${province.code}\"")
                    }
                }
            )
            put("timeType", "")
            put("sellerTypeId", JSONObject.NULL)
            put("accountIds", JSONArray())
        }

        val request = Request.Builder()
            .url("https://maestro.lqdt1.com/search/list")
            .header("User-Agent", AuctionHttpClient.BROWSER_USER_AGENT)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("Origin", baseOrigin)
            .header("Referer", "$baseOrigin/")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        AuctionHttpClient.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string().orEmpty()
            if (body.isBlank() || !body.trimStart().startsWith("{")) return emptyList()

            val root = JSONObject(body)
            val results = root.optJSONArray("assetSearchResults") ?: return emptyList()
            val lots = mutableListOf<AuctionLot>()

            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val assetId = item.optString("assetId", "").ifBlank { continue }
                val accountId = item.optString("accountId", "")
                val title = item.optString("assetShortDescription", "").trim().ifBlank { continue }
                val desc = item.optString("assetLongDescription", "").trim().ifBlank { title }
                val bid = item.optDouble("currentBid").takeIf { !it.isNaN() && it >= 0.0 }
                val bidCount = item.optInt("bidCount", 0)
                val city = item.optString("locationCity", "").trim()
                val stateCode = item.optString("locationState", "").trim()
                val seller = item.optString("companyName", "").ifBlank { "GovDeals Canada" }

                val rawPhoto = item.optString("photo", "").trim()
                val imgUrl = when {
                    rawPhoto.startsWith("http", true) -> AuctionHttpClient.sanitizeHttpsUrl(rawPhoto)
                    rawPhoto.isNotBlank() -> AuctionHttpClient.sanitizeHttpsUrl("https://webassets.lqdt1.com/assets/photos/$rawPhoto")
                    else -> null
                }

                val endRaw = item.optString("assetAuctionEndDate", "").trim()
                val endMillis = parseIsoMillis(endRaw)
                val detectedProv = CanadianProvince.fromCodeOrText("$stateCode $city")
                    .let { if (it == CanadianProvince.ALL) province else it }

                val assetUrl = if (accountId.isNotBlank()) {
                    "$baseOrigin/en/asset/$assetId/$accountId"
                } else {
                    "$baseOrigin/en/asset/$assetId"
                }

                lots.add(
                    AuctionLot(
                        id = "GOVDEALS_$assetId",
                        sourceId = sourceId,
                        title = title.take(160),
                        description = desc.take(600),
                        currentBid = bid,
                        currency = item.optString("currencyCode", "CAD").ifBlank { "CAD" },
                        bidCount = bidCount,
                        imageUrl = imgUrl,
                        auctionUrl = assetUrl,
                        province = detectedProv,
                        city = if (city.isNotBlank()) "$city, ${detectedProv.code}" else detectedProv.displayNameFr,
                        auctionHouse = seller,
                        category = AuctionCategory.inferFromText("$title $desc"),
                        closingTimeMillis = endMillis,
                        closingLabel = endRaw.take(16).replace("T", " ").ifBlank { "En cours" },
                        lotNumber = assetId
                    )
                )
            }
            return lots
        }
    }

    private fun fetchFromHtml(url: String, province: CanadianProvince): List<AuctionLot> {
        val request = AuctionHttpClient.newGetRequest(url)
        AuctionHttpClient.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val html = response.body?.string().orEmpty()
            if (html.isBlank()) return emptyList()
            val doc = Jsoup.parse(html, baseOrigin)
            return AuctionHttpClient.parseJsonLdLots(
                doc = doc,
                sourceId = sourceId,
                baseOrigin = baseOrigin,
                defaultProvince = province,
                defaultCity = province.displayNameFr
            )
        }
    }

    private fun parseIsoMillis(raw: String): Long? {
        if (raw.isBlank()) return null
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss"
        )
        for (p in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(p, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(raw)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return null
    }
}
