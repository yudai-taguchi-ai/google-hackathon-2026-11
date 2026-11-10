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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import jp.oif.tsunaminavi.offline.FunctionCallParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 端末の中の聞き分けモデル（追加学習した FunctionGemma・LiteRT-LM）。
 * モデルの置き場所: <外部の files>/intent.litertlm（286MB のため APK に入れず、adb push で置く）。
 * 入力の型 intent_prompt.txt はモデルの中の型と学習の関数一覧から作り、assets に同梱した（{text} の所に聞いた文が入る）。
 * 利用できない・読めない・時間切れの時は null を返す。
 */
class OnDeviceIntent(private val context: Context) {
    @Volatile private var engine: Engine? = null
    @Volatile private var prompt: String? = null
    private val lock = Mutex()
    private val running = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "functiongemma-inference").apply { isDaemon = true }
    }
    @Volatile var status: String = "not loaded"
        private set

    suspend fun load() = withContext(Dispatchers.Default) {
        lock.withLock {
            if (engine != null) return@withLock
            val dir = context.getExternalFilesDir(null) ?: return@withLock
            val model = File(dir, "intent.litertlm")
            if (!model.exists()) {
                status = "model not on device"
                return@withLock
            }
            var candidate: Engine? = null
            runCatching {
                val started = System.currentTimeMillis()
                val e = Engine(EngineConfig(modelPath = model.path, backend = Backend.CPU(), cacheDir = context.cacheDir.path))
                candidate = e
                e.initialize()
                val loadedPrompt = context.assets.open("intent_prompt.txt").bufferedReader().use { it.readText() }.removePrefix("<bos>")
                val loaded = System.currentTimeMillis() - started
                val warm = System.currentTimeMillis()
                check(toId(run(e, loadedPrompt, "The road is blocked.")) != null) { "warm-up returned an invalid call" }
                prompt = loadedPrompt
                engine = e
                candidate = null
                status = "ready (load ${loaded} ms · first answer ${System.currentTimeMillis() - warm} ms)"
            }.onFailure {
                candidate?.let { e -> runCatching { e.close() } }
                status = "load failed: ${it.message}"
            }
            Log.i("TsunamiNavi", "on-device intent model: $status")
        }
    }

    val ready: Boolean get() = engine != null

    /** 聞いた文 → 頼みごとの id。使えない時は null。 */
    suspend fun classify(text: String): String? {
        val e = engine ?: return null
        val p = prompt ?: return null
        // Native inference may ignore coroutine cancellation. Keep it on a dedicated
        // worker and keep the engine busy until that call actually exits.
        return withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                if (!running.compareAndSet(false, true)) {
                    continuation.resume(null)
                    return@suspendCancellableCoroutine
                }
                try {
                    worker.execute {
                        val id = try {
                            toId(run(e, p, text))
                        } catch (error: Exception) {
                            Log.w("TsunamiNavi", "on-device classify failed: ${error.message}")
                            null
                        } finally {
                            running.set(false)
                        }
                        if (continuation.isActive) continuation.resume(id)
                    }
                } catch (error: Exception) {
                    running.set(false)
                    Log.w("TsunamiNavi", "on-device worker failed: ${error.message}")
                    continuation.resume(null)
                }
            }
        }
    }

    private fun run(e: Engine, p: String, text: String): String =
        e.createSession(SessionConfig(samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0))).use { s ->
            s.generateContent(listOf(InputData.Text(p.replace("{text}", text.trim()))))
        }

    companion object {
        private const val TIMEOUT_MS = 8000L
        /** 学習の関数名・引数 → アプリの id（docs/intent-model.md の対応表）。 */
        fun toId(output: String): String? = FunctionCallParser.toId(output)
    }
}
