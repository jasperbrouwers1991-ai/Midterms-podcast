package nl.jasper.midterms

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Episode(
    val date: String,
    val title: String,
    val summary: String,
    val text: String,
    /** Absolute URL of the MP3, or null when ElevenLabs failed (then the phone reads the text aloud). */
    val audioUrl: String?,
    val durationSeconds: Int,
)

object Feed {
    suspend fun load(): List<Episode> = withContext(Dispatchers.IO) {
        // Cache-buster so a freshly published episode shows up right away.
        val url = URL(BuildConfig.FEED_URL + "?t=" + System.currentTimeMillis())
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            useCaches = false
        }
        try {
            if (conn.responseCode != 200) error("Server gaf ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            parse(body, BuildConfig.FEED_URL.substringBeforeLast('/') + "/")
        } finally {
            conn.disconnect()
        }
    }

    fun parse(json: String, baseUrl: String): List<Episode> {
        val arr = JSONObject(json).getJSONArray("episodes")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val audio = o.optString("audio", "").takeIf { it.isNotBlank() && it != "null" }
            Episode(
                date = o.getString("date"),
                title = o.optString("title", o.getString("date")),
                summary = o.optString("summary", ""),
                text = o.optString("text", ""),
                audioUrl = audio?.let { if (it.startsWith("http")) it else baseUrl + it },
                durationSeconds = o.optInt("duration", 0),
            )
        }.sortedByDescending { it.date }
    }
}
