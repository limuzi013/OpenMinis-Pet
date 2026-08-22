package com.openminis.app.debug

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Bounded HTTPS downloader used only by authenticated Skill/MCP URL imports.
 *
 * A URL import endpoint is otherwise an SSRF primitive when Web Remote is
 * exposed through a public tunnel. This downloader validates every redirect,
 * resolves through an OkHttp [Dns] that rejects local/private destinations,
 * allows only the normal HTTPS port, and stops reading at the caller's byte
 * cap. The validated addresses returned by [PublicOnlyDns] are the addresses
 * OkHttp connects to, so validation and connection do not perform separate
 * DNS lookups.
 */
internal object SafeRemoteImporter {
    private const val MAX_REDIRECTS = 4

    private object PublicOnlyDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val addresses = Dns.SYSTEM.lookup(hostname)
            if (addresses.isEmpty() || addresses.any(::isForbiddenAddress)) {
                throw UnknownHostException("URL host does not resolve to a public address")
            }
            return addresses
        }
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .dns(PublicOnlyDns)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    suspend fun downloadText(rawUrl: String, maxBytes: Int): String = withContext(Dispatchers.IO) {
        require(maxBytes in 1..4 * 1024 * 1024) { "invalid download size limit" }
        var url = validateUrl(rawUrl)
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json, text/markdown, text/plain;q=0.9, */*;q=0.1")
                .header("User-Agent", "OpenMinis-Pet-Remote-Importer")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code in 300..399) {
                    if (redirectCount >= MAX_REDIRECTS) {
                        throw RPCException(-32602, "Too many redirects while downloading URL")
                    }
                    val location = response.header("Location")
                        ?: throw RPCException(-32602, "Redirect response has no Location header")
                    url = validateUrl(url.resolve(location)?.toString() ?: location)
                    return@repeat
                }
                if (!response.isSuccessful) {
                    throw RPCException(-32602, "URL download failed with HTTP ${response.code}")
                }
                val body = response.body
                    ?: throw RPCException(-32602, "URL download returned an empty response")
                val announced = body.contentLength()
                if (announced > maxBytes) {
                    throw RPCException(-32602, "URL content exceeds the ${maxBytes / 1024} KiB limit")
                }
                val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
                body.byteStream().use { input ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > maxBytes) {
                            throw RPCException(-32602, "URL content exceeds the ${maxBytes / 1024} KiB limit")
                        }
                        output.write(buffer, 0, count)
                    }
                }
                return@withContext output.toString(Charsets.UTF_8.name())
            }
        }
        throw RPCException(-32602, "URL download did not complete")
    }

    private fun validateUrl(rawUrl: String): HttpUrl {
        val url = rawUrl.trim().toHttpUrlOrNull()
            ?: throw RPCException(-32602, "URL is not valid")
        if (url.scheme != "https") {
            throw RPCException(-32602, "Only HTTPS import URLs are allowed")
        }
        if (url.port != 443) {
            throw RPCException(-32602, "Import URLs must use the standard HTTPS port")
        }
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            throw RPCException(-32602, "Credentials in import URLs are not allowed")
        }
        val host = url.host.lowercase().trimEnd('.')
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) {
            throw RPCException(-32602, "Local import hosts are not allowed")
        }
        // Resolve now for a fast, user-facing validation error. PublicOnlyDns
        // repeats the same policy at the actual connection boundary.
        try {
            PublicOnlyDns.lookup(host)
        } catch (e: UnknownHostException) {
            throw RPCException(-32602, e.message ?: "Import host is unavailable")
        }
        return url
    }

    private fun isForbiddenAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress ||
            address.isLinkLocalAddress || address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) return true

        val bytes = address.address
        if (bytes.size == 4) {
            val a = bytes[0].toInt() and 0xff
            val b = bytes[1].toInt() and 0xff
            // Carrier-grade NAT and benchmark ranges are not globally
            // reachable and can expose services in a device/VPN environment.
            if (a == 100 && b in 64..127) return true
            if (a == 198 && b in 18..19) return true
            if (a == 0 || a >= 224) return true
        } else if (address is Inet6Address && bytes.isNotEmpty()) {
            // fc00::/7 unique-local addresses are not covered consistently by
            // InetAddress.isSiteLocalAddress on Android/Java.
            if ((bytes[0].toInt() and 0xfe) == 0xfc) return true
        }
        return false
    }
}
