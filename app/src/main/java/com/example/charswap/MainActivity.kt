package com.example.charswap

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                App()
            }
        }
    }
}

private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var endpoint by remember { mutableStateOf(prefs(ctx).getString("endpoint", "") ?: "") }
    var apiKey by remember { mutableStateOf(prefs(ctx).getString("apiKey", "") ?: "") }
    var video by remember { mutableStateOf<Uri?>(null) }
    var faces by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<File?>(null) }

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            video = uri
            result = null
        }
    }
    val pickFaces = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { list ->
        if (list.isNotEmpty()) {
            faces = list
            result = null
        }
    }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Замена персонажей в видео", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = endpoint,
            onValueChange = { endpoint = it },
            label = { Text("Адрес API (https://...)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("Ключ API") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedButton(
            onClick = { pickVideo.launch("video/*") },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (video == null) "1. Выбрать видео" else "1. Видео выбрано (изменить)") }

        OutlinedButton(
            onClick = { pickFaces.launch("image/*") },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (faces.isEmpty()) "2. Загрузить фото персонажей" else "2. Фото: ${faces.size} (изменить)") }

        if (faces.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                faces.take(5).forEach { Thumb(it) }
            }
        }

        Button(
            enabled = !busy && video != null && faces.isNotEmpty() && endpoint.isNotBlank(),
            onClick = {
                prefs(ctx).edit().putString("endpoint", endpoint.trim()).putString("apiKey", apiKey.trim()).apply()
                scope.launch {
                    busy = true
                    result = null
                    status = "Отправка и обработка… это может занять несколько минут, не закрывайте приложение"
                    try {
                        val out = File(ctx.cacheDir, "result_${System.currentTimeMillis()}.mp4")
                        SwapApi.swap(ctx, endpoint.trim(), apiKey.trim(), video!!, faces, out)
                        result = out
                        status = "Готово"
                    } catch (e: Exception) {
                        status = "Ошибка: ${e.message}"
                    } finally {
                        busy = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("3. Заменить персонажей") }

        if (busy) CircularProgressIndicator()
        if (status.isNotEmpty()) Text(status)

        result?.let { file ->
            key(file.absolutePath) {
                AndroidView(
                    factory = { c ->
                        VideoView(c).apply {
                            setMediaController(MediaController(c))
                            setVideoPath(file.absolutePath)
                            setOnPreparedListener { mp ->
                                mp.isLooping = true
                                start()
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp),
                )
            }
            Button(
                onClick = {
                    scope.launch {
                        val ok = saveToGallery(ctx, file)
                        status = if (ok) "Сохранено в Фильмы/CharSwap" else "Не удалось сохранить"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Сохранить в галерею") }
        }
    }
}

@Composable
private fun Thumb(uri: Uri) {
    val ctx = LocalContext.current
    val bmp by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                ctx.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }?.asImageBitmap()
            }.getOrNull()
        }
    }
    bmp?.let {
        Image(
            bitmap = it,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
    }
}

private suspend fun saveToGallery(ctx: Context, file: File): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "charswap_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/CharSwap")
        }
        val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("insert failed")
        ctx.contentResolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        } ?: error("open failed")
    }.isSuccess
}
