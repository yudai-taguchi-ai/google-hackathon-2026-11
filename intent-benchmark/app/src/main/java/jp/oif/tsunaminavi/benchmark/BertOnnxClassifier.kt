package jp.oif.tsunaminavi.benchmark

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.LongBuffer
import jp.oif.tsunaminavi.offline.BertTokenizer

/** Debug-only classifier for the exported ten-class multilingual BERT model. */
class BertOnnxClassifier(model: File, vocabulary: File) : Closeable {
    private val environment = OrtEnvironment.getEnvironment()
    private val tokenizer = BertTokenizer(vocabulary.readLines(Charsets.UTF_8))
    private val session: OrtSession = OrtSession.SessionOptions().use { options ->
        environment.createSession(model.absolutePath, options)
    }

    fun classify(text: String): String {
        val tokens = tokenizer.encode(text)
        val shape = longArrayOf(1, tokens.size.toLong())
        val attention = LongArray(tokens.size) { 1L }
        val segments = LongArray(tokens.size)
        OnnxTensor.createTensor(environment, LongBuffer.wrap(tokens), shape).use { inputIds ->
            OnnxTensor.createTensor(environment, LongBuffer.wrap(attention), shape).use { mask ->
                OnnxTensor.createTensor(environment, LongBuffer.wrap(segments), shape).use { types ->
                    val inputs = mapOf("input_ids" to inputIds, "attention_mask" to mask, "token_type_ids" to types)
                        .filterKeys { it in session.inputNames }
                    session.run(inputs).use { result ->
                        @Suppress("UNCHECKED_CAST")
                        val logits = (result[0].value as Array<FloatArray>)[0]
                        require(logits.size == LABELS.size) { "Unexpected BERT output size: ${logits.size}" }
                        return LABELS[logits.indices.maxBy { logits[it] }]
                    }
                }
            }
        }
    }

    override fun close() = session.close()

    companion object {
        private val LABELS = listOf("reselect", "reselect_slow", "reselect_wheelchair", "ask_floor",
            "road_blocked", "lost_family", "injured", "when_to_return", "repeat", "other")
    }
}
