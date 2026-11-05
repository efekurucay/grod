package dev.grod

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Result of one conditional GET of the rules URL. */
sealed class FetchResult {
    data object NotModified : FetchResult()
    data class Body(val text: String, val etag: String?) : FetchResult()
    data class Failed(val message: String) : FetchResult()
}

/** Blocking; call off the main thread. */
object RuleFetch {
    private const val TIMEOUT_MS = 10_000
    private const val MAX_BYTES = 1 shl 20

    fun get(url: String, etag: String?): FetchResult {
        if (!url.startsWith("https://")) return FetchResult.Failed("rules URL must start with https://")
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            return FetchResult.Failed("bad URL: ${e.message}")
        }
        return try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Accept", "application/json")
            if (etag != null) conn.setRequestProperty("If-None-Match", etag)
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> FetchResult.NotModified
                HttpURLConnection.HTTP_OK -> {
                    val bytes = conn.inputStream.use { it.readNBytesCompat(MAX_BYTES + 1) }
                    if (bytes.size > MAX_BYTES) FetchResult.Failed("rules URL body is larger than 1 MiB")
                    else FetchResult.Body(String(bytes, Charsets.UTF_8), conn.getHeaderField("ETag"))
                }
                else -> FetchResult.Failed("rules URL returned HTTP $code")
            }
        } catch (e: IOException) {
            FetchResult.Failed("rules URL: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            conn.disconnect()
        }
    }

    // InputStream.readNBytes is API 33+; minSdk is 26.
    private fun InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() < max) {
            val n = read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
