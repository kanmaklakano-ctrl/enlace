package com.example.model

enum class CanadianProvince(
    val code: String,
    val displayNameFr: String,
    val displayNameEn: String,
    val taxRate: Double,
    val taxLabel: String,
    val majorCities: String,
    val hibidSubdomain: String?,
    val gcsCode: String?
) {
    ALL(
        code = "CA",
        displayNameFr = "Tout le Canada",
        displayNameEn = "All Canada",
        taxRate = 0.13,
        taxLabel = "Estimation moyenne 13 %",
        majorCities = "10 provinces & 3 territoires",
        hibidSubdomain = "canada",
        gcsCode = null
    ),
    ON(
        code = "ON",
        displayNameFr = "Ontario",
        displayNameEn = "Ontario",
        taxRate = 0.13,
        taxLabel = "TVH 13 %",
        majorCities = "Toronto, Ottawa, Hamilton, London",
        hibidSubdomain = "ontario",
        gcsCode = "ON"
    ),
    QC(
        code = "QC",
        displayNameFr = "Québec",
        displayNameEn = "Quebec",
        taxRate = 0.14975,
        taxLabel = "TPS 5 % + TVQ 9,975 %",
        majorCities = "Montréal, Québec, Laval, Sherbrooke",
        hibidSubdomain = "quebec",
        gcsCode = "QC"
    ),
    AB(
        code = "AB",
        displayNameFr = "Alberta",
        displayNameEn = "Alberta",
        taxRate = 0.05,
        taxLabel = "TPS 5 %",
        majorCities = "Calgary, Edmonton, Red Deer",
        hibidSubdomain = "alberta",
        gcsCode = "AB"
    ),
    BC(
        code = "BC",
        displayNameFr = "Colombie-Britannique",
        displayNameEn = "British Columbia",
        taxRate = 0.12,
        taxLabel = "TPS 5 % + TVP 7 %",
        majorCities = "Vancouver, Victoria, Surrey, Kelowna",
        hibidSubdomain = "british-columbia",
        gcsCode = "BC"
    ),
    MB(
        code = "MB",
        displayNameFr = "Manitoba",
        displayNameEn = "Manitoba",
        taxRate = 0.12,
        taxLabel = "TPS 5 % + TVP 7 %",
        majorCities = "Winnipeg, Brandon",
        hibidSubdomain = "manitoba",
        gcsCode = "MB"
    ),
    SK(
        code = "SK",
        displayNameFr = "Saskatchewan",
        displayNameEn = "Saskatchewan",
        taxRate = 0.11,
        taxLabel = "TPS 5 % + TVP 6 %",
        majorCities = "Saskatoon, Regina",
        hibidSubdomain = "saskatchewan",
        gcsCode = "SK"
    ),
    NS(
        code = "NS",
        displayNameFr = "Nouvelle-Écosse",
        displayNameEn = "Nova Scotia",
        taxRate = 0.14,
        taxLabel = "TVH 14 %",
        majorCities = "Halifax, Dartmouth, Sydney",
        hibidSubdomain = "nova-scotia",
        gcsCode = "NS"
    ),
    NB(
        code = "NB",
        displayNameFr = "Nouveau-Brunswick",
        displayNameEn = "New Brunswick",
        taxRate = 0.15,
        taxLabel = "TVH 15 %",
        majorCities = "Moncton, Saint John, Fredericton",
        hibidSubdomain = "new-brunswick",
        gcsCode = "NB"
    ),
    NL(
        code = "NL",
        displayNameFr = "Terre-Neuve-et-Labrador",
        displayNameEn = "Newfoundland and Labrador",
        taxRate = 0.15,
        taxLabel = "TVH 15 %",
        majorCities = "St. John's, Corner Brook",
        hibidSubdomain = "newfoundland-and-labrador",
        gcsCode = "NL"
    ),
    PE(
        code = "PE",
        displayNameFr = "Île-du-Prince-Édouard",
        displayNameEn = "Prince Edward Island",
        taxRate = 0.15,
        taxLabel = "TVH 15 %",
        majorCities = "Charlottetown, Summerside",
        hibidSubdomain = "prince-edward-island",
        gcsCode = "PE"
    ),
    YT(
        code = "YT",
        displayNameFr = "Yukon",
        displayNameEn = "Yukon",
        taxRate = 0.05,
        taxLabel = "TPS 5 %",
        majorCities = "Whitehorse",
        hibidSubdomain = "canada",
        gcsCode = "YT"
    ),
    NT(
        code = "NT",
        displayNameFr = "Territoires du Nord-Ouest",
        displayNameEn = "Northwest Territories",
        taxRate = 0.05,
        taxLabel = "TPS 5 %",
        majorCities = "Yellowknife",
        hibidSubdomain = "canada",
        gcsCode = "NT"
    ),
    NU(
        code = "NU",
        displayNameFr = "Nunavut",
        displayNameEn = "Nunavut",
        taxRate = 0.05,
        taxLabel = "TPS 5 %",
        majorCities = "Iqaluit",
        hibidSubdomain = "canada",
        gcsCode = "NU"
    );

    companion object {
        val quickFilterProvinces = listOf(ALL, ON, QC, AB, BC, MB, SK, NS, NB)

        fun fromCodeOrText(raw: String?): CanadianProvince {
            if (raw.isNullOrBlank()) return ALL
            val clean = raw.trim().uppercase()
            entries.firstOrNull { it.code == clean }?.let { return it }

            // Check explicit ", XX" or "(XX)" or " - XX" Canadian province abbreviations first
            val codeMatch = Regex("""(?:,\s*|\(\s*|-\s*|\b)(ON|QC|AB|BC|MB|SK|NS|NB|NL|PE|YT|NT|NU)(?:\b|\)|,)""")
                .find(clean)?.groupValues?.get(1)
            if (codeMatch != null) {
                entries.firstOrNull { it.code == codeMatch }?.let { return it }
            }

            return when {
                clean.contains("ONTARIO") || clean.contains("TORONTO") || clean.contains("OTTAWA") ||
                    clean.contains("MISSISSAUGA") || clean.contains("HAMILTON") || clean.contains("SCARBOROUGH") ||
                    clean.contains("BRAMPTON") || clean.contains("KITCHENER") || clean.contains("WINDSOR") ||
                    clean.contains("BRANTFORD") || clean.contains("NEWMARKET") || clean.contains("BURLINGTON") ||
                    clean.contains("BARRIE") || clean.contains("VAUGHAN") || clean.contains("MARKHAM") ||
                    clean.contains("OAKVILLE") || clean.contains("PICKERING") || clean.contains("WATERLOO") ||
                    clean.contains("GUELPH") -> ON
                clean.contains("QUEBEC") || clean.contains("QUÉBEC") || clean.contains("MONTREAL") ||
                    clean.contains("MONTRÉAL") || clean.contains("LAVAL") || clean.contains("GATINEAU") ||
                    clean.contains("SHERBROOKE") || clean.contains("LONGUEUIL") || clean.contains("SAINT-LAURENT") ||
                    clean.contains("BROSSARD") || clean.contains("TROIS-RIVI") || clean.contains("DRUMMONDVILLE") -> QC
                clean.contains("ALBERTA") || clean.contains("CALGARY") || clean.contains("EDMONTON") ||
                    clean.contains("RED DEER") || clean.contains("LETHBRIDGE") || clean.contains("LA CRETE") ||
                    clean.contains("NISKU") || clean.contains("AIRDRIE") -> AB
                clean.contains("BRITISH COLUMBIA") || clean.contains("COLOMBIE") || clean.contains("VANCOUVER") ||
                    clean.contains("VICTORIA") || clean.contains("SURREY") || clean.contains("KELOWNA") ||
                    clean.contains("LANGLEY") || clean.contains("BURNABY") || clean.contains("RICHMOND") ||
                    clean.contains("DELTA") || clean.contains("ABBOTSFORD") -> BC
                clean.contains("MANITOBA") || clean.contains("WINNIPEG") || clean.contains("BRANDON") ||
                    clean.contains("HEADINGLEY") -> MB
                clean.contains("SASKATCHEWAN") || clean.contains("SASKATOON") || clean.contains("REGINA") -> SK
                clean.contains("NOVA SCOTIA") || clean.contains("NOUVELLE-ÉCOSSE") || clean.contains("HALIFAX") ||
                    clean.contains("DARTMOUTH") -> NS
                clean.contains("NEW BRUNSWICK") || clean.contains("NOUVEAU-BRUNSWICK") || clean.contains("MONCTON") ||
                    clean.contains("FREDERICTON") || clean.contains("SAINT JOHN") -> NB
                clean.contains("NEWFOUNDLAND") || clean.contains("TERRE-NEUVE") -> NL
                clean.contains("PRINCE EDWARD") || clean.contains("PRINCE-ÉDOUARD") || clean.contains("PEI") -> PE
                clean.contains("YUKON") || clean.contains("WHITEHORSE") -> YT
                clean.contains("NORTHWEST") || clean.contains("NORD-OUEST") || clean.contains("NWT") -> NT
                clean.contains("NUNAVUT") || clean.contains("IQALUIT") -> NU
                else -> ALL
            }
        }
    }
}
