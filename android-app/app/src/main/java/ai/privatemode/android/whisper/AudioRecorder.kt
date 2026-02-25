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
 *
 * Recording is capped at [MAX_DURATION_SECONDS] to keep transcription time
 * bounded on mobile devices.
 */
class AudioRecorder {

    companion object {
        /** Maximum recording duration in seconds. */
        const val MAX_DURATION_SECONDS = 60
    }

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_FLOAT

    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        .coerceAtLeast(sampleRate / 10 * 4) // at least 100ms of float samples

    private val maxSamples = sampleRate * MAX_DURATION_SECONDS

    @Volatile
    private var recording = false

    private val lock = Any()
    private val pcmBuffer = mutableListOf<Float>()

    @Volatile
    private var finished = false

    private val _amplitudes = MutableStateFlow<List<Float>>(emptyList())
    val amplitudes: StateFlow<List<Float>> = _amplitudes.asStateFlow()

    /**
     * Blocking call — run on Dispatchers.IO.
     * Reads 100ms chunks until [stopRecording] is called or [MAX_DURATION_SECONDS] is reached.
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
        finished = false
        synchronized(lock) { pcmBuffer.clear() }
        _amplitudes.value = emptyList()

        val chunkSize = sampleRate / 10 // 100ms = 1600 samples at 16kHz
        val chunk = FloatArray(chunkSize)
        var totalSamples = 0

        try {
            while (recording && totalSamples < maxSamples) {
                val read = recorder.read(chunk, 0, chunkSize, AudioRecord.READ_BLOCKING)
                if (read > 0) {
                    val remaining = maxSamples - totalSamples
                    val toAdd = read.coerceAtMost(remaining)
                    synchronized(lock) {
                        for (i in 0 until toAdd) {
                            pcmBuffer.add(chunk[i])
                        }
                    }
                    totalSamples += toAdd
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
            recording = false
            finished = true
        }
    }

    fun stopRecording() {
        recording = false
    }

    /** Returns true once [startRecording] has fully exited. */
    fun isFinished(): Boolean = finished

    fun getSamples(): FloatArray = synchronized(lock) { pcmBuffer.toFloatArray() }
}
