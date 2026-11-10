package jp.oif.tsunaminavi

import android.content.Context
import java.io.File
import jp.oif.tsunaminavi.offline.InundationMap
import jp.oif.tsunaminavi.offline.Shelter
import jp.oif.tsunaminavi.offline.WalkGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val packFile = File(dir, "pack.json")
    private val audioDir = File(dir, "audio").apply { mkdirs() }
    private val graphFile = File(dir, "walk_graph.bin")

    suspend fun download(area: String, lang: String, expiresAt: String?) {
        val q = "/api/pack?area=$area&lang=$lang" + (expiresAt?.let { "&expires_at=$it" } ?: "")
        val tmp = File(dir, "pack.json.tmp")
        Api.download(q, tmp)
        val json = withContext(Dispatchers.IO) { Json.parseToJsonElement(tmp.readText()).jsonObject }
        for (a in json["answers"]!!.jsonArray) {
            val o = a.jsonObject
            val id = o["id"]!!.jsonPrimitive.content
            Api.download(o["audio_url"]!!.jsonPrimitive.content, File(audioDir, "$id.wav"))
        }
        json["graph_url"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.let { Api.download(it, graphFile) }
        withContext(Dispatchers.IO) { tmp.renameTo(packFile) }
    }

    suspend fun load(): Pack? = withContext(Dispatchers.IO) {
        if (!packFile.exists()) return@withContext null
        val json = Json.parseToJsonElement(packFile.readText()).jsonObject
        Pack(
            version = json["version"]!!.jsonPrimitive.content,
            lang = json["lang"]?.jsonPrimitive?.content ?: "en",
            shelters = json["shelters"]!!.jsonArray.map { parseShelter(it.jsonObject) },
            inundation = parseInundation(json["inundation"]!!.jsonObject),
            answers = json["answers"]!!.jsonArray.associate {
                it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject["text"]!!.jsonPrimitive.content
            },
            audioDir = audioDir,
            sizeBytes = packFile.length() + graphFile.length() + (audioDir.listFiles()?.sumOf { it.length() } ?: 0L),
            graph = if (graphFile.exists()) runCatching { WalkGraph.fromBytes(graphFile.readBytes()) }.getOrNull() else null,
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
