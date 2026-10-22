package blbl.cat3399.core.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanShareServerTest {
    private val client = OkHttpClient()
    private val servers = mutableListOf<LanShareServer>()

    @After
    fun tearDown() {
        servers.forEach { it.stop() }
        servers.clear()
    }

    private fun startServer(
        download: LanShareServer.DownloadResource? = null,
        upload: LanShareServer.UploadResource? = null,
    ): String {
        val server = LanShareServer(download = download, upload = upload)
        servers.add(server)
        val urls = server.start()
        assertTrue("server failed to start (no network interface?)", urls.isNotEmpty())
        return urls.first()
    }

    private fun get(url: String): okhttp3.Response =
        client.newCall(Request.Builder().url(url).build()).execute()

    @Test
    fun `download serves bytes with attachment header`() {
        val url =
            startServer(
                download =
                    LanShareServer.DownloadResource.ofBytes(
                        bytes = "{\"hello\":\"world\"}".toByteArray(),
                        fileName = "blbl_config.json",
                        contentType = "application/json",
                    ),
            )
        get(url).use { resp ->
            assertEquals(200, resp.code)
            assertEquals("{\"hello\":\"world\"}", resp.body?.string())
            assertEquals("application/json", resp.header("Content-Type"))
            assertEquals("attachment; filename=\"blbl_config.json\"", resp.header("Content-Disposition"))
        }
    }

    @Test
    fun `unknown path returns 404`() {
        val url = startServer(download = LanShareServer.DownloadResource.ofBytes("x".toByteArray(), "x.txt", "text/plain"))
        get("$url/nope").use { resp -> assertEquals(404, resp.code) }
    }

    @Test
    fun `head returns headers without body`() {
        val url =
            startServer(
                download =
                    LanShareServer.DownloadResource.ofBytes(
                        bytes = "payload".toByteArray(),
                        fileName = "a.bin",
                        contentType = "application/octet-stream",
                    ),
            )
        client.newCall(Request.Builder().url(url).head().build()).execute().use { resp ->
            assertEquals(200, resp.code)
            assertEquals("7", resp.header("Content-Length"))
            assertEquals("", resp.body?.string().orEmpty())
        }
    }

    @Test
    fun `upload accepts json body`() {
        val url =
            startServer(
                upload =
                    LanShareServer.UploadResource(
                        label = "配置",
                        accept = { body -> LanShareServer.UploadResponse.Accepted("got ${body.size} bytes") },
                    ),
            )
        val body = "{\"k\":1}".toRequestBody("application/json".toMediaType())
        client.newCall(Request.Builder().url("$url").post(body).build()).execute().use { resp ->
            assertEquals(200, resp.code)
            val text = resp.body?.string().orEmpty()
            assertTrue(text.contains("\"ok\":true"))
            assertTrue(text.contains("got 7 bytes"))
        }
    }

    @Test
    fun `upload rejected returns 400 with error`() {
        val url =
            startServer(
                upload =
                    LanShareServer.UploadResource(
                        label = "配置",
                        accept = { LanShareServer.UploadResponse.Rejected("不是 blbl 配置文件") },
                    ),
            )
        val body = "{}".toRequestBody("application/json".toMediaType())
        client.newCall(Request.Builder().url("$url").post(body).build()).execute().use { resp ->
            assertEquals(400, resp.code)
            assertTrue(resp.body?.string().orEmpty().contains("不是 blbl 配置文件"))
        }
    }

    @Test
    fun `upload oversized body returns 413`() {
        val url =
            startServer(
                upload = LanShareServer.UploadResource(label = "配置", accept = { LanShareServer.UploadResponse.Accepted("ok") }),
            )
        val big = ByteArray(LanShareServer.MAX_UPLOAD_BYTES + 1)
        val body = big.toRequestBody("application/json".toMediaType())
        client.newCall(Request.Builder().url("$url").post(body).build()).execute().use { resp ->
            assertEquals(413, resp.code)
        }
    }

    @Test
    fun `upload page serves html`() {
        val url =
            startServer(
                upload = LanShareServer.UploadResource(label = "配置", accept = { LanShareServer.UploadResponse.Accepted("ok") }),
            )
        get(url).use { resp ->
            assertEquals(200, resp.code)
            val html = resp.body?.string().orEmpty()
            assertTrue(html.contains("<input type=\"file\""))
            assertTrue(html.contains("导入配置到 Blbl"))
        }
    }
}
