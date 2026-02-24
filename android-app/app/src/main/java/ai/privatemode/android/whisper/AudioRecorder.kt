package ai.privatemode.android.whisper

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Records 16kHz mono float PCM audio using AudioRecord.
 * This is the native format whisper.cpp expects, avoiding any transcoding.
 */
class AudioRecorder {

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_FLOAT

    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        .coerceAtLeast(sampleRate / 10 * 4) // at least 100ms of float samples

    @Volatile
    private var recording = false

    private val pcmBuffer = mutableListOf<Float>()

    private val _amplitudes = MutableStateFlow<List<Float>>(emptyList())
    val amplitudes: StateFlow<List<Float>> = _amplitudes.asStateFlow()

    /**
     * Blocking call — run on Dispatchers.IO.
     * Reads 100ms chunks until [stopRecording] is called.
     */
    fun startRecording() {
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize,
        )

        recorder.startRecording()
        recording = true
        pcmBuffer.clear()
        _amplitudes.value = emptyList()

        val chunkSize = sampleRate / 10 // 100ms = 1600 samples at 16kHz
        val chunk = FloatArray(chunkSize)

        try {
            while (recording) {
                val read = recorder.read(chunk, 0, chunkSize, AudioRecord.READ_BLOCKING)
                if (read > 0) {
                    for (i in 0 until read) {
                        pcmBuffer.add(chunk[i])
                    }
                    // Compute amplitude for waveform display
                    var maxAmp = 0f
                    for (i in 0 until read) {
                        val a = abs(chunk[i])
                        if (a > maxAmp) maxAmp = a
                    }
                    val list = _amplitudes.value
                    _amplitudes.value = (list + maxAmp.coerceIn(0f, 1f)).takeLast(30)
                }
            }
        } finally {
            recorder.stop()
            recorder.release()
        }
    }

    fun stopRecording() {
        recording = false
    }

    fun getSamples(): FloatArray = pcmBuffer.toFloatArray()
}
