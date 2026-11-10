package jp.oif.tsunaminavi.benchmark

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import jp.oif.tsunaminavi.offline.KeywordIntent

/** Isolated manual benchmarking screen; results never trigger evacuation actions. */
class IntentBenchmarkActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var input by remember { mutableStateOf("") }
                var modelUri by remember { mutableStateOf<Uri?>(null) }
                var vocabUri by remember { mutableStateOf<Uri?>(null) }
                var gemmaUri by remember { mutableStateOf<Uri?>(null) }
                var result by remember { mutableStateOf("") }
                var running by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()
                val chooseModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { modelUri = it }
                val chooseVocab = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vocabUri = it }
                val chooseGemma = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { gemmaUri = it }
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("意図分類の比較（開発用）", style = MaterialTheme.typography.headlineSmall)
                    Text("判定結果は避難操作に反映されません。")
                    Button(onClick = { chooseModel.launch(arrayOf("*/*")) }, enabled = !running) {
                        Text(if (modelUri == null) "BERT ONNXモデルを選択" else "BERTモデル選択済み")
                    }
                    Button(onClick = { chooseVocab.launch(arrayOf("*/*")) }, enabled = !running) {
                        Text(if (vocabUri == null) "BERT vocab.txt を選択" else "辞書選択済み")
                    }
                    Button(onClick = { chooseGemma.launch(arrayOf("*/*")) }, enabled = !running) {
                        Text(if (gemmaUri == null) "FunctionGemmaモデルを選択" else "FunctionGemmaモデル選択済み")
                    }
                    OutlinedTextField(value = input, onValueChange = { input = it }, label = { Text("比較する文章") }, modifier = Modifier.fillMaxWidth())
                    Button(enabled = !running && input.isNotBlank(), onClick = {
                        val text = input
                        val selectedModel = modelUri
                        val selectedVocab = vocabUri
                        val selectedGemma = gemmaUri
                        running = true
                        scope.launch {
                            result = withContext(Dispatchers.IO) {
                                val start = SystemClock.elapsedRealtimeNanos()
                                val rule = KeywordIntent.classify(text)
                                val ruleMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                                val lines = mutableListOf("ルール: $rule (%.2f ms)".format(ruleMs))
                                if (selectedModel != null && selectedVocab != null) {
                                    try {
                                        val model = copyForBenchmark(selectedModel, "bert-model.onnx")
                                        val vocab = copyForBenchmark(selectedVocab, "bert-vocab.txt")
                                        val loadStart = SystemClock.elapsedRealtimeNanos()
                                        BertOnnxClassifier(model, vocab).use { bert ->
                                            val loadMs = (SystemClock.elapsedRealtimeNanos() - loadStart) / 1_000_000.0
                                            bert.classify(text) // warm-up is excluded from the timed inference
                                            val inferStart = SystemClock.elapsedRealtimeNanos()
                                            val id = bert.classify(text)
                                            val inferMs = (SystemClock.elapsedRealtimeNanos() - inferStart) / 1_000_000.0
                                            lines.add("BERT: $id (判定 %.2f ms、初期化 %.2f ms)".format(inferMs, loadMs))
                                        }
                                    } catch (error: Exception) {
                                        lines.add("BERT エラー: ${error.message}")
                                    }
                                } else lines.add("BERT: モデルと辞書を選択してください")
                                if (selectedGemma != null) {
                                    try {
                                        val model = copyForBenchmark(selectedGemma, "functiongemma.litertlm")
                                        val loadStart = SystemClock.elapsedRealtimeNanos()
                                        FunctionGemmaClassifier(model, cacheDir).use { gemma ->
                                            val loadMs = (SystemClock.elapsedRealtimeNanos() - loadStart) / 1_000_000.0
                                            gemma.classify(text)
                                            val inferStart = SystemClock.elapsedRealtimeNanos()
                                            val id = gemma.classify(text)
                                            val inferMs = (SystemClock.elapsedRealtimeNanos() - inferStart) / 1_000_000.0
                                            lines.add("FunctionGemma: $id (判定 %.2f ms、初期化 %.2f ms)".format(inferMs, loadMs))
                                        }
                                    } catch (error: Exception) {
                                        lines.add("FunctionGemma エラー: ${error.message}")
                                    }
                                } else lines.add("FunctionGemma: モデルを選択してください")
                                lines.joinToString("\n")
                            }
                            running = false
                        }
                    }) { Text(if (running) "測定中…" else "同じ文章で測定") }
                    Text(result)
                }
            }
        }
    }

    private fun copyForBenchmark(uri: Uri, name: String): File {
        val directory = File(cacheDir, "intent-benchmark").apply { mkdirs() }
        val temp = File.createTempFile("import-", ".tmp", directory)
        try {
            val source = requireNotNull(contentResolver.openInputStream(uri)) { "Cannot open selected file" }
            source.use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
            val target = File(directory, name)
            check(temp.renameTo(target)) { "Could not store selected file" }
            return target
        } finally {
            temp.delete()
        }
    }
}
