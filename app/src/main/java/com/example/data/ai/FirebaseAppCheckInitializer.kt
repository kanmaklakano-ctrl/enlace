package com.example.data.ai

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory

private const val TAG = "AppCheckInit"

// App Check contract keys defined by the Firebase SDK and runtime harness.
private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"

@Volatile
var appCheckInitSummary: String =
    "v${BuildConfig.VERSION_NAME} (#${BuildConfig.VERSION_CODE}) • ${BuildConfig.BUILD_TYPE} • non initialisé"
    private set

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
            // 2. Debug: Read debug token from Intent extra
            val debugToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)

            if (!debugToken.isNullOrEmpty()) {
                val persistenceKey = firebaseApp.persistenceKey
                val prefs = context.getSharedPreferences(
                    "$DEBUG_STORE_PREFIX$persistenceKey",
                    Context.MODE_PRIVATE
                )
                // CRITICAL: MUST use commit() instead of apply() so the secret is written
                // synchronously before DebugAppCheckProviderFactory reads it.
                prefs.edit()
                    .putString(DEBUG_SECRET_KEY, debugToken)
                    .commit()
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
            // 3. Release: Look up recaptcha_site_key safely without throwing Resources.NotFoundException
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
