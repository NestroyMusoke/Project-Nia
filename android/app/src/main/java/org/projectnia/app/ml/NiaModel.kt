package org.projectnia.app.ml

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

data class ModelOutput(
    val generalProbabilities: FloatArray,
    val embedding: FloatArray,
)

class NiaModel(context: Context) : Closeable {
    companion object {
        const val MODEL_ASSET = "project_nia_model3_mobile_fp16.tflite"
        const val CLASS_COUNT = 32
        const val EMBEDDING_SIZE = 384
    }

    private val interpreter = Interpreter(
        mapAsset(context, MODEL_ASSET),
        Interpreter.Options()
            .setNumThreads(4)
            // This model's runtime reshape is not supported by XNNPack on the
            // Galaxy A17. The standard CPU kernels preserve the frozen model
            // outputs and support its fixed 64 x 272 input tensor.
            .setUseXNNPACK(false),
    ).apply {
        resizeInput(0, intArrayOf(1, V3Preprocessor.SEQUENCE_LENGTH, V3Preprocessor.FEATURE_COUNT))
        allocateTensors()
    }

    private val probabilityOutputIndex: Int
    private val embeddingOutputIndex: Int

    init {
        val outputs = (0 until interpreter.outputTensorCount).associateWith {
            interpreter.getOutputTensor(it).shape().last()
        }
        probabilityOutputIndex = outputs.entries.singleOrNull { it.value == CLASS_COUNT }?.key
            ?: error("Model has no $CLASS_COUNT-value probability output: $outputs")
        embeddingOutputIndex = outputs.entries.singleOrNull { it.value == EMBEDDING_SIZE }?.key
            ?: error("Model has no $EMBEDDING_SIZE-value embedding output: $outputs")
    }

    @Synchronized
    fun infer(features: Array<FloatArray>): ModelOutput {
        require(features.size == V3Preprocessor.SEQUENCE_LENGTH)
        require(features.all { row -> row.size == V3Preprocessor.FEATURE_COUNT && row.all { it.isFinite() } })

        val input = directFloatBuffer(V3Preprocessor.SEQUENCE_LENGTH * V3Preprocessor.FEATURE_COUNT)
        features.forEach(input::put)
        input.rewind()

        val probabilityBuffer = directFloatBuffer(CLASS_COUNT)
        val embeddingBuffer = directFloatBuffer(EMBEDDING_SIZE)
        val outputs = mutableMapOf<Int, Any>(
            probabilityOutputIndex to probabilityBuffer,
            embeddingOutputIndex to embeddingBuffer,
        )
        interpreter.runForMultipleInputsOutputs(arrayOf(input), outputs)

        val probabilities = FloatArray(CLASS_COUNT)
        val embedding = FloatArray(EMBEDDING_SIZE)
        probabilityBuffer.rewind()
        embeddingBuffer.rewind()
        probabilityBuffer.get(probabilities)
        embeddingBuffer.get(embedding)
        return ModelOutput(probabilities, embedding)
    }

    override fun close() = interpreter.close()

    private fun mapAsset(context: Context, name: String): MappedByteBuffer {
        val descriptor = context.assets.openFd(name)
        return FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
            channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.declaredLength)
        }.also { descriptor.close() }
    }

    private fun directFloatBuffer(elementCount: Int) = ByteBuffer
        .allocateDirect(elementCount * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
}
