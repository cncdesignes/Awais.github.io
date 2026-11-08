package com.awais.assistant

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.compose.runtime.mutableStateOf
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

class AwaisApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Speaker.init(this)
    }
}

/** Settings stored in encrypted storage (API key is never inside the APK). */
object Prefs {
    private var sp: SharedPreferences? = null

    private fun p(c: Context): SharedPreferences {
        sp?.let { return it }
        val app = c.applicationContext
        val made = try {
            val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(
                app, "awais_secure", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            app.getSharedPreferences("awais_plain", Context.MODE_PRIVATE)
        }
        sp = made
        return made
    }

    private fun s(c: Context, k: String, d: String) = p(c).getString(k, d) ?: d
    private fun b(c: Context, k: String, d: Boolean) = p(c).getBoolean(k, d)

    fun apiKey(c: Context) = s(c, "api_key", "")
    fun setApiKey(c: Context, v: String) = p(c).edit().putString("api_key", v.trim()).apply()

    fun model(c: Context) = s(c, "model", "gemini-2.5-flash")
    fun setModel(c: Context, v: String) = p(c).edit().putString("model", v.trim()).apply()

    fun lang(c: Context) = s(c, "lang", "en-IN")
    fun setLang(c: Context, v: String) = p(c).edit().putString("lang", v).apply()

    fun voice(c: Context) = s(c, "voice", "")
    fun setVoice(c: Context, v: String) = p(c).edit().putString("voice", v).apply()

    fun deep(c: Context) = b(c, "deep", true)
    fun setDeep(c: Context, v: Boolean) = p(c).edit().putBoolean("deep", v).apply()

    fun wake(c: Context) = b(c, "wake", false)
    fun setWake(c: Context, v: Boolean) = p(c).edit().putBoolean("wake", v).apply()

    fun micEnabled(c: Context) = b(c, "mic", true)
    fun setMicEnabled(c: Context, v: Boolean) = p(c).edit().putBoolean("mic", v).apply()

    fun wakeWords(c: Context) = s(c, "wake_words", "awais,a wise,away is,a ways,awes")
    fun setWakeWords(c: Context, v: String) = p(c).edit().putString("wake_words", v).apply()
}

/** Android Text-to-Speech wrapper with voice selection. */
object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var ctx: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private val callbacks = HashMap<String, () -> Unit>()
    private val waiting = ArrayList<Pair<String, () -> Unit>>()

    fun init(c: Context) {
        if (tts != null) return
        ctx = c.applicationContext
        tts = TextToSpeech(ctx) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) = finish(id)
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) = finish(id)
                })
                main.post {
                    val copy = waiting.toList()
                    waiting.clear()
                    copy.forEach { speak(it.first, it.second) }
                }
            }
        }
    }

    private fun finish(id: String?) {
        main.post { id?.let { callbacks.remove(it) }?.invoke() }
    }

    fun voices(): List<Voice> = try {
        tts?.voices?.filter { it.locale.language in setOf("en", "ur", "hi") }
            ?.sortedBy { it.name } ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    fun speak(text: String, onDone: () -> Unit = {}) {
        val t = tts
        val c = ctx
        if (t == null || c == null || !ready) {
            waiting.add(text to onDone)
            return
        }
        val arabic = text.any { it in '\u0600'..'\u06FF' }
        t.setPitch(if (Prefs.deep(c)) 0.8f else 1.0f)
        t.setSpeechRate(1.0f)
        if (arabic) {
            if (t.setLanguage(Locale("ur", "PK")) < TextToSpeech.LANG_AVAILABLE) {
                t.setLanguage(Locale("hi", "IN"))
            }
        } else {
            val v = voices().firstOrNull { it.name == Prefs.voice(c) }
            if (v != null) t.voice = v else t.setLanguage(Locale("en", "IN"))
        }
        val id = UUID.randomUUID().toString()
        callbacks[id] = onDone
        val r = t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (r == TextToSpeech.ERROR) {
            callbacks.remove(id)
            onDone()
        }
    }
}

/** Speech recognition + conversation loop. Must be used on the main thread. */
object VoiceEngine {
    /** idle / listening / thinking / speaking */
    val state = mutableStateOf("idle")
    private var sr: SpeechRecognizer? = null
    private val main = Handler(Looper.getMainLooper())

    fun listenOnce(ctx: Context, onResult: (String?) -> Unit) {
        val app = ctx.applicationContext
        if (!SpeechRecognizer.isRecognitionAvailable(app)) {
            onResult(null)
            return
        }
        sr?.destroy()
        val r = SpeechRecognizer.createSpeechRecognizer(app)
        sr = r
        var delivered = false
        fun deliver(t: String?) {
            if (delivered) return
            delivered = true
            main.post {
                r.destroy()
                if (sr === r) sr = null
            }
            onResult(t)
        }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { state.value = "listening" }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(e: Int) = deliver(null)
            override fun onResults(b: Bundle?) =
                deliver(b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Prefs.lang(app))
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        r.startListening(i)
    }

    /** Listen -> understand -> act -> speak (and listen again if a confirmation is needed). */
    fun converse(ctx: Context, scope: CoroutineScope, onFinished: () -> Unit) {
        listenOnce(ctx) { text ->
            if (text.isNullOrBlank()) {
                state.value = "idle"
                onFinished()
                return@listenOnce
            }
            state.value = "thinking"
            scope.launch {
                val reply = Assistant.handle(ctx, text)
                state.value = "speaking"
                Speaker.speak(reply.text) {
                    if (reply.followUp) {
                        converse(ctx, scope, onFinished)
                    } else {
                        state.value = "idle"
                        onFinished()
                    }
                }
            }
        }
    }
}
