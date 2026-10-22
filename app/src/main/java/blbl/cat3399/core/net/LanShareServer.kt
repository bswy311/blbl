package blbl.cat3399.core.net

import blbl.cat3399.core.log.AppLog
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A tiny one-shot LAN HTTP server for sharing small resources (log zip, config backup)
 * between the TV and a phone/PC browser on the same WiFi.
 *
 * Why: TV devices often lack the system document picker (ACTION_CREATE_DOCUMENT is not
 * handled), which breaks every SAF-based export/import. Serving files directly on the LAN
 * removes that dependency entirely and needs nothing installed on the other side.
 *
 * Security: every URL carries a random token generated per instance, so someone who merely
 * guesses the TV IP cannot fetch the resource. The server is one-shot and stops when the
 * share dialog closes; resources never touch disk (bytes are held in memory).
 *
 * Routes (all under /<token>):
 *  - GET/HEAD  /            download the resource (when a download is configured)
 *  - GET/HEAD  /import      upload page (when an upload is configured)
 *  - POST      /import      receive the upload body (raw bytes, size-capped)
 */
class LanShareServer(
    private val download: DownloadResource? = null,
    private val upload: UploadResource? = null,
) : Closeable {
    class DownloadResource(
        val fileName: String,
        val contentType: String,
        val length: () -> Long,
        val openStream: () -> InputStream?,
    ) {
        companion object {
            fun ofFile(
                file: File,
                fileName: String,
                contentType: String,
            ): DownloadResource =
                DownloadResource(
                    fileName = fileName,
                    contentType = contentType,
                    length = { file.length() },
                    openStream = { if (file.isFile) FileInputStream(file) else null },
                )

            fun ofBytes(
                bytes: ByteArray,
                fileName: String,
                contentType: String,
            ): DownloadResource =
                DownloadResource(
                    fileName = fileName,
                    contentType = contentType,
                    length = { bytes.size.toLong() },
                    openStream = { ByteArrayInputStream(bytes) },
                )
        }
    }

    data class UploadResource(
        val label: String,
        /** Runs on a server worker thread; must return the response to send back. */
        val accept: (ByteArray) -> UploadResponse,
    )

    sealed class UploadResponse {
        data class Accepted(val message: String) : UploadResponse()

        data class Rejected(val error: String) : UploadResponse()
    }

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    @Volatile
    private var executor: ExecutorService? = null

    private val token: String = newToken()

    val port: Int
        get() = serverSocket?.localPort ?: 0

    /**
     * Starts the server and returns the URL to hand out for every site-local IPv4 address
     * found on this device. Empty list means startup failed.
     */
    fun start(): List<String> {
        synchronized(this) {
            if (serverSocket != null) return urls()
            val ips = localIpv4s()
            if (ips.isEmpty()) {
                AppLog.w("LanShare", "no site-local IPv4 address, cannot serve")
                return emptyList()
            }
            val socket =
                runCatching { ServerSocket(0, 50, InetAddress.getByName("0.0.0.0")) }.getOrElse { t ->
                    AppLog.w("LanShare", "bind failed", t)
                    return emptyList()
                }
            serverSocket = socket
            executor =
                Executors.newFixedThreadPool(2) { r ->
                    Thread(r, "blbl-lan-share-worker").apply { isDaemon = true }
                }
            acceptThread =
                thread(name = "blbl-lan-share-accept", isDaemon = true) {
                    AppLog.i("LanShare", "started port=${socket.localPort} download=${download != null} upload=${upload != null}")
                    while (!socket.isClosed) {
                        val client =
                            runCatching { socket.accept() }
                                .getOrNull()
                                ?: break
                        executor?.execute {
                            runCatching { handleClient(client) }
                                .onFailure { t ->
                                    AppLog.w("LanShare", "handle client failed", t)
                                    runCatching { client.close() }
                                }
                        } ?: runCatching { client.close() }
                    }
                    AppLog.i("LanShare", "accept loop ended")
                }
            return urls()
        }
    }

    private fun urls(): List<String> {
        val port = port
        if (port <= 0) return emptyList()
        val path = if (download != null) "/$token" else "/$token/import"
        return localIpv4s().map { "http://$it:$port$path" }
    }

    private fun handleClient(socket: Socket) {
        socket.soTimeout = 15_000
        socket.use { s ->
            val input = s.getInputStream().bufferedReader(StandardCharsets.ISO_8859_1)
            val requestLine = input.readLine()?.trim().orEmpty()
            if (requestLine.isBlank()) return
            val parts = requestLine.split(' ', limit = 3)
            if (parts.size < 2) return
            val method = parts[0].trim().uppercase(Locale.US)
            val path = parts[1].trim().substringBefore('?')

            var contentLength = -1L
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0 && line.substring(0, idx).trim().equals("Content-Length", ignoreCase = true)) {
                    contentLength = line.substring(idx + 1).trim().toLongOrNull() ?: -1L
                }
            }

            if (method == "GET" || method == "HEAD") {
                when (path) {
                    "/$token" -> {
                        val resource = download
                        if (resource != null) {
                            serveDownload(s, method, resource)
                        } else if (upload != null) {
                            serveHtml(s, uploadPageHtml(upload.label))
                        } else {
                            respondJson(s, 404, error = "not found")
                        }
                    }

                    "/$token/import" ->
                        if (upload != null) {
                            serveHtml(s, uploadPageHtml(upload.label))
                        } else {
                            respondJson(s, 404, error = "not found")
                        }

                    else -> respondPlain(s, 404, "Not Found", "not found")
                }
                return
            }

            if (method == "POST" && path == "/$token/import") {
                val target = upload
                if (target == null) {
                    respondJson(s, 404, error = "not found")
                    return
                }
                if (contentLength < 0) {
                    respondJson(s, 411, error = "缺少 Content-Length")
                    return
                }
                if (contentLength > MAX_UPLOAD_BYTES) {
                    drainInput(input, contentLength)
                    respondJson(s, 413, error = "文件过大（上限 ${MAX_UPLOAD_BYTES / 1024}KB）")
                    return
                }
                val body = ByteArray(contentLength.toInt())
                var off = 0
                while (off < body.size) {
                    val c = input.read()
                    if (c < 0) break
                    body[off++] = c.toByte()
                }
                if (off < body.size) {
                    respondJson(s, 400, error = "请求体不完整")
                    return
                }
                AppLog.i("LanShare", "upload received from=${s.inetAddress.hostAddress.orEmpty()} bytes=$off")
                val response =
                    runCatching { target.accept(body) }
                        .getOrElse { t ->
                            AppLog.w("LanShare", "upload handler failed", t)
                            UploadResponse.Rejected("处理失败：${t.message.orEmpty()}")
                        }
                when (response) {
                    is UploadResponse.Accepted -> respondJson(s, 200, ok = true, message = response.message)
                    is UploadResponse.Rejected -> respondJson(s, 400, ok = false, error = response.error)
                }
                return
            }

            respondPlain(s, 405, "Method Not Allowed", "method=$method")
        }
    }

    private fun serveDownload(socket: Socket, method: String, resource: DownloadResource) {
        val stream = resource.openStream() ?: run {
            respondPlain(socket, 404, "Not Found", "resource unavailable")
            return
        }
        stream.use { fin ->
            val len = resource.length()
            val out = BufferedOutputStream(socket.getOutputStream())
            out.write("HTTP/1.1 200 OK\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            out.write("Connection: close\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            out.write("Content-Type: ${resource.contentType}\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            out.write("Content-Length: $len\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            out.write(
                "Content-Disposition: attachment; filename=\"${resource.fileName}\"\r\n".toByteArray(StandardCharsets.ISO_8859_1),
            )
            out.write("\r\n".toByteArray(StandardCharsets.ISO_8859_1))
            if (method == "HEAD") {
                out.flush()
                return
            }
            fin.copyTo(out)
            out.flush()
            AppLog.i("LanShare", "download served from=${socket.inetAddress.hostAddress.orEmpty()} bytes=$len")
        }
    }

    private fun serveHtml(socket: Socket, html: String) {
        val bytes = html.toByteArray(StandardCharsets.UTF_8)
        val out = BufferedOutputStream(socket.getOutputStream())
        out.write("HTTP/1.1 200 OK\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        out.write("Connection: close\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        out.write("Content-Type: text/html; charset=utf-8\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        out.write("Content-Length: ${bytes.size}\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        out.write("\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    private fun respondPlain(socket: Socket, code: Int, message: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val out = BufferedOutputStream(socket.getOutputStream())
        out.write("HTTP/1.1 $code $message\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        out.write("Connection: close\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        out.write("Content-Type: text/plain; charset=utf-8\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        out.write("Content-Length: ${bytes.size}\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        out.write("\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    /** Reads and discards up to a capped number of body bytes so the client can read our reply. */
    private fun drainInput(input: java.io.BufferedReader, contentLength: Long) {
        var remaining = contentLength.coerceAtMost(4L * 1024 * 1024)
        while (remaining > 0) {
            val c = input.read()
            if (c < 0) break
            remaining--
        }
    }

    private fun respondJson(
        socket: Socket,
        code: Int,
        ok: Boolean? = null,
        message: String? = null,
        error: String? = null,
    ) {
        val sb = StringBuilder("{")
        var first = true
        if (ok != null) {
            sb.append("\"ok\":").append(ok)
            first = false
        }
        if (message != null) {
            if (!first) sb.append(',')
            first = false
            sb.append("\"message\":\"").append(escapeJson(message)).append('"')
        }
        if (error != null) {
            if (!first) sb.append(',')
            sb.append("\"error\":\"").append(escapeJson(error)).append('"')
        }
        sb.append('}')
        val bytes = sb.toString().toByteArray(StandardCharsets.UTF_8)
        val out = BufferedOutputStream(socket.getOutputStream())
        out.write("HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        out.write("Connection: close\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        out.write("Content-Type: application/json; charset=utf-8\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        out.write("Content-Length: ${bytes.size}\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        out.write("\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    private fun escapeJson(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append("\\u").append(ch.code.toString(16).padStart(4, '0')) else sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun uploadPageHtml(label: String): String {
        return """
            <!doctype html>
            <html lang="zh">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>导入$label - Blbl</title>
            <style>body{font-family:sans-serif;margin:24px;max-width:520px}input,button{margin-top:12px;font-size:16px}#msg{margin-top:16px;white-space:pre-wrap;color:#333}</style>
            </head>
            <body>
            <h2>导入${label}到 Blbl</h2>
            <p>选择 Blbl 导出的 .json 配置文件上传，电视端确认后生效并重启。</p>
            <input type="file" id="f" accept=".json,application/json">
            <button id="b" onclick="up()">上传</button>
            <div id="msg"></div>
            <script>
            function up(){
            var f=document.getElementById('f').files[0],m=document.getElementById('msg'),b=document.getElementById('b');
            if(!f){m.textContent='请先选择文件';return;}
            b.disabled=true;m.textContent='上传中…';
            f.text().then(function(t){return fetch('import',{method:'POST',headers:{'Content-Type':'application/json'},body:t})})
            .then(function(r){return r.json()})
            .then(function(j){m.textContent=j.ok?('上传成功：'+j.message):('上传失败：'+j.error);b.disabled=false;})
            .catch(function(e){m.textContent='上传出错：'+e;b.disabled=false;});
            }
            </script>
            </body>
            </html>
        """.trimIndent()
    }

    override fun close() {
        stop()
    }

    /** Stops the server without blocking the calling thread. */
    fun stop() {
        val socket = serverSocket ?: return
        serverSocket = null
        runCatching { socket.close() }

        val exec = executor
        executor = null
        exec?.shutdown()
        thread(name = "blbl-lan-share-stop", isDaemon = true) {
            runCatching { exec?.awaitTermination(800, TimeUnit.MILLISECONDS) }
            runCatching { exec?.shutdownNow() }
        }

        acceptThread = null
        AppLog.i("LanShare", "stopped")
    }

    companion object {
        const val MAX_UPLOAD_BYTES = 512 * 1024

        private fun newToken(): String {
            val bytes = ByteArray(8)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { ((it.toInt() and 0xFF)).toString(16).padStart(2, '0') }
        }

        /** Site-local IPv4 addresses of this device (WiFi/Ethernet), de-duplicated. */
        fun localIpv4s(): List<String> {
            val result = LinkedHashSet<String>()
            runCatching {
                NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { nif ->
                    if (!nif.isUp || nif.isLoopback) return@forEach
                    nif.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .filter { !it.isLoopbackAddress && it.isSiteLocalAddress }
                        .forEach { it.hostAddress?.let(result::add) }
                }
            }
            return result.toList()
        }
    }
}
