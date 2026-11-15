package ai.roadmarket.app

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale

/** 앱 목소리. 문장을 차례로 말해요. 말할 때마다 기록을 남겨요. */
object Voice {
    private val h = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var speaking = false
    private val queue = ArrayDeque<String>()

    fun init(c: Context) {
        try {
            val t = TextToSpeech(c, Mode.ON_DEVICE)
            tts = t
            t.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    t.addEventListener(object : EventListener {
                        override fun onStart() {}
                        override fun onReady() {}
                        override fun onDone() { h.post { speaking = false; next() } }
                        override fun onError(e: ErrorType) { h.post { speaking = false; next() } }
                        override fun onStop() { h.post { speaking = false; next() } }
                        override fun onUpdatedRms(d: Double) {}
                    })
                    t.setLocale(Locale.KOREA)
                    h.post { ready = true; next() }
                }
                override fun onFailure(e: ErrorType) {}
            })
        } catch (e: Throwable) {
            tts = null
        }
    }

    fun say(sentence: String) {
        Log.i("ROADMARKET_SAY", sentence)
        queue.addLast(sentence)
        next()
    }

    private fun next() {
        if (!ready || speaking) return
        val s = queue.removeFirstOrNull() ?: return
        speaking = true
        try { tts?.speak(s, true) } catch (e: Throwable) { speaking = false }
    }
}
