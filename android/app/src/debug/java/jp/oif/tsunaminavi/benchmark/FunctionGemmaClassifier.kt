package jp.oif.tsunaminavi.benchmark

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.Closeable
import java.io.File

/** Uses the same function declarations and prompt as the held-out evaluation. */
class FunctionGemmaClassifier(model: File, cacheDir: File) : Closeable {
    private val engine = Engine(EngineConfig(modelPath = model.absolutePath, backend = Backend.CPU(),
        maxNumTokens = 1024, cacheDir = cacheDir.absolutePath))

    init { engine.initialize() }

    fun classify(text: String): String {
        require(!text.contains("<start_") && !text.contains("<end_") && !text.contains("<bos>")) {
            "Input contains reserved model tokens"
        }
        val prompt = PREFIX + text.trim() + "<end_of_turn>\n<start_of_turn>model\n"
        val config = ConversationConfig(automaticToolCalling = false,
            chatTemplate = "{{ messages[-1]['content'][0]['text'] }}",
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0), maxOutputToken = 64)
        val response = engine.createConversation(config).use { it.sendMessage(prompt).toString() }
        val call = CALL.find(response) ?: return "解析失敗: $response"
        val name = call.groupValues[1]
        val args = call.groupValues[2]
        return when (name) {
            "reselect" -> when (MOBILITY.find(args)?.groupValues?.get(1)) {
                "normal" -> "reselect"
                "slow" -> "reselect_slow"
                "wheelchair" -> "reselect_wheelchair"
                else -> "解析失敗: $response"
            }
            in NAMES -> if (args.isBlank()) name else "解析失敗: $response"
            else -> "解析失敗: $response"
        }
    }

    override fun close() = engine.close()

    companion object {
        private val CALL = Regex("<start_function_call>call:(\\w+)\\{(.*?)\\}<end_function_call>", RegexOption.DOT_MATCHES_ALL)
        private val MOBILITY = Regex("^mobility:<escape>(normal|slow|wheelchair)<escape>$")
        private val NAMES = setOf("ask_floor", "road_blocked", "lost_family", "injured", "when_to_return", "repeat", "other")
        private const val PREFIX = "<bos><start_of_turn>developer\n" +
            "You are a model that can do function calling with the following functions. Choose exactly one function for the user's message." +
            "<start_function_declaration>declaration:reselect{description:<escape>Choose another evacuation site. Use slow when the traveler cannot walk fast; wheelchair for a wheelchair, stroller, or inability to use stairs.<escape>,parameters:{properties:{mobility:{description:<escape>Traveler mobility<escape>,enum:[<escape>normal<escape>,<escape>slow<escape>,<escape>wheelchair<escape>],type:<escape>STRING<escape>}},required:[<escape>mobility<escape>],type:<escape>OBJECT<escape>}}<end_function_declaration>" +
            "<start_function_declaration>declaration:ask_floor{description:<escape>Ask which floor to evacuate to.<escape>,parameters:{type:<escape>OBJECT<escape>}}<end_function_declaration>" +
            "<start_function_declaration>declaration:road_blocked{description:<escape>The route is blocked, flooded, or impassable.<escape>,parameters:{type:<escape>OBJECT<escape>}}<end_function_declaration>" +
            "<start_function_declaration>declaration:lost_family{description:<escape>The traveler is separated from family or friends.<escape>,parameters:{type:<escape>OBJECT<escape>}}<end_function_declaration>" +
            "<start_function_declaration>declaration:injured{description:<escape>The traveler is injured, sick, or cannot move.<escape>,parameters:{type:<escape>OBJECT<escape>}}<end_function_declaration>" +
            "<start_function_declaration>declaration:when_to_return{description:<escape>Ask when it is safe to return or retrieve belongings.<escape>,parameters:{type:<escape>OBJECT<escape>}}<end_function_declaration>" +
            "<start_function_declaration>declaration:repeat{description:<escape>Repeat or clarify the current guidance.<escape>,parameters:{type:<escape>OBJECT<escape>}}<end_function_declaration>" +
            "<start_function_declaration>declaration:other{description:<escape>Unclear, unrelated, or unsupported request.<escape>,parameters:{type:<escape>OBJECT<escape>}}<end_function_declaration>" +
            "<end_of_turn>\n<start_of_turn>user\n"
    }
}
