package jp.oif.tsunaminavi

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 端末の中の音声入力（通信が無くても、端末に言語のデータがあれば動く）。
 * ボタンを押している間だけ聞き、離したら終える（慌てて言葉の間が空いても途中で切れないように）。
 */
class Speech(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null
    private var lastPartial: String? = null
    private var done = false

    fun start(language: String, onPartial: (String) -> Unit, onFinal: (String?) -> Unit) {
        recognizer?.destroy()
        lastPartial = null
        done = false
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        val finish = { text: String? ->
            if (!done) {
                done = true
                onFinal(text ?: lastPartial)
            }
        }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }?.let {
                        lastPartial = it
                        onPartial(it)
                    }
            }
            override fun onResults(results: Bundle) = finish(results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
            override fun onError(error: Int) = finish(null)
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // 黙っても切らない（対応している認識エンジンだけ効く。効かなくても、離すまで途中の文字を使う）
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 10_000L)
        })
    }

    /** 指を離した時。聞いた所までで文字にする。 */
    fun stop() {
        recognizer?.stopListening()
    }

    fun destroy() {
        recognizer?.destroy()
        recognizer = null
    }
}
