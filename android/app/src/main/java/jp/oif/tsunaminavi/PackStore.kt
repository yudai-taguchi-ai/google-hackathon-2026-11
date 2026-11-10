package jp.oif.tsunaminavi

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.UUID
import jp.oif.tsunaminavi.offline.InundationMap
import jp.oif.tsunaminavi.offline.Shelter
import jp.oif.tsunaminavi.offline.WalkGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 圏外でも使えるように端末に保存したパック。 */
class Pack(
    val version: String,
    /** 答えの文と音声の言語（en / ja / zh / ko）。 */
    val lang: String,
    val shelters: List<Shelter>,
    val inundation: InundationMap,
    val answers: Map<String, String>,
    val audioDir: File,
    val sizeBytes: Long,
    val graph: WalkGraph?,
) {
    fun audioFile(id: String): File = File(audioDir, "$id.wav")
}

/** パックを Cloud Run から取って保存し、保存したものを読み込む。 */
class PackStore(context: Context) {
    private val dir = File(context.filesDir, "pack").apply { mkdirs() }
    private val active = AtomicFile(File(dir, "active-generation"))
    private val pointerLock = Any()
    private val downloadLock = Mutex()

    suspend fun download(area: String, lang: String, expiresAt: String?) = downloadLock.withLock {
        val q = "/api/pack?area=$area&lang=$lang" + (expiresAt?.let { "&expires_at=$it" } ?: "")
        val generation = "generation-${UUID.randomUUID()}"
        val staged = File(dir, generation)
        withContext(Dispatchers.IO) { check(staged.mkdir()) { "Could not create pack staging directory" } }
        var committed = false
        try {
            val packFile = File(staged, "pack.json")
            val audioDir = File(staged, "audio")
            withContext(Dispatchers.IO) { check(audioDir.mkdir()) }
            Api.download(q, packFile)
            val json = withContext(Dispatchers.IO) { Json.parseToJsonElement(packFile.readText()).jsonObject }
            for (a in json["answers"]!!.jsonArray) {
                val o = a.jsonObject
                val id = o["id"]!!.jsonPrimitive.content
                require(id.matches(Regex("[a-z_]+"))) { "Invalid answer id" }
                val audio = File(audioDir, "$id.wav")
                Api.download(o["audio_url"]!!.jsonPrimitive.content, audio)
                withContext(Dispatchers.IO) { check(audio.length() > 44) { "Empty audio: $id" } }
            }
            json["graph_url"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.let {
                Api.download(it, File(staged, "walk_graph.bin"))
            }
            withContext(Dispatchers.IO) {
                // Validate all data before changing which generation is active.
                val checked = readPack(staged, strictGraph = true)
                check(checked.shelters.isNotEmpty() && checked.answers.isNotEmpty()) { "Pack is empty" }
                synchronized(pointerLock) {
                    val out = active.startWrite()
                    try {
                        out.write(generation.toByteArray(Charsets.UTF_8))
                        active.finishWrite(out)
                    } catch (error: Throwable) {
                        active.failWrite(out)
                        throw error
                    }
                }
                committed = true
            }
        } finally {
            if (!committed) withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { staged.deleteRecursively() }
        }
    }

    suspend fun load(): Pack? = withContext(Dispatchers.IO) {
        val name = synchronized(pointerLock) {
            if (active.baseFile.exists() || File(dir, "active-generation.bak").exists()) active.openRead().bufferedReader().use { it.readText() } else null
        }
        if (name != null) require(name.matches(Regex("generation-[0-9a-f-]+"))) { "Invalid pack generation" }
        val selected = if (name == null) dir else File(dir, name)
        if (!File(selected, "pack.json").exists()) return@withContext null
        readPack(selected)
    }

    private fun readPack(selected: File, strictGraph: Boolean = false): Pack {
        val packFile = File(selected, "pack.json")
        val audioDir = File(selected, "audio")
        val graphFile = File(selected, "walk_graph.bin")
        val json = Json.parseToJsonElement(packFile.readText()).jsonObject
        return Pack(
            version = json["version"]!!.jsonPrimitive.content,
            lang = json["lang"]?.jsonPrimitive?.content ?: "en",
            shelters = json["shelters"]!!.jsonArray.map { parseShelter(it.jsonObject) },
            inundation = parseInundation(json["inundation"]!!.jsonObject),
            answers = json["answers"]!!.jsonArray.associate {
                it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject["text"]!!.jsonPrimitive.content
            },
            audioDir = audioDir,
            sizeBytes = packFile.length() + graphFile.length() + (audioDir.listFiles()?.sumOf { it.length() } ?: 0L),
            graph = if (!graphFile.exists()) null else if (strictGraph) WalkGraph.fromBytes(graphFile.readBytes()) else runCatching { WalkGraph.fromBytes(graphFile.readBytes()) }.getOrNull(),
        )
    }

    private fun parseShelter(o: JsonObject) = Shelter(
        id = o["id"]!!.jsonPrimitive.content,
        name = o["name"]!!.jsonPrimitive.content,
        address = o["address"]?.jsonPrimitive?.content ?: "",
        lat = o["lat"]!!.jsonPrimitive.double,
        lon = o["lon"]!!.jsonPrimitive.double,
        inundationDepth = o["inundation_depth"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content,
        floorMin = o["floor_min"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.int,
        openSpace = o["open_space"]?.jsonPrimitive?.boolean ?: false,
        graphNode = o["graph_node"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.int,
    )

    private fun parseInundation(fc: JsonObject): InundationMap =
        InundationMap.fromMultiPolygons(fc["features"]!!.jsonArray.associate { f ->
            val depth = f.jsonObject["properties"]!!.jsonObject["depth"]!!.jsonPrimitive.content
            val coords = f.jsonObject["geometry"]!!.jsonObject["coordinates"]!!.jsonArray
            depth to coords.map { poly ->
                poly.jsonArray.map { ring ->
                    ring.jsonArray.map { pt -> (pt as JsonArray).map { it.jsonPrimitive.double } }
                }
            }
        })
}
