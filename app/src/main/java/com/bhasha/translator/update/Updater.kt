// SPDX-License-Identifier: AGPL-3.0-or-later
package com.bhasha.translator.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.bhasha.translator.core.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val version: String) : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val downloaded: Long, val total: Long) : UpdateState
    data class Ready(val release: Release, val apk: File) : UpdateState
    data class Failed(val message: String, val release: Release? = null) : UpdateState
}

data class Release(val version: String, val notes: String, val apkUrl: String, val apkBytes: Long, val sha256Url: String?)

/**
 * App updates from the project's GitHub releases. The app is installed from an
 * APK rather than a store, so it looks for a newer release itself, downloads
 * the APK, checks it against the published SHA-256 and hands it to Android's
 * installer. Android asks the user to confirm, and refuses any APK that is not
 * signed with the same key as the installed app.
 */
class Updater(private val context: Context, private val settings: Settings, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state
    private var job: Job? = null

    val installedVersion: String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"

    /** The once-a-day check made when the app opens; silent unless an update exists. */
    fun checkIfDue() {
        if (!settings.autoUpdateCheck.value) return
        if (System.currentTimeMillis() - settings.lastUpdateCheck < CHECK_EVERY_MS) return
        check(quiet = true)
    }

    fun check(quiet: Boolean = false) {
        if (job?.isActive == true) return
        job = scope.launch {
            if (!quiet) _state.value = UpdateState.Checking
            val result = runCatching { withContext(Dispatchers.IO) { latestRelease() } }
            val release = result.getOrNull()
            if (result.isSuccess) settings.lastUpdateCheck = System.currentTimeMillis()
            _state.value = when {
                release != null && isNewer(release.version, installedVersion) -> UpdateState.Available(release)
                result.isSuccess -> if (quiet) UpdateState.Idle else UpdateState.UpToDate(installedVersion)
                quiet -> UpdateState.Idle
                else -> UpdateState.Failed("Could not reach GitHub. Check the internet connection and try again.")
            }
        }
    }

    fun download(release: Release) {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = UpdateState.Downloading(release, 0, release.apkBytes)
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val dir = File(context.externalCacheDir ?: context.cacheDir, "updates").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val apk = File(dir, "Bhasha-${release.version}.apk")
                    fetch(release.apkUrl, apk) { done ->
                        _state.value = UpdateState.Downloading(release, done, release.apkBytes)
                    }
                    val expected = release.sha256Url?.let { text(it).trim().substringBefore(' ').lowercase() }
                    if (expected != null && expected != sha256(apk)) {
                        apk.delete()
                        error("The download does not match the published checksum.")
                    }
                    apk
                }
            }
            _state.value = result.fold(
                onSuccess = { UpdateState.Ready(release, it) },
                onFailure = { UpdateState.Failed(it.message ?: "The download failed.", release) },
            )
        }
    }

    /** Opens Android's installer on the downloaded APK. */
    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun dismiss() {
        job?.cancel()
        _state.value = UpdateState.Idle
    }

    private fun latestRelease(): Release? {
        val reply = json.decodeFromString<GitHubRelease>(text("$API/releases/latest"))
        val apk = reply.assets.firstOrNull { it.name.endsWith(".apk") } ?: return null
        val sum = reply.assets.firstOrNull { it.name == apk.name + ".sha256" }
        return Release(reply.tag.removePrefix("v"), reply.body.orEmpty().trim(), apk.url, apk.size, sum?.url)
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Bhasha-Android")
            setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
        }

    private fun text(url: String): String {
        val c = open(url)
        try {
            if (c.responseCode != 200) error("GitHub answered ${c.responseCode}.")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private suspend fun fetch(url: String, to: File, progress: (Long) -> Unit) = withContext(Dispatchers.IO) {
        val c = open(url)
        try {
            if (c.responseCode != 200) error("GitHub answered ${c.responseCode}.")
            c.inputStream.use { input ->
                to.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    var done = 0L
                    var reported = 0L
                    while (true) {
                        ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (done - reported >= 512 * 1024) {
                            reported = done
                            progress(done)
                        }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Serializable
    private class GitHubRelease(
        @SerialName("tag_name") val tag: String,
        val body: String? = null,
        val assets: List<Asset> = emptyList(),
    )

    @Serializable
    private class Asset(
        val name: String,
        val size: Long = 0,
        @SerialName("browser_download_url") val url: String,
    )

    companion object {
        const val REPOSITORY = "https://github.com/debmalyaroy/bhasha"
        private const val API = "https://api.github.com/repos/debmalyaroy/bhasha"
        private const val CHECK_EVERY_MS = 24 * 60 * 60 * 1000L
        private val json = Json { ignoreUnknownKeys = true }

        /** Compares MAJOR.MINOR.PATCH versions. */
        fun isNewer(candidate: String, installed: String): Boolean {
            fun parts(v: String) = v.split('.', '-').map { it.toIntOrNull() ?: 0 }
            val a = parts(candidate)
            val b = parts(installed)
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
