package com.example.data.ai

import android.util.Log
import com.example.data.network.AuctionHttpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "CarfaxReportReader"
private const val MAX_PDF_BYTES = 10 * 1024 * 1024

data class CarfaxReadResult(
    val extractedText: String? = null,
    val pdfBytes: ByteArray? = null,
    val sourceMethod: String? = null
) {
    val hasContent: Boolean
        get() = !extractedText.isNullOrBlank() || (pdfBytes != null && pdfBytes.isNotEmpty())
}

data class CarfaxRenderRequest(
    val id: Long = requestIdCounter.incrementAndGet(),
    val url: String,
    val deferred: CompletableDeferred<String?>
) {
    companion object {
        private val requestIdCounter = AtomicLong(0L)
    }
}

data class CarfaxExtractedSnapshot(
    val combinedText: String,
    val hasApiPayload: Boolean,
    val isCarfaxReady: Boolean,
    val rawCharCount: Int
)

object CarfaxReportReader {

    private val carfaxSignalKeywords = listOf(
        "accident", "damage", "collision", "no reported accidents", "aucun accident",
        "dommage", "service record", "dossiers d'entretien", "odometer", "odomètre",
        "kilom", "registration", "immatriculation", "stolen", "volé", "recall",
        "rappel", "lien", "salvage", "rebuilt", "irreparable", "normal",
        "vehicle history report", "detailed history", "historique détaillé",
        "u.s. history", "import record", "claim", "estimate", "police reported",
        "glass record", "carfax canada", "branding", "statut", "insurance record",
        "dossier d'assurance", "privilège", "vga", "sinistre"
    )

    /**
     * Injected into the Activity-attached WebView on page start & finish.
     * Hooks fetch() and XMLHttpRequest to silently capture CARFAX JSON API responses
     * even before/without full DOM rendering, and dismisses cookie consent banners.
     */
    val CARFAX_INTERCEPTOR_JS: String = """
        (function() {
            try {
                if (!window.__encanCarfaxPayloads) {
                    window.__encanCarfaxPayloads = [];
                }
                var isInteresting = function(str) {
                    if (!str || str.length < 350) return false;
                    var lower = str.toLowerCase();
                    var hits = 0;
                    var keys = ['accident','damage','odometer','service','recall','lien','branding','registration','stolen','vin','history','claim','record','carfax'];
                    for (var i = 0; i < keys.length; i++) {
                        if (lower.indexOf(keys[i]) !== -1) hits++;
                    }
                    return hits >= 3;
                };
                var storePayload = function(url, text) {
                    try {
                        if (!isInteresting(text)) return;
                        if (window.__encanCarfaxPayloads.length < 6) {
                            var already = window.__encanCarfaxPayloads.some(function(p) { return p.len === text.length && p.url === url; });
                            if (!already) {
                                window.__encanCarfaxPayloads.push({
                                    url: String(url || ''),
                                    len: text.length,
                                    body: text.slice(0, 28000)
                                });
                            }
                        }
                    } catch (e) {}
                };

                if (!window.__encanHooked) {
                    window.__encanHooked = true;
                    if (window.fetch) {
                        var origFetch = window.fetch;
                        window.fetch = function() {
                            var reqUrl = (arguments[0] && arguments[0].url) ? arguments[0].url : String(arguments[0] || '');
                            return origFetch.apply(this, arguments).then(function(resp) {
                                try {
                                    var ct = (resp.headers && resp.headers.get) ? (resp.headers.get('content-type') || '') : '';
                                    if (ct.indexOf('json') !== -1 || ct.indexOf('text') !== -1 || reqUrl.toLowerCase().indexOf('report') !== -1 || reqUrl.toLowerCase().indexOf('vhr') !== -1) {
                                        resp.clone().text().then(function(txt) { storePayload(reqUrl, txt); }).catch(function(){});
                                    }
                                } catch (e) {}
                                return resp;
                            });
                        };
                    }
                    if (window.XMLHttpRequest) {
                        var origOpen = XMLHttpRequest.prototype.open;
                        var origSend = XMLHttpRequest.prototype.send;
                        XMLHttpRequest.prototype.open = function(method, url) {
                            this.__encanUrl = url;
                            return origOpen.apply(this, arguments);
                        };
                        XMLHttpRequest.prototype.send = function() {
                            this.addEventListener('load', function() {
                                try {
                                    if (this.responseType === '' || this.responseType === 'text') {
                                        storePayload(this.__encanUrl || '', this.responseText || '');
                                    }
                                } catch (e) {}
                            });
                            return origSend.apply(this, arguments);
                        };
                    }
                }

                // Gently dismiss cookie consent banners if present (non-destructive)
                var cookieSelectors = [
                    '#onetrust-accept-btn-handler',
                    'button[id*="accept" i]',
                    'button[class*="accept-all" i]'
                ];
                for (var s = 0; s < cookieSelectors.length; s++) {
                    var btn = document.querySelector(cookieSelectors[s]);
                    if (btn && btn.offsetParent !== null) {
                        btn.click();
                        break;
                    }
                }
            } catch (e) {}
        })();
    """.trimIndent()

    /**
     * Extracts visible DOM text (including open Shadow DOM & same-origin iframes)
     * plus any intercepted CARFAX JSON API payloads.
     */
    val CARFAX_EXTRACT_JS: String = """
        (function() {
            try {
                var collectText = function(node) {
                    if (!node) return '';
                    var out = '';
                    try {
                        if (node.innerText && node.innerText.trim().length > 0) {
                            out += node.innerText + '\n';
                        } else if (node.textContent && node.textContent.trim().length > 0) {
                            out += node.textContent + '\n';
                        }
                    } catch (e) {}
                    try {
                        var all = node.querySelectorAll ? node.querySelectorAll('*') : [];
                        for (var i = 0; i < all.length && i < 400; i++) {
                            if (all[i].shadowRoot) {
                                out += collectText(all[i].shadowRoot) + '\n';
                            }
                        }
                    } catch (e) {}
                    return out;
                };

                var domText = collectText(document.body);
                try {
                    var frames = document.querySelectorAll('iframe');
                    for (var f = 0; f < frames.length; f++) {
                        try {
                            var doc = frames[f].contentDocument;
                            if (doc && doc.body) {
                                domText += '\n' + collectText(doc.body);
                            }
                        } catch (e) {}
                    }
                } catch (e) {}

                var payloads = [];
                if (Array.isArray(window.__encanCarfaxPayloads)) {
                    for (var p = 0; p < window.__encanCarfaxPayloads.length; p++) {
                        payloads.push(window.__encanCarfaxPayloads[p].body || '');
                    }
                }

                return JSON.stringify({
                    domText: domText.slice(0, 24000),
                    apiPayloads: payloads,
                    title: document.title || '',
                    readyState: document.readyState || ''
                });
            } catch (e) {
                return JSON.stringify({ domText: '', apiPayloads: [], title: '', readyState: '' });
            }
        })();
    """.trimIndent()

    suspend fun tryDirectHttpFetch(url: String): CarfaxReadResult = withContext(Dispatchers.IO) {
        val cleanUrl = url.trim()
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            return@withContext CarfaxReadResult()
        }
        try {
            val request = Request.Builder()
                .url(cleanUrl)
                .header("User-Agent", AuctionHttpClient.BROWSER_USER_AGENT)
                .header("Accept", "application/pdf,text/html,application/xhtml+xml,*/*;q=0.8")
                .header("Accept-Language", "fr-CA,fr;q=0.9,en-CA;q=0.8,en;q=0.7")
                .build()

            AuctionHttpClient.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext CarfaxReadResult()
                val contentType = response.header("Content-Type").orEmpty().lowercase()
                val body = response.body ?: return@withContext CarfaxReadResult()

                // Check if this is a PDF CARFAX / inspection report -> pass native PDF bytes to Gemini
                if ("application/pdf" in contentType || cleanUrl.lowercase().substringBefore('?').endsWith(".pdf")) {
                    val bytes = body.bytes()
                    val isPdfMagic = bytes.size >= 5 &&
                        bytes[0] == '%'.code.toByte() &&
                        bytes[1] == 'P'.code.toByte() &&
                        bytes[2] == 'D'.code.toByte() &&
                        bytes[3] == 'F'.code.toByte()
                    if (isPdfMagic && bytes.size <= MAX_PDF_BYTES) {
                        return@withContext CarfaxReadResult(
                            extractedText = null,
                            pdfBytes = bytes,
                            sourceMethod = "PDF"
                        )
                    }
                    return@withContext CarfaxReadResult()
                }

                // Otherwise inspect static HTML
                val source = body.source()
                source.request(700_000L)
                val buffer = source.buffer.clone()
                val html = buffer.readUtf8(minOf(buffer.size, 700_000L))
                val doc = Jsoup.parse(html, cleanUrl)
                doc.select("script, style, noscript, svg, nav, footer").remove()
                val visibleText = doc.body()?.text().orEmpty()
                    .replace(Regex("""\s+"""), " ")
                    .trim()

                if (isCarfaxRendered(visibleText)) {
                    CarfaxReadResult(
                        extractedText = condenseCarfaxText(visibleText),
                        pdfBytes = null,
                        sourceMethod = "HTTP"
                    )
                } else {
                    CarfaxReadResult()
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Direct HTTP CARFAX fetch skipped for $cleanUrl: ${e.message}")
            CarfaxReadResult()
        }
    }

    fun parseExtractionPayload(rawJsResult: String?): CarfaxExtractedSnapshot {
        val decoded = decodeJsString(rawJsResult)
        if (decoded.isBlank()) {
            return CarfaxExtractedSnapshot("", hasApiPayload = false, isCarfaxReady = false, rawCharCount = 0)
        }
        return try {
            val obj = JSONObject(decoded)
            val domText = obj.optString("domText", "").trim()
            val apiArr = obj.optJSONArray("apiPayloads")
            val apiSummary = StringBuilder()
            var hasApi = false
            if (apiArr != null) {
                for (i in 0 until apiArr.length()) {
                    val rawPayload = apiArr.optString(i, "").trim()
                    if (rawPayload.length >= 250) {
                        val readable = flattenJsonToReadableLines(rawPayload)
                        if (readable.isNotBlank()) {
                            hasApi = true
                            apiSummary.appendLine(readable)
                        }
                    }
                }
            }

            val combined = buildString {
                if (domText.isNotBlank()) {
                    appendLine(domText)
                }
                if (apiSummary.isNotBlank()) {
                    appendLine("=== DONNÉES STRUCTURÉES CARFAX ===")
                    append(apiSummary.toString())
                }
            }.trim()

            val ready = isCarfaxRendered(combined) || (hasApi && apiSummary.length >= 350)
            CarfaxExtractedSnapshot(
                combinedText = combined,
                hasApiPayload = hasApi,
                isCarfaxReady = ready,
                rawCharCount = combined.length
            )
        } catch (_: Exception) {
            val ready = isCarfaxRendered(decoded)
            CarfaxExtractedSnapshot(
                combinedText = decoded,
                hasApiPayload = false,
                isCarfaxReady = ready,
                rawCharCount = decoded.length
            )
        }
    }

    internal fun flattenJsonToReadableLines(rawJson: String, maxChars: Int = 4_000): String {
        val trimmed = rawJson.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return if (isCarfaxRendered(trimmed)) trimmed.take(maxChars) else ""
        }
        val out = StringBuilder()
        fun walk(value: Any?, prefix: String, depth: Int) {
            if (out.length >= maxChars || depth > 7 || value == null || value === JSONObject.NULL) return
            when (value) {
                is JSONObject -> {
                    val keys = value.keys()
                    while (keys.hasNext() && out.length < maxChars) {
                        val key = keys.next()
                        val child = value.opt(key)
                        val lowerKey = key.lowercase()
                        if (lowerKey in setOf("svg", "icon", "css", "style", "token", "recaptcha", "signature", "hash", "imageurl", "logo")) {
                            continue
                        }
                        val nextPrefix = if (prefix.isEmpty()) key else "$prefix.$key"
                        walk(child, nextPrefix, depth + 1)
                    }
                }
                is JSONArray -> {
                    val limit = minOf(value.length(), 45)
                    for (i in 0 until limit) {
                        if (out.length >= maxChars) break
                        walk(value.opt(i), prefix, depth + 1)
                    }
                }
                is String -> {
                    val v = value.replace(Regex("""\s+"""), " ").trim()
                    if (v.isNotEmpty() && v.length <= 280 && !v.startsWith("http") && !v.startsWith("data:")) {
                        val shortKey = prefix.substringAfterLast('.')
                        out.append(shortKey).append(": ").appendLine(v)
                    }
                }
                is Number, is Boolean -> {
                    val shortKey = prefix.substringAfterLast('.')
                    out.append(shortKey).append(": ").appendLine(value.toString())
                }
            }
        }
        return try {
            val parsed: Any = if (trimmed.startsWith("{")) JSONObject(trimmed) else JSONArray(trimmed)
            walk(parsed, "", 0)
            out.toString().trim().take(maxChars)
        } catch (_: Exception) {
            ""
        }
    }

    internal fun decodeJsString(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return try {
            JSONArray("[$raw]").optString(0, "").trim()
        } catch (_: Exception) {
            raw.trim().removeSurrounding("\"")
        }
    }

    internal fun isCarfaxRendered(text: String): Boolean {
        val cleaned = text.trim()
        if (cleaned.length < 280) return false
        val lower = cleaned.lowercase()
        // Reject pure loading/bot/JS-disabled screens
        if (cleaned.length < 500 && (
                "enable javascript" in lower ||
                    "please wait" in lower ||
                    "checking your browser" in lower ||
                    "verify you are human" in lower ||
                    "loading report" in lower
                )
        ) {
            return false
        }
        val matchedSignals = carfaxSignalKeywords.count { it in lower }
        return matchedSignals >= 3
    }

    internal fun condenseCarfaxText(raw: String, maxChars: Int = 6_000): String {
        val filtered = raw.lines()
            .map { it.replace(Regex("""\s+"""), " ").trim() }
            .filter { it.length >= 2 }
            .filterNot { line ->
                val lower = line.lowercase()
                lower in setOf("print", "imprimer", "download", "télécharger", "share", "partager", "help", "aide", "faq") ||
                    lower.startsWith("© ") ||
                    lower.startsWith("privacy policy") ||
                    lower.startsWith("terms of use")
            }

        val deduped = ArrayList<String>(filtered.size)
        for (line in filtered) {
            if (deduped.lastOrNull() != line) {
                deduped.add(line)
            }
        }

        return deduped.joinToString("\n").take(maxChars)
    }
}
