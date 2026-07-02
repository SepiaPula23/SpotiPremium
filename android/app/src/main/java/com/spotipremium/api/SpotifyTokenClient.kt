package com.spotipremium.api

import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class SpotifyTokenClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

    // Fallback secret (version 61)
    private val fallbackVersion = 61
    private val fallbackSecret = byteArrayOf(
        44, 55, 47, 42, 70, 40, 34, 114, 76, 74, 50, 111, 120, 97, 75, 76,
        94, 102, 43, 69, 49, 120, 118, 80, 64, 78
    )

    data class TokenResult(
        val accessToken: String,
        val clientId: String,
        val expiresAtMs: Long
    )

    suspend fun getToken(): TokenResult? = withContext(Dispatchers.IO) {
        try {
            val (version, secretBytes) = fetchSecret()
            val totp = generateTotp(secretBytes, version)

            val url = "https://open.spotify.com/api/token?reason=init" +
                    "&productType=web-player" +
                    "&totp=$totp" +
                    "&totpVer=$version" +
                    "&totpServer=$totp"

            val resp = client.newCall(Request.Builder()
                .url(url)
                .addHeader("User-Agent", ua)
                .build()).execute()
            try {
                if (resp.code == 200) {
                    val json = resp.body?.string() ?: return@withContext null
                    val root = JsonParser.parseString(json).asJsonObject
                    val accessToken = root.get("accessToken")?.asString ?: return@withContext null
                    val clientId = root.get("clientId")?.asString ?: ""
                    val expiresAtMs = root.get("accessTokenExpirationTimestampMs")?.asLong ?: 0L
                    TokenResult(accessToken, clientId, expiresAtMs)
                } else null
            } finally { resp.close() }
        } catch (_: Exception) { null }
    }

    private suspend fun fetchSecret(): Pair<Int, ByteArray> {
        return try {
            val resp = client.newCall(Request.Builder()
                .url("https://code.thetadev.de/ThetaDev/spotify-secrets/raw/branch/main/secrets/secretDict.json")
                .addHeader("User-Agent", ua)
                .build()).execute()
            try {
                if (resp.code == 200) {
                    val json = resp.body?.string() ?: return Pair(fallbackVersion, fallbackSecret)
                    val root = JsonParser.parseString(json).asJsonObject
                    val versions = root.keySet().map { it.toIntOrNull() ?: 0 }
                    val maxVersion = versions.maxOrNull() ?: fallbackVersion
                    val arr = root.get(maxVersion.toString())?.asJsonArray ?: return Pair(fallbackVersion, fallbackSecret)
                    val bytes = ByteArray(arr.size()) { arr.get(it).asInt.toByte() }
                    Pair(maxVersion, bytes)
                } else Pair(fallbackVersion, fallbackSecret)
            } finally { resp.close() }
        } catch (_: Exception) { Pair(fallbackVersion, fallbackSecret) }
    }

    private fun generateTotp(secretBytes: ByteArray, version: Int): String {
        // Transform bytes: transformed[t] = secretBytes[t] XOR ((t % 33) + 9)
        val transformed = ByteArray(secretBytes.size)
        for (t in secretBytes.indices) {
            val e = secretBytes[t].toInt() and 0xFF
            transformed[t] = (e xor ((t % 33) + 9)).toByte()
        }

        // Join each byte as decimal string: [65, 66] -> "6566"
        val joined = buildString {
            for (b in transformed) {
                append(b.toInt() and 0xFF)
            }
        }

        // Encode the UTF-8 bytes of the joined string as hex: "6566" -> "36353636"
        val hexStr = joined.toByteArray().joinToString("") { "%02x".format(it) }

        // Decode hex string to bytes
        val hexLen = hexStr.length
        val hexBytes = ByteArray(hexLen / 2)
        for (i in 0 until hexLen step 2) {
            hexBytes[i / 2] = hexStr.substring(i, i + 2).toInt(16).toByte()
        }

        // Encode hex bytes to base32
        val b32Secret = base32Encode(hexBytes)

        // Decode base32 to get raw key bytes
        val rawKey = base32Decode(b32Secret)

        // Generate TOTP (RFC 6238)
        val timeCounter = System.currentTimeMillis() / 1000 / 30
        val counterBytes = ByteArray(8)
        var c = timeCounter
        for (i in 7 downTo 0) {
            counterBytes[i] = (c and 0xFF).toByte()
            c = c shr 8
        }

        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(rawKey, "HmacSHA1"))
        val hmac = mac.doFinal(counterBytes)

        val offset = hmac[hmac.size - 1].toInt() and 0xF
        val code = ((hmac[offset].toInt() and 0x7F) shl 24) or
                ((hmac[offset + 1].toInt() and 0xFF) shl 16) or
                ((hmac[offset + 2].toInt() and 0xFF) shl 8) or
                (hmac[offset + 3].toInt() and 0xFF)

        return (code % 1000000).toString().padStart(6, '0')
    }

    private fun base32Encode(data: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val bits = StringBuilder()
        for (b in data) {
            bits.append((b.toInt() and 0xFF).toString(2).padStart(8, '0'))
        }
        val sb = StringBuilder()
        var i = 0
        while (i < bits.length) {
            val end = minOf(i + 5, bits.length)
            val chunk = bits.substring(i, end)
            val padded = chunk.padEnd(5, '0')
            sb.append(alphabet[padded.toInt(2)])
            i += 5
        }
        // Pad to multiple of 8 chars
        while (sb.length % 8 != 0) sb.append('=')
        return sb.toString()
    }

    private fun base32Decode(str: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val cleaned = str.replace("=", "").uppercase()
        val bits = StringBuilder()
        for (c in cleaned) {
            val idx = alphabet.indexOf(c)
            if (idx >= 0) {
                bits.append(idx.toString(2).padStart(5, '0'))
            }
        }
        val bytes = mutableListOf<Byte>()
        var i = 0
        while (i + 8 <= bits.length) {
            val byteStr = bits.substring(i, i + 8)
            bytes.add(byteStr.toInt(2).toByte())
            i += 8
        }
        return bytes.toByteArray()
    }
}
