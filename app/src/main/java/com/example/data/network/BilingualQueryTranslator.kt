package com.example.data.network

import java.text.Normalizer

/**
 * Translates French Canadian auction search terms to English equivalents for
 * English-indexed Canadian auction platforms (HiBid, GCSurplus, Michener Allen,
 * North Toronto Auction, McDougall, GovDeals, etc.) and provides bilingual
 * synonym expansion for local filtering.
 */
object BilingualQueryTranslator {

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
        "telephone" to "phone",
        "cellulaire" to "iphone",
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
        "cuisine" to "kitchen"
    )

    private val synonymGroups: List<Set<String>> = listOf(
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
        setOf("ordinateur", "portable", "laptop", "computer", "macbook", "thinkpad", "dell", "hp", "lenovo", "ipad", "tablet", "desktop", "pc"),
        setOf("bijou", "bijoux", "jewelry", "jewellery", "gold", "or", "10k", "14k", "18k", "ring", "bague", "necklace", "collier", "diamond", "diamant", "bracelet", "watch", "montre"),
        setOf("meuble", "meubles", "furniture", "sofa", "canape", "divan", "table", "chair", "chaise", "cabinet", "armoire", "dresser", "desk", "bureau"),
        setOf("moto", "motocyclette", "motorcycle", "harley", "yamaha", "kawasaki", "suzuki", "ducati", "ktm", "vtt", "atv", "quad", "motoneige", "snowmobile", "ski-doo", "polaris"),
        setOf("bateau", "bateaux", "boat", "boats", "marine", "pontoon", "kayak", "seadoo", "jet ski"),
        setOf("roulotte", "vr", "rv", "camper", "campeur", "motorhome", "fifth wheel", "travel trailer"),
        setOf("velo", "bicyclette", "bike", "bicycle", "ebike", "e-bike"),
        setOf("entrepot", "casier", "storage", "locker", "unit", "pallet", "palette", "liquidation")
    )

    fun normalize(input: String): String {
        val decomposed = Normalizer.normalize(input.trim().lowercase(), Normalizer.Form.NFD)
        return decomposed.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
    }

    /**
     * Converts a French (or mixed) query to the best English search string
     * for English-indexed Canadian auction sites.
     */
    fun toEnglishSearchQuery(rawQuery: String): String {
        val trimmed = rawQuery.trim()
        if (trimmed.isBlank()) return ""
        val tokens = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
        val translated = tokens.map { token ->
            val norm = normalize(token)
            frToEnApiTerm[norm] ?: token
        }
        return translated.joinToString(" ")
    }

    /**
     * Returns true if the query was translated (so the UI can inform the user).
     */
    fun translationHint(rawQuery: String): String? {
        val trimmed = rawQuery.trim()
        if (trimmed.isBlank()) return null
        val english = toEnglishSearchQuery(trimmed)
        return if (!normalize(english).equals(normalize(trimmed), ignoreCase = true)) {
            english
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
        val tokens = norm.split(Regex("\\s+")).filter { it.length >= 2 }
        val expanded = mutableSetOf<String>()
        for (tok in tokens) {
            expanded.add(tok)
            frToEnApiTerm[tok]?.let { expanded.add(it) }
            synonymGroups.forEach { group ->
                if (tok in group) expanded.addAll(group)
            }
        }
        return expanded
    }
}
