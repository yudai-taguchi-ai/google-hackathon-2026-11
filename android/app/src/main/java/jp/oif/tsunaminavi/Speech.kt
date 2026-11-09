package jp.oif.tsunaminavi

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 端末の中の音声入力（通信が無くても、端末に言語のデータがあれば動く）。
 * 押している間は聞き続ける: 音声認識が途中で「終わり」やエラーを返しても、指を離すまで聞き直して文をつなげる。
 * 聞き直しても直らないエラー（言語のデータが無い・権限が無い など）の時は、理由を返して止める。
 */
class Speech(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var holding = false
    private var finished = true
    private var language = "en-US"
    private val pieces = mutableListOf<String>()
    private var partial: String? = null
    private var onPartial: (String) -> Unit = {}
    private var onFinal: (String?, String?) -> Unit = { _, _ -> }
    private var restarts = 0
    private var preferOffline = true

    /** 指で押した時。onFinal(聞いた文, エラーの説明) は、指を離した後か、直らないエラーの時に 1 回だけ呼ぶ。 */
    fun start(language: String, offline: Boolean, onPartial: (String) -> Unit, onFinal: (String?, String?) -> Unit) {
        this.language = language
        this.preferOffline = offline
        this.onPartial = onPartial
        this.onFinal = onFinal
        main.removeCallbacksAndMessages(null)
        pieces.clear()
        partial = null
        restarts = 0
        holding = true
        listenOnce()
    }

    /** 指を離した時。聞いている途中なら、聞いた所までで終える。 */
    fun stop() {
        if (!holding) return
        holding = false
        val r = recognizer
        if (r != null) {
            r.stopListening()
            // 結果が返ってこない機種のために、少し待って締める
            main.postDelayed({ finishNow(null) }, 1_500)
        } else {
            finishNow(null)
        }
    }

    fun destroy() {
        holding = false
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
    }

    private fun listenOnce() {
        finished = false
        recognizer?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }?.let {
                        partial = it
                        onPartial(joined(it))
                    }
            }

            override fun onResults(results: Bundle) {
                val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: partial
                text?.takeIf { it.isNotBlank() }?.let { pieces += it }
                partial = null
                if (holding) restart() else finishNow(null)
            }

            override fun onError(error: Int) {
                partial?.takeIf { it.isNotBlank() }?.let { pieces += it }
                partial = null
                val fatal = error in FATAL
                when {
                    holding && !fatal && restarts < MAX_RESTARTS -> restart()
                    fatal -> {
                        holding = false
                        finishNow("Speech error $error: ${describe(error)}")
                    }
                    else -> finishNow("Speech error $error: ${describe(error)}")
                }
            }

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
            // 通信がある時は通信を使う認識（端末に言語データが無くても動く）。圏外の時だけ端末の中で認識する
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        })
    }

    private fun restart() {
        restarts++
        main.post { if (holding) listenOnce() else finishNow(null) }
    }

    private fun joined(extra: String? = null) = (pieces + listOfNotNull(extra)).joinToString(" ").trim()

    private fun finishNow(error: String?) {
        if (finished) return
        finished = true
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        val text = joined(partial).ifBlank { null }
        onFinal(text, if (text == null) error else null)
    }

    /**
     * 圏外用の音声データ（端末の中の音声認識の言語データ）を、通信がある時に先にダウンロードしておく（Android 13 以降）。
     * 結果は onStatus に "ready" / "downloading" / "not available" などで返す。
     */
    fun prepareOffline(language: String, onStatus: (String) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < 33) {
            onStatus("not supported on this Android version")
            return
        }
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            onStatus("on-device recognition not available")
            return
        }
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        }
        r.checkRecognitionSupport(intent, context.mainExecutor, object : android.speech.RecognitionSupportCallback {
            override fun onSupportResult(support: android.speech.RecognitionSupport) {
                when {
                    language in support.installedOnDeviceLanguages -> onStatus("ready")
                    language in support.pendingOnDeviceLanguages -> onStatus("downloading")
                    language in support.supportedOnDeviceLanguages -> {
                        r.triggerModelDownload(intent)
                        onStatus("downloading")
                    }
                    else -> onStatus("not available for $language")
                }
                r.destroy()
            }

            override fun onError(error: Int) {
                onStatus("cannot check (error $error)")
                r.destroy()
            }
        })
    }

    companion object {
        private const val MAX_RESTARTS = 20
        private val FATAL = setOf(
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT,
        )

        fun describe(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
            SpeechRecognizer.ERROR_NETWORK -> "network (offline speech data may be missing)"
            SpeechRecognizer.ERROR_AUDIO -> "audio recording"
            SpeechRecognizer.ERROR_SERVER -> "server"
            SpeechRecognizer.ERROR_CLIENT -> "client"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no speech heard"
            SpeechRecognizer.ERROR_NO_MATCH -> "no match"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer busy"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "microphone permission"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "too many requests"
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "server disconnected"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "language not supported"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "offline English speech data is not downloaded. Use the buttons, or save data on Wi-Fi / 圏外用の英語の音声データがありません"
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "cannot check language support"
            else -> "unknown"
        }
    }
}
