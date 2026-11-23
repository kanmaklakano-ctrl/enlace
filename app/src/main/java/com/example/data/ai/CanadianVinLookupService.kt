package com.example.data.ai

import android.util.Log
import com.example.data.network.AuctionHttpClient
import com.example.model.CanadianProvince
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private const val TAG = "CanadianVinLookup"

data class VinRecallItem(
    val campaignNumber: String,
    val source: String,
    val component: String,
    val summary: String,
    val date: String = ""
)

data class VinLookupSummary(
    val vin: String? = null,
    val isVinValid: Boolean = false,
    val modelYear: String = "",
    val make: String = "",
    val model: String = "",
    val trim: String = "",
    val bodyClass: String = "",
    val driveType: String = "",
    val engineSummary: String = "",
    val transmission: String = "",
    val fuelType: String = "",
    val plantLocation: String = "",
    val manufacturedInCanada: Boolean = false,
    val recalls: List<VinRecallItem> = emptyList(),
    val recallsChecked: Boolean = false,
    val transportCanadaRecallsCount: Int = 0,
    val complaintsCount: Int = 0,
    val topComplaintSystems: List<String> = emptyList(),
    val cpicStolenCheckUrl: String = "https://www.cpic-cipc.ca/sve-rve-fra.htm",
    val transportCanadaRecallUrl: String = "https://wwwapps.tc.gc.ca/Saf-Sec-Sur/7/VRDB-BDRV/search-recherche/menu.aspx?lang=fra",
    val provincialRegistryLabel: String = "Vérification provinciale",
    val provincialRegistryUrl: String = "https://www.carfax.ca/",
    val lookupSourceLabel: String = ""
) {
    val hasDecodedVehicle: Boolean
        get() = make.isNotBlank() && model.isNotBlank()

    val hasAnyUsefulData: Boolean
        get() = hasDecodedVehicle || recallsChecked || complaintsCount > 0

    fun compactSpecsLine(): String {
        val parts = buildList {
            val title = listOf(modelYear, make, model, trim).filter { it.isNotBlank() }.joinToString(" ")
            if (title.isNotBlank()) add(title)
            if (engineSummary.isNotBlank()) add(engineSummary)
            if (driveType.isNotBlank()) add(driveType)
            if (transmission.isNotBlank()) add(transmission)
            if (plantLocation.isNotBlank()) add(plantLocation)
        }
        return parts.joinToString(" • ")
    }

    fun toPromptSummary(): String = buildString {
        if (!vin.isNullOrBlank()) {
            appendLine("NIV vérifié : $vin (Validité officielle : ${if (isVinValid) "VALIDE" else "À VÉRIFIER / INCOMPLET"})")
        }
        if (hasDecodedVehicle) {
            appendLine("Décodage officiel constructeur (vPIC) : ${compactSpecsLine()}")
            if (bodyClass.isNotBlank()) appendLine("Carrosserie : $bodyClass")
            if (fuelType.isNotBlank()) appendLine("Carburant : $fuelType")
            if (manufacturedInCanada) appendLine("Assemblage : Fabriqué au Canada")
        }
        if (recallsChecked) {
            appendLine("Rappels de sécurité officiels (Transports Canada / NHTSA) : ${recalls.size} rappel(s) trouvé(s)")
            recalls.take(5).forEach { r ->
                appendLine("  - [${r.source} ${r.campaignNumber}] ${r.component} (${r.date.ifBlank { "date n/d" }}) : ${r.summary.take(180)}")
            }
        }
        if (complaintsCount > 0) {
            appendLine("Fiabilité / plaintes propriétaires recensées (NHTSA) : $complaintsCount plainte(s) pour ce modèle/année")
            if (topComplaintSystems.isNotEmpty()) {
                appendLine("Systèmes les plus signalés : ${topComplaintSystems.joinToString(", ")}")
            }
        }
        appendLine("Services officiels canadiens recommandés : CIPC/GRC (vol au Canada), Transports Canada (rappels), $provincialRegistryLabel")
    }.trim()
}

object CanadianVinLookupService {

    private val knownMakes = listOf(
        "Mercedes-Benz", "Land Rover", "Alfa Romeo", "Aston Martin",
        "Toyota", "Honda", "Ford", "Chevrolet", "GMC", "Dodge", "Ram", "Jeep", "Chrysler",
        "Hyundai", "Kia", "Nissan", "Mazda", "Subaru", "Volkswagen", "Audi", "BMW",
        "Porsche", "Lexus", "Acura", "Infiniti", "Volvo", "Tesla", "Mitsubishi", "Buick",
        "Cadillac", "Lincoln", "Mini", "Fiat", "Genesis", "Jaguar", "Rivian", "Polestar"
    )

    suspend fun lookupVehicleIntelligence(
        vin: String?,
        listingTitle: String,
        province: CanadianProvince
    ): VinLookupSummary? = withContext(Dispatchers.IO) {
        val cleanVin = vin?.trim()?.uppercase()?.takeIf { it.length == 17 }
        val (provLabel, provUrl) = provincialRegistryFor(province)

        // Step 1: Decode 17-char VIN via official NHTSA vPIC API (covers all North American & Canadian VINs)
        val decoded = if (cleanVin != null) {
            withTimeoutOrNull(4_500L) { decodeVinVpic(cleanVin) }
        } else {
            null
        }

        // Determine Year / Make / Model either from official VIN decode or parsed from listing title
        val parsedFallback = if (decoded == null || !decoded.hasDecodedVehicle) {
            parseYearMakeModelFromTitle(listingTitle)
        } else {
            null
        }

        val targetYear = decoded?.modelYear?.takeIf { it.isNotBlank() } ?: parsedFallback?.first.orEmpty()
        val targetMake = decoded?.make?.takeIf { it.isNotBlank() } ?: parsedFallback?.second.orEmpty()
        val targetModel = decoded?.model?.takeIf { it.isNotBlank() } ?: parsedFallback?.third.orEmpty()

        if (decoded == null && (targetYear.isBlank() || targetMake.isBlank() || targetModel.isBlank())) {
            return@withContext null
        }

        // Step 2: Query Transport Canada VRDB + NHTSA Recalls + NHTSA Reliability Complaints in parallel
        val (tcRecalls, nhtsaRecalls, complaintsPair) = coroutineScope {
            val tcDeferred = async {
                if (targetYear.isNotBlank() && targetMake.isNotBlank() && targetModel.isNotBlank()) {
                    withTimeoutOrNull(3_500L) { fetchTransportCanadaRecalls(targetMake, targetModel, targetYear) }
                        ?: emptyList()
                } else {
                    emptyList()
                }
            }
            val nhtsaRecallDeferred = async {
                if (targetYear.isNotBlank() && targetMake.isNotBlank() && targetModel.isNotBlank()) {
                    withTimeoutOrNull(3_500L) { fetchNhtsaRecalls(targetMake, targetModel, targetYear) }
                        ?: emptyList()
                } else {
                    emptyList()
                }
            }
            val complaintsDeferred = async {
                if (targetYear.isNotBlank() && targetMake.isNotBlank() && targetModel.isNotBlank()) {
                    withTimeoutOrNull(3_500L) { fetchNhtsaComplaints(targetMake, targetModel, targetYear) }
                        ?: (0 to emptyList())
                } else {
                    0 to emptyList()
                }
            }
            Triple(tcDeferred.await(), nhtsaRecallDeferred.await(), complaintsDeferred.await())
        }

        // Merge Transport Canada + NHTSA recalls cleanly (prefer Transport Canada entries first, deduplicate by component/campaign)
        val mergedRecalls = buildList {
            val seen = mutableSetOf<String>()
            (tcRecalls + nhtsaRecalls).forEach { recall ->
                val key = "${recall.campaignNumber.uppercase()}|${recall.component.lowercase().take(24)}"
                if (seen.add(key)) add(recall)
            }
        }

        val sourceLabel = buildList {
            if (decoded?.hasDecodedVehicle == true) add("NIV vPIC")
            if (tcRecalls.isNotEmpty()) add("Transports Canada")
            if (nhtsaRecalls.isNotEmpty() || complaintsPair.first > 0) add("Rappels & Fiabilité NA")
        }.joinToString(" + ").ifBlank { "Décodage NIV officiel" }

        val base = decoded ?: VinLookupSummary(
            vin = cleanVin,
            modelYear = targetYear,
            make = targetMake,
            model = targetModel
        )

        val result = base.copy(
            recalls = mergedRecalls,
            recallsChecked = targetYear.isNotBlank() && targetMake.isNotBlank() && targetModel.isNotBlank(),
            transportCanadaRecallsCount = tcRecalls.size,
            complaintsCount = complaintsPair.first,
            topComplaintSystems = complaintsPair.second,
            provincialRegistryLabel = provLabel,
            provincialRegistryUrl = provUrl,
            lookupSourceLabel = sourceLabel
        )

        if (result.hasAnyUsefulData) result else null
    }

    private fun decodeVinVpic(vin: String): VinLookupSummary? {
        val url = "https://vpic.nhtsa.dot.gov/api/vehicles/DecodeVinValuesExtended/${encode(vin)}?format=json"
        val jsonText = executeGet(url) ?: return null
        return parseVpicJson(vin, jsonText)
    }

    internal fun parseVpicJson(vin: String, jsonText: String): VinLookupSummary? {
        return try {
            val root = JSONObject(jsonText)
            val results = root.optJSONArray("Results") ?: return null
            if (results.length() == 0) return null
            val item = results.optJSONObject(0) ?: return null

            fun field(key: String): String {
                val raw = item.optString(key, "").trim()
                return if (raw.equals("null", ignoreCase = true) || raw.equals("Not Applicable", ignoreCase = true)) {
                    ""
                } else {
                    raw
                }
            }

            val errorCode = field("ErrorCode")
            val isValid = errorCode == "0" || errorCode.startsWith("0,") || errorCode.isBlank()
            val year = field("ModelYear")
            val make = field("Make").toTitleCaseWords()
            val model = field("Model")
            val trim = listOf(field("Trim"), field("Series")).filter { it.isNotBlank() }.distinct().joinToString(" ")
            val bodyClass = field("BodyClass")
            val driveType = field("DriveType")
            val displacement = field("DisplacementL").takeIf { it.isNotBlank() }?.let { d ->
                d.toDoubleOrNull()?.let { String.format(java.util.Locale.US, "%.1fL", it) } ?: "${d}L"
            }.orEmpty()
            val cylinders = field("EngineCylinders").takeIf { it.isNotBlank() }?.let { "${it} cyl" }.orEmpty()
            val hp = field("EngineHP").takeIf { it.isNotBlank() }?.let { "${it.substringBefore('.')} ch" }.orEmpty()
            val turbo = if (field("Turbo").equals("Yes", ignoreCase = true)) "Turbo" else ""
            val engineSummary = listOf(displacement, cylinders, turbo, hp).filter { it.isNotBlank() }.joinToString(" ")

            val transSpeeds = field("TransmissionSpeeds").takeIf { it.isNotBlank() }?.let { "$it vit." }.orEmpty()
            val transStyle = field("TransmissionStyle")
            val transmission = listOf(transStyle, transSpeeds).filter { it.isNotBlank() }.joinToString(" ")

            val fuelPrimary = field("FuelTypePrimary")
            val electrification = field("ElectrificationLevel")
            val fuelType = listOf(fuelPrimary, electrification).filter { it.isNotBlank() }.distinct().joinToString(" / ")

            val plantCountry = field("PlantCountry").toTitleCaseWords()
            val plantCity = field("PlantCity").toTitleCaseWords()
            val plantLocation = listOf(plantCity, plantCountry).filter { it.isNotBlank() }.joinToString(", ")
            val madeInCanada = plantCountry.equals("Canada", ignoreCase = true) || vin.startsWith("2")

            if (make.isBlank() && model.isBlank()) return null

            VinLookupSummary(
                vin = vin,
                isVinValid = isValid,
                modelYear = year,
                make = make,
                model = model,
                trim = trim,
                bodyClass = bodyClass,
                driveType = driveType,
                engineSummary = engineSummary,
                transmission = transmission,
                fuelType = fuelType,
                plantLocation = plantLocation,
                manufacturedInCanada = madeInCanada
            )
        } catch (e: Exception) {
            Log.d(TAG, "Failed parsing vPIC JSON: ${e.message}")
            null
        }
    }

    private fun fetchTransportCanadaRecalls(
        make: String,
        model: String,
        year: String
    ): List<VinRecallItem> {
        val cleanModel = model.substringBefore(' ').trim().ifBlank { model }
        val urls = listOf(
            "https://data.tc.gc.ca/v1.3/api/fra/banque-donnees-rappels-vehicules/rappel-sommaire/nom-marque/${encode(make)}/nom-modele/${encode(cleanModel)}/annees-modele/$year-$year?format=json",
            "https://data.tc.gc.ca/v1.3/api/eng/vehicle-recall-database/recall-summary/make-name/${encode(make)}/model-name/${encode(cleanModel)}/year-range/$year-$year?format=json"
        )
        for (url in urls) {
            val body = executeGet(url) ?: continue
            val parsed = parseTransportCanadaRecallsJson(body)
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyList()
    }

    internal fun parseTransportCanadaRecallsJson(jsonText: String): List<VinRecallItem> {
        return try {
            val trimmed = jsonText.trim()
            val rows: JSONArray = when {
                trimmed.startsWith("{") -> {
                    val obj = JSONObject(trimmed)
                    obj.optJSONArray("ResultSet")
                        ?: obj.optJSONArray("results")
                        ?: return emptyList()
                }
                trimmed.startsWith("[") -> JSONArray(trimmed)
                else -> return emptyList()
            }
            val list = mutableListOf<VinRecallItem>()
            for (i in 0 until minOf(rows.length(), 15)) {
                val entry = rows.opt(i)
                if (entry is JSONArray) {
                    // Transport Canada v1.3 ResultSet format: array of {Name, Value} objects
                    val map = mutableMapOf<String, String>()
                    for (j in 0 until entry.length()) {
                        val kv = entry.optJSONObject(j) ?: continue
                        val name = kv.optString("Name", "").uppercase()
                        val value = kv.optString("Value", "").trim()
                        if (name.isNotBlank() && value.isNotBlank()) {
                            map[name] = value
                        }
                    }
                    val number = map["RECALL_NUMBER_NUM"] ?: map["NUMERO_RAPPEL"] ?: ""
                    val system = map["SYSTEM_TYPE_ETXT"] ?: map["SYSTEM_TYPE_FTXT"] ?: map["CATEGORY_ETXT"] ?: "Sécurité"
                    val comment = map["COMMENT_FTXT"] ?: map["COMMENT_ETXT"] ?: map["NOTIFICATION_TYPE_ETXT"] ?: "Rappel de sécurité Transports Canada"
                    val date = map["RECALL_DATE_DTE"] ?: ""
                    if (number.isNotBlank() || comment.isNotBlank()) {
                        list.add(
                            VinRecallItem(
                                campaignNumber = number.ifBlank { "TC" },
                                source = "Transports Canada",
                                component = system,
                                summary = comment,
                                date = date.take(10)
                            )
                        )
                    }
                } else if (entry is JSONObject) {
                    val number = entry.optString("recallNumber", entry.optString("RECALL_NUMBER_NUM", ""))
                    val system = entry.optString("systemType", entry.optString("category", "Sécurité"))
                    val comment = entry.optString("comment", entry.optString("summary", "Rappel de sécurité Transports Canada"))
                    val date = entry.optString("recallDate", "")
                    if (number.isNotBlank() || comment.isNotBlank()) {
                        list.add(
                            VinRecallItem(
                                campaignNumber = number.ifBlank { "TC" },
                                source = "Transports Canada",
                                component = system,
                                summary = comment,
                                date = date.take(10)
                            )
                        )
                    }
                }
            }
            list
        } catch (e: Exception) {
            Log.d(TAG, "Failed parsing Transport Canada recalls: ${e.message}")
            emptyList()
        }
    }

    private fun fetchNhtsaRecalls(
        make: String,
        model: String,
        year: String
    ): List<VinRecallItem> {
        for (candidateModel in modelLookupVariants(model)) {
            val url = "https://api.nhtsa.gov/recalls/recallsByVehicle?make=${encode(make)}&model=${encode(candidateModel)}&modelYear=${encode(year)}"
            val body = executeGet(url) ?: continue
            val parsed = parseNhtsaRecallsJson(body)
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyList()
    }

    internal fun parseNhtsaRecallsJson(jsonText: String): List<VinRecallItem> {
        return try {
            val root = JSONObject(jsonText)
            val results = root.optJSONArray("results") ?: return emptyList()
            val list = mutableListOf<VinRecallItem>()
            for (i in 0 until minOf(results.length(), 15)) {
                val obj = results.optJSONObject(i) ?: continue
                val campaign = obj.optString("NHTSACampaignNumber", "").trim()
                val component = obj.optString("Component", "").trim().ifBlank { "Sécurité" }
                val summary = obj.optString("Summary", "").trim()
                val date = obj.optString("ReportReceivedDate", "").trim()
                if (campaign.isNotBlank() || summary.isNotBlank()) {
                    list.add(
                        VinRecallItem(
                            campaignNumber = campaign.ifBlank { "NHTSA" },
                            source = "NHTSA/NA",
                            component = component,
                            summary = summary.ifBlank { component },
                            date = date
                        )
                    )
                }
            }
            list
        } catch (e: Exception) {
            Log.d(TAG, "Failed parsing NHTSA recalls: ${e.message}")
            emptyList()
        }
    }

    private fun fetchNhtsaComplaints(
        make: String,
        model: String,
        year: String
    ): Pair<Int, List<String>> {
        for (candidateModel in modelLookupVariants(model)) {
            val url = "https://api.nhtsa.gov/complaints/complaintsByVehicle?make=${encode(make)}&model=${encode(candidateModel)}&modelYear=${encode(year)}"
            val body = executeGet(url) ?: continue
            val parsed = parseNhtsaComplaintsJson(body)
            if (parsed.first > 0) return parsed
        }
        return 0 to emptyList()
    }

    private fun modelLookupVariants(model: String): List<String> {
        val cleaned = model.trim()
        val variants = linkedSetOf(cleaned)
        // e.g. "GLC300" -> "GLC 300", "GLC", "GLC-Class"
        val alphaNum = Regex("""^([A-Za-z]{1,4})(\d{2,4}[A-Za-z]?)$""").matchEntire(cleaned)
        if (alphaNum != null) {
            val letters = alphaNum.groupValues[1]
            val digits = alphaNum.groupValues[2]
            variants.add("$letters $digits")
            variants.add(letters)
            variants.add("$letters-CLASS")
        }
        val firstWord = cleaned.substringBefore(' ').trim()
        if (firstWord.length >= 2 && firstWord != cleaned) {
            variants.add(firstWord)
        }
        return variants.toList()
    }

    internal fun parseNhtsaComplaintsJson(jsonText: String): Pair<Int, List<String>> {
        return try {
            val root = JSONObject(jsonText)
            val count = root.optInt("count", 0)
            val results = root.optJSONArray("results") ?: return count to emptyList()
            val countsBySystem = mutableMapOf<String, Int>()
            for (i in 0 until results.length()) {
                val obj = results.optJSONObject(i) ?: continue
                val comps = obj.optString("components", "").trim()
                comps.split(',', ';').forEach { part ->
                    val cleaned = part.trim()
                    if (cleaned.isNotBlank() && !cleaned.equals("UNKNOWN OR OTHER", ignoreCase = true)) {
                        countsBySystem[cleaned] = (countsBySystem[cleaned] ?: 0) + 1
                    }
                }
            }
            val top = countsBySystem.entries
                .sortedByDescending { it.value }
                .take(4)
                .map { "${it.key.toTitleCaseWords()} (${it.value})" }
            maxOf(count, results.length()) to top
        } catch (e: Exception) {
            Log.d(TAG, "Failed parsing NHTSA complaints: ${e.message}")
            0 to emptyList()
        }
    }

    internal fun parseYearMakeModelFromTitle(title: String): Triple<String, String, String>? {
        val yearMatch = Regex("""\b(19[89]\d|20[0-2]\d)\b""").find(title) ?: return null
        val year = yearMatch.groupValues[1]
        val matchedMake = knownMakes.firstOrNull { make ->
            Regex("""(?i)\b${Regex.escape(make)}\b""").containsMatchIn(title)
        } ?: return null

        val afterMake = title.substringAfter(matchedMake, missingDelimiterValue = "")
            .ifBlank {
                val idx = title.lowercase().indexOf(matchedMake.lowercase())
                if (idx >= 0) title.substring(idx + matchedMake.length) else ""
            }
            .trim()
            .replace(Regex("""^[-–:\s]+"""), "")

        val stopWords = setOf(
            "awd", "4wd", "fwd", "rwd", "4x4", "sedan", "suv", "coupe", "cabriolet", "convertible",
            "sport", "utility", "pickup", "truck", "van", "wagon", "hatchback", "auto", "automatic",
            "manual", "car", "vehicle", "voiture", "camion", "edition", "limited", "touring"
        )
        val modelTokens = afterMake.split(Regex("""[\s,/|]+"""))
            .map { it.trim().trimEnd('-', '.') }
            .filter { it.isNotBlank() && it.length <= 18 }
            .takeWhile { token ->
                val lower = token.lowercase()
                // Only reject full/long VIN-like strings (11-17 chars), NOT 4-7 char model names like GLC300 or RX350
                lower !in stopWords && !Regex("""^[A-HJ-NPR-Z0-9]{11,17}$""").matches(token)
            }
            .take(2)

        val model = modelTokens.joinToString(" ").trim()
        if (model.isBlank()) return null
        return Triple(year, matchedMake, model)
    }

    internal fun provincialRegistryFor(province: CanadianProvince): Pair<String, String> = when (province) {
        CanadianProvince.QC -> "SAAQ Québec (Statut / RDPRM)" to "https://saaq.gouv.qc.ca/services-en-ligne/citoyens/verifier-statut-vehicule"
        CanadianProvince.ON -> "ServiceOntario (UVIP / MTO)" to "https://www.ontario.ca/fr/page/trousse-dinformation-sur-les-vehicules-doccasion"
        CanadianProvince.AB -> "Registre Alberta (VIR)" to "https://www.alberta.ca/vehicle-information-report"
        CanadianProvince.BC -> "ICBC Colombie-Britannique" to "https://www.icbc.com/vehicle-registration/buy-vehicle/buy-a-used-vehicle"
        CanadianProvince.MB -> "MPI Manitoba" to "https://www.mpi.mb.ca/buying-a-used-vehicle/"
        CanadianProvince.SK -> "SGI Saskatchewan (VIN)" to "https://mysgi.sgi.sk.ca/afOnlineServices/enquire/vinSearch"
        CanadianProvince.NS, CanadianProvince.NB, CanadianProvince.NL, CanadianProvince.PE ->
            "Transports Canada (Rappels)" to "https://wwwapps.tc.gc.ca/Saf-Sec-Sur/7/VRDB-BDRV/search-recherche/menu.aspx?lang=fra"
        else -> "Registres Canada / CARFAX" to "https://www.carfax.ca/"
    }

    private fun executeGet(url: String): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", AuctionHttpClient.BROWSER_USER_AGENT)
                .build()
            AuctionHttpClient.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string()
            }
        } catch (e: Exception) {
            Log.d(TAG, "VIN lookup HTTP error for $url: ${e.message}")
            null
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value.trim(), StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun String.toTitleCaseWords(): String {
        if (isBlank()) return ""
        return lowercase().split(' ').joinToString(" ") { w ->
            if (w.length <= 2) w.uppercase() else w.replaceFirstChar { it.uppercase() }
        }
    }
}
