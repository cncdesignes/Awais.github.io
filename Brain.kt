package com.awais.assistant

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun hasPerm(c: Context, p: String) =
    ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

// ---------------------------------------------------------------- App launcher

object AppLauncher {
    data class AppInfo(val label: String, val pkg: String)

    private fun canon(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private val aliases: Map<String, String> = mapOf(
        "گوگل" to "google", "یوٹیوب" to "youtube", "واٹس ایپ" to "whatsapp", "واٹساپ" to "whatsapp",
        "فیس بک" to "facebook", "فیسبک" to "facebook", "انسٹاگرام" to "instagram", "ٹک ٹاک" to "tiktok",
        "گیلری" to "gallery", "کیمرہ" to "camera", "کیمرا" to "camera", "سیٹنگز" to "settings",
        "سیٹنگ" to "settings", "کروم" to "chrome", "insta" to "instagram", "fb" to "facebook",
        "setting" to "settings", "photos" to "gallery", "yt" to "youtube"
    ).mapKeys { canon(it.key) }

    private val synonyms = mapOf(
        "gallery" to listOf("gallery", "photos"),
        "camera" to listOf("camera"),
        "settings" to listOf("settings")
    )

    @Suppress("DEPRECATION")
    fun installed(c: Context): List<AppInfo> {
        val pm = c.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0).map {
            AppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName)
        }
    }

    fun find(c: Context, spoken: String): AppInfo? {
        var q = canon(spoken)
        if (q.isEmpty()) return null
        aliases[q]?.let { q = it }
        val queries = synonyms[q] ?: listOf(q)
        var best: AppInfo? = null
        var bestScore = 0
        for (a in installed(c)) {
            val l = canon(a.label)
            val pk = a.pkg.lowercase()
            for (qq in queries) {
                val base = when {
                    l == qq -> 100
                    l.startsWith(qq) -> 80
                    l.contains(qq) && qq.length >= 3 -> 60
                    qq.contains(l) && l.length >= 3 -> 40
                    qq.length >= 4 && pk.contains(qq) -> 30
                    else -> 0
                }
                if (base > 0) {
                    val score = base * 100 - minOf(l.length, 99)
                    if (score > bestScore) {
                        bestScore = score
                        best = a
                    }
                }
            }
        }
        return best
    }

    fun launch(c: Context, a: AppInfo): Boolean {
        val i = c.packageManager.getLaunchIntentForPackage(a.pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            c.startActivity(i); true
        } catch (e: Exception) {
            false
        }
    }
}

// ---------------------------------------------------------------- Phone controls

object PhoneControls {
    private fun go(c: Context, i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        c.startActivity(i)
    }

    fun torch(c: Context, on: Boolean): Boolean = try {
        val cm = c.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        if (id == null) false else {
            cm.setTorchMode(id, on); true
        }
    } catch (e: Exception) {
        false
    }

    fun battery(c: Context): Int =
        (c.getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
            .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    fun bluetooth(c: Context) = go(c, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    fun wifi(c: Context) = go(c, Intent(Settings.ACTION_WIFI_SETTINGS))
    fun contacts(c: Context) = go(c, Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI))

    /** Returns the new volume in percent. */
    fun volume(c: Context, delta: Int?, absolute: Int?): Int {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val v = if (absolute != null) absolute.coerceIn(0, 100) * max / 100
        else (am.getStreamVolume(AudioManager.STREAM_MUSIC) + (delta ?: 0) * maxOf(1, max / 5)).coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, v, AudioManager.FLAG_SHOW_UI)
        return v * 100 / max
    }

    fun alarm(c: Context, h: Int, m: Int) = go(
        c, Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "AWAIS")
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
    )

    fun call(c: Context, number: String) =
        go(c, Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number))))

    @Suppress("DEPRECATION")
    fun sms(c: Context, number: String, body: String) {
        val sm = if (Build.VERSION.SDK_INT >= 31) c.getSystemService(SmsManager::class.java)
        else SmsManager.getDefault()
        sm.sendMultipartTextMessage(number, null, sm.divideMessage(body), null, null)
    }

    fun navigate(c: Context, place: String) {
        try {
            go(c, Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(place)))
                .setPackage("com.google.android.apps.maps"))
        } catch (e: ActivityNotFoundException) {
            go(c, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place))))
        }
    }

    fun findContact(c: Context, q: String): Pair<String, String>? {
        c.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$q%"), null
        )?.use { cur ->
            if (cur.moveToFirst()) return cur.getString(0) to cur.getString(1)
        }
        return null
    }
}

// ---------------------------------------------------------------- Gemini (cloud brain)

object Gemini {
    private const val SYSTEM =
        "You are AWAIS, a friendly, intelligent personal voice assistant for a user named Awais. " +
            "Reply in the same language and script the user used (English, Urdu, or Roman Urdu). " +
            "Keep answers short and natural for speaking: 1 to 3 sentences. " +
            "No markdown, no lists, no emojis. Address him as Awais occasionally."

    suspend fun ask(c: Context, turns: List<Pair<String, String>>): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val key = Prefs.apiKey(c)
                require(key.isNotBlank()) { "API key missing" }
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/${Prefs.model(c)}:generateContent")
                val contents = JSONArray()
                turns.forEach { (role, text) ->
                    contents.put(
                        JSONObject().put("role", role)
                            .put("parts", JSONArray().put(JSONObject().put("text", text)))
                    )
                }
                val body = JSONObject()
                    .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM))))
                    .put("contents", contents)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 30000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("x-goog-api-key", key)
                }
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val txt = stream?.bufferedReader()?.use { it.readText() } ?: ""
                if (code !in 200..299) {
                    val msg = runCatching { JSONObject(txt).getJSONObject("error").getString("message") }.getOrDefault("")
                    error("HTTP $code $msg")
                }
                JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                    .getString("text").replace(Regex("[*#`_]"), "").trim()
            }
        }
}

// ---------------------------------------------------------------- Assistant brain

data class ChatMsg(val fromUser: Boolean, val text: String)
class Reply(val text: String, val followUp: Boolean = false)

object Assistant {
    val history = mutableStateListOf<ChatMsg>()
    private val aiContext = ArrayList<Pair<String, String>>()
    private var pending: (() -> String)? = null
    private var loaded = false

    private fun file(c: Context) = File(c.applicationContext.filesDir, "history.json")

    fun load(c: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val arr = JSONArray(file(c).readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                history.add(ChatMsg(o.getBoolean("u"), o.getString("t")))
            }
        }
    }

    private fun save(c: Context) {
        runCatching {
            val arr = JSONArray()
            history.takeLast(100).forEach { arr.put(JSONObject().put("u", it.fromUser).put("t", it.text)) }
            file(c).writeText(arr.toString())
        }
    }

    fun clear(c: Context) {
        history.clear(); aiContext.clear(); pending = null; save(c)
    }

    /** Call on the main thread. */
    suspend fun handle(ctx: Context, raw: String): Reply {
        val c = ctx.applicationContext
        load(c)
        history.add(ChatMsg(true, raw))
        val reply = try {
            process(c, raw)
        } catch (e: Exception) {
            Reply("Awais, kuch masla aa gaya: ${e.message}")
        }
        history.add(ChatMsg(false, reply.text))
        save(c)
        return reply
    }

    private suspend fun process(c: Context, raw: String): Reply {
        val t = CommandParser.clean(raw)

        // Waiting for yes/no confirmation (calls, messages)
        pending?.let { p ->
            if (CommandParser.isNo(t)) {
                pending = null
                return Reply("Theek hai Awais, cancel kar diya.")
            }
            if (CommandParser.isYes(t)) {
                pending = null
                return Reply(runCatching { p() }.getOrElse { "Awais, ye kaam nahi ho saka: ${it.message}" })
            }
            pending = null
        }

        if (CommandParser.isGreeting(t)) return Reply("Salam Awais! Main aapki kya madad kar sakta hoon?")

        return when (val cmd = CommandParser.parse(raw)) {
            is Cmd.OpenApp -> {
                val app = AppLauncher.find(c, cmd.name)
                if (app != null && AppLauncher.launch(c, app)) Reply("OK Awais, main abhi ${app.label} open kar raha hoon.")
                else if (cmd.name.split(" ").size <= 2) Reply("Awais, mujhe '${cmd.name}' naam ki app nahi mili.")
                else askAi(c, raw)
            }
            is Cmd.Torch ->
                if (PhoneControls.torch(c, cmd.on)) Reply("Awais, aapka flashlight ${if (cmd.on) "on" else "off"} ho gaya hai.")
                else Reply("Awais, flashlight is waqt control nahi ho raha.")
            Cmd.Battery -> Reply("Awais, aapki battery ${PhoneControls.battery(c)} percent hai.")
            Cmd.Bluetooth -> { PhoneControls.bluetooth(c); Reply("OK Awais, Bluetooth settings khol raha hoon.") }
            Cmd.Wifi -> { PhoneControls.wifi(c); Reply("OK Awais, Wi-Fi settings khol raha hoon.") }
            Cmd.Contacts -> { PhoneControls.contacts(c); Reply("OK Awais, contacts khol raha hoon.") }
            Cmd.TimeNow -> Reply("Awais, abhi waqt ${SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date())} hai.")
            is Cmd.Volume -> {
                val p = PhoneControls.volume(c, cmd.delta, cmd.absolute)
                Reply("Awais, volume $p percent kar diya hai.")
            }
            is Cmd.Alarm -> {
                PhoneControls.alarm(c, cmd.hour, cmd.minute)
                Reply("OK Awais, alarm ${"%02d:%02d".format(cmd.hour, cmd.minute)} par set kar diya hai.")
            }
            is Cmd.Navigate -> {
                PhoneControls.navigate(c, cmd.place)
                Reply("OK Awais, ${cmd.place} ke liye navigation start kar raha hoon.")
            }
            is Cmd.Call -> {
                if (!hasPerm(c, Manifest.permission.CALL_PHONE)) return Reply("Awais, call ke liye Settings mein Phone permission dein.")
                val target = resolve(c, cmd.target) ?: return Reply("Awais, mujhe contacts mein '${cmd.target}' nahi mila, ya contacts permission nahi hai.")
                pending = {
                    PhoneControls.call(c, target.second)
                    "OK Awais, ${target.first} ko call laga raha hoon."
                }
                Reply("Awais, kya main ${target.first} ko call karun? Haan ya nahi bolein.", true)
            }
            is Cmd.Sms -> {
                if (!hasPerm(c, Manifest.permission.SEND_SMS)) return Reply("Awais, message ke liye Settings mein SMS permission dein.")
                val target = resolve(c, cmd.target) ?: return Reply("Awais, mujhe contacts mein '${cmd.target}' nahi mila, ya contacts permission nahi hai.")
                pending = {
                    PhoneControls.sms(c, target.second, cmd.body)
                    "OK Awais, ${target.first} ko message bhej diya."
                }
                Reply("Awais, ${target.first} ko ye message bhejun: ${cmd.body}. Haan ya nahi?", true)
            }
            is Cmd.Unclear -> Reply(cmd.say, true)
            Cmd.None -> askAi(c, raw)
        }
    }

    private fun resolve(c: Context, target: String): Pair<String, String>? {
        if (Regex("^[+\\d][\\d\\s-]{4,}$").matches(target)) return target to target.replace(" ", "")
        if (!hasPerm(c, Manifest.permission.READ_CONTACTS)) return null
        return PhoneControls.findContact(c, target)
    }

    private suspend fun askAi(c: Context, raw: String): Reply {
        if (Prefs.apiKey(c).isBlank()) {
            return Reply("Awais, ye sawal samajhne ke liye Gemini API key chahiye. Settings mein key daal dein. Local commands bina internet ke chalte hain.")
        }
        aiContext.add("user" to CommandParser.clean(raw).ifEmpty { raw })
        while (aiContext.size > 16) aiContext.removeAt(0)
        while (aiContext.firstOrNull()?.first == "model") aiContext.removeAt(0)
        val r = Gemini.ask(c, aiContext.toList())
        return r.fold(
            { ans -> aiContext.add("model" to ans); Reply(ans) },
            { e ->
                if (aiContext.isNotEmpty()) aiContext.removeAt(aiContext.size - 1)
                Reply("Awais, AI se jawab nahi mil saka. ${e.message ?: ""}")
            }
        )
    }
}
