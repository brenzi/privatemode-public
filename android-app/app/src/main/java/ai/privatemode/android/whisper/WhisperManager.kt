package ai.privatemode.android.whisper

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

sealed class WhisperModelState {
    data object NotDownloaded : WhisperModelState()
    data class Downloading(val progress: Float) : WhisperModelState()
    data object Ready : WhisperModelState()
    data class Error(val message: String) : WhisperModelState()
}

class WhisperManager(private val context: Context) {

    private val TAG = "WhisperManager"
    private val MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin"
    private val MODEL_DIR = "whisper"
    private val MODEL_FILE = "ggml-small-q5_1.bin"

    private val _modelState = MutableStateFlow<WhisperModelState>(WhisperModelState.NotDownloaded)
    val modelState: StateFlow<WhisperModelState> = _modelState.asStateFlow()

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private fun modelFile(): File = File(File(context.filesDir, MODEL_DIR), MODEL_FILE)

    suspend fun initialize() = withContext(Dispatchers.IO) {
        if (!WhisperNative.loadLibrary()) {
            _modelState.value = WhisperModelState.Error("Failed to load native library")
            return@withContext
        }
        val file = modelFile()
        if (file.exists()) {
            Log.i(TAG, "Loading model from ${file.absolutePath} (${file.length() / 1024 / 1024}MB)")
            val result = WhisperNative.nativeInit(file.absolutePath)
            _modelState.value = if (result == 0) {
                Log.i(TAG, "Model loaded successfully")
                WhisperModelState.Ready
            } else {
                Log.e(TAG, "nativeInit returned $result")
                WhisperModelState.Error("Failed to load model")
            }
        } else {
            _modelState.value = WhisperModelState.NotDownloaded
        }
    }

    suspend fun downloadModel() = withContext(Dispatchers.IO) {
        try {
            _modelState.value = WhisperModelState.Downloading(0f)

            val dir = File(context.filesDir, MODEL_DIR)
            if (!dir.exists()) dir.mkdirs()
            val file = modelFile()
            val tmpFile = File(dir, "$MODEL_FILE.tmp")

            val request = Request.Builder().url(MODEL_URL).build()
            val response = downloadClient.newCall(request).execute()

            if (!response.isSuccessful) {
                _modelState.value = WhisperModelState.Error("Download failed: ${response.code}")
                return@withContext
            }

            val body = response.body ?: run {
                _modelState.value = WhisperModelState.Error("Empty response")
                return@withContext
            }

            val contentLength = body.contentLength()
            var bytesRead = 0L

            body.byteStream().use { input ->
                FileOutputStream(tmpFile).use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesRead += read
                        if (contentLength > 0) {
                            _modelState.value = WhisperModelState.Downloading(
                                bytesRead.toFloat() / contentLength
                            )
                        }
                    }
                }
            }

            tmpFile.renameTo(file)

            if (!WhisperNative.isLoaded()) {
                WhisperNative.loadLibrary()
            }
            val result = WhisperNative.nativeInit(file.absolutePath)
            _modelState.value = if (result == 0) {
                WhisperModelState.Ready
            } else {
                WhisperModelState.Error("Failed to load model")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Model download failed", e)
            _modelState.value = WhisperModelState.Error(e.message ?: "Download failed")
        }
    }

    fun deleteModel() {
        WhisperNative.nativeFree()
        modelFile().delete()
        _modelState.value = WhisperModelState.NotDownloaded
    }

    fun transcribe(samples: FloatArray): String {
        val cores = Runtime.getRuntime().availableProcessors()
        // ggml's thread barrier deadlocks on x86_64 emulators; use 1 thread there.
        // Real ARM devices get up to 4 threads.
        val isEmulator = android.os.Build.HARDWARE.contains("ranchu") ||
            android.os.Build.HARDWARE.contains("goldfish") ||
            android.os.Build.FINGERPRINT.contains("generic")
        val threads = if (isEmulator) 1 else cores.coerceIn(2, 4)
        Log.i(TAG, "transcribe: ${samples.size} samples (${samples.size / 16000f}s), $threads threads ($cores cores, emulator=$isEmulator)")
        val start = System.currentTimeMillis()
        val result = WhisperNative.nativeTranscribe(samples, threads)
        Log.i(TAG, "transcribe done in ${System.currentTimeMillis() - start}ms: \"${result.take(100)}\"")
        return result
    }

    fun isReady(): Boolean = _modelState.value is WhisperModelState.Ready
}
