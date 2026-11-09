package jp.oif.tsunaminavi

import android.app.Application
import android.content.Context
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.appCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.initialize
import java.io.File

/** アプリの起動時に 1 回だけ動く。Firebase（Gemini Live 用）と App Check の準備をする。 */
class TsunamiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        firebaseReady = runCatching {
            Firebase.initialize(this) ?: return@runCatching false
            presetDebugSecret(this)
            Firebase.appCheck.installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
            true
        }.getOrDefault(false)
    }

    companion object {
        /** google-services.json を入れてビルドした時だけ true。false なら Gemini Live のボタンを出さない。 */
        var firebaseReady = false
            private set

        /**
         * App Check のデバッグ用の合言葉を、Firebase コンソールに登録したものに合わせる。
         * ログ（logcat）を見られないため、ビルド時に GitHub Secrets から入れた値を、デバッグの仕組みの保存先に先に書いておく。
         */
        private fun presetDebugSecret(context: Context) {
            val secret = BuildConfig.APP_CHECK_DEBUG_SECRET
            if (secret.isBlank()) return
            val key = FirebaseApp.getInstance().persistenceKey
            context.getSharedPreferences("com.google.firebase.appcheck.debug.store.$key", Context.MODE_PRIVATE)
                .edit().putString("com.google.firebase.appcheck.debug.DEBUG_SECRET", secret).apply()
        }

        /** 今使っているデバッグ用の合言葉（画面で確かめるため）。見つからなければ null。 */
        fun currentDebugSecret(context: Context): String? {
            val dir = File(context.applicationInfo.dataDir, "shared_prefs")
            val file = dir.listFiles()?.firstOrNull { it.name.contains("appcheck.debug") } ?: return null
            return Regex("DEBUG_SECRET\">([^<]+)<").find(file.readText())?.groupValues?.get(1)
        }
    }
}
