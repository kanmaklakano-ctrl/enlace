package com.example.data.ai

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "AppCheckInit"

// App Check contract keys defined by the Firebase SDK and runtime harness.
private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"

// Local device preferences for optional personal sideloaded-device App Check debug token.
private const val ENCAN_APPCHECK_PREFS = "encan_appcheck_device_prefs"
private const val KEY_CANDIDATE_TOKEN = "candidate_debug_token"
private const val KEY_ACTIVE_PERSONAL_TOKEN = "active_personal_debug_token"

@Volatile
var appCheckInitSummary: String =
    "v${BuildConfig.VERSION_NAME} (#${BuildConfig.VERSION_CODE}) • ${BuildConfig.BUILD_TYPE} • non initialisé"
    private set

@Volatile
private var cachedActiveDebugTokenInProcess: String? = null

fun configureAppCheck(context: Context, intent: Intent?) {
    val buildPrefix = "v${BuildConfig.VERSION_NAME} (#${BuildConfig.VERSION_CODE}) • ${BuildConfig.BUILD_TYPE}"
    try {
        // 1. Ensure FirebaseApp is initialized
        val firebaseApp = FirebaseApp.getApps(context).firstOrNull()
            ?: FirebaseApp.initializeApp(context)

        if (firebaseApp == null) {
            appCheckInitSummary = "$buildPrefix • FirebaseApp absent (google-services.json manquant)"
            Log.e(TAG, "FirebaseApp could not be initialized (missing google-services.json).")
            return
        }

        if (BuildConfig.DEBUG) {
            // 2. Debug: Read debug token from Intent extra (AI Studio / emulator harness)
            val debugToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)
                ?.takeIf { it.isNotBlank() }
                ?: readActivePersonalAppCheckToken(context)

            if (!debugToken.isNullOrEmpty()) {
                writeDebugSecretSync(context, firebaseApp.persistenceKey, debugToken)
                cachedActiveDebugTokenInProcess = debugToken
                Log.d(TAG, "App Check debug secret configured successfully.")
            } else {
                Log.w(TAG, "No debug token found in Intent extra.")
            }

            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance()
            )
            appCheckInitSummary = "$buildPrefix • DebugProvider (token=${if (!debugToken.isNullOrEmpty()) "oui" else "non"})"
            Log.d(TAG, "DebugAppCheckProviderFactory installed.")
        } else {
            // 3. Release: Use user-authorized personal device debug token if explicitly activated
            // for sideloaded APKs installed outside Google Play; otherwise install reCAPTCHA Enterprise.
            val personalToken = readActivePersonalAppCheckToken(context)
            if (!personalToken.isNullOrBlank()) {
                writeDebugSecretSync(context, firebaseApp.persistenceKey, personalToken)
                cachedActiveDebugTokenInProcess = personalToken
                FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                    DebugAppCheckProviderFactory.getInstance()
                )
                appCheckInitSummary = "$buildPrefix • Jeton appareil (${personalToken.take(8)}…)"
                Log.d(TAG, "Personal device DebugAppCheckProviderFactory installed.")
                return
            }

            val resId = context.resources.getIdentifier(
                RECAPTCHA_SITE_KEY_RES,
                "string",
                context.packageName
            )
            if (resId != 0) {
                val siteKey = context.getString(resId)
                if (siteKey.isNotBlank()) {
                    FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                        RecaptchaAppCheckProviderFactory.getInstance(siteKey)
                    )
                    appCheckInitSummary = "$buildPrefix • reCAPTCHA (${siteKey.take(8)}…)"
                    Log.d(TAG, "RecaptchaAppCheckProviderFactory installed.")
                } else {
                    appCheckInitSummary = "$buildPrefix • recaptcha_site_key vide"
                    Log.e(TAG, "recaptcha_site_key resource string is blank.")
                }
            } else {
                appCheckInitSummary = "$buildPrefix • recaptcha_site_key introuvable"
                Log.e(TAG, "recaptcha_site_key string resource not found in package.")
            }
        }
    } catch (e: Exception) {
        appCheckInitSummary = "$buildPrefix • erreur init (${e.javaClass.simpleName})"
        Log.e(TAG, "Failed to initialize Firebase App Check", e)
    }
}

private fun writeDebugSecretSync(context: Context, persistenceKey: String, debugToken: String) {
    val prefs = context.getSharedPreferences(
        "$DEBUG_STORE_PREFIX$persistenceKey",
        Context.MODE_PRIVATE
    )
    // CRITICAL: MUST use commit() instead of apply() so the secret is written
    // synchronously before DebugAppCheckProviderFactory reads it.
    prefs.edit()
        .putString(DEBUG_SECRET_KEY, debugToken)
        .commit()
}

fun readActivePersonalAppCheckToken(context: Context): String? {
    val prefs = context.getSharedPreferences(ENCAN_APPCHECK_PREFS, Context.MODE_PRIVATE)
    return prefs.getString(KEY_ACTIVE_PERSONAL_TOKEN, null)?.trim()?.takeIf { it.isNotBlank() }
}

fun getOrCreateDeviceAppCheckToken(context: Context): String {
    val prefs = context.getSharedPreferences(ENCAN_APPCHECK_PREFS, Context.MODE_PRIVATE)
    val existingActive = prefs.getString(KEY_ACTIVE_PERSONAL_TOKEN, null)?.trim()
    if (!existingActive.isNullOrBlank()) return existingActive

    val existingCandidate = prefs.getString(KEY_CANDIDATE_TOKEN, null)?.trim()
    if (!existingCandidate.isNullOrBlank()) return existingCandidate

    // Also reuse any existing Firebase debug secret already stored on this device if present
    val firebaseApp = FirebaseApp.getApps(context).firstOrNull()
    val existingSdkSecret = firebaseApp?.let { app ->
        context.getSharedPreferences("$DEBUG_STORE_PREFIX${app.persistenceKey}", Context.MODE_PRIVATE)
            .getString(DEBUG_SECRET_KEY, null)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }
    val token = existingSdkSecret ?: UUID.randomUUID().toString()
    prefs.edit().putString(KEY_CANDIDATE_TOKEN, token).commit()
    return token
}

fun getFirebaseAppCheckConsoleUrl(context: Context): String {
    val options = FirebaseApp.getApps(context).firstOrNull()?.options
    val projectId = options?.projectId.orEmpty()
    val appId = options?.applicationId.orEmpty()
    return when {
        projectId.isNotBlank() && appId.isNotBlank() ->
            "https://console.firebase.google.com/project/$projectId/appcheck/apps?selectedAppId=$appId"
        projectId.isNotBlank() ->
            "https://console.firebase.google.com/project/$projectId/appcheck/apps"
        else -> "https://console.firebase.google.com/"
    }
}

fun getFirebaseAppCheckProductsUrl(context: Context): String {
    val projectId = FirebaseApp.getApps(context).firstOrNull()?.options?.projectId.orEmpty()
    return if (projectId.isNotBlank()) {
        "https://console.firebase.google.com/project/$projectId/appcheck/products"
    } else {
        "https://console.firebase.google.com/"
    }
}

/**
 * Saves and activates a personal Firebase App Check Debug Token on this device,
 * then immediately tests live token exchange against Firebase App Check.
 */
suspend fun activateAndVerifyPersonalAppCheckToken(
    context: Context,
    rawToken: String
): Result<String> = withContext(Dispatchers.IO) {
    val token = rawToken.trim()
    val uuidRegex = Regex("""^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$""")
    if (!uuidRegex.matches(token)) {
        return@withContext Result.failure(
            IllegalArgumentException("Format du jeton invalide. Un jeton Firebase App Check ressemble à : xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx")
        )
    }

    val appContext = context.applicationContext
    val firebaseApp = FirebaseApp.getApps(appContext).firstOrNull()
        ?: FirebaseApp.initializeApp(appContext)
        ?: return@withContext Result.failure(
            IllegalStateException("FirebaseApp n'est pas initialisé dans cet APK.")
        )

    // Save synchronously in both EncanLive prefs and Firebase DebugAppCheckProvider store
    appContext.getSharedPreferences(ENCAN_APPCHECK_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_CANDIDATE_TOKEN, token)
        .putString(KEY_ACTIVE_PERSONAL_TOKEN, token)
        .commit()
    writeDebugSecretSync(appContext, firebaseApp.persistenceKey, token)

    val needsRestartForDifferentDebugToken =
        cachedActiveDebugTokenInProcess != null && cachedActiveDebugTokenInProcess != token

    if (needsRestartForDifferentDebugToken) {
        return@withContext Result.failure(
            IllegalStateException("Nouveau jeton enregistré ! Fermez complètement EncanLive puis rouvrez-la pour appliquer le nouveau jeton.")
        )
    }

    cachedActiveDebugTokenInProcess = token
    FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
        DebugAppCheckProviderFactory.getInstance()
    )

    val buildPrefix = "v${BuildConfig.VERSION_NAME} (#${BuildConfig.VERSION_CODE}) • ${BuildConfig.BUILD_TYPE}"
    appCheckInitSummary = "$buildPrefix • Jeton appareil (${token.take(8)}…)"

    return@withContext try {
        withTimeout(15_000L) {
            suspendCancellableCoroutine<Unit> { cont ->
                FirebaseAppCheck.getInstance()
                    .getAppCheckToken(true)
                    .addOnSuccessListener {
                        if (cont.isActive) cont.resume(Unit)
                    }
                    .addOnFailureListener { error ->
                        if (cont.isActive) cont.resumeWithException(error)
                    }
            }
        }
        appCheckInitSummary = "$buildPrefix • Jeton appareil vérifié ✓"
        Result.success("Appareil autorisé ! L'agent IA est maintenant activé de façon permanente sur ce téléphone.")
    } catch (e: Exception) {
        Log.w(TAG, "Personal App Check token verification failed", e)
        val rawMsg = e.localizedMessage.orEmpty()
        val friendly = if (rawMsg.contains("403") || rawMsg.contains("attestation", ignoreCase = true)) {
            "Firebase n'a pas encore validé ce code. Dans la console Firebase (App Check → Apps → com.aistudio.encanlive.qxmkvr → ⋮ → Manage debug tokens), collez exactement ce jeton, cliquez sur Save, puis réappuyez sur Activer & tester."
        } else {
            "Vérification échouée : ${rawMsg.ifBlank { e.javaClass.simpleName }}"
        }
        Result.failure(IllegalStateException(friendly, e))
    }
}

fun clearPersonalAppCheckToken(context: Context) {
    val appContext = context.applicationContext
    appContext.getSharedPreferences(ENCAN_APPCHECK_PREFS, Context.MODE_PRIVATE)
        .edit()
        .remove(KEY_ACTIVE_PERSONAL_TOKEN)
        .commit()

    val buildPrefix = "v${BuildConfig.VERSION_NAME} (#${BuildConfig.VERSION_CODE}) • ${BuildConfig.BUILD_TYPE}"
    if (!BuildConfig.DEBUG) {
        val resId = appContext.resources.getIdentifier(
            RECAPTCHA_SITE_KEY_RES,
            "string",
            appContext.packageName
        )
        if (resId != 0) {
            val siteKey = appContext.getString(resId)
            if (siteKey.isNotBlank()) {
                FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                    RecaptchaAppCheckProviderFactory.getInstance(siteKey)
                )
                appCheckInitSummary = "$buildPrefix • reCAPTCHA (${siteKey.take(8)}…)"
            }
        }
    }
}
