package id.oscarmandala.otacix.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Satu baris lirik. timeMs = -1 bila lirik tidak bertanda waktu. */
class LyricLine(val timeMs: Long, val text: String)

class LyricsData(val lines: List<LyricLine>, val synced: Boolean)

/**
 * Pengambil lirik dari LRCLIB (lrclib.net): gratis, tanpa kunci API, ada lirik bertanda waktu (disorot mengikuti lagu).
 * Melempar IOException bila jaringan bermasalah; mengembalikan null bila lirik memang tidak ada.
 */
object Lyrics {
    private const val UA = "Otacix/1.0 (by Oscar Mandala)"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(14, TimeUnit.SECONDS).build()

    private val cache = ConcurrentHashMap<String, LyricsData>()
    private val misses = ConcurrentHashMap.newKeySet<String>()

    suspend fun load(t: Track): LyricsData? {
        cache[t.id]?.let { return it }
        if (misses.contains(t.id)) return null
        val r = withContext(Dispatchers.IO) { find(t) }
        if (r != null) cache[t.id] = r else misses.add(t.id)
        return r
    }

    // ---------------------------------------------------------------- pencarian
    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): String? {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            return r.body?.string()
        }
    }

    private val noise = Regex(
        """[\(\[\{][^\)\]\}]*(official|lyric|lirik|video|audio|mv|m/v|visualizer|clip|hd|hq|4k|remaster|explicit)[^\)\]\}]*[\)\]\}]""",
        RegexOption.IGNORE_CASE,
    )
    private val spaces = Regex("""\s+""")
    private val topic = Regex("""\s*-\s*Topic\z""", RegexOption.IGNORE_CASE)
    private val vevo = Regex("""\s*(VEVO|Official)\z""", RegexOption.IGNORE_CASE)

    private fun cleanTitle(s: String): String =
        s.replace(noise, " ").replace(spaces, " ").trim().trim('-', '|', ' ')

    private fun cleanArtist(s: String): String =
        s.replace(topic, "").replace(vevo, "").trim()

    /** Pasangan (artis, judul) yang dicoba berurutan. Judul YouTube sering berbentuk "Artis - Lagu". */
    private fun candidates(t: Track): List<Pair<String, String>> {
        val art = cleanArtist(t.artist)
        val title = cleanTitle(t.title)
        val out = LinkedHashSet<Pair<String, String>>()
        val i = title.indexOf(" - ")
        if (i > 0) {
            val a = title.substring(0, i).trim()
            val b = title.substring(i + 3).trim()
            if (a.isNotEmpty() && b.isNotEmpty()) {
                out.add(a to b)
                if (art.isNotEmpty() && !a.equals(art, ignoreCase = true)) out.add(art to b)
            }
        }
        if (title.isNotEmpty()) out.add(art to title)
        return out.toList()
    }

    private fun str(o: JSONObject, key: String): String = if (o.isNull(key)) "" else o.optString(key, "")

    private fun fromObj(o: JSONObject): LyricsData? {
        if (o.optBoolean("instrumental", false)) return null
        val synced = str(o, "syncedLyrics")
        if (synced.isNotBlank()) {
            val l = parseSynced(synced)
            if (l.isNotEmpty()) return LyricsData(l, true)
        }
        val plain = str(o, "plainLyrics")
        if (plain.isNotBlank()) {
            val l = plain.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { LyricLine(-1L, it) }
            if (l.isNotEmpty()) return LyricsData(l, false)
        }
        return null
    }

    private val stamp = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?\]""")

    private fun parseSynced(s: String): List<LyricLine> {
        val out = ArrayList<LyricLine>()
        for (raw in s.lines()) {
            val ms = stamp.findAll(raw).toList()
            if (ms.isEmpty()) continue
            val text = raw.substring(ms.last().range.last + 1).trim()
            if (text.isEmpty()) continue
            for (m in ms) {
                val mm = m.groupValues[1].toLong()
                val ss = m.groupValues[2].toLong()
                val frac = m.groupValues[3]
                val part = if (frac.isEmpty()) 0L else frac.padEnd(3, '0').take(3).toLong()
                out.add(LyricLine(mm * 60_000L + ss * 1_000L + part, text))
            }
        }
        return out.sortedBy { it.timeMs }
    }

    private fun find(t: Track): LyricsData? {
        val cands = candidates(t)
        if (cands.isEmpty()) return null

        // 1) cocok persis (artis + judul + durasi)
        for ((a, b) in cands) {
            val url = "https://lrclib.net/api/get?artist_name=${enc(a)}&track_name=${enc(b)}" +
                if (t.dur > 0) "&duration=${t.dur}" else ""
            val body = get(url) ?: continue
            val d = runCatching { fromObj(JSONObject(body)) }.getOrNull()
            if (d != null) return d
        }

        // 2) cari bebas, pilih yang durasinya paling mendekati
        for ((a, b) in cands) {
            val q = if (a.isNotEmpty()) "$a $b" else b
            val body = get("https://lrclib.net/api/search?q=${enc(q)}") ?: continue
            val arr = runCatching { JSONArray(body) }.getOrNull() ?: continue
            var best: LyricsData? = null
            var bestScore = Int.MAX_VALUE
            for (i in 0 until minOf(arr.length(), 12)) {
                val o = arr.optJSONObject(i) ?: continue
                val d = fromObj(o) ?: continue
                val diff = if (t.dur > 0 && !o.isNull("duration")) Math.abs(o.optDouble("duration", 0.0) - t.dur).toInt() else 0
                val score = diff * 2 + (if (d.synced) 0 else 30)
                if (score < bestScore) { bestScore = score; best = d }
            }
            if (best != null && bestScore <= 40) return best
        }
        return null
    }
}
