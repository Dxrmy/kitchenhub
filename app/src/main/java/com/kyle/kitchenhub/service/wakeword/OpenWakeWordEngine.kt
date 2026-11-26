package com.kyle.kitchenhub.service.wakeword

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.kyle.kitchenhub.service.audio.MicArbiter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.sqrt

class OpenWakeWordEngine(private val context: Context) {

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHUNK_SAMPLES = 1280
        private const val MEL_BINS = 32
        private const val MEL_WINDOW = 76
        private const val MEL_STEP = 8
        private const val EMB_DIM = 96
        private const val EMB_WINDOW = 16
        private const val TAG = "OpenWakeWord"
        private const val MIN_MODEL_BYTES = 10_240
        private const val GOOGLE_MODEL_NAME = "hey_google.onnx"
        private const val FALLBACK_MODEL_NAME = "hey_jarvis_v0.1.onnx"
    }

    var threshold: Float = 0.25f
    var patienceFrames: Int = 1
    var cooldownMs: Long = 2000L

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val sessionOpts: OrtSession.SessionOptions by lazy {
        OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
        }
    }
    private val melSession: OrtSession by lazy {
        env.createSession(context.assets.open("models/melspectrogram.onnx").readBytes(), sessionOpts)
    }
    private val embSession: OrtSession by lazy {
        env.createSession(context.assets.open("models/embedding_model.onnx").readBytes(), sessionOpts)
    }
    private val melFlat = FloatArray(MEL_WINDOW * MEL_BINS)
    private val embFlat = FloatArray(EMB_WINDOW * EMB_DIM)
    private var wwSession: OrtSession? = null
    private var customModelPath: String? = null

    private val melBuffer = Array(MEL_WINDOW) { FloatArray(MEL_BINS) }
    private var melCount = 0
    private var framesSinceEmb = 0
    private val embBuffer = Array(EMB_WINDOW) { FloatArray(EMB_DIM) }
    private var embCount = 0

    private var job: Job? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    fun setModel(assetOrPath: String, isCustomFile: Boolean = false) {
        wwSession?.close()
        customModelPath = if (isCustomFile) assetOrPath else null
        val bytes = if (isCustomFile) File(assetOrPath).readBytes()
        else context.assets.open("models/$assetOrPath").readBytes()
        require(isValidOnnxBytes(bytes)) { "Invalid ONNX model: $assetOrPath" }
        wwSession = env.createSession(bytes, sessionOpts)
    }

    private fun isValidOnnxBytes(bytes: ByteArray): Boolean {
        return bytes.size > MIN_MODEL_BYTES && bytes[0] != '<'.code.toByte()
    }

    private fun loadPreferredWakeWordModel() {
        val externalFile = File(context.getExternalFilesDir("models"), GOOGLE_MODEL_NAME)
        if (externalFile.isFile) {
            val bytes = runCatching { externalFile.readBytes() }.getOrNull()
            if (bytes != null && isValidOnnxBytes(bytes)) {
                wwSession?.close()
                customModelPath = externalFile.absolutePath
                wwSession = env.createSession(bytes, sessionOpts)
                return
            }
        }

        val bundledGoogle = runCatching {
            context.assets.open("models/$GOOGLE_MODEL_NAME").use { it.readBytes() }
        }.getOrNull()
        if (bundledGoogle != null && isValidOnnxBytes(bundledGoogle)) {
            wwSession?.close()
            customModelPath = null
            wwSession = env.createSession(bundledGoogle, sessionOpts)
            Log.i(TAG, "Loaded bundled $GOOGLE_MODEL_NAME (${bundledGoogle.size} bytes)")
            return
        }

        Log.w(TAG, "hey_google.onnx not found; falling back to hey_jarvis_v0.1.onnx until model is installed")
        setModel(FALLBACK_MODEL_NAME)
    }

    @SuppressLint("MissingPermission")
    fun start(
        onScore: (score: Float, rmsDb: Float) -> Unit,
        onDetected: (score: Float) -> Unit
    ) {
        if (job?.isActive == true) return
        flushBuffers()

        job = CoroutineScope(Dispatchers.Default).launch {
            if (wwSession == null) loadPreferredWakeWordModel()
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(CHUNK_SAMPLES * 4)

            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return@launch
            }
            if (!MicArbiter.acquireForWakeWord(record)) {
                record.release()
                return@launch
            }

            attachAudioEffects(record.audioSessionId)
            record.startRecording()

            val pcm = ShortArray(CHUNK_SAMPLES)
            val floatPcm = FloatArray(CHUNK_SAMPLES)
            var consecutiveHits = 0
            var lastTriggerAt = 0L
            var lastLogAt = 0L
            var loggedFirstChunk = false

            try {
                while (isActive) {
                    val read = record.read(pcm, 0, CHUNK_SAMPLES)
                    if (read != CHUNK_SAMPLES) continue
                    var sumSq = 0.0
                    for (i in 0 until CHUNK_SAMPLES) {
                        val f = pcm[i].toFloat()
                        floatPcm[i] = f
                        sumSq += f * f
                    }
                    val rms = sqrt(sumSq / CHUNK_SAMPLES).toFloat().coerceAtLeast(1f)
                    if (!loggedFirstChunk) {
                        loggedFirstChunk = true
                        Log.i(TAG, "AudioRecord active: first rms=$rms thr=$threshold")
                    }
                    val score = runPipeline(floatPcm)
                    onScore(score, rms)

                    val now = System.currentTimeMillis()
                    if (score >= 0.08f && now - lastLogAt >= 500L) {
                        lastLogAt = now
                        Log.i(TAG, "score=$score rms=$rms thr=$threshold")
                    }
                    if (score >= threshold && now - lastTriggerAt >= cooldownMs) {
                        consecutiveHits++
                        if (consecutiveHits >= patienceFrames) {
                            consecutiveHits = 0
                            lastTriggerAt = now
                            onDetected(score)
                            break
                        }
                    } else {
                        consecutiveHits = 0
                    }
                }
            } finally {
                releaseEffects()
                MicArbiter.releaseWakeWord()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        releaseEffects()
    }

    private fun flushBuffers() {
        for (r in melBuffer) r.fill(0f)
        for (r in embBuffer) r.fill(0f)
        melCount = 0
        framesSinceEmb = 0
        embCount = 0
    }

    private fun runPipeline(samples: FloatArray): Float {
        val melInName = melSession.inputNames.first()
        val melTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(samples), longArrayOf(1, CHUNK_SAMPLES.toLong()))
        val newFrames: Array<FloatArray> = melTensor.use { t ->
            melSession.run(mapOf(melInName to t)).use { res ->
                val raw = res[0].value as Array<Array<Array<FloatArray>>>
                val frames = raw[0][0]
                Array(frames.size) { f ->
                    FloatArray(MEL_BINS) { b -> frames[f][b] / 10f + 2f }
                }
            }
        }

        var latestScore = 0f
        for (frame in newFrames) {
            if (melCount < MEL_WINDOW) {
                frame.copyInto(melBuffer[melCount++])
            } else {
                for (i in 0 until MEL_WINDOW - 1) melBuffer[i + 1].copyInto(melBuffer[i])
                frame.copyInto(melBuffer[MEL_WINDOW - 1])
            }
            framesSinceEmb++
            if (melCount == MEL_WINDOW && framesSinceEmb >= MEL_STEP) {
                framesSinceEmb = 0
                val emb = runEmbedding()
                if (embCount < EMB_WINDOW) {
                    emb.copyInto(embBuffer[embCount++])
                } else {
                    for (i in 0 until EMB_WINDOW - 1) embBuffer[i + 1].copyInto(embBuffer[i])
                    emb.copyInto(embBuffer[EMB_WINDOW - 1])
                }
                if (embCount == EMB_WINDOW) latestScore = runClassifier()
            }
        }
        return latestScore
    }

    private fun runEmbedding(): FloatArray {
        for (i in 0 until MEL_WINDOW) melBuffer[i].copyInto(melFlat, i * MEL_BINS)
        val inName = embSession.inputNames.first()
        val t = OnnxTensor.createTensor(env, FloatBuffer.wrap(melFlat), longArrayOf(1, MEL_WINDOW.toLong(), MEL_BINS.toLong(), 1))
        return t.use { tensor ->
            embSession.run(mapOf(inName to tensor)).use { res ->
                val raw = res[0].value as Array<Array<Array<FloatArray>>>
                raw[0][0][0]
            }
        }
    }

    private fun runClassifier(): Float {
        val session = wwSession ?: return 0f
        for (i in 0 until EMB_WINDOW) embBuffer[i].copyInto(embFlat, i * EMB_DIM)
        val inName = session.inputNames.first()
        val outName = session.outputNames.first()
        val t = OnnxTensor.createTensor(env, FloatBuffer.wrap(embFlat), longArrayOf(1, EMB_WINDOW.toLong(), EMB_DIM.toLong()))
        return t.use { tensor ->
            session.run(mapOf(inName to tensor)).use { res ->
                val raw = res.get(outName).get().value as Array<FloatArray>
                raw[0][0]
            }
        }
    }

    private fun attachAudioEffects(sessionId: Int) {
        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
        }
        if (NoiseSuppressor.isAvailable()) {
            ns = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
        }
    }

    private fun releaseEffects() {
        runCatching { aec?.release() }; aec = null
        runCatching { ns?.release() }; ns = null
    }
}
