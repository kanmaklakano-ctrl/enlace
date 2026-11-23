package com.example.data.update

import android.util.Log
import com.example.BuildConfig
import com.example.data.network.AuctionHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

private const val TAG = "AppUpdateChecker"

data class AppUpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val releaseNotes: String,
    val publishedAt: String,
    val releasePageUrl: String,
    val downloadUrl: String,
    val repoSlug: String
)

sealed interface AppUpdateStatus {
    data object Idle : AppUpdateStatus
    data object Checking : AppUpdateStatus
    data object NotConfigured : AppUpdateStatus
    data class UpToDate(
        val installedVersionName: String,
        val installedVersionCode: Int,
        val repoSlug: String
    ) : AppUpdateStatus
    data class UpdateAvailable(val info: AppUpdateInfo) : AppUpdateStatus
    data class Error(val message: String) : AppUpdateStatus
}

object AppUpdateChecker {

    /**
     * Normalizes either "owner/repo" or a full GitHub URL such as
     * "https://github.com/owner/repo/releases" into "owner/repo".
     */
    fun normalizeRepoSlug(raw: String?): String {
        val trimmed = raw.orEmpty().trim()
        if (trimmed.isBlank()) return ""
        val withoutScheme = trimmed
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .removePrefix("github.com/")
            .removeSuffix(".git")
            .trim('/')
        val parts = withoutScheme.split('/').filter { it.isNotBlank() }
        if (parts.size < 2) return ""
        val owner = parts[0]
        val repo = parts[1]
        val validPart = Regex("""^[A-Za-z0-9_.-]+$""")
        return if (validPart.matches(owner) && validPart.matches(repo)) "$owner/$repo" else ""
    }

    suspend fun checkForUpdate(
        repoSlugRaw: String,
        installedVersionCode: Int = BuildConfig.VERSION_CODE,
        installedVersionName: String = BuildConfig.VERSION_NAME
    ): AppUpdateStatus = withContext(Dispatchers.IO) {
        val slug = normalizeRepoSlug(repoSlugRaw)
        if (slug.isBlank()) {
            return@withContext AppUpdateStatus.NotConfigured
        }

        try {
            // Layer 1: Direct version.json published on the latest-apk GitHub Release (fast, no API rate limit)
            val versionJsonUrl = "https://github.com/$slug/releases/download/latest-apk/version.json"
            val directJson = fetchString(versionJsonUrl)
            val parsedDirect = directJson?.let { parseVersionJson(it, slug) }
            if (parsedDirect != null) {
                return@withContext evaluateUpdate(
                    remote = parsedDirect,
                    installedVersionCode = installedVersionCode,
                    installedVersionName = installedVersionName
                )
            }

            // Layer 2: Fallback to GitHub Releases REST API (handles repos before version.json existed)
            val apiUrl = "https://api.github.com/repos/$slug/releases/tags/latest-apk"
            val apiBody = fetchString(apiUrl, accept = "application/vnd.github+json")
            val parsedApi = apiBody?.let { parseGitHubReleaseApiJson(it, slug) }
            if (parsedApi != null) {
                return@withContext evaluateUpdate(
                    remote = parsedApi,
                    installedVersionCode = installedVersionCode,
                    installedVersionName = installedVersionName
                )
            }

            AppUpdateStatus.Error("Impossible de vérifier les mises à jour pour $slug (vérifiez que le dépôt est public et qu'une Release latest-apk existe).")
        } catch (e: Exception) {
            Log.d(TAG, "Update check failed for $slug: ${e.message}")
            AppUpdateStatus.Error("Vérification impossible pour le moment : ${e.localizedMessage ?: "erreur réseau"}.")
        }
    }

    internal fun evaluateUpdate(
        remote: AppUpdateInfo,
        installedVersionCode: Int,
        installedVersionName: String
    ): AppUpdateStatus {
        val isNewer = when {
            remote.versionCode > 0 -> remote.versionCode > installedVersionCode
            remote.versionName.isNotBlank() -> isNewerSemver(remote.versionName, installedVersionName)
            else -> false
        }
        return if (isNewer) {
            AppUpdateStatus.UpdateAvailable(remote)
        } else {
            AppUpdateStatus.UpToDate(
                installedVersionName = installedVersionName,
                installedVersionCode = installedVersionCode,
                repoSlug = remote.repoSlug
            )
        }
    }

    internal fun parseVersionJson(jsonText: String, repoSlug: String): AppUpdateInfo? {
        return try {
            val obj = JSONObject(jsonText.trim())
            val versionCode = obj.optInt("versionCode", 0)
            val versionName = obj.optString("versionName", "").trim()
            if (versionCode <= 0 && versionName.isBlank()) return null

            val defaultReleasePage = "https://github.com/$repoSlug/releases/tag/latest-apk"
            val defaultDownload = "https://github.com/$repoSlug/releases/download/latest-apk/EncanLive.apk"

            AppUpdateInfo(
                versionCode = versionCode,
                versionName = versionName.ifBlank { "build $versionCode" },
                releaseNotes = obj.optString("releaseNotes", "").trim(),
                publishedAt = obj.optString("publishedAt", "").trim().take(10),
                releasePageUrl = obj.optString("releasePageUrl", "").trim().ifBlank { defaultReleasePage },
                downloadUrl = obj.optString("downloadUrl", "").trim().ifBlank { defaultDownload },
                repoSlug = repoSlug
            )
        } catch (e: Exception) {
            Log.d(TAG, "Failed parsing version.json: ${e.message}")
            null
        }
    }

    internal fun parseGitHubReleaseApiJson(jsonText: String, repoSlug: String): AppUpdateInfo? {
        return try {
            val obj = JSONObject(jsonText.trim())
            val title = obj.optString("name", "").trim()
            val body = obj.optString("body", "").trim()
            val htmlUrl = obj.optString("html_url", "https://github.com/$repoSlug/releases/tag/latest-apk").trim()
            val publishedAt = obj.optString("published_at", "").trim().take(10)

            var apkUrl = htmlUrl
            val assets = obj.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        val dl = asset.optString("browser_download_url", "").trim()
                        if (dl.isNotBlank()) {
                            apkUrl = dl
                            break
                        }
                    }
                }
            }

            val combined = "$title $body"
            val buildMatch = Regex("""(?i)build\s+(\d+)""").find(combined)
            val versionMatch = Regex("""(?i)\bv?(\d+\.\d+(?:\.\d+)?)\b""").find(title)
            val versionCode = buildMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            val versionName = versionMatch?.groupValues?.getOrNull(1).orEmpty()

            if (versionCode <= 0 && versionName.isBlank()) return null

            AppUpdateInfo(
                versionCode = versionCode,
                versionName = versionName.ifBlank { "build $versionCode" },
                releaseNotes = body.take(240),
                publishedAt = publishedAt,
                releasePageUrl = htmlUrl,
                downloadUrl = apkUrl,
                repoSlug = repoSlug
            )
        } catch (e: Exception) {
            Log.d(TAG, "Failed parsing GitHub Release API JSON: ${e.message}")
            null
        }
    }

    internal fun isNewerSemver(remote: String, local: String): Boolean {
        val rParts = remote.trimStart('v', 'V').split('.').map { it.toIntOrNull() ?: 0 }
        val lParts = local.trimStart('v', 'V').split('.').map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(rParts.size, lParts.size)
        for (i in 0 until maxLen) {
            val rv = rParts.getOrElse(i) { 0 }
            val lv = lParts.getOrElse(i) { 0 }
            if (rv != lv) return rv > lv
        }
        return false
    }

    private fun fetchString(url: String, accept: String = "application/json"): String? {
        val req = Request.Builder()
            .url(url)
            .header("Accept", accept)
            .header("User-Agent", "EncanLive-Android/${BuildConfig.VERSION_NAME}")
            .build()
        return AuctionHttpClient.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string()
        }
    }
}
