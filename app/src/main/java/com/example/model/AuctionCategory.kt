package com.example.model

enum class AuctionCategory(
    val displayNameFr: String,
    val searchKeywords: List<String>
) {
    ALL("Toutes", emptyList()),
    VEHICLES(
        "Véhicules",
        listOf("ford", "toyota", "honda", "chevrolet", "gmc", "ram", "dodge", "nissan", "car", "truck", "suv", "van", "auto", "vehicule", "véhicule", "camion", "voiture", "trailer", "remorque", "motorcycle", "moto", "boat", "bateau", "atv", "vtt")
    ),
    TOOLS_EQUIPMENT(
        "Outils & Atelier",
        listOf("dewalt", "milwaukee", "makita", "tool", "outil", "saw", "drill", "compressor", "generator", "génératrice", "welder", "soudure", "wrench", "toolbox", "atelier", "snap-on", "ryobi")
    ),
    MACHINERY(
        "Machinerie",
        listOf("tractor", "tracteur", "excavator", "skid steer", "forklift", "chariot", "john deere", "kubota", "caterpillar", "cat ", "bobcat", "loader", "machinery", "machinerie", "farm", "agricole", "plow", "snowblower")
    ),
    ELECTRONICS(
        "Électronique",
        listOf("apple", "iphone", "ipad", "macbook", "laptop", "computer", "ordinateur", "samsung", "sony", "camera", "tv", "television", "console", "playstation", "xbox", "nintendo", "monitor", "printer", "audio", "speaker")
    ),
    JEWELRY_COLLECTIBLES(
        "Bijoux & Collection",
        listOf("gold", "or ", "14k", "18k", "10k", "silver", "argent", "diamond", "diamant", "ring", "bague", "watch", "montre", "rolex", "coin", "pièce", "monnaie", "art", "painting", "antique", "vintage", "collectible", "card", "hockey")
    ),
    HOME_FURNITURE(
        "Maison & Meubles",
        listOf("furniture", "meuble", "sofa", "table", "chair", "chaise", "cabinet", "kitchen", "appliance", "fridge", "washer", "dryer", "bed", "lamp", "rug", "tapis", "decor", "patio", "estate")
    ),
    STORAGE_LIQUIDATION(
        "Entrepôts & Lots",
        listOf("storage", "locker", "unit", "entrepôt", "entrepot", "pallet", "palette", "liquidation", "surplus", "bulk", "wholesale", "box lot", "mystery")
    );

    companion object {
        fun inferFromText(text: String): AuctionCategory {
            val lower = text.lowercase()
            return entries
                .filter { it != ALL }
                .firstOrNull { cat -> cat.searchKeywords.any { kw -> lower.contains(kw) } }
                ?: ALL
        }
    }
}
