package jp.oif.tsunaminavi.benchmark

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.SessionConfig
import java.io.Closeable
import java.io.File
import jp.oif.tsunaminavi.offline.FunctionCallParser

/** Matches the production app's prompt, CPU backend and Session API. */
class FunctionGemmaClassifier(model: File, cacheDir: File, context: Context) : Closeable {
    private val prompt = context.assets.open("intent_prompt.txt").bufferedReader().use { it.readText() }.removePrefix("<bos>")
    private val engine = Engine(EngineConfig(modelPath = model.absolutePath, backend = Backend.CPU(), cacheDir = cacheDir.absolutePath))
    init { engine.initialize() }
    fun classify(text: String): String {
        val response = engine.createSession(SessionConfig(samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0))).use {
            it.generateContent(listOf(InputData.Text(prompt.replace("{text}", text.trim()))))
        }
        return FunctionCallParser.toId(response) ?: "解析失敗: $response"
    }
    override fun close() = engine.close()
}
