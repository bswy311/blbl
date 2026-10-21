package blbl.cat3399.core.update

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import blbl.cat3399.BuildConfig
import blbl.cat3399.core.log.AppLog
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.net.await
import blbl.cat3399.core.net.ipv4OnlyDns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

object ApkUpdater {
    // Updates come from this fork's own GitHub Releases. The official channel (cat3399.top) can
    // never be installed over our builds because its APKs are signed with the original author's
    // key, so releasing there would only produce "安装失败" prompts.
    private const val UPDATE_REPO = "bswy311/blbl"
    private const val GITHUB_BASE = "https://github.com"

    /**
     * 候选 GitHub 加速镜像（前缀形式，直接拼在完整 github.com 地址前面）；空串＝直连 GitHub。
     *
     * 这里的顺序只是兜底优先级：真正请求前会并发测速，按实测速度排序，所以某个镜像今天快、
     * 明天慢都不影响（2026-10-21 实测 ghfast.top 只有 40~90KB/s 甚至 6 秒 0 字节，而
     * gh-proxy.com / hk.gh-proxy.com 能到 0.7~2MB/s，同一镜像开 4 个连接还能叠加）。
     */
    private val GITHUB_MIRROR_PREFIXES = listOf("https://gh-proxy.com/", "https://hk.gh-proxy.com/", "https://ghfast.top/", "")

    private const val CHANGELOG_PATH = "/$UPDATE_REPO/raw/main/CHANGELOG.md"

    private val CHANGELOG_URLS: List<String> = GITHUB_MIRROR_PREFIXES.map { prefix -> "$prefix$GITHUB_BASE$CHANGELOG_PATH" }

    /** 测速时每个源最多读这么多字节（越小越快挑完，越大越准）。 */
    private const val PROBE_BYTES = 160 * 1024

    private const val PROBE_TIME_BUDGET_MS = 3_000L

    /** 分段并发下载的段数：实测同一镜像 4 连接合计 ≈ 单连接的 3~5 倍。 */
    private const val DOWNLOAD_SEGMENTS = 4

    private const val TAG = "Update"

    val TEST_CHANGELOG_URL: String
        get() = CHANGELOG_URLS.first()

    private const val COOLDOWN_MS = 5_000L

    @Volatile
    private var lastStartedAtMs: Long = 0L

    private val okHttpLazy: Lazy<OkHttpClient> =
        lazy {
            OkHttpClient.Builder()
                .dns(ipv4OnlyDns { BiliClient.prefs.ipv4OnlyEnabled })
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        }

    private val okHttp: OkHttpClient
        get() = okHttpLazy.value

    // 下载用短一点的超时：镜像卡住时要尽快换源，而不是等满 60 秒。
    private val downloadClientLazy: Lazy<OkHttpClient> =
        lazy {
            okHttp
                .newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .build()
        }

    private val downloadClient: OkHttpClient
        get() = downloadClientLazy.value

    // 测速要快：连不上/不吐数据的镜像尽快判死，别拖着整个下载。
    private val probeClientLazy: Lazy<OkHttpClient> =
        lazy {
            downloadClient
                .newBuilder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(6, TimeUnit.SECONDS)
                .build()
        }

    private val probeClient: OkHttpClient
        get() = probeClientLazy.value

    fun evictConnections() {
        if (okHttpLazy.isInitialized()) okHttp.connectionPool.evictAll()
    }

    sealed class Progress {
        data object Connecting : Progress()

        data class Downloading(
            val downloadedBytes: Long,
            val totalBytes: Long?,
            val bytesPerSecond: Long,
        ) : Progress() {
            val percent: Int? =
                totalBytes?.takeIf { it > 0 }?.let { total ->
                    ((downloadedBytes.toDouble() / total.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
                }

            val hint: String =
                buildString {
                    if (totalBytes != null && totalBytes > 0) {
                        append("${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)}")
                    } else {
                        append(formatBytes(downloadedBytes))
                    }
                    if (bytesPerSecond > 0) append("（${formatBytes(bytesPerSecond)}/s）")
                }
        }
    }

    data class RemoteUpdate(
        val versionName: String,
        val changelog: String,
        val versions: List<RemoteUpdate> = emptyList(),
    ) {
        val displayChangelog: String
            get() = changelog.ifBlank { "暂无更新日志" }
    }

    fun markStarted(nowMs: Long = System.currentTimeMillis()) {
        lastStartedAtMs = nowMs
    }

    fun cooldownLeftMs(nowMs: Long = System.currentTimeMillis()): Long {
        val last = lastStartedAtMs
        val left = (last + COOLDOWN_MS) - nowMs
        return left.coerceAtLeast(0)
    }

    /**
     * 取更新日志。多个镜像同时请求，谁先成功用谁（不再串行等一个慢镜像）。
     *
     * @param url 传 null 时用内置镜像池；传具体地址时只测这一个（用于自检）。
     */
    suspend fun fetchLatestUpdate(url: String? = null): RemoteUpdate {
        val urls = if (url.isNullOrBlank()) CHANGELOG_URLS else mirrorCandidates(url)
        check(urls.isNotEmpty()) { "empty changelog url" }
        return withContext(Dispatchers.IO) {
            var lastError: Throwable? = null
            val maxAttempts = 2
            for (attempt in 1..maxAttempts) {
                ensureActive()
                try {
                    return@withContext fetchFirstSuccessful(urls)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    lastError = t
                    if (attempt < maxAttempts) delay(400L * attempt)
                }
            }
            throw lastError ?: IllegalStateException("fetch latest version failed")
        }
    }

    private suspend fun fetchFirstSuccessful(urls: List<String>): RemoteUpdate = coroutineScope {
        val results = Channel<Pair<String, Result<RemoteUpdate>>>(urls.size)
        for (url in urls) {
            launch {
                results.send(url to runCatching { fetchLatestUpdateOnce(url) })
            }
        }
        var firstError: Throwable? = null
        repeat(urls.size) {
            val (url, result) = results.receive()
            val update = result.getOrNull()
            if (update != null) return@coroutineScope update
            val error = result.exceptionOrNull()
            AppLog.w(TAG, "changelog failed $url: ${error?.message}")
            if (firstError == null) firstError = error
        }
        throw firstError ?: IllegalStateException("no changelog source available")
    }

    private suspend fun fetchLatestUpdateOnce(url: String): RemoteUpdate {
        val req =
            Request.Builder()
                .url(url)
                .header("Cache-Control", "no-cache")
                .get()
                .build()
        val res = okHttp.newCall(req).await()
        res.use { r ->
            check(r.isSuccessful) { "HTTP ${r.code} ${r.message}" }
            val body = r.body ?: error("empty body")
            val text = withContext(Dispatchers.IO) { body.string() }
            return parseChangelog(text)
        }
    }

    internal fun parseChangelog(raw: String): RemoteUpdate {
        val versions = parseChangelogVersions(raw)
        return versions.first().copy(versions = versions)
    }

    internal fun parseChangelogVersions(raw: String): List<RemoteUpdate> {
        val normalized = raw.replace("\r\n", "\n").replace('\r', '\n').trim()
        check(normalized.isNotBlank()) { "更新日志为空" }

        val lines = normalized.lines()
        val allHeadings =
            lines.withIndex()
                .mapNotNull { (index, line) ->
                    parseVersionHeading(line)?.let { heading -> index to heading }
                }
        check(allHeadings.isNotEmpty()) { "未找到版本标题" }

        val versionLevel = allHeadings.first().second.level
        val headings = allHeadings.filter { (_, heading) -> heading.level == versionLevel }
        return headings.mapIndexed { index, (headingIndex, heading) ->
            val nextHeadingIndex = headings.getOrNull(index + 1)?.first ?: lines.size
            val sectionLines =
                lines.subList(headingIndex + 1, nextHeadingIndex)
                    .dropLastWhile { it.isBlank() }
            val changelog =
                sectionLines
                    .joinToString("\n")
                    .trim()

            RemoteUpdate(
                versionName = heading.versionName,
                changelog = changelog,
            )
        }
    }

    /** 返回 GitHub 原始地址；下载时 [downloadApkToCache] 会把它展开成镜像池里的多个候选。 */
    fun apkUrlFor(versionName: String): String {
        val cleanVersion = versionName.trim().removePrefix("v")
        val channel = if (BuildConfig.DEBUG) "debug" else "release"
        return "$GITHUB_BASE/$UPDATE_REPO/releases/download/v$cleanVersion/blbl-android-$cleanVersion-$channel.apk"
    }

    /** GitHub 地址展开成「镜像前缀 + 原地址」；非 GitHub 地址原样返回。 */
    internal fun mirrorCandidates(url: String): List<String> {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (!trimmed.startsWith("$GITHUB_BASE/")) return listOf(trimmed)
        return GITHUB_MIRROR_PREFIXES.map { prefix -> prefix + trimmed }.distinct()
    }

    private data class VersionHeading(
        val level: Int,
        val versionName: String,
    )

    private fun parseVersionHeading(line: String): VersionHeading? {
        val trimmed = line.trim()
        val match = Regex("""^(#{1,6})\s+\[?v?([0-9]+(?:\.[0-9]+)*(?:[-+][A-Za-z0-9_.-]+)?)\]?(?:\s+.*)?$""").matchEntire(trimmed)
            ?: return null
        val level = match.groupValues[1].length
        val versionName = match.groupValues[2].trim()
        if (parseVersion(versionName) == null) return null
        return VersionHeading(level = level, versionName = versionName)
    }

    fun isRemoteNewer(remoteVersionName: String, currentVersionName: String = BuildConfig.VERSION_NAME): Boolean {
        val remote = parseVersion(remoteVersionName) ?: return false
        // 当前版本无法解析时（如 debug 包 versionName 为 "debug"），视为 0.0.0，
        // 这样任何正式版本都会被认为是更新版本。
        val current = parseVersion(currentVersionName) ?: return true
        return compareVersion(remote, current) > 0
    }

    /**
     * 从镜像池里挑最快的源，再开 [DOWNLOAD_SEGMENTS] 个连接分段并发下载。
     *
     * 单个镜像常有每连接限速（实测 4 连接合计 ≈ 单连接 3~5 倍），分段还能在某个源中途抽风时
     * 自动换下一个源重下该段，不必整包重来。任一源不支持 Range 时退回单连接下载。
     */
    suspend fun downloadApkToCache(
        context: Context,
        url: String,
        onProgress: (Progress) -> Unit,
    ): File {
        onProgress(Progress.Connecting)

        val candidates = mirrorCandidates(url)
        check(candidates.isNotEmpty()) { "empty download url" }

        val dir = File(context.cacheDir, "test_update").apply { mkdirs() }
        val partsDir = File(dir, "parts").apply { mkdirs() }
        partsDir.listFiles()?.forEach { runCatching { it.delete() } }
        val target = File(dir, "update.apk")
        runCatching { target.delete() }
        runCatching { File(dir, "update.apk.part").delete() }

        val ranked = rankMirrors(candidates)
        val ranged = ranked.filter { it.supportsRange && (it.totalBytes ?: 0L) > 0L }
        if (ranged.isEmpty()) {
            val fallback = ranked.firstOrNull { (it.totalBytes ?: 0L) > 0L } ?: ranked.first()
            AppLog.i(TAG, "download single-stream from ${fallback.url}")
            return downloadSingleStream(url = fallback.url, target = target, onProgress = onProgress)
        }

        val total = ranged.first().totalBytes!!
        val segments = planSegments(totalBytes = total, count = DOWNLOAD_SEGMENTS)
        val partFiles = segments.map { File(partsDir, "seg_${it.index}.part") }
        val downloaded = AtomicLong(0L)
        AppLog.i(
            TAG,
            "download segments=${segments.size} total=$total sources=${ranged.map { it.url }}",
        )

        coroutineScope {
            val ticker =
                launch {
                    var lastBytes = 0L
                    var lastAt = System.currentTimeMillis()
                    while (isActive) {
                        delay(200L)
                        val now = System.currentTimeMillis()
                        val bytes = downloaded.get()
                        val elapsedMs = (now - lastAt).coerceAtLeast(1L)
                        val speed = ((bytes - lastBytes) * 1000L / elapsedMs).coerceAtLeast(0L)
                        lastBytes = bytes
                        lastAt = now
                        onProgress(Progress.Downloading(downloadedBytes = bytes, totalBytes = total, bytesPerSecond = speed))
                    }
                }
            try {
                segments
                    .mapIndexed { index, segment ->
                        async(Dispatchers.IO) {
                            downloadSegment(
                                segment = segment,
                                outputFile = partFiles[index],
                                mirrors = ranged,
                                mirrorStart = index % ranged.size,
                                progress = downloaded,
                            )
                        }
                    }.awaitAll()
            } finally {
                ticker.cancel()
            }
        }

        withContext(Dispatchers.IO) {
            mergeSegments(partFiles = partFiles, target = target)
            partFiles.forEach { runCatching { it.delete() } }
            check(target.length() == total) { "下载的文件不完整（${target.length()}/$total）" }
            verifyApk(target)
        }
        onProgress(Progress.Downloading(downloadedBytes = total, totalBytes = total, bytesPerSecond = 0L))
        return target
    }

    private suspend fun downloadSingleStream(
        url: String,
        target: File,
        onProgress: (Progress) -> Unit,
    ): File =
        withContext(Dispatchers.IO) {
            val part = File(target.parentFile, "update.apk.part")
            runCatching { part.delete() }

            val req = Request.Builder().url(url).get().build()
            downloadClient.newCall(req).execute().use { r ->
                check(r.isSuccessful) { "HTTP ${r.code} ${r.message}" }
                val body = r.body ?: error("empty body")
                val total = body.contentLength().takeIf { it > 0 }
                body.byteStream().use { input ->
                    FileOutputStream(part).use { output ->
                        val buf = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var lastEmitAtMs = 0L
                        var speedAtMs = System.currentTimeMillis()
                        var speedBytes = 0L
                        var bytesPerSecond = 0L

                        while (true) {
                            ensureActive()
                            val read = input.read(buf)
                            if (read <= 0) break
                            output.write(buf, 0, read)
                            downloaded += read

                            speedBytes += read
                            val nowMs = System.currentTimeMillis()
                            val speedElapsedMs = nowMs - speedAtMs
                            if (speedElapsedMs >= 1_000) {
                                bytesPerSecond = (speedBytes * 1_000L / speedElapsedMs.coerceAtLeast(1)).coerceAtLeast(0)
                                speedBytes = 0L
                                speedAtMs = nowMs
                            }

                            if (nowMs - lastEmitAtMs >= 200) {
                                lastEmitAtMs = nowMs
                                onProgress(Progress.Downloading(downloadedBytes = downloaded, totalBytes = total, bytesPerSecond = bytesPerSecond))
                            }
                        }
                        output.fd.sync()
                    }
                }
            }

            check(part.exists() && part.length() > 0) { "downloaded file is empty" }
            check(part.renameTo(target)) { "rename failed" }
            verifyApk(target)
            target
        }

    private data class Probe(
        val url: String,
        val bytesPerSecond: Double,
        val totalBytes: Long?,
        val supportsRange: Boolean,
    )

    internal data class Segment(
        val index: Int,
        val start: Long,
        val end: Long,
    ) {
        val length: Long
            get() = end - start + 1
    }

    /** 均分成 [count] 段；最后一段吃掉除不尽的零头。 */
    internal fun planSegments(
        totalBytes: Long,
        count: Int,
    ): List<Segment> {
        if (totalBytes <= 0L) return emptyList()
        val safeCount = count.coerceAtLeast(1)
        val per = (totalBytes + safeCount - 1) / safeCount
        val out = ArrayList<Segment>(safeCount)
        var start = 0L
        var index = 0
        while (start < totalBytes) {
            val end = (start + per - 1).coerceAtMost(totalBytes - 1)
            out += Segment(index = index, start = start, end = end)
            start = end + 1
            index++
        }
        return out
    }

    private suspend fun rankMirrors(candidates: List<String>): List<Probe> = coroutineScope {
        val probes =
            candidates
                .map { url -> async(Dispatchers.IO) { probeMirror(url) } }
                .mapNotNull { it.await() }
        check(probes.isNotEmpty()) { "所有下载源都不可用" }
        probes.sortedByDescending { it.bytesPerSecond }
    }

    /** 读一小段测速（也用来拿文件总大小、判断源是否支持 Range）。 */
    private fun probeMirror(url: String): Probe? {
        return try {
            val req =
                Request.Builder()
                    .url(url)
                    .header("Range", "bytes=0-${PROBE_BYTES - 1}")
                    .get()
                    .build()
            probeClient.newCall(req).execute().use { r ->
                if (!r.isSuccessful) {
                    AppLog.w(TAG, "probe HTTP ${r.code} $url")
                    return null
                }
                val body = r.body ?: return null
                val total = parseTotalBytes(r)
                val startedAt = System.nanoTime()
                val budgetNanos = PROBE_TIME_BUDGET_MS * 1_000_000L
                var read = 0L
                val buf = ByteArray(32 * 1024)
                body.byteStream().use { input ->
                    while (read < PROBE_BYTES) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        read += n
                        if (System.nanoTime() - startedAt >= budgetNanos) break
                    }
                }
                if (read <= 0L) return null
                val elapsedSec = ((System.nanoTime() - startedAt) / 1_000_000_000.0).coerceAtLeast(0.001)
                Probe(
                    url = url,
                    bytesPerSecond = read / elapsedSec,
                    totalBytes = total,
                    supportsRange = r.code == 206,
                )
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "probe failed $url: ${t.message}")
            null
        }
    }

    private fun parseTotalBytes(response: okhttp3.Response): Long? {
        response.header("Content-Range")
            ?.substringAfterLast('/', "")
            ?.trim()
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?.let { return it }
        return response.body?.contentLength()?.takeIf { it > 0L }
    }

    private suspend fun downloadSegment(
        segment: Segment,
        outputFile: File,
        mirrors: List<Probe>,
        mirrorStart: Int,
        progress: AtomicLong,
    ) {
        var lastError: Throwable? = null
        for (attempt in mirrors.indices) {
            val mirror = mirrors[(mirrorStart + attempt) % mirrors.size]
            var written = 0L
            try {
                val req =
                    Request.Builder()
                        .url(mirror.url)
                        .header("Range", "bytes=${segment.start}-${segment.end}")
                        .get()
                        .build()
                val res = downloadClient.newCall(req).await()
                res.use { r ->
                    if (r.code != 206) throw IOException("HTTP ${r.code}")
                    val body = r.body ?: throw IOException("empty body")
                    FileOutputStream(outputFile, false).use { output ->
                        body.byteStream().use { input ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buf)
                                if (n <= 0) break
                                output.write(buf, 0, n)
                                written += n
                                progress.addAndGet(n.toLong())
                            }
                        }
                        output.fd.sync()
                    }
                }
                if (written != segment.length) throw IOException("分段不完整（$written/${segment.length}）")
                return
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                progress.addAndGet(-written)
                lastError = t
                AppLog.w(TAG, "segment ${segment.index} via ${mirror.url} failed: ${t.message}")
            }
        }
        throw IOException("下载失败：${lastError?.message ?: "未知错误"}", lastError)
    }

    private fun mergeSegments(
        partFiles: List<File>,
        target: File,
    ) {
        target.outputStream().use { out ->
            for (part in partFiles) {
                part.inputStream().use { it.copyTo(out) }
            }
        }
    }

    /** 分段来自不同镜像，拼完确认是完整 APK 再交给安装器。 */
    private fun verifyApk(file: File) {
        try {
            java.util.zip.ZipFile(file).use { zip ->
                check(zip.getEntry("AndroidManifest.xml") != null) { "下载的文件不是有效的 APK" }
            }
        } catch (e: java.util.zip.ZipException) {
            throw IOException("下载的文件不是有效的 APK", e)
        }
    }

    fun installApk(context: Context, apkFile: File) {
        val uri = installUriFor(context, apkFile)
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        grantInstallerReadPermissions(context, intent, uri)
        context.startActivity(intent)
    }

    @SuppressLint("SetWorldReadable")
    private fun installUriFor(context: Context, apkFile: File): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val authority = "${context.packageName}.fileprovider"
            FileProvider.getUriForFile(context, authority, apkFile)
        } else {
            apkFile.setReadable(true, false)
            Uri.fromFile(apkFile)
        }
    }

    @Suppress("DEPRECATION")
    private fun grantInstallerReadPermissions(
        context: Context,
        intent: Intent,
        uri: Uri,
    ) {
        if (uri.scheme != "content") return

        val installers =
            context.packageManager.queryIntentActivities(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY,
            )
        for (installer in installers) {
            val packageName = installer.activityInfo?.packageName ?: continue
            context.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun formatBytes(bytes: Long): String {
        val b = bytes.coerceAtLeast(0)
        if (b < 1024) return "${b}B"
        val kb = b / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1fKB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1fMB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2fGB", gb)
    }

    private fun parseVersion(raw: String): List<Int>? {
        val cleaned = raw.trim().removePrefix("v")
        val digitsOnly =
            cleaned.takeWhile { ch ->
                ch.isDigit() || ch == '.'
            }
        if (digitsOnly.isBlank()) return null
        val parts = digitsOnly.split('.').filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        val nums = parts.map { it.toIntOrNull() ?: return null }
        return nums
    }

    private fun compareVersion(a: List<Int>, b: List<Int>): Int {
        val max = maxOf(a.size, b.size)
        for (i in 0 until max) {
            val ai = a.getOrElse(i) { 0 }
            val bi = b.getOrElse(i) { 0 }
            if (ai != bi) return ai.compareTo(bi)
        }
        return 0
    }
}
