package com.awais.assistant

sealed class Cmd {
    data class OpenApp(val name: String) : Cmd()
    data class Torch(val on: Boolean) : Cmd()
    object Battery : Cmd()
    object Bluetooth : Cmd()
    object Wifi : Cmd()
    object Contacts : Cmd()
    object TimeNow : Cmd()
    data class Volume(val delta: Int?, val absolute: Int?) : Cmd()
    data class Alarm(val hour: Int, val minute: Int) : Cmd()
    data class Call(val target: String) : Cmd()
    data class Sms(val target: String, val body: String) : Cmd()
    data class Navigate(val place: String) : Cmd()
    data class Unclear(val say: String) : Cmd()
    object None : Cmd()
}

/**
 * Offline command understanding for English, Urdu and Roman Urdu.
 * Anything that returns Cmd.None is sent to the AI (Gemini).
 */
object CommandParser {
    private val wake = setOf("awais", "awes", "owais", "uwais", "awaiz", "awaise", "اویس")

    private val openVerbs = setOf(
        "open", "launch", "start", "run", "kholo", "kholein", "kholna", "khol", "chalao",
        "chalayein", "chalana", "chala", "shuru", "کھولو", "کھولیں", "کھول", "چلاؤ", "چلائیں", "چلا", "اوپن"
    )

    private val fillers = setOf(
        "karo", "kro", "kar", "kardo", "do", "dein", "den", "de", "please", "plz", "pls", "app",
        "application", "ko", "ka", "ki", "ke", "mein", "me", "zara", "ek", "aik", "jaldi", "mujhe",
        "meri", "mera", "the", "a", "hai", "hain", "na", "yar", "yaar", "ji", "jee", "kijiye",
        "کرو", "کریں", "دو", "دیں", "ایپ", "کو", "کا", "کی", "کے", "میں", "ذرا", "ایک", "مجھے", "پلیز"
    )

    private val navStop = setOf(
        "navigate", "navigation", "directions", "direction", "rasta", "raasta", "route", "to", "se",
        "liye", "batao", "dikhao", "jana", "jaana", "wahan", "gps", "راستہ", "نیویگیٹ", "نیویگیشن",
        "start", "chalao", "kholo", "karo", "kar", "do", "ka", "ki", "ke", "ko", "hai", "mujhe", "me", "mein"
    ) + fillers

    private val yes = setOf("yes", "yeah", "yep", "haan", "han", "ha", "ji", "jee", "theek", "thik", "ok", "okay",
        "confirm", "sure", "bilkul", "ہاں", "جی", "ٹھیک", "بالکل")
    private val no = setOf("no", "nahi", "nahin", "nai", "cancel", "mat", "stop", "nope", "نہیں", "منسوخ", "نا")
    private val greetings = setOf("hello", "hi", "hey", "salam", "salaam", "assalam o alaikum",
        "assalamualaikum", "assalamu alaikum", "aoa", "ہیلو", "سلام", "السلام علیکم")

    fun norm(s: String): String =
        s.lowercase().replace(Regex("[.,!?؟،\"'()]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Text without the wake word. */
    fun clean(raw: String): String =
        norm(raw).split(" ").filter { it.isNotEmpty() && it !in wake }.joinToString(" ")

    fun isYes(t: String) = t.split(" ").any { it in yes } && !isNo(t)
    fun isNo(t: String) = t.split(" ").any { it in no }
    fun isGreeting(t: String) = t in greetings

    private fun parseTime(text: String): Pair<Int, Int>? {
        val m = Regex("(\\d{1,2})(?:[: ](\\d{2}))?").find(text) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        val pm = Regex("\\b(pm|p m|shaam|sham|raat|dopahar)\\b").containsMatchIn(text)
        val am = Regex("\\b(am|a m|subah|savere)\\b").containsMatchIn(text)
        if (pm && h < 12) h += 12
        if (am && h == 12) h = 0
        return if (h in 0..23 && min in 0..59) h to min else null
    }

    private val smsUrdu = Regex("(\\S+)\\s+ko\\s+(?:message|sms|msg|میسج)\\s*(?:bhejo|bhej do|bhej|karo|kar do|send)?\\s*(.*)")
    private val smsEn = Regex("(?:message|sms|msg|میسج)\\s+(?:to\\s+)?(\\S+)\\s*(?:saying|that|ke|keh|likho|likh)?\\s*(.*)")

    fun parse(raw: String): Cmd {
        val tokens = norm(raw).split(" ").filter { it.isNotEmpty() && it !in wake }
        if (tokens.isEmpty()) return Cmd.None
        val text = tokens.joinToString(" ")
        val compact = text.replace(" ", "").replace("-", "")
        fun has(vararg w: String) = tokens.any { it in w }

        // Alarm
        if (has("alarm", "الارم")) {
            val t = parseTime(text) ?: return Cmd.Unclear("Awais, alarm kis waqt ka set karun?")
            return Cmd.Alarm(t.first, t.second)
        }

        // Flashlight
        if (has("flashlight", "torch", "tourch", "flash", "ٹارچ", "فلیش") || compact.contains("flashlight")) {
            val off = has("off", "band", "bujha", "bujhao", "bujhado", "بند", "بجھاؤ", "بجھا")
            return Cmd.Torch(!off)
        }

        if (has("battery", "batri", "بیٹری")) return Cmd.Battery
        if (has("bluetooth", "blutooth", "بلوٹوتھ")) return Cmd.Bluetooth
        if (compact.contains("wifi") || compact.contains("وائیفائی")) return Cmd.Wifi

        // Volume
        if (has("volume", "awaz", "awaaz", "آواز", "mute")) {
            if (has("mute")) return Cmd.Volume(null, 0)
            val num = Regex("\\d+").find(text)?.value?.toIntOrNull()
            if (num != null) return Cmd.Volume(null, num)
            val up = has("up", "badhao", "zyada", "zyadah", "barha", "increase", "high", "full", "بڑھاؤ", "اونچی")
            val down = has("down", "kam", "ghatao", "decrease", "low", "کم", "گھٹاؤ")
            return when {
                up -> Cmd.Volume(1, null)
                down -> Cmd.Volume(-1, null)
                else -> Cmd.Unclear("Awais, volume kam karun ya zyada?")
            }
        }

        // SMS
        if (has("message", "sms", "msg", "میسج")) {
            val m = smsUrdu.find(text) ?: smsEn.find(text)
            if (m != null) {
                val target = m.groupValues[1]
                val body = m.groupValues[2].trim()
                if (body.isEmpty()) return Cmd.Unclear("Awais, $target ko kya message bhejun? Poora command dobara bolein.")
                return Cmd.Sms(target, body)
            }
            return Cmd.Unclear("Awais, kisko message bhejna hai aur kya likhna hai?")
        }

        // Call
        val callByWord = has("call", "dial", "کال") ||
            text.contains("phone karo") || text.contains("phone lagao") || text.contains("phone milao") ||
            text.contains("فون کرو") || text.contains("فون لگاؤ")
        if (callByWord) {
            val stop = setOf("call", "dial", "phone", "lagao", "laga", "milao", "mila", "karo", "to",
                "contact", "کال", "فون", "لگاؤ", "کرو") + fillers + openVerbs
            val target = tokens.filter { it !in stop }.joinToString(" ")
            if (target.isEmpty()) return Cmd.Unclear("Awais, kisko call karun?")
            return Cmd.Call(target)
        }

        if (has("contacts", "contact", "کانٹیکٹس", "کانٹیکٹ")) return Cmd.Contacts

        // Navigation
        val navKeys = setOf("navigate", "navigation", "directions", "direction", "rasta", "raasta", "route",
            "راستہ", "نیویگیٹ", "نیویگیشن")
        if (tokens.any { it in navKeys } || text.startsWith("take me to ")) {
            val place = if (text.startsWith("take me to ")) text.removePrefix("take me to ").trim()
            else tokens.filter { it !in navStop }.joinToString(" ")
            if (place.isEmpty()) return Cmd.Unclear("Awais, kahan jaana hai?")
            return Cmd.Navigate(place)
        }

        // Time
        val timeKeys = listOf("what time", "time kya", "time batao", "waqt", "kitne baje", "what is the time", "وقت", "کتنے بجے")
        if (timeKeys.any { text.contains(it) }) return Cmd.TimeNow

        // Open app (last, because "chalao"/"start" are generic)
        if (tokens.any { it in openVerbs }) {
            val name = tokens.filter { it !in openVerbs && it !in fillers }.joinToString(" ")
            if (name.isNotEmpty()) return Cmd.OpenApp(name)
        }

        return Cmd.None
    }
}
