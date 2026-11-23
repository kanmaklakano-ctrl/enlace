package com.example.data.network

import com.example.model.AuctionLot
import java.text.Normalizer

/**
 * Structured analysis of a user's French/English auction search query.
 */
data class SmartQueryPlan(
    val rawQuery: String,
    val cleanedQuery: String,
    val englishQuery: String,
    val apiQueries: List<String>,
    val expandedTerms: Set<String>,
    val wantsBulkLot: Boolean,
    val isDeviceSearch: Boolean,
    val explicitlyWantsAccessory: Boolean
) {
    val shouldFilterAccessoriesByDefault: Boolean
        get() = isDeviceSearch && !explicitlyWantsAccessory
}

/**
 * Smart bilingual search engine for Canadian auction platforms:
 * - Strips French/English auction filler words ("lot", "enchere", "encan", "de", "du", etc.)
 *   so queries like "lot iphone" or "enchere samsung" never fail exact remote search.
 * - Translates French terms to English for English-indexed platforms.
 * - Generates high-precision multi-variant API queries (e.g., "iphone" -> ["apple iphone", "iphone"],
 *   "samsung" -> ["samsung galaxy", "samsung"]) so real devices surface ahead of $1 accessories.
 * - Detects accessory spam (cases, screen protectors, cables, chargers, lens covers, mounts)
 *   and scores lot relevance so real products appear first.
 */
object BilingualQueryTranslator {

    private val auctionFillerWords: Set<String> = setOf(
        "lot", "lots", "enchere", "encheres", "encan", "encans", "auction", "auctions",
        "vente", "ventes", "de", "du", "des", "le", "la", "les", "un", "une",
        "en", "au", "aux", "pour", "avec", "sur", "dans", "pas", "cher", "canada",
        "live", "direct", "online", "ligne", "acheter", "trouver", "cherche"
    )

    private val bulkIntentWords: Set<String> = setOf(
        "lot", "lots", "palette", "palettes", "pallet", "pallets", "bulk", "gros",
        "caisse", "boite", "box", "bundle", "revendeur", "reseller", "liquidation", "wholesale"
    )

    private val deviceSearchRoots: Set<String> = setOf(
        "iphone", "samsung", "galaxy", "telephone", "telephones", "cellulaire", "cellulaires",
        "smartphone", "smartphones", "phone", "phones", "pixel", "ipad", "tablet", "tablette",
        "macbook", "imac", "laptop", "ordinateur", "portable", "airpods", "watch", "applewatch",
        "smartwatch", "ps5", "ps4", "playstation", "xbox", "nintendo", "switch", "gopro",
        "dji", "drone", "camera", "tv", "tele", "televiseur", "motorola", "oneplus", "xiaomi"
    )

    private val explicitAccessoryTokens: Set<String> = setOf(
        "case", "cases", "coque", "coques", "etui", "etuis", "housse", "cover", "bumper",
        "protector", "protecteur", "vitre", "verre", "glass", "tempered", "film", "screen",
        "charger", "chargeur", "cable", "cables", "cord", "adapter", "adaptateur",
        "mount", "support", "holder", "stand", "tripod", "holster", "clip", "strap", "band",
        "bracelet", "battery", "batterie", "parts", "piece", "pieces", "otterbox", "spigen",
        "wallet", "pouch", "dock", "stylus", "pen", "skin", "decal", "lens", "ring"
    )

    private val accessoryTitlePatterns: List<Regex> = listOf(
        Regex("""\b(phone\s*case|protective\s*case|wallet\s*case|magsafe\s*case|defender\s*series|silicone\s*case|clear\s*case|leather\s*case|rugged\s*case|folio\s*case|waterproof\s*case|bumper\s*case|carrying\s*case|hard\s*case|soft\s*case|hybrid\s*case|otterbox|spigen|caseology|lifeproof|supcase|uag\s*case|casetify|pelican\s*case)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(case|cover|coque|etui)\s+(for|pour|compatible|fits?)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(designed\s+for|compatible\s+with|fits?\s+for|made\s+for|replacement\s+for|works\s+with)\s+(apple\s+)?(iphone|samsung|galaxy|ipad|macbook|pixel|airpods|watch)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bfor\s+(apple\s+)?(iphone|samsung|galaxy|ipad|macbook|pixel|airpods)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(screen\s*protector|tempered\s*glass|privacy\s*screen|camera\s*lens\s*protector|lens\s*protector|hydrogel\s*film|glass\s*protector)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(charging\s*cable|usb[\-\s]?c\s*cable|lightning\s*cable|charger\s*block|wall\s*charger|car\s*charger|wireless\s*charger|charging\s*pad|charging\s*station|charging\s*dock|power\s*bank|gan\s*charger|magsafe\s*charger|adapter|carplay\s*adapter|dongle|flash\s*drive|portable\s*ssd|nvme|m\.2\s*ssd|sd\s*card|memory\s*card)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(car\s*mount|phone\s*mount|vent\s*mount|bike\s*mount|phone\s*holder|desk\s*stand|phone\s*stand|selfie\s*stick|tripod\s*mount|belt\s*clip|holster|armband|lanyard|popsocket|kickstand|watch\s*band|watch\s*strap|silicone\s*band|replacement\s*band|ear\s*tips|remote\s*control|replacement\s*remote)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(case|cover)\b.*\b(iphone|samsung|galaxy|ipad|pixel)\b|\b(iphone|samsung|galaxy|ipad|pixel)\b.*\b(case|cover|protector|charger|cable|mount|holder|stand|holster|skin|decal|band|strap|screen\s*only|back\s*glass|digitizer|lcd\s*replacement)\b""", RegexOption.IGNORE_CASE)
    )

    private val realDeviceSignalPatterns: List<Regex> = listOf(
        Regex("""\b(32|64|128|256|512)\s*gb\b|\b[12]\s*tb\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(unlocked|deverrouille|factory\s*unlocked|sim\s*free|esim|5g|lte|wifi|cellular|renewed|refurbished|reconditionne|sealed|brand\s*new|imei|ios|android)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(iphone\s*(?:1[1-9]|[6-9]|x|xr|xs|se)|galaxy\s*(?:s2[0-9]|s10|z\s*fold|z\s*flip|a[0-9]{2}|note|tab\s*s|watch)|pixel\s*[4-9]|macbook\s*(?:air|pro)|ipad\s*(?:pro|air|mini))\b""", RegexOption.IGNORE_CASE)
    )

    private val frToEnApiTerm: Map<String, String> = mapOf(
        "voiture" to "car",
        "voitures" to "car",
        "auto" to "car",
        "autos" to "car",
        "automobile" to "car",
        "vehicule" to "vehicle",
        "vehicules" to "vehicle",
        "char" to "car",
        "camion" to "truck",
        "camions" to "truck",
        "camionnette" to "pickup",
        "fourgonnette" to "van",
        "fourgon" to "van",
        "vus" to "suv",
        "remorque" to "trailer",
        "remorques" to "trailer",
        "moto" to "motorcycle",
        "motocyclette" to "motorcycle",
        "motoneige" to "snowmobile",
        "skidoo" to "snowmobile",
        "vtt" to "atv",
        "quad" to "atv",
        "bateau" to "boat",
        "bateaux" to "boat",
        "roulotte" to "rv",
        "vr" to "rv",
        "campeur" to "camper",
        "tracteur" to "tractor",
        "tracteurs" to "tractor",
        "excavatrice" to "excavator",
        "pelle" to "excavator",
        "chargeuse" to "loader",
        "chariot" to "forklift",
        "elevateur" to "forklift",
        "machinerie" to "equipment",
        "outil" to "tool",
        "outils" to "tools",
        "perceuse" to "drill",
        "scie" to "saw",
        "compresseur" to "compressor",
        "generatrice" to "generator",
        "soudeuse" to "welder",
        "souffleuse" to "snowblower",
        "tondeuse" to "mower",
        "ordinateur" to "laptop",
        "portable" to "laptop",
        "ecran" to "monitor",
        "imprimante" to "printer",
        "telephone" to "smartphone",
        "telephones" to "smartphone",
        "cellulaire" to "iphone",
        "cellulaires" to "iphone",
        "tablette" to "ipad",
        "ecouteurs" to "airpods",
        "tele" to "tv",
        "televiseur" to "tv",
        "camera" to "camera",
        "appareil" to "camera",
        "velo" to "bike",
        "bicyclette" to "bike",
        "bijou" to "jewelry",
        "bijoux" to "jewelry",
        "bague" to "ring",
        "montre" to "watch",
        "montres" to "watch",
        "collier" to "necklace",
        "diamant" to "diamond",
        "or" to "gold",
        "argent" to "silver",
        "monnaie" to "coin",
        "piece" to "coin",
        "meuble" to "furniture",
        "meubles" to "furniture",
        "canape" to "sofa",
        "divan" to "sofa",
        "chaise" to "chair",
        "table" to "table",
        "armoire" to "cabinet",
        "frigo" to "fridge",
        "refrigerateur" to "refrigerator",
        "laveuse" to "washer",
        "secheuse" to "dryer",
        "cuisiniere" to "stove",
        "entrepot" to "storage",
        "casier" to "locker",
        "palette" to "pallet",
        "palettes" to "pallet",
        "pneu" to "tire",
        "pneus" to "tires",
        "jante" to "wheel",
        "jantes" to "wheels",
        "bois" to "lumber",
        "acier" to "steel",
        "terrain" to "land",
        "maison" to "house",
        "bureau" to "desk",
        "restaurant" to "restaurant",
        "cuisine" to "kitchen",
        "electronique" to "electronics"
    )

    private val synonymGroups: List<Set<String>> = listOf(
        setOf(
            "iphone", "apple", "ios", "smartphone", "cellulaire", "telephone", "phone", "cell", "mobile"
        ),
        setOf(
            "samsung", "galaxy", "fold", "flip", "smartphone", "android", "telephone", "phone"
        ),
        setOf(
            "telephone", "telephones", "cellulaire", "cellulaires", "smartphone", "smartphones",
            "phone", "iphone", "samsung", "galaxy", "pixel", "motorola", "oneplus", "xiaomi"
        ),
        setOf(
            "voiture", "voitures", "auto", "autos", "automobile", "vehicule", "vehicules", "char",
            "car", "cars", "vehicle", "vehicles", "sedan", "coupe", "suv", "vus", "hatchback",
            "wagon", "minivan", "crossover", "convertible",
            "ford", "toyota", "honda", "hyundai", "kia", "chevrolet", "chevy", "gmc", "dodge",
            "ram", "jeep", "nissan", "mazda", "subaru", "volkswagen", "vw", "bmw", "mercedes",
            "audi", "lexus", "acura", "volvo", "chrysler", "buick", "cadillac", "tesla", "mitsubishi",
            "truck", "camion", "pickup", "van"
        ),
        setOf("camion", "camions", "camionnette", "truck", "trucks", "pickup", "f-150", "f150", "f-250", "f-350", "f-450", "silverado", "sierra", "ram", "tacoma", "tundra", "dump", "semi", "freightliner", "kenworth", "peterbilt"),
        setOf("remorque", "remorques", "trailer", "trailers", "flatbed", "enclosed"),
        setOf("tracteur", "tracteurs", "tractor", "tractors", "john deere", "kubota", "massey", "case", "new holland", "skid steer", "bobcat"),
        setOf("outil", "outils", "tool", "tools", "dewalt", "milwaukee", "makita", "ryobi", "bosch", "snap-on", "craftsman", "drill", "saw", "wrench", "impact", "perceuse", "scie"),
        setOf("generatrice", "generator", "generators", "honda eu", "champ", "champion", "onan", "stamford"),
        setOf("ordinateur", "portable", "laptop", "computer", "macbook", "imac", "thinkpad", "dell", "hp", "lenovo", "ipad", "tablet", "tablette", "desktop", "pc"),
        setOf("bijou", "bijoux", "jewelry", "jewellery", "gold", "or", "10k", "14k", "18k", "ring", "bague", "necklace", "collier", "diamond", "diamant", "bracelet", "watch", "montre", "rolex", "omega"),
        setOf("meuble", "meubles", "furniture", "sofa", "canape", "divan", "table", "chair", "chaise", "cabinet", "armoire", "dresser", "desk", "bureau"),
        setOf("moto", "motocyclette", "motorcycle", "harley", "yamaha", "kawasaki", "suzuki", "ducati", "ktm", "vtt", "atv", "quad", "motoneige", "snowmobile", "ski-doo", "polaris"),
        setOf("bateau", "bateaux", "boat", "boats", "marine", "pontoon", "kayak", "seadoo", "jet ski"),
        setOf("roulotte", "vr", "rv", "camper", "campeur", "motorhome", "fifth wheel", "travel trailer"),
        setOf("velo", "bicyclette", "bike", "bicycle", "ebike", "e-bike"),
        setOf("entrepot", "casier", "storage", "locker", "unit", "pallet", "palette", "palettes", "liquidation", "freight"),
        setOf("console", "ps5", "ps4", "playstation", "xbox", "nintendo", "switch")
    )

    fun normalize(input: String): String {
        val decomposed = Normalizer.normalize(input.trim().lowercase(), Normalizer.Form.NFD)
        return decomposed.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
    }

    /**
     * Full smart analysis of the user's query: strips filler words like "lot" / "enchere",
     * translates FR -> EN, builds precision API query variants, and determines device/accessory intent.
     */
    fun analyzeQuery(rawQuery: String): SmartQueryPlan {
        val trimmed = rawQuery.trim()
        if (trimmed.isBlank()) {
            return SmartQueryPlan(
                rawQuery = "",
                cleanedQuery = "",
                englishQuery = "",
                apiQueries = emptyList(),
                expandedTerms = emptySet(),
                wantsBulkLot = false,
                isDeviceSearch = false,
                explicitlyWantsAccessory = false
            )
        }

        val rawTokens = trimmed
            .replace("'", " ")
            .replace("’", " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        val normTokens = rawTokens.map { normalize(it) }
        val wantsBulk = normTokens.any { it in bulkIntentWords }
        val explicitlyWantsAccessory = normTokens.any { it in explicitAccessoryTokens }

        // Keep meaningful tokens; only fall back to rawTokens if every token was a filler word (e.g. user searched just "lot")
        val meaningfulPairs = rawTokens.zip(normTokens).filter { (_, norm) ->
            norm !in auctionFillerWords
        }
        val activePairs = if (meaningfulPairs.isNotEmpty()) meaningfulPairs else rawTokens.zip(normTokens)

        val cleanedQuery = activePairs.joinToString(" ") { it.first }
        val englishTokens = activePairs.map { (orig, norm) -> frToEnApiTerm[norm] ?: orig }
        val englishQuery = englishTokens.joinToString(" ")
        val englishNorm = normalize(englishQuery)
        val activeNormTokens = activePairs.map { it.second }.toSet()

        val isDeviceSearch = activeNormTokens.any { it in deviceSearchRoots } ||
            englishNorm.contains("iphone") ||
            englishNorm.contains("samsung") ||
            englishNorm.contains("galaxy") ||
            englishNorm.contains("smartphone") ||
            englishNorm.contains("macbook") ||
            englishNorm.contains("ipad") ||
            englishNorm.contains("pixel")

        val apiQueries = buildSmartApiQueries(englishQuery, englishNorm, explicitlyWantsAccessory)
        val expandedTerms = expandMatchingTerms(cleanedQuery)

        return SmartQueryPlan(
            rawQuery = trimmed,
            cleanedQuery = cleanedQuery,
            englishQuery = englishQuery,
            apiQueries = apiQueries,
            expandedTerms = expandedTerms,
            wantsBulkLot = wantsBulk,
            isDeviceSearch = isDeviceSearch,
            explicitlyWantsAccessory = explicitlyWantsAccessory
        )
    }

    private fun buildSmartApiQueries(
        englishQuery: String,
        englishNorm: String,
        explicitlyWantsAccessory: Boolean
    ): List<String> {
        if (englishQuery.isBlank()) return emptyList()
        if (explicitlyWantsAccessory) return listOf(englishQuery)

        val out = linkedSetOf<String>()
        when {
            englishNorm == "iphone" -> {
                out.add("apple iphone")
                out.add("iphone")
            }
            englishNorm.startsWith("iphone ") -> {
                out.add("apple $englishQuery")
                out.add(englishQuery)
            }
            englishNorm == "samsung" -> {
                out.add("samsung galaxy")
                out.add("samsung")
            }
            englishNorm == "galaxy" -> {
                out.add("samsung galaxy")
            }
            englishNorm in setOf("smartphone", "phone", "cell phone", "mobile") -> {
                out.add("apple iphone")
                out.add("samsung galaxy")
                out.add("unlocked phone")
            }
            englishNorm == "ipad" -> {
                out.add("apple ipad")
                out.add("ipad")
            }
            englishNorm == "macbook" -> {
                out.add("apple macbook")
                out.add("macbook")
            }
            englishNorm == "pixel" -> {
                out.add("google pixel")
                out.add("pixel")
            }
            englishNorm == "ps5" -> {
                out.add("playstation 5")
                out.add("ps5")
            }
            englishNorm == "car" -> {
                out.add("car")
                out.add("suv")
            }
            else -> {
                out.add(englishQuery)
            }
        }
        return out.toList()
    }

    /**
     * Converts a French (or mixed) query to the best cleaned English search string
     * for English-indexed Canadian auction sites.
     */
    fun toEnglishSearchQuery(rawQuery: String): String = analyzeQuery(rawQuery).englishQuery

    /**
     * Returns a smart hint string whenever the search engine cleaned filler words
     * or translated French terms (so the UI shows transparency).
     */
    fun translationHint(rawQuery: String): String? {
        val plan = analyzeQuery(rawQuery)
        if (plan.rawQuery.isBlank()) return null
        val primaryApi = plan.apiQueries.firstOrNull() ?: plan.englishQuery
        return if (!normalize(primaryApi).equals(normalize(plan.rawQuery), ignoreCase = true)) {
            if (plan.apiQueries.size > 1 && plan.isDeviceSearch) {
                plan.apiQueries.take(2).joinToString(" + ")
            } else {
                primaryApi
            }
        } else {
            null
        }
    }

    /**
     * Expands a query into all normalized tokens + bilingual synonyms for local matching.
     */
    fun expandMatchingTerms(rawQuery: String): Set<String> {
        val norm = normalize(rawQuery)
        if (norm.isBlank()) return emptySet()
        val rawTokens = norm.replace("'", " ").split(Regex("\\s+")).filter { it.length >= 2 }
        val filtered = rawTokens.filter { it !in auctionFillerWords }.ifEmpty { rawTokens }
        val expanded = mutableSetOf<String>()
        for (tok in filtered) {
            expanded.add(tok)
            frToEnApiTerm[tok]?.let { expanded.add(it) }
            synonymGroups.forEach { group ->
                if (tok in group) expanded.addAll(group)
            }
        }
        return expanded
    }

    /**
     * Returns true if a lot is very likely an accessory (case, screen protector, cable,
     * charger, mount, SSD, CarPlay adapter, etc.) rather than the actual device searched.
     */
    fun isLikelyAccessory(lot: AuctionLot, plan: SmartQueryPlan): Boolean {
        if (!plan.isDeviceSearch || plan.explicitlyWantsAccessory) return false
        val titleNorm = normalize(lot.title)
        val descNorm = normalize(lot.description.take(220))
        val combined = "$titleNorm $descNorm"

        // If title explicitly matches an accessory pattern, flag it (unless it's clearly a real unlocked GB phone with a free case)
        val titleHitsAccessory = accessoryTitlePatterns.any { it.containsMatchIn(titleNorm) }
        if (titleHitsAccessory) {
            val clearlyRealPhoneWithSpec = Regex("""\b(iphone|galaxy|pixel|ipad|macbook)\b.*\b(64|128|256|512)\s*gb\b""", RegexOption.IGNORE_CASE)
                .containsMatchIn(titleNorm) && !titleNorm.contains("flash drive") && !titleNorm.contains("ssd")
            if (!clearlyRealPhoneWithSpec) return true
        }

        // For Samsung searches, also flag non-phone appliances/SSDs/monitors ONLY when user asked for phone/smartphone
        val askedForPhone = plan.rawQuery.lowercase().let {
            it.contains("phone") || it.contains("telephone") || it.contains("cellulaire") || it.contains("galaxy")
        }
        if (askedForPhone) {
            val nonPhoneAppliance = Regex("""\b(washer|dryer|dishwasher|microwave|fridge|refrigerator|stove|range|oven|vacuum|toner|cartridge|printer|monitor|tv|oled|qled)\b""", RegexOption.IGNORE_CASE)
                .containsMatchIn(titleNorm)
            if (nonPhoneAppliance) return true
        }

        // Very low price (< $12) on HiBid for a device query without GB/unlocked specs and containing generic accessory cues
        if ((lot.currentBid ?: 0.0) in 0.01..12.0 &&
            !realDeviceSignalPatterns.any { it.containsMatchIn(titleNorm) } &&
            Regex("""\b(pack|2-pack|3-pack|case|cover|glass|film|cable|charger|mount|stand|holder|adapter|strap|band|kit|protector|replacement|skin|sleeve)\b""", RegexOption.IGNORE_CASE)
                .containsMatchIn(combined)
        ) {
            return true
        }

        return false
    }

    /**
     * Computes a smart relevance score so real matching devices/items appear at the top.
     */
    fun scoreRelevance(lot: AuctionLot, plan: SmartQueryPlan): Int {
        var score = 0
        val titleNorm = normalize(lot.title)
        val descNorm = normalize(lot.description.take(300))

        if (!lot.imageUrl.isNullOrBlank()) score += 35

        if (plan.englishQuery.isNotBlank()) {
            val engNorm = normalize(plan.englishQuery)
            val primaryApiNorm = normalize(plan.apiQueries.firstOrNull().orEmpty())

            if (primaryApiNorm.isNotBlank() && titleNorm.contains(primaryApiNorm)) score += 130
            if (titleNorm.contains(engNorm)) score += 95

            val coreTokens = engNorm.split(Regex("\\s+")).filter { it.length >= 2 }
            val matchedInTitle = coreTokens.count { titleNorm.contains(it) }
            score += matchedInTitle * 40
            if (coreTokens.isNotEmpty() && matchedInTitle == coreTokens.size) score += 50
            if (descNorm.contains(engNorm)) score += 20
        }

        if (plan.isDeviceSearch) {
            val hasDeviceSpec = realDeviceSignalPatterns.any { it.containsMatchIn(titleNorm) }
            if (hasDeviceSpec) score += 110
            if (isLikelyAccessory(lot, plan)) {
                score -= 320
            }
            val bid = lot.currentBid ?: 0.0
            when {
                bid >= 100.0 -> score += 55
                bid >= 35.0 -> score += 30
                bid in 0.01..5.0 -> score -= 40
            }
        }

        if (plan.wantsBulkLot) {
            if (Regex("""\b(lot\s+of|pallet|bulk|box\s+of|bundle|x[2-9]|x\d{2}|\d+\s*pcs?|\d+\s*units?|reseller|wholesale)\b""", RegexOption.IGNORE_CASE)
                    .containsMatchIn("$titleNorm $descNorm")
            ) {
                score += 65
            }
        }

        if (lot.bidCount > 0) score += minOf(lot.bidCount * 2, 24)
        return score
    }
}
