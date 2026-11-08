package com.awais.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/** Unpacks the offline Vosk model (bundled as assets/model.zip) on first run. */
object ModelLoader {
    fun load(c: Context): Model {
        val root = File(c.filesDir, "vosk")
        val done = File(root, ".done")
        if (!done.exists()) {
            root.deleteRecursively()
            root.mkdirs()
            c.assets.open("model.zip").use { ins ->
                ZipInputStream(ins.buffered()).use { zin ->
                    var e = zin.nextEntry
                    while (e != null) {
                        val out = File(root, e.name)
                        if (!out.canonicalPath.startsWith(root.canonicalPath)) throw SecurityException("bad zip")
                        if (e.isDirectory) out.mkdirs() else {
                            out.parentFile?.mkdirs()
                            FileOutputStream(out).use { zin.copyTo(it) }
                        }
                        e = zin.nextEntry
                    }
                }
            }
            done.createNewFile()
        }
        val dir = root.listFiles { f -> f.isDirectory }?.firstOrNull()
            ?: throw IllegalStateException("Wake-word model missing")
        return Model(dir.absolutePath)
    }
}

/**
 * Foreground service (microphone type). Listens offline for the wake word with Vosk,
 * then hands the mic to Android SpeechRecognizer for the actual command.
 */
class WakeWordService : Service() {
    companion object {
        const val ACTION_STOP = "awais.STOP"
        const val ACTION_MANUAL = "awais.MANUAL"
        private const val CH = "awais_listen"
        private const val NID = 1
        @Volatile var running = false

        fun start(c: Context) {
            ContextCompat.startForegroundService(c, Intent(c, WakeWordService::class.java))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var model: Model? = null
    @Volatile private var wakeActive = false
    @Volatile private var loopThread: Thread? = null
    private var inSession = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.setWake(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        try {
            ServiceCompat.startForeground(
                this, NID, notif("AWAIS sun raha hai. \"Awais\" kahein."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } catch (e: Exception) {
            Log.e("AWAIS", "startForeground failed", e)
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        Speaker.init(applicationContext)
        if (intent?.action == ACTION_MANUAL) triggerSession() else startWakeLoop()
        return START_STICKY
    }

    private fun startWakeLoop() {
        if (!Prefs.micEnabled(this)) { stopSelf(); return }
        if (loopThread?.isAlive == true || inSession) return
        wakeActive = true
        loopThread = Thread { wakeLoop() }.apply { name = "awais-wake"; start() }
    }

    private fun stopWakeLoop() {
        wakeActive = false
        try { loopThread?.join(800) } catch (_: InterruptedException) {}
        loopThread = null
    }

    private fun wakeLoop() {
        var ar: AudioRecord? = null
        var rec: Recognizer? = null
        var detected = false
        try {
            val m = model ?: ModelLoader.load(applicationContext).also { model = it }
            val words = Prefs.wakeWords(this).split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            val grammar = JSONArray(words + "[unk]").toString()
            rec = Recognizer(m, 16000f, grammar)
            val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val a = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 4096) * 2
            )
            ar = a
            if (a.state != AudioRecord.STATE_INITIALIZED) {
                update("Mic open nahi ho saka")
                return
            }
            a.startRecording()
            val buf = ShortArray(2048)
            while (wakeActive) {
                val n = a.read(buf, 0, buf.size)
                if (n <= 0) continue
                val text = if (rec.acceptWaveForm(buf, n)) JSONObject(rec.result).optString("text")
                else JSONObject(rec.partialResult).optString("partial")
                if (text.isNotBlank() && words.any { text.contains(it) }) {
                    detected = true
                    break
                }
            }
        } catch (e: Throwable) {
            Log.e("AWAIS", "wake loop error", e)
            update("Wake-word error: ${e.message}")
        } finally {
            runCatching { ar?.stop() }
            runCatching { ar?.release() }
            runCatching { rec?.close() }
            wakeActive = false
        }
        if (detected) main.post { triggerSession() }
    }

    private fun triggerSession() {
        if (inSession) return
        inSession = true
        stopWakeLoop()
        update("Sun raha hoon...")
        Speaker.speak("Ji Awais?") {
            VoiceEngine.converse(applicationContext, scope) { endSession() }
        }
    }

    private fun endSession() {
        inSession = false
        VoiceEngine.state.value = "idle"
        if (running) {
            update("AWAIS sun raha hai. \"Awais\" kahein.")
            main.postDelayed({ startWakeLoop() }, 400)
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "AWAIS listening", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notif(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("AWAIS")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun update(text: String) {
        getSystemService(NotificationManager::class.java).notify(NID, notif(text))
    }

    override fun onDestroy() {
        running = false
        wakeActive = false
        scope.cancel()
        runCatching { model?.close() }
        VoiceEngine.state.value = "idle"
        super.onDestroy()
    }
}
