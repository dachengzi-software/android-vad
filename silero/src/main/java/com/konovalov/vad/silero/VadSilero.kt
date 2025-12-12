package com.konovalov.vad.silero

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtSession.SessionOptions
import android.content.Context
import android.util.Log
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate
import com.konovalov.vad.silero.utils.AudioUtils.getFramesCount
import com.konovalov.vad.silero.utils.AudioUtils.toFloatArray
import java.io.Closeable

/**
 * Created by Georgiy Konovalov on 6/1/2023.
 *
 * The Silero VAD algorithm, based on DNN, analyzes the audio signal to determine whether it
 * contains speech or non-speech segments. It offers higher accuracy in differentiating speech from
 * background noise compared to the WebRTC VAD algorithm.
 *
 * The Silero VAD supports the following parameters:
 *
 * Sample Rates:
 *
 *      8000Hz,
 *      16000Hz
 *
 * Frame Sizes (per sample rate):
 *
 *    For 8000Hz: 80, 160, 240
 *    For 16000Hz: 160, 320, 480
 *
 * Mode:
 *
 *    NORMAL,
 *    LOW_BITRATE,
 *    AGGRESSIVE,
 *    VERY_AGGRESSIVE
 *
 * Please note that the VAD class supports these specific combinations of sample
 * rates and frame sizes, and the classifiers determine the aggressiveness of the voice
 * activity detection algorithm.
 *
 * @param context           is required for reading the model file from file system.
 * @param sampleRate        is required for processing audio input.
 * @param frameSize         is required for processing audio input.
 * @param mode              is required for the VAD model.
 * @param speechDurationMs  is minimum duration in milliseconds for speech segments (optional).
 * @param silenceDurationMs is minimum duration in milliseconds for silence segments (optional).
 */
class VadSilero(
    context: Context,
    sampleRate: SampleRate,
    frameSize: FrameSize,
    mode: Mode,
    speechDurationMs: Int = 0,
    silenceDurationMs: Int = 0
) : Closeable {

    /**
     * Valid Sample Rates and Frame Sizes for Silero VAD DNN model.
     */
    var supportedParameters: Map<SampleRate, Set<FrameSize>> = mapOf(
        SampleRate.SAMPLE_RATE_8K to setOf(
            FrameSize.FRAME_SIZE_256,
            FrameSize.FRAME_SIZE_512,
            FrameSize.FRAME_SIZE_768
        ),
        SampleRate.SAMPLE_RATE_16K to setOf(
            FrameSize.FRAME_SIZE_512,
            FrameSize.FRAME_SIZE_1024,
            FrameSize.FRAME_SIZE_1536
        )
    )
        private set

    companion object {
        private const val TAG = "VadSilero"

        private val SAMPLE_RATES = intArrayOf(8000, 16000)
    }

    private val env: OrtEnvironment
    private val session: OrtSession
    private var isInitiated: Boolean = false

    private var speechFramesCount = 0
    private var silenceFramesCount = 0
    private var maxSpeechFramesCount = 0
    private var maxSilenceFramesCount = 0

    private lateinit var state: Array<Array<FloatArray>>

    private var context: Array<FloatArray> = arrayOf()

    private var lastSr = 0
    private var lastBatchSize = 0

    private val windowSizeSamples: Int =
        if (sampleRate == SampleRate.SAMPLE_RATE_16K) 512 else 256
    private val contextSize: Int =
        if (sampleRate == SampleRate.SAMPLE_RATE_16K) 64 else 32

    /**
     * Determines if the provided audio data contains speech based on the inference result.
     * The audio data is passed to the model for prediction. The result is obtained and compared
     * with the threshold value to determine if it represents speech.
     *
     * @param audioData audio data to analyze.
     * @return 'true' if speech is detected, 'false' otherwise.
     */
    fun isSpeech(audioData: ShortArray): Boolean {
        return isContinuousSpeech(predict(toFloatArray(audioData)))
    }

    /**
     * Determines if the provided audio data contains speech based on the inference result.
     * The audio data is passed to the model for prediction. The result is obtained and compared
     * with the threshold value to determine if it represents speech.
     * Size of audio chunk for ByteArray should be 2x of Frame size.
     *
     * @param audioData audio data to analyze.
     * @return 'true' if speech is detected, 'false' otherwise.
     */
    fun isSpeech(audioData: ByteArray): Boolean {
        return isContinuousSpeech(predict(toFloatArray(audioData)))
    }

    /**
     * Determines if the provided audio data contains speech based on the inference result.
     * The audio data is passed to the model for prediction. The result is obtained and compared
     * with the threshold value to determine if it represents speech.
     *
     * @param audioData audio data to analyze.
     * @return 'true' if speech is detected, 'false' otherwise.
     */
    fun isSpeech(audioData: FloatArray): Boolean {
        return isContinuousSpeech(predict(audioData))
    }

    /**
     * This method designed to detect long utterances without returning false
     * positive results when user makes pauses between sentences.
     *
     * @param isSpeech predicted frame result.
     * @return 'true' if speech is detected, 'false' otherwise.
     */
    private fun isContinuousSpeech(isSpeech: Boolean): Boolean {
        if (isSpeech) {
            if (speechFramesCount <= maxSpeechFramesCount) speechFramesCount++

            if (speechFramesCount > maxSpeechFramesCount) {
                silenceFramesCount = 0
                return true
            }
        } else {
            if (silenceFramesCount <= maxSilenceFramesCount) silenceFramesCount++

            if (silenceFramesCount > maxSilenceFramesCount) {
                speechFramesCount = 0
                return false
            } else if (speechFramesCount > maxSpeechFramesCount) {
                return true
            }
        }
        return false
    }

    /**
     * Determines if the provided audio data contains speech based on the inference result.
     * The audio data is passed to the model for prediction. The result is extracted and compared
     * with the threshold value to determine if it represents speech.
     *
     * @param pcm audio data to analyze.
     * @return 'true' if speech is detected, 'false' otherwise.
     */
    private fun predict(pcm: FloatArray): Boolean {
        checkState()

        // 自动 trim/pad 到固定长度windowSizeSamples
        val input = pcm.copyOf(windowSizeSamples)

        val speechProbability = call(arrayOf(input), sampleRate.value)[0]
        val thresholdValue = threshold()
        val isSpeechDetected = speechProbability > thresholdValue

        Log.d(
            TAG,
            "VAD Result - Probability: $speechProbability, Threshold: $thresholdValue, IsSpeech: $isSpeechDetected, Mode: $mode"
        )

        return isSpeechDetected
    }

    /** Reset state using batch size = 1 */
    fun resetStates() {
        resetStates(1)
    }

    /** Reset with specific batch size */
    private fun resetStates(batchSize: Int) {
        state = Array(2) { Array(batchSize) { FloatArray(128) } }
        context = arrayOf<FloatArray>()
        lastSr = 0
        lastBatchSize = 0
    }

    fun reset() {
        resetStates()
    }

    /**
     * Inner class for validation result
     */
    data class ValidationResult(
        val x: Array<FloatArray>,
        val sr: Int
    )

    /**
     * Validate input data
     *
     * @param x Audio data array
     * @param sr Sample rate
     * @return Validated input data and sample rate
     */
    private fun validateInput(xInput: Array<FloatArray>, srInput: Int): ValidationResult {
        var x = xInput
        var sr = srInput

        // Ensure input is at least 2D
        if (x.size == 1) {
            x = arrayOf(x[0])
        }

        // Check if input dimension is valid
        if (x.size > 2) {
            throw IllegalArgumentException("Incorrect audio data dimension: ${x[0].size}")
        }

        // Downsample if sample rate is a multiple of 16000
        if (sr != 16000 && sr % 16000 == 0) {
            val step = sr / 16000
            val reducedX = Array(x.size) { FloatArray(0) }

            for (i in x.indices) {
                val current = x[i]
                val newArr = FloatArray((current.size + step - 1) / step)

                var index = 0
                var j = 0
                while (j < current.size) {
                    newArr[index++] = current[j]
                    j += step
                }

                reducedX[i] = newArr
            }

            x = reducedX
            sr = 16000
        }

        // Validate sample rate
        if (!SAMPLE_RATES.contains(sr)) {
            throw IllegalArgumentException(
                "Only supports sample rates $SAMPLE_RATES (or multiples of 16000)"
            )
        }

        // Check if audio chunk is too short
        if (sr.toFloat() / x[0].size > 31.25f) {
            throw IllegalArgumentException("Input audio is too short")
        }

        return ValidationResult(x, sr)
    }

    /**
     * Creates and returns a map of input tensors for the given audio data, Sample Rate and Frame Size.
     * The audio data is converted to a float array and wrapped in an OnnxTensor with the
     * corresponding tensor shape. The sample rate, hidden state (H), and cell state (C) tensors
     * are also created and added to the map.
     *
     * @param audioData audio data to analyze.
     * @throws OrtException if there was an error in creating the tensors or getting the OrtEnvironment.
     * @return map of input tensors as a TensorMap<String, OnnxTensor>.
     */
    private fun call(xInput: Array<FloatArray>, srInput: Int): FloatArray {
        val result = validateInput(xInput, srInput)
        val x = result.x
        val sr = result.sr

        val batchSize = x.size
        val numSamples = if (sr == 16000) 512 else 256
        val contextSize = if (sr == 16000) 64 else 32

        // Reset state if sample rate or batch changes
        if (lastSr != 0 && lastSr != sr) {
            resetStates(batchSize)
        } else if (lastBatchSize != 0 && lastBatchSize != batchSize) {
            resetStates(batchSize)
        } else if (lastBatchSize == 0) {
            lastBatchSize = batchSize
        }

        if (context.isEmpty()) {
            context = Array(batchSize) { FloatArray(contextSize) }
        }
        // Combine context + new chunk
        val xWithContext = Array(batchSize) { FloatArray(contextSize + numSamples) }
        for (i in 0 until batchSize) {
            // Copy context
            System.arraycopy(context[i], 0, xWithContext[i], 0, contextSize)
            // Copy input
            System.arraycopy(x[i], 0, xWithContext[i], contextSize, numSamples)
        }
        var inputTensor: OnnxTensor? = null
        var stateTensor: OnnxTensor? = null
        var srTensor: OnnxTensor? = null
        var ortOutputs: OrtSession.Result? = null

        try {
            inputTensor = OnnxTensor.createTensor(env, xWithContext)
            stateTensor = OnnxTensor.createTensor(env, state)
            srTensor = OnnxTensor.createTensor(env, longArrayOf(sr.toLong()))

            val inputs = hashMapOf(
                "input" to inputTensor, "sr" to srTensor, "state" to stateTensor
            )

            ortOutputs = session.run(inputs)

            val output = ortOutputs[0].value as Array<FloatArray>
            state = ortOutputs[1].value as Array<Array<FloatArray>>

            // Save last context
            for (i in 0 until batchSize) {
                val row = xWithContext[i]
                System.arraycopy(row, row.size - contextSize, context[i], 0, contextSize)
            }

            lastSr = sr
            lastBatchSize = batchSize

            return output[0]

        } finally {
            inputTensor?.close()
            stateTensor?.close()
            srTensor?.close()
            ortOutputs?.close()
        }
    }

    /**
     * Retrieves the model data as a byte array from silero_vad.onnx.
     *
     * @param context android context.
     * @return model data as a ByteArray.
     */
    private fun getModel(context: Context): ByteArray {
        return context.assets.open("silero_vad.onnx").use { it.readBytes() }
    }

    /**
     * Calculates and returns the threshold value based on the value of detection mode.
     * The threshold value represents the confidence level required for VAD to make proper decision.
     * ex. Mode.VERY_AGGRESSIVE requiring a very high prediction accuracy from the model.
     *
     * @return threshold Float value.
     */
    private fun threshold(): Float = when (mode) {
        Mode.NORMAL -> 0.5f
        Mode.AGGRESSIVE -> 0.8f
        Mode.VERY_AGGRESSIVE -> 0.95f
        else -> 0f
    }

    /**
     * Set, retrieve and validate sample rate for Vad Model.
     *
     * Valid Sample Rates:
     *
     *      8000Hz,
     *      16000Hz
     *
     * @param sampleRate is required for processing audio input.
     * @throws IllegalArgumentException if there was invalid sample rate.
     */
    var sampleRate: SampleRate = sampleRate
        set(sampleRate) {
            require(supportedParameters.containsKey(sampleRate)) {
                "VAD doesn't support Sample Rate:${sampleRate}!"
            }
            field = sampleRate
        }

    /**
     * Set, retrieve and validate frame size for Vad Model.
     *
     * Valid Frame Sizes (per sample rate):
     *
     *      For 8000Hz: 256, 512, 768
     *      For 16000Hz: 512, 1024, 1536
     *
     * @param frameSize is required for processing audio input.
     * @throws IllegalArgumentException if there was invalid frame size.
     */
    var frameSize: FrameSize = frameSize
        set(frameSize) {
            require(supportedParameters[sampleRate]?.contains(frameSize) ?: false) {
                "VAD doesn't support Sample rate:${sampleRate} and Frame Size:${frameSize}!"
            }
            field = frameSize
        }

    /**
     * Set and retrieve detection mode for Vad model.
     *
     * Mode:
     *
     *    NORMAL,
     *    LOW_BITRATE,
     *    AGGRESSIVE,
     *    VERY_AGGRESSIVE
     *
     * @param mode is required for the VAD model.
     */
    var mode: Mode = mode
        set(mode) {
            field = mode
        }

    /**
     * Set, retrieve and validate speechDurationMs for Vad Model.
     * The value of this parameter will define the necessary and sufficient duration of positive
     * results to recognize result as speech. This parameter is optional.
     *
     * Permitted range (0ms >= speechDurationMs <= 300000ms).
     *
     * Parameters used for {@link VadSilero.isSpeech}.
     *
     * @param speechDurationMs speech duration ms.
     * @throws IllegalArgumentException if out of permitted range.
     */
    var speechDurationMs: Int = speechDurationMs
        set(speechDurationMs) {
            require(speechDurationMs in 0..300000) {
                "The parameter 'speechDurationMs' should 0ms >= speechDurationMs <= 300000ms!"
            }

            field = speechDurationMs
            maxSpeechFramesCount = getFramesCount(sampleRate.value, frameSize.value, speechDurationMs)
        }

    /**
     * Set, retrieve and validate silenceDurationMs for Vad Model.
     * The value of this parameter will define the necessary and sufficient duration of
     * negative results to recognize it as silence. This parameter is optional.
     *
     * Permitted range (0ms >= silenceDurationMs <= 300000ms).
     *
     * Parameters used in {@link VadSilero.isSpeech}.
     *
     * @param silenceDurationMs silence duration ms.
     * @throws IllegalArgumentException if out of permitted range.
     */
    var silenceDurationMs: Int = silenceDurationMs
        set(silenceDurationMs) {
            require(silenceDurationMs in 0..300000) {
                "The parameter 'silenceDurationMs' should be 0ms >= silenceDurationMs <= 300000ms!"
            }

            field = silenceDurationMs
            maxSilenceFramesCount = getFramesCount(sampleRate.value, frameSize.value, silenceDurationMs)
        }

    /**
     * Closes the ONNX Session and releases all associated resources.
     * This method should be called when the VAD is no longer needed to free up system resources.
     */
    override fun close() {
        checkState()
        isInitiated = false

        session.close()
        env.close()
        reset()
    }

    /**
     * Check if VAD session already closed.
     *
     * @throws IllegalArgumentException if session already closed.
     */
    private fun checkState() {
        require(isInitiated) { "You can't use Vad after closing session!" }
    }

    /**
     * Constants representing the input tensor names used during model prediction.
     */
    private object InputTensors {
        const val INPUT = "input"
        const val SR = "sr"
        const val STATE = "state"
    }

    /**
     * Constants representing the output tensor names used when the model returns a result.
     */
    private object OutputTensors {
        const val OUTPUT = 0
        const val HN = 1
        const val CN = 2
    }

    /**
     * Initializes the ONNIX Runtime by creating a session with the provided
     * model file and session options.
     *
     * @param context is required for accessing the model file.
     * @throws IllegalArgumentException if invalid parameters have been set for the model.
     * @throws OrtException if the model failed to parse, wasn't compatible or caused an error.
     */
    init {
        this.sampleRate = sampleRate
        this.frameSize = frameSize
        this.mode = mode
        this.silenceDurationMs = silenceDurationMs
        this.speechDurationMs = speechDurationMs

        val sessionOptions = SessionOptions().apply {
            setIntraOpNumThreads(1)
            setInterOpNumThreads(1)
            setOptimizationLevel(SessionOptions.OptLevel.ALL_OPT)
        }

        this.env = OrtEnvironment.getEnvironment()
        this.session = env.createSession(getModel(context), sessionOptions)
        resetStates()
        this.isInitiated = true
    }
}