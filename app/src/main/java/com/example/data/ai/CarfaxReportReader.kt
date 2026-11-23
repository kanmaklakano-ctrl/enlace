package com.example.data.ai

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.data.network.AuctionHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONArray
import org.jsoup.Jsoup
import kotlin.coroutines.resume

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

object CarfaxReportReader {

    private val carfaxSignalKeywords = listOf(
        "accident", "damage", "collision", "no reported accidents", "aucun accident",
        "dommage", "service record", "dossiers d'entretien", "odometer", "odomètre",
        "kilom", "registration", "immatriculation", "stolen", "volé", "recall",
        "rappel", "lien", "salvage", "rebuilt", "irreparable", "normal",
        "vehicle history report", "detailed history", "historique détaillé",
        "u.s. history", "import record", "claim", "estimate", "police reported",
        "glass record", "carfax canada", "branding", "statut"
    )

    suspend fun readReport(
        context: Context,
        reportUrl: String,
        timeoutMs: Long = 15_000L
    ): CarfaxReadResult {
        val cleanUrl = reportUrl.trim()
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            return CarfaxReadResult()
        }

        // Layer 1: Direct HTTP check (detects PDF reports immediately & reads static HTML reports)
        val httpResult = tryDirectHttpFetch(cleanUrl)
        if (httpResult.hasContent) {
            Log.d(TAG, "Read CARFAX/inspection report via ${httpResult.sourceMethod}")
            return httpResult
        }

        // Layer 2: Headless Chromium WebView for JS/reCAPTCHA SPA reports (e.g. vhr.carfax.ca)
        val renderedText = tryRenderWithHeadlessWebView(context, cleanUrl, timeoutMs)
        if (!renderedText.isNullOrBlank()) {
            Log.d(TAG, "Read CARFAX report via WebView (${renderedText.length} chars)")
            return CarfaxReadResult(
                extractedText = condenseCarfaxText(renderedText),
                pdfBytes = null,
                sourceMethod = "WebView"
            )
        }

        return CarfaxReadResult()
    }

    private suspend fun tryDirectHttpFetch(url: String): CarfaxReadResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", AuctionHttpClient.BROWSER_USER_AGENT)
                .header("Accept", "application/pdf,text/html,application/xhtml+xml,*/*;q=0.8")
                .header("Accept-Language", "fr-CA,fr;q=0.9,en-CA;q=0.8,en;q=0.7")
                .build()

            AuctionHttpClient.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext CarfaxReadResult()
                val contentType = response.header("Content-Type").orEmpty().lowercase()
                val body = response.body ?: return@withContext CarfaxReadResult()

                // Check if this is a PDF CARFAX / inspection report -> pass native PDF bytes to Gemini
                if ("application/pdf" in contentType || url.lowercase().substringBefore('?').endsWith(".pdf")) {
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
                val doc = Jsoup.parse(html, url)
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
            Log.w(TAG, "Direct HTTP CARFAX fetch failed for $url: ${e.message}")
            CarfaxReadResult()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun tryRenderWithHeadlessWebView(
        context: Context,
        url: String,
        timeoutMs: Long
    ): String? = withTimeoutOrNull(timeoutMs) {
        withContext(Dispatchers.Main) {
            var webView: WebView? = null
            try {
                webView = WebView(context.applicationContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.loadsImagesAutomatically = false
                    settings.blockNetworkImage = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    // Keep the genuine Chromium engine version (remove only "; wv") for reCAPTCHA v3 compatibility
                    val nativeUa = settings.userAgentString.orEmpty()
                    if (nativeUa.isNotBlank()) {
                        settings.userAgentString = nativeUa.replace("; wv)", ")")
                    }
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean = false
                    }
                    // Give the offscreen WebView real viewport bounds so responsive SPAs & IntersectionObservers render
                    measure(
                        android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                        android.view.View.MeasureSpec.makeMeasureSpec(2400, android.view.View.MeasureSpec.EXACTLY)
                    )
                    layout(0, 0, 1080, 2400)
                }
                runCatching {
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                }

                webView.loadUrl(url)

                // Wait for SPA bootstrap + reCAPTCHA v3 + report API fetch
                delay(1_800L)
                var bestText = ""
                var renderedHits = 0
                var scrolled = false
                val maxPolls = ((timeoutMs - 2_000L) / 700L).toInt().coerceIn(5, 20)

                for (poll in 0 until maxPolls) {
                    val text = evaluateBodyText(webView)
                    if (text.length > bestText.length) {
                        bestText = text
                    }
                    if (isCarfaxRendered(text)) {
                        renderedHits++
                        if (!scrolled) {
                            scrolled = true
                            // Trigger any lazy-loaded Detailed History / Service Record sections
                            webView.evaluateJavascript(
                                "try{window.scrollTo(0, document.body.scrollHeight);}catch(e){}",
                                null
                            )
                        }
                        // Give CARFAX 1 extra beat so Detailed History / Service rows finish rendering
                        if (renderedHits >= 2 && text.length >= 1_200) {
                            return@withContext if (text.length >= bestText.length) text else bestText
                        }
                    }
                    delay(700L)
                }

                if (isCarfaxRendered(bestText) || bestText.length >= 500) {
                    bestText
                } else {
                    null
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Headless WebView CARFAX render unavailable: ${e.message}")
                null
            } finally {
                runCatching {
                    webView?.stopLoading()
                    webView?.loadUrl("about:blank")
                    webView?.clearHistory()
                    webView?.removeAllViews()
                    webView?.destroy()
                }
            }
        }
    }

    private suspend fun evaluateBodyText(webView: WebView): String =
        suspendCancellableCoroutine { cont ->
            val script = """
                (function() {
                    try {
                        var bodyText = document.body ? (document.body.innerText || document.body.textContent || '') : '';
                        var root = document.querySelector('main, #app, #root, app-root, .report-container');
                        var rootText = root ? (root.innerText || root.textContent || '') : '';
                        return rootText.length > bodyText.length * 0.6 ? rootText : bodyText;
                    } catch (e) {
                        return '';
                    }
                })();
            """.trimIndent()
            webView.evaluateJavascript(script) { raw ->
                val decoded = decodeJsString(raw)
                if (cont.isActive) cont.resume(decoded)
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
        if (cleaned.length < 300) return false
        val lower = cleaned.lowercase()
        // Reject pure loading/bot/JS-disabled screens
        if (cleaned.length < 450 && (
                "enable javascript" in lower ||
                    "please wait" in lower ||
                    "checking your browser" in lower ||
                    "loading report" in lower
                )
        ) {
            return false
        }
        val matchedSignals = carfaxSignalKeywords.count { it in lower }
        return matchedSignals >= 3
    }

    internal fun condenseCarfaxText(raw: String, maxChars: Int = 5_500): String {
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

        // Collapse only consecutive duplicate lines so repeated provinces/statuses under different dates remain intact
        val deduped = ArrayList<String>(filtered.size)
        for (line in filtered) {
            if (deduped.lastOrNull() != line) {
                deduped.add(line)
            }
        }

        return deduped.joinToString("\n").take(maxChars)
    }
}
