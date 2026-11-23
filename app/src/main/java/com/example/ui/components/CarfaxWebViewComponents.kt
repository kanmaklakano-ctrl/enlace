package com.example.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.ai.CarfaxExtractedSnapshot
import com.example.data.ai.CarfaxRenderRequest
import com.example.data.ai.CarfaxReportReader
import com.example.ui.theme.LiveEmerald
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

@SuppressLint("SetJavaScriptEnabled")
private fun configureCarfaxWebView(
    webView: WebView,
    onProgressChanged: (Int) -> Unit = {},
    onPageFinished: () -> Unit = {}
) {
    webView.layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )
    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        loadsImagesAutomatically = true
        blockNetworkImage = false
        useWideViewPort = true
        loadWithOverviewMode = true
        cacheMode = WebSettings.LOAD_DEFAULT
        javaScriptCanOpenWindowsAutomatically = false
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        // Preserve authentic Chromium version while removing "; wv)" marker for reCAPTCHA v3
        val nativeUa = userAgentString.orEmpty()
        if (nativeUa.isNotBlank()) {
            userAgentString = nativeUa.replace("; wv)", ")")
        }
    }
    runCatching {
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
    }
    webView.webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            onProgressChanged(newProgress)
        }

        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean = true
    }
    webView.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView?,
            request: WebResourceRequest?
        ): Boolean = false

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            view?.evaluateJavascript(CarfaxReportReader.CARFAX_INTERCEPTOR_JS, null)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            view?.evaluateJavascript(CarfaxReportReader.CARFAX_INTERCEPTOR_JS, null)
            onPageFinished()
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            // Swallow transient sub-resource errors silently to avoid logcat noise
        }
    }
}

private suspend fun extractCarfaxFromWebView(webView: WebView): CarfaxExtractedSnapshot =
    suspendCancellableCoroutine { cont ->
        runCatching {
            webView.evaluateJavascript(CarfaxReportReader.CARFAX_EXTRACT_JS) { raw ->
                val snapshot = CarfaxReportReader.parseExtractionPayload(raw)
                if (cont.isActive) cont.resume(snapshot)
            }
        }.onFailure {
            if (cont.isActive) {
                cont.resume(CarfaxExtractedSnapshot("", hasApiPayload = false, isCarfaxReady = false, rawCharCount = 0))
            }
        }
    }

private fun safeDestroyWebView(webView: WebView?) {
    runCatching {
        webView?.stopLoading()
        webView?.webChromeClient = null
        webView?.webViewClient = WebViewClient()
        webView?.loadUrl("about:blank")
        webView?.removeAllViews()
        webView?.destroy()
    }
}

/**
 * Window-attached silent WebView mounted behind the main Scaffold inside the Activity Window.
 * Because it is genuinely attached to the Activity Window with real mobile viewport bounds and
 * alpha(0.01f), Chromium keeps VSync, requestAnimationFrame, and reCAPTCHA v3 active.
 */
@Composable
fun HiddenAttachedCarfaxWebView(
    request: CarfaxRenderRequest?,
    modifier: Modifier = Modifier
) {
    if (request == null) return

    var webViewRef by remember(request.id) { mutableStateOf<WebView?>(null) }

    Box(
        modifier = modifier
            .size(width = 390.dp, height = 844.dp)
            .alpha(0.01f)
    ) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).also { wv ->
                    configureCarfaxWebView(wv)
                    wv.loadUrl(request.url)
                    webViewRef = wv
                }
            },
            onRelease = { wv ->
                webViewRef = null
                safeDestroyWebView(wv)
            },
            modifier = Modifier.fillMaxSize()
        )
    }

    LaunchedEffect(request.id, webViewRef) {
        val wv = webViewRef ?: return@LaunchedEffect
        delay(2_200L)
        var bestSnapshot = CarfaxExtractedSnapshot("", hasApiPayload = false, isCarfaxReady = false, rawCharCount = 0)
        var readyHits = 0
        var scrolled = false

        for (poll in 0 until 22) {
            if (request.deferred.isCompleted) break
            val snap = extractCarfaxFromWebView(wv)
            if (snap.rawCharCount > bestSnapshot.rawCharCount || (snap.isCarfaxReady && !bestSnapshot.isCarfaxReady)) {
                bestSnapshot = snap
            }
            if (snap.isCarfaxReady) {
                readyHits++
                if (!scrolled) {
                    scrolled = true
                    runCatching {
                        wv.evaluateJavascript(
                            "try{window.scrollTo(0, document.body.scrollHeight);}catch(e){}",
                            null
                        )
                    }
                }
                if (readyHits >= 2 || snap.hasApiPayload) {
                    val chosen = if (snap.rawCharCount >= bestSnapshot.rawCharCount) snap.combinedText else bestSnapshot.combinedText
                    request.deferred.complete(CarfaxReportReader.condenseCarfaxText(chosen))
                    return@LaunchedEffect
                }
            }
            delay(750L)
        }

        if (!request.deferred.isCompleted) {
            val result = if (bestSnapshot.isCarfaxReady || bestSnapshot.rawCharCount >= 600) {
                CarfaxReportReader.condenseCarfaxText(bestSnapshot.combinedText)
            } else {
                null
            }
            request.deferred.complete(result)
        }
    }

    DisposableEffect(request.id) {
        onDispose {
            if (!request.deferred.isCompleted) {
                request.deferred.complete(null)
            }
        }
    }
}

/**
 * Interactive full-screen in-app CARFAX Reader dialog.
 * Displays the live CARFAX report inside EncanLive, detects automatically when the report
 * finishes loading (even if Cloudflare/reCAPTCHA asks for 1 tap), and sends the full extracted
 * text + intercepted API data straight to Gemini in one tap.
 */
@Composable
fun CarfaxReaderDialog(
    url: String,
    onDismiss: () -> Unit,
    onAnalyzeCapturedCarfax: (String) -> Unit,
    onOpenExternal: (String) -> Unit
) {
    var webViewRef by remember(url) { mutableStateOf<WebView?>(null) }
    var loadProgress by remember(url) { mutableIntStateOf(10) }
    var latestSnapshot by remember(url) {
        mutableStateOf(CarfaxExtractedSnapshot("", hasApiPayload = false, isCarfaxReady = false, rawCharCount = 0))
    }
    var isCapturingNow by remember(url) { mutableStateOf(false) }

    LaunchedEffect(url, webViewRef) {
        val wv = webViewRef ?: return@LaunchedEffect
        delay(1_400L)
        while (true) {
            val snap = extractCarfaxFromWebView(wv)
            if (snap.rawCharCount >= latestSnapshot.rawCharCount || snap.isCarfaxReady) {
                latestSnapshot = snap
            }
            delay(1_000L)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Top bar
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 4.dp
            ) {
                Column(modifier = Modifier.statusBarsPadding()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.testTag("carfax_modal_close_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Fermer le rapport CARFAX",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Icon(
                            imageVector = Icons.Filled.DirectionsCar,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Lecteur CARFAX intégré",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = url.removePrefix("https://").removePrefix("http://"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = { onOpenExternal(url) },
                            modifier = Modifier.testTag("carfax_modal_external_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = "Ouvrir dans le navigateur externe",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (loadProgress in 1..99) {
                        LinearProgressIndicator(
                            progress = { loadProgress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Visible interactive WebView
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).also { wv ->
                            configureCarfaxWebView(
                                webView = wv,
                                onProgressChanged = { p -> loadProgress = p }
                            )
                            wv.loadUrl(url)
                            webViewRef = wv
                        }
                    },
                    onRelease = { wv ->
                        webViewRef = null
                        safeDestroyWebView(wv)
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Bottom AI Capture & Send Bar
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 10.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (latestSnapshot.isCarfaxReady) {
                            Icon(
                                imageVector = Icons.Filled.Verified,
                                contentDescription = null,
                                tint = LiveEmerald,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "Rapport CARFAX détecté ! Appuyez ci-dessous pour l'analyser par l'IA.",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = LiveEmerald
                            )
                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Attendez l'affichage du rapport (ou validez la case anti-robot si demandée)…",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    val canAnalyze = latestSnapshot.isCarfaxReady || latestSnapshot.rawCharCount >= 180
                    Button(
                        onClick = {
                            if (isCapturingNow) return@Button
                            isCapturingNow = true
                            val wv = webViewRef
                            if (wv != null) {
                                wv.evaluateJavascript(CarfaxReportReader.CARFAX_EXTRACT_JS) { raw ->
                                    val fresh = CarfaxReportReader.parseExtractionPayload(raw)
                                    val best = if (fresh.rawCharCount >= latestSnapshot.rawCharCount) {
                                        fresh.combinedText
                                    } else {
                                        latestSnapshot.combinedText
                                    }
                                    val condensed = CarfaxReportReader.condenseCarfaxText(best)
                                    isCapturingNow = false
                                    if (condensed.isNotBlank()) {
                                        onAnalyzeCapturedCarfax(condensed)
                                    }
                                }
                            } else {
                                isCapturingNow = false
                                val condensed = CarfaxReportReader.condenseCarfaxText(latestSnapshot.combinedText)
                                if (condensed.isNotBlank()) {
                                    onAnalyzeCapturedCarfax(condensed)
                                }
                            }
                        },
                        enabled = canAnalyze && !isCapturingNow,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("carfax_modal_analyze_ai_button"),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (latestSnapshot.isCarfaxReady) LiveEmerald else MaterialTheme.colorScheme.primary,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (latestSnapshot.isCarfaxReady) {
                                "Analyser ce rapport CARFAX avec l'IA"
                            } else {
                                "Envoyer cette page à l'IA"
                            },
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        }
    }
}
