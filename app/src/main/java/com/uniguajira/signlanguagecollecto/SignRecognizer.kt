package com.uniguajira.signlanguagecollecto

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONArray
import org.json.JSONObject
import java.nio.FloatBuffer

/**
 * Wraps the ONNX sign-language model.
 *
 * Usage:
 *  1. Call [addFrame] on every incoming [BluetoothService.SensorData].
 *  2. Returns a [Result] once the sliding window is full (25 frames), null before that.
 *  3. Call [reset] when switching back to capture mode.
 *  4. Call [close] in onDestroy.
 *
 * Thread-safe: [addFrame] may be called from the BLE callback thread;
 * the ONNX session is thread-safe for concurrent inference.
 */
class SignRecognizer(context: Context) : AutoCloseable {

    data class Result(
        val label: String,
        val confidence: Float,       // 0..1
        val windowFill: Int          // how many frames are currently in the window (max WINDOW_SIZE)
    )

    // ── Model constants ───────────────────────────────────────────────────────

    companion object {
        const val WINDOW_SIZE = 25
        const val N_FEATURES  = 14
        const val CONFIDENCE_THRESHOLD = 0.55f   // below this → show "?" in UI
    }

    // ── ONNX runtime ──────────────────────────────────────────────────────────

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    // ── Scaler parameters (loaded from assets) ────────────────────────────────

    private val featMin: FloatArray
    private val featMax: FloatArray
    private val featRange: FloatArray

    // ── Label map ─────────────────────────────────────────────────────────────

    private val labels: List<String>

    // ── Sliding window ────────────────────────────────────────────────────────

    /** Each element is a normalized frame of N_FEATURES floats. */
    private val window = ArrayDeque<FloatArray>(WINDOW_SIZE)

    init {
        // ── Load ONNX model ───────────────────────────────────────────────────
        val modelBytes = context.assets.open("sign_model.onnx").readBytes()
        val opts = OrtSession.SessionOptions()
        session = env.createSession(modelBytes, opts)

        // ── Load scaler_params.json ───────────────────────────────────────────
        val scalerJson = JSONObject(
            context.assets.open("scaler_params.json").bufferedReader().readText()
        )
        val minArray = scalerJson.getJSONArray("min")
        val maxArray = scalerJson.getJSONArray("max")
        featMin   = FloatArray(N_FEATURES) { minArray.getDouble(it).toFloat() }
        featMax   = FloatArray(N_FEATURES) { maxArray.getDouble(it).toFloat() }
        featRange = FloatArray(N_FEATURES) { i ->
            val r = featMax[i] - featMin[i]
            if (r == 0f) 1f else r
        }

        // ── Load labels.json ──────────────────────────────────────────────────
        val labelsArr: JSONArray = JSONArray(
            context.assets.open("labels.json").bufferedReader().readText()
        )
        labels = List(labelsArr.length()) { labelsArr.getString(it) }
    }

    /**
     * Adds one sensor frame to the sliding window and runs inference if full.
     * Returns null while the window is still filling up.
     */
    fun addFrame(data: BluetoothService.SensorData): Result? {
        // Normalize the 14 features
        val raw = floatArrayOf(
            data.flex1.toFloat(), data.flex2.toFloat(), data.flex3.toFloat(),
            data.flex4.toFloat(), data.flex5.toFloat(),
            data.accelX, data.accelY, data.accelZ,
            data.gyroX,  data.gyroY,  data.gyroZ,
            data.roll,   data.pitch,  data.yaw
        )
        val normalized = FloatArray(N_FEATURES) { i -> (raw[i] - featMin[i]) / featRange[i] }

        if (window.size >= WINDOW_SIZE) window.removeFirst()
        window.addLast(normalized)

        val fill = window.size
        if (fill < WINDOW_SIZE) return Result("", 0f, fill)

        // ── Build input tensor: shape (1, N_FEATURES, WINDOW_SIZE) ────────────
        // PyTorch Conv1d convention → (batch, channels, timesteps)
        val inputData = FloatArray(N_FEATURES * WINDOW_SIZE) { idx ->
            val f = idx / WINDOW_SIZE      // feature index
            val t = idx % WINDOW_SIZE      // timestep index
            window[t][f]
        }

        // ── Run inference ─────────────────────────────────────────────────────
        val tensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(inputData),
            longArrayOf(1L, N_FEATURES.toLong(), WINDOW_SIZE.toLong())
        )
        val logits: FloatArray
        try {
            val outputs = session.run(mapOf("sensor_input" to tensor))
            val outTensor = outputs[0].value as Array<*>
            logits = outTensor[0] as FloatArray    // (N_CLASSES,)
            outputs.close()
        } finally {
            tensor.close()
        }

        // Softmax
        val maxLogit = logits.max()
        val exps     = FloatArray(logits.size) { Math.exp((logits[it] - maxLogit).toDouble()).toFloat() }
        val sumExps  = exps.sum()
        val probs    = FloatArray(logits.size) { exps[it] / sumExps }

        val predIdx    = probs.indices.maxByOrNull { probs[it] }!!
        val confidence = probs[predIdx]

        return Result(labels[predIdx], confidence, WINDOW_SIZE)
    }

    /** Clears the sliding window (call when entering recognition mode). */
    fun reset() {
        window.clear()
    }

    override fun close() {
        session.close()
        env.close()
    }
}
