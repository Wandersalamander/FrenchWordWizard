package com.example.myapplication.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

object ModelDownloader {
    /**
     * Downloads [url] to [target], reporting progress via [onProgress].
     * Writes to a `.part` file and atomically renames on success so we never
     * leave a half-baked .task file that init would happily try to load.
     * Throws on failure; caller should clean up and surface the error.
     */
    suspend fun download(
        url: String,
        target: File,
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
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
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
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                throw java.io.IOException("rename ${tmp.name} -> ${target.name} failed")
            }
        } finally {
            conn.disconnect()
            if (tmp.exists() && !target.exists()) tmp.delete()
        }
    }
}
