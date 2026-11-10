package jp.oif.tsunaminavi

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.InputData
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.SessionConfig
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 端末の中の聞き分けモデル（追加学習した FunctionGemma・LiteRT-LM）。
 * 置き場所: <外部の files>/intent.litertlm と、学習と同じ形の入力の型 intent_prompt.txt（{text} の所に聞いた文が入る）。
 * どちらか無い・読めない・時間切れの時は null を返し、呼ぶ側はキーワード方式に戻る。
 */
class OnDeviceIntent(private val context: Context) {
    private var engine: Engine? = null
    private var prompt: String? = null
    private val lock = Mutex()
    var status: String = "not loaded"
        private set

    suspend fun load() = withContext(Dispatchers.Default) {
        lock.withLock {
            if (engine != null) return@withLock
            val dir = context.getExternalFilesDir(null) ?: return@withLock
            val model = File(dir, "intent.litertlm")
            val template = File(dir, "intent_prompt.txt")
            if (!model.exists() || !template.exists()) {
                status = "model not on device"
                return@withLock
            }
            runCatching {
                val started = System.currentTimeMillis()
                val e = Engine(EngineConfig(modelPath = model.path, backend = Backend.CPU(), cacheDir = context.cacheDir.path))
                e.initialize()
                engine = e
                prompt = template.readText().removePrefix("<bos>")
                status = "ready (loaded in ${System.currentTimeMillis() - started} ms)"
            }.onFailure {
                status = "load failed: ${it.message}"
            }
            Log.i("TsunamiNavi", "on-device intent model: $status")
        }
    }

    val ready: Boolean get() = engine != null

    /** 聞いた文 → 頼みごとの id。使えない時は null。 */
    suspend fun classify(text: String): String? = withContext(Dispatchers.Default) {
        val e = engine ?: return@withContext null
        val p = prompt ?: return@withContext null
        withTimeoutOrNull(TIMEOUT_MS) {
            lock.withLock {
                runCatching {
                    val started = System.currentTimeMillis()
                    val out = e.createSession(SessionConfig(samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0))).use { s ->
                        s.generateContent(listOf(InputData.Text(p.replace("{text}", text))))
                    }
                    val id = toId(out)
                    Log.i("TsunamiNavi", "on-device '$text' -> $id in ${System.currentTimeMillis() - started} ms (raw: ${out.take(120)})")
                    id
                }.getOrElse {
                    Log.w("TsunamiNavi", "on-device classify failed: ${it.message}")
                    null
                }
            }
        }
    }

    companion object {
        private const val TIMEOUT_MS = 4000L
        private val CALL = Regex("""call:(\w+)\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL)
        private val ARG = Regex("""([^:,{}]+?):<escape>(.*?)<escape>""", RegexOption.DOT_MATCHES_ALL)
        private val NAMES = setOf("ask_floor", "road_blocked", "lost_family", "injured", "when_to_return", "repeat", "other")

        /** 学習の関数名・引数 → アプリの id（docs/intent-model.md の対応表）。 */
        fun toId(output: String): String? {
            val m = CALL.find(output) ?: return null
            val name = m.groupValues[1]
            val args = ARG.findAll(m.groupValues[2]).associate { it.groupValues[1].trim() to it.groupValues[2].trim() }
            return when (name) {
                "reselect" -> when (args["mobility"]) {
                    "slow" -> "reselect_slow"
                    "wheelchair" -> "reselect_wheelchair"
                    else -> "reselect"
                }
                in NAMES -> name
                else -> null
            }
        }
    }
}
