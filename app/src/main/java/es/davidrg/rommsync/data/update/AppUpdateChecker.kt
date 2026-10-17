package es.davidrg.rommsync.data.update

import okhttp3.OkHttpClient
import okhttp3.Request
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import es.davidrg.rommsync.BuildConfig
import es.davidrg.rommsync.core.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Outcome of checking GitHub for a newer release.
 */
sealed class UpdateCheckResult {
    /** Installed version matches the latest published release. */
    data object UpToDate : UpdateCheckResult()

    /** A newer release with a downloadable APK exists. */
    data class UpdateAvailable(
        val latestVersion: String,
        val currentVersion: String,
        val apkUrl: String,
        val apkName: String,
        val apkSize: Long,
        val releaseUrl: String,
        val releaseNotes: String,
    ) : UpdateCheckResult()

    /** Network/API failure with a user-presentable message. */
    data class Error(val message: String) : UpdateCheckResult()
}

/**
 * Checks the project's GitHub releases for a newer APK.
 *
 * Uses a dedicated lightweight OkHttp client (no auth interceptor) pointed at
 * api.github.com. The GitHub API requires a User-Agent header.
 */
class AppUpdateChecker(
    private val okHttpClient: OkHttpClient = defaultClient(),
    private val moshi: Moshi = defaultMoshi(),
    private val owner: String = OWNER,
    private val repo: String = REPO,
) {

    suspend fun check(): UpdateCheckResult = withContext(Dispatchers.IO) {
        val url = "https://api.github.com/repos/$owner/$repo/releases/latest"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/vnd.github+json")
            .build()

        val response = try {
            okHttpClient.newCall(request).execute()
        } catch (e: Exception) {
            return@withContext UpdateCheckResult.Error(
                tr("android.update.no_connection", e.message ?: tr("android.update.network_error")),
            )
        }

        response.use { resp ->
            if (!resp.isSuccessful) {
                return@withContext UpdateCheckResult.Error(
                    tr("android.update.github_response", resp.code),
                )
            }
            val body = resp.body?.string() ?: return@withContext UpdateCheckResult.Error(
                tr("android.update.empty_response"),
            )
            val release = try {
                moshi.adapter(GitHubReleaseDto::class.java).fromJson(body)
            } catch (e: Exception) {
                return@withContext UpdateCheckResult.Error(tr("android.update.invalid_response"))
            } ?: return@withContext UpdateCheckResult.Error(tr("android.update.invalid_response"))

            val apkAsset = release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
            val currentVersion = BuildConfig.VERSION_NAME

            if (apkAsset == null) {
                // Release without APK asset — treat as up to date rather than error.
                return@withContext UpdateCheckResult.UpToDate
            }

            if (isNewer(release.tagName, currentVersion)) {
                UpdateCheckResult.UpdateAvailable(
                    latestVersion = release.tagName.removePrefix("v"),
                    currentVersion = "v$currentVersion",
                    apkUrl = apkAsset.browserDownloadUrl,
                    apkSize = apkAsset.size,
                    apkName = apkAsset.name,
                    releaseUrl = release.htmlUrl ?: url,
                    releaseNotes = release.body.orEmpty(),
                )
            } else {
                UpdateCheckResult.UpToDate
            }
        }
    }

    companion object {
        const val OWNER = "Thinkscape"
        const val REPO = "romm-sync"
        private const val USER_AGENT = "RomM-Sync-Android-Updater"

        /** Lightweight client: short timeouts, no retries needed for a JSON GET. */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        fun defaultMoshi(): Moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

        /**
         * Compares two version strings where the remote tag may carry a `v`
         * prefix and build metadata ("1.2.3+meta" → "1.2.3"). Pre-release
         * suffixes follow semver ordering, so fork builds compare correctly:
         * `1.0.0-thinkscape.2` > `1.0.0-thinkscape.1`, and a plain `1.0.0`
         * is newer than any `1.0.0-…` pre-release.
         * Returns true when [remote] is strictly newer than [current].
         */
        fun isNewer(remote: String, current: String): Boolean = compareVersions(remote, current) > 0

        internal fun compareVersions(a: String, b: String): Int {
            val (coreA, preA) = split(a)
            val (coreB, preB) = split(b)
            for (i in 0 until 3) {
                if (coreA[i] != coreB[i]) return coreA[i].compareTo(coreB[i])
            }
            return comparePreRelease(preA, preB)
        }

        private fun split(version: String): Pair<List<Int>, List<String>> {
            val cleaned = version.trim()
                .removePrefix("v")
                .removePrefix("V")
                .substringBefore('+')
            val core = cleaned.substringBefore('-')
            val pre = if ('-' in cleaned) cleaned.substringAfter('-').split('.').filter { it.isNotEmpty() } else emptyList()
            val numbers = core.split('.').map { part ->
                part.filter(Char::isDigit).takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
            }.let { if (it.size >= 3) it.take(3) else it + List(3 - it.size) { 0 } }
            return numbers to pre
        }

        /** Semver §11: no pre-release > any pre-release; numeric ids numerically, others lexically. */
        private fun comparePreRelease(a: List<String>, b: List<String>): Int {
            if (a.isEmpty() || b.isEmpty()) return b.size.coerceAtMost(1) - a.size.coerceAtMost(1)
            for (i in 0 until minOf(a.size, b.size)) {
                val na = a[i].toLongOrNull()
                val nb = b[i].toLongOrNull()
                val cmp = when {
                    na != null && nb != null -> na.compareTo(nb)
                    na != null -> -1
                    nb != null -> 1
                    else -> a[i].compareTo(b[i])
                }
                if (cmp != 0) return cmp
            }
            return a.size.compareTo(b.size)
        }
    }
}
