package com.spotipremium.api

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import java.util.concurrent.TimeUnit

object RemoteLogger {

    private var serverUrl: String = ""
    private var context: Context? = null
    private val localBuffer = mutableListOf<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    fun init(ctx: Context, url: String) {
        context = ctx
        serverUrl = url
    }

    fun refreshUrl(url: String) {
        serverUrl = url
    }

    fun log(tag: String, message: String) {
        val entry = "[$tag] $message"
        localBuffer.add(entry)
        if (localBuffer.size > 1000) localBuffer.removeAt(0)
        Log.d("RemoteLogger", entry)

        // Send to server (fire and forget)
        if (serverUrl.isNotBlank()) {
            scope.launch {
                try {
                    val body = FormBody.Builder().add("entry", entry).build()
                    client.newCall(Request.Builder()
                        .url("$serverUrl/api/log")
                        .post(body)
                        .build()).execute().close()
                } catch (_: Exception) {}
            }
        }
    }

    fun getLocalBuffer(): List<String> = localBuffer.toList()
}
