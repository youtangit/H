package com.example.charswap

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

private class UriRequestBody(
    private val context: Context,
    private val uri: Uri,
    private val mime: String,
) : RequestBody() {
    override fun contentType(): MediaType? = mime.toMediaTypeOrNull()

    override fun contentLength(): Long = try {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    } catch (e: Exception) {
        -1L
    }

    override fun writeTo(sink: BufferedSink) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Не удалось открыть файл")
        input.source().use { sink.writeAll(it) }
    }
}

object SwapApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES)
        .readTimeout(30, TimeUnit.MINUTES)
        .build()

    suspend fun swap(
        context: Context,
        endpoint: String,
        apiKey: String,
        video: Uri,
        faces: List<Uri>,
        outFile: File,
    ) = withContext(Dispatchers.IO) {
        require(endpoint.startsWith("https://")) { "Адрес API должен начинаться с https://" }

        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
        val videoMime = context.contentResolver.getType(video) ?: "video/mp4"
        body.addFormDataPart("video", "video.mp4", UriRequestBody(context, video, videoMime))
        faces.forEachIndexed { i, uri ->
            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
            body.addFormDataPart("face", "face$i.jpg", UriRequestBody(context, uri, mime))
        }

        val request = Request.Builder().url(endpoint).post(body.build()).apply {
            if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
        }.build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val text = response.body?.string()?.take(300).orEmpty()
                throw IOException("Сервер вернул ${response.code}: $text")
            }
            val type = response.body?.contentType()?.toString().orEmpty()
            if (type.startsWith("video") || type.startsWith("application/octet-stream")) {
                saveBody(response, outFile)
            } else {
                val json = JSONObject(response.body?.string().orEmpty())
                val url = extractUrl(json)
                    ?: throw IOException("В ответе сервера нет ссылки на результат")
                download(url, outFile)
            }
        }
    }

    private fun extractUrl(json: JSONObject): String? {
        json.optString("url").takeIf { it.isNotBlank() }?.let { return it }
        return when (val out = json.opt("output")) {
            is String -> out.takeIf { it.isNotBlank() }
            is JSONArray -> if (out.length() > 0) out.optString(0) else null
            else -> null
        }
    }

    private fun download(url: String, outFile: File) {
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Не удалось скачать результат: ${resp.code}")
            saveBody(resp, outFile)
        }
    }

    private fun saveBody(response: Response, outFile: File) {
        val body = response.body ?: throw IOException("Пустой ответ сервера")
        body.byteStream().use { input ->
            outFile.outputStream().use { output -> input.copyTo(output) }
        }
    }
}
