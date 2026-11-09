package jp.oif.tsunaminavi

import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.AudioTranscriptionConfig
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.SpeechConfig
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.Voice
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.liveGenerationConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * 通信がある時の声の会話（Gemini Live）。
 * Gemini は避難先を自分で決めない。頼みごとを関数で受け取り、アプリが公式データから選び直した結果だけを話す。
 * 頼みごとの一覧は、圏外の小さいモデル・ボタンと同じ（docs/decisions.md）。
 */
@OptIn(PublicPreviewAPI::class)
class LiveAssistant(
    private val handle: (name: String, mobility: String?) -> Map<String, String>,
    private val onTranscript: (user: String?, model: String?) -> Unit,
) {
    private var session: LiveSession? = null

    val active: Boolean get() = session != null

    /** @param situation 始める時点のアプリの状態（警報・避難先・残り時間など）。指示文の後ろに付ける。 */
    suspend fun start(situation: String) {
        if (session != null) return
        val model = Firebase.ai(backend = GenerativeBackend.agentPlatform(location = "us-central1")).liveModel(
            modelName = BuildConfig.LIVE_MODEL,
            generationConfig = liveGenerationConfig {
                responseModality = ResponseModality.AUDIO
                speechConfig = SpeechConfig(voice = Voice("Puck"))
                inputAudioTranscription = AudioTranscriptionConfig()
                outputAudioTranscription = AudioTranscriptionConfig()
            },
            systemInstruction = content { text(INSTRUCTION + "\n\nCurrent situation (from the app, official data):
" + situation) },
            tools = listOf(Tool.functionDeclarations(FUNCTIONS)),
        )
        session = model.connect().also { it.startAudioConversation(::onCall, { user, model -> onTranscript(user?.text, model?.text) }) }
    }

    suspend fun stop() {
        val s = session ?: return
        session = null
        runCatching { s.stopAudioConversation() }
        runCatching { s.close() }
    }

    private fun onCall(call: FunctionCallPart): FunctionResponsePart {
        val mobility = call.args["mobility"]?.jsonPrimitive?.content
        val result = runCatching { handle(call.name, mobility) }
            .getOrElse { mapOf("error" to (it.message ?: "failed")) }
        android.util.Log.i("TsunamiNavi", "Live call ${call.name}(mobility=$mobility) -> $result")
        return FunctionResponsePart(call.name, JsonObject(result.mapValues { JsonPrimitive(it.value) }))
    }

    companion object {
        private const val INSTRUCTION = """You are a tsunami evacuation guide for a traveler in the Osaka Bay area. The current situation is given at the end. Do not say a warning is in effect unless the situation says so.
Rules:
- Speak in the user's language. Keep every reply under 3 short sentences, in the imperative.
- Never name or invent an evacuation place, floor, distance or time yourself. Use only values returned by the functions.
- At the start, tell the user where to go now, using the current situation. If the user asks again later, call get_guidance for the latest values.
- If the situation says it is a training telegram, say once that this is a drill.
- When the user says they cannot walk fast, use a wheelchair, the road is blocked, or asks for another place, ALWAYS call the matching function before you answer, then read the new guidance.
- If the function returns changed=false, say the same place is still the best choice and give the new walking minutes.
- For lost family, injury, when to return, or floors, call the function and say its "answer".
- Always remind: do not use a car, stay away from rivers, do not go back until the warning is lifted."""

        private fun fn(name: String, description: String, params: Map<String, Schema> = emptyMap()) =
            FunctionDeclaration(name, description, params)

        private val FUNCTIONS = listOf(
            fn("get_guidance", "Get the current evacuation target, floor, walking minutes and minutes left. Call first."),
            fn(
                "reselect", "Choose another evacuation site from official data.",
                mapOf("mobility" to Schema.enumeration(listOf("normal", "slow", "wheelchair"), "normal: just another place, slow: cannot walk fast (child, elderly, injury), wheelchair: wheelchair or stroller")),
            ),
            fn("road_blocked", "The road is blocked or flooded. Choose another site."),
            fn("ask_floor", "How high to go up."),
            fn("lost_family", "The user lost family or friends."),
            fn("injured", "The user is injured or cannot move."),
            fn("when_to_return", "When the user can go back."),
        )
    }
}
