package com.example.data.network

import com.example.model.AuctionCategory
import com.example.model.AuctionLot
import com.example.model.AuctionSourceId
import com.example.model.CanadianProvince
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

object AuctionHttpClient {
    const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Dedicated OkHttpClient for Coil image loading so CDNs (cdn.hibid.com,
     * CloudFront, gcsurplus.ca, mcdougallauction.com) receive browser headers
     * and never block image requests with 403/hotlink denials.
     */
    val imageClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .header("Accept", "image/avif,image/webp,image/apng,image/jpeg,image/*,*/*;q=0.8")
                    .header("Accept-Language", "fr-CA,fr;q=0.9,en-CA;q=0.8")
                    .build()
                chain.proceed(req)
            }
            .build()
    }

    fun newGetRequest(url: String, extraHeaders: Map<String, String> = emptyMap()): Request {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,application/json;q=0.8,*/*;q=0.7")
            .header("Accept-Language", "en-CA,en;q=0.9,fr-CA;q=0.8,fr;q=0.7")
        extraHeaders.forEach { (k, v) -> builder.header(k, v) }
        return builder.build()
    }

    fun urlEncode(value: String): String =
        URLEncoder.encode(value.trim(), StandardCharsets.UTF_8.name())

    fun sanitizeHttpsUrl(rawUrl: String?, baseOrigin: String? = null): String? {
        if (rawUrl.isNullOrBlank()) return null
        val trimmed = rawUrl.trim()
            .replace(" ", "%20")
            .replace("{", "%7B")
            .replace("}", "%7D")
        val resolved = when {
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("http://", ignoreCase = true) -> "https://" + trimmed.removePrefix("http://")
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") && !baseOrigin.isNullOrBlank() ->
                baseOrigin.trimEnd('/') + trimmed
            !baseOrigin.isNullOrBlank() &&
                !trimmed.contains("://") &&
                !trimmed.startsWith("javascript:", true) &&
                !trimmed.startsWith("mailto:", true) &&
                !trimmed.startsWith("data:", true) ->
                baseOrigin.trimEnd('/') + "/" + trimmed.trimStart('/')
            else -> return null
        }
        return runCatching {
            val uri = URI(resolved)
            if (uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
                uri.toString()
            } else {
                null
            }
        }.getOrNull()
    }

    /**
     * Validates and normalizes a scraped photo URL, filtering out non-photo UI
     * icons (logos, open-bidding.gif, spacers, favicons, SVGs, data URIs).
     */
    fun sanitizeImageUrl(rawUrl: String?, baseOrigin: String? = null): String? {
        val clean = sanitizeHttpsUrl(rawUrl, baseOrigin) ?: return null
        val lower = clean.lowercase()
        val blockedFragments = listOf(
            "open-bidding.gif",
            "mini-logo",
            "logo.png",
            "logo.svg",
            "logo.gif",
            "favicon",
            "spacer",
            "blank.gif",
            "placeholder",
            "no-image",
            "noimage",
            "1x1",
            ".svg",
            "spinner",
            "loading.gif",
            "icon-"
        )
        if (blockedFragments.any { lower.contains(it) }) return null
        return clean
    }

    fun extractPrice(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        val normalized = raw.replace("\u00A0", " ")
        val regex = Regex("""(?:CA?\$|\$|CAD|USD)\s*(\d{1,3}(?:[,\s]\d{3})*(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?)""")
        val match = regex.find(normalized)
            ?: Regex("""(\d{1,3}(?:,\d{3})*(?:\.\d{2}))""").find(normalized)
            ?: return null
        var num = match.groupValues[1].replace(" ", "")
        if (num.contains(',') && num.contains('.')) {
            num = num.replace(",", "")
        } else if (num.contains(',')) {
            val parts = num.split(',')
            num = if (parts.last().length == 2) num.replace(",", ".") else num.replace(",", "")
        }
        return num.toDoubleOrNull()?.takeIf { it > 0.0 && it < 50_000_000.0 }
    }

    fun extractInt(raw: String?): Int {
        if (raw.isNullOrBlank()) return 0
        val match = Regex("""\d+""").find(raw) ?: return 0
        return match.value.toIntOrNull() ?: 0
    }

    fun parseJsonLdLots(
        doc: Document,
        sourceId: AuctionSourceId,
        baseOrigin: String,
        defaultProvince: CanadianProvince,
        defaultCity: String
    ): List<AuctionLot> {
        val lots = mutableListOf<AuctionLot>()
        val scripts = doc.select("script[type=application/ld+json]")
        for (script in scripts) {
            val content = script.data().trim()
            if (content.isBlank()) continue
            runCatching {
                if (content.startsWith("[")) {
                    val arr = JSONArray(content)
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let {
                            extractLotsFromJsonLdNode(it, sourceId, baseOrigin, defaultProvince, defaultCity, lots)
                        }
                    }
                } else if (content.startsWith("{")) {
                    extractLotsFromJsonLdNode(JSONObject(content), sourceId, baseOrigin, defaultProvince, defaultCity, lots)
                }
            }
        }
        return lots.distinctBy { it.auctionUrl }
    }

    private fun extractLotsFromJsonLdNode(
        node: JSONObject,
        sourceId: AuctionSourceId,
        baseOrigin: String,
        defaultProvince: CanadianProvince,
        defaultCity: String,
        out: MutableList<AuctionLot>
    ) {
        node.optJSONArray("@graph")?.let { graph ->
            for (i in 0 until graph.length()) {
                graph.optJSONObject(i)?.let {
                    extractLotsFromJsonLdNode(it, sourceId, baseOrigin, defaultProvince, defaultCity, out)
                }
            }
        }

        val type = node.optString("@type", "").lowercase()
        if (type == "itemlist" || node.has("itemListElement")) {
            val items = node.optJSONArray("itemListElement")
            if (items != null) {
                for (i in 0 until items.length()) {
                    val elem = items.optJSONObject(i) ?: continue
                    val target = elem.optJSONObject("item") ?: elem
                    extractLotsFromJsonLdNode(target, sourceId, baseOrigin, defaultProvince, defaultCity, out)
                }
            }
            return
        }

        if (type.contains("product") || type.contains("vehicle") || type.contains("offer") || type.contains("event")) {
            val name = node.optString("name", "").trim()
            val url = sanitizeHttpsUrl(node.optString("url", ""), baseOrigin)
            if (name.length >= 4 && url != null) {
                val desc = node.optString("description", "").trim().ifBlank { name }
                val imageRaw = when (val imgObj = node.opt("image")) {
                    is String -> imgObj
                    is JSONArray -> imgObj.optString(0, "")
                    is JSONObject -> imgObj.optString("url", "")
                    else -> ""
                }
                val imageUrl = sanitizeImageUrl(imageRaw, baseOrigin)
                val offers = node.optJSONObject("offers") ?: node.optJSONArray("offers")?.optJSONObject(0)
                val price = offers?.optDouble("price")?.takeIf { !it.isNaN() && it > 0.0 }
                    ?: extractPrice(offers?.optString("price", ""))
                    ?: extractPrice(offers?.optString("lowPrice", ""))
                val locObj = node.optJSONObject("location")
                val address = locObj?.optJSONObject("address")
                val stateText = address?.optString("addressRegion", "").orEmpty()
                val cityText = address?.optString("addressLocality", "").orEmpty()
                    .ifBlank { locObj?.optString("name", "").orEmpty() }
                    .ifBlank { defaultCity }
                val detectedProv = CanadianProvince.fromCodeOrText("$stateText $cityText $name $desc")
                    .let { if (it == CanadianProvince.ALL) defaultProvince else it }

                out.add(
                    AuctionLot(
                        id = "${sourceId.name}_${url.hashCode()}",
                        sourceId = sourceId,
                        title = name.take(160),
                        description = desc.take(500),
                        currentBid = price,
                        currency = offers?.optString("priceCurrency", "CAD")?.ifBlank { "CAD" } ?: "CAD",
                        bidCount = 0,
                        imageUrl = imageUrl,
                        auctionUrl = url,
                        province = detectedProv,
                        city = cityText.ifBlank { detectedProv.displayNameFr },
                        auctionHouse = sourceId.displayName,
                        category = AuctionCategory.inferFromText("$name $desc"),
                        closingLabel = offers?.optString("priceValidUntil", "").orEmpty()
                            .ifBlank { node.optString("endDate", "").take(16) }
                            .ifBlank { "En cours" }
                    )
                )
            }
        }
    }
}
