package jp.oif.tsunaminavi

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Cloud Run のサーバー（backend/）を呼ぶ。API の形は backend/README.md の「API」。 */
object Api {
    private val base = BuildConfig.API_BASE_URL
    private const val TIMEOUT_MS = 20_000

    suspend fun getJson(path: String): JsonObject = withContext(Dispatchers.IO) {
        Json.parseToJsonElement(request("GET", path, null)).jsonObject
    }

    suspend fun postJson(path: String, body: String = "{}"): JsonObject = withContext(Dispatchers.IO) {
        Json.parseToJsonElement(request("POST", path, body)).jsonObject
    }

    suspend fun download(path: String, to: File) = withContext(Dispatchers.IO) {
        val conn = open("GET", path)
        try {
            check(conn.responseCode == 200) { "HTTP ${conn.responseCode}: $path" }
            conn.inputStream.use { input -> to.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
    }

    private fun request(method: String, path: String, body: String?): String {
        val conn = open(method, path)
        try {
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            check(conn.responseCode == 200) { "HTTP ${conn.responseCode}: $path" }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(method: String, path: String): HttpURLConnection =
        (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS * 3
        }
}
