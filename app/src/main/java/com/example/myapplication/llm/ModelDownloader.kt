package com.example.myapplication.llm

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

object ModelDownloader {
    private const val TAG = "ModelDownloader"

    /**
     * Downloads [url] to [target], reporting progress via [onProgress].
     * Writes to a `.part` file and atomically renames on success so we never
     * leave a half-baked .task file that init would happily try to load.
     *
     * SHA256 is always computed during streaming (negligible CPU cost) and
     * logged on success so a hash can be pinned later. If [expectedSha256] is
     * non-null, the download is rejected when the hash doesn't match — the
     * `.part` file is deleted before throwing.
     *
     * Throws on failure; caller should clean up and surface the error.
     */
    suspend fun download(
        url: String,
        target: File,
        expectedSha256: String? = null,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".part")
        if (tmp.exists()) tmp.delete()

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        try {
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) {
                throw java.io.IOException("HTTP $code from $url")
            }
            val total = conn.contentLengthLong
            var downloaded = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        downloaded += read
                        // Throttle progress callbacks so the UI flow doesn't drown.
                        if (downloaded - lastReport > 256 * 1024 || read < buffer.size) {
                            onProgress(downloaded, total)
                            lastReport = downloaded
                        }
                    }
                    onProgress(downloaded, total)
                }
            }
            // The atomic rename below guarantees we don't leave a half-baked
            // target file even if the connection dies mid-stream, but a server
            // that hangs up after sending only some of the bytes would still
            // produce a fully-written-but-incomplete .part. Detect that here.
            if (total > 0 && downloaded != total) {
                throw java.io.IOException(
                    "Truncated download: got $downloaded of $total bytes from $url"
                )
            }
            val actualSha256 = digest.digest().toHexString()
            Log.i(TAG, "Downloaded $downloaded bytes, sha256=$actualSha256")
            if (expectedSha256 != null && !actualSha256.equals(expectedSha256, ignoreCase = true)) {
                tmp.delete()
                throw java.io.IOException(
                    "SHA256 mismatch for $url: expected $expectedSha256, got $actualSha256"
                )
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                throw java.io.IOException("rename ${tmp.name} -> ${target.name} failed")
            }
        } finally {
            conn.disconnect()
            if (tmp.exists() && !target.exists()) tmp.delete()
        }
    }

    private fun ByteArray.toHexString(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            sb.append(HEX_CHARS[(b.toInt() ushr 4) and 0xF])
            sb.append(HEX_CHARS[b.toInt() and 0xF])
        }
        return sb.toString()
    }

    private val HEX_CHARS = "0123456789abcdef".toCharArray()
}
