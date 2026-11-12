/*
 * Copyright (c) 2026 Auxio Project
 * MetadataLookup.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.auxio.fetch

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.oxycblt.auxio.BuildConfig

/** What we already know about a song, used to search for it online. */
data class LookupQuery(
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
)

/** A proposed set of tags and artwork for a song, found online. */
data class MetadataMatch(
    val recordingId: String,
    val releaseId: String?,
    val releaseGroupId: String?,
    val title: String,
    val artist: String,
    val album: String?,
    val year: Int?,
    val score: Int,
)

/**
 * Looks up song information on MusicBrainz and album artwork on the Cover Art Archive.
 *
 * MusicBrainz asks clients to send at most one request per second and to identify themselves with a
 * descriptive User-Agent, so every request goes through a shared rate limiter.
 */
class MetadataLookup {
    private val rateLimit = Mutex()
    private var lastRequestMs = 0L

    /** Search MusicBrainz for the recording that best fits [query]. Returns null if none fits. */
    suspend fun findMatch(query: LookupQuery): MetadataMatch? {
        val lucene = buildString {
            append("recording:\"").append(escape(query.title)).append('"')
            query.artist?.let { append(" AND artist:\"").append(escape(it)).append('"') }
            query.durationMs?.let {
                if (it > 0) {
                    append(" AND dur:[").append(it - DURATION_SLACK_MS)
                    append(" TO ").append(it + DURATION_SLACK_MS).append(']')
                }
            }
        }
        val url =
            "$MUSICBRAINZ_BASE/recording?fmt=json&limit=10&query=" +
                URLEncoder.encode(lucene, "UTF-8")
        val json = JSONObject(String(get(url, rateLimited = true) ?: return null))
        val recordings = json.optJSONArray("recordings") ?: return null
        return pickBest(recordings, query)
    }

    /**
     * Download the front cover for a match, trying the specific release first and then the release
     * group. Returns the raw image bytes, or null if no artwork exists.
     */
    suspend fun fetchCover(match: MetadataMatch): ByteArray? {
        match.releaseId?.let { id ->
            get("$COVER_ART_BASE/release/$id/front-500", rateLimited = false)?.let {
                return it
            }
        }
        match.releaseGroupId?.let { id ->
            get("$COVER_ART_BASE/release-group/$id/front-500", rateLimited = false)?.let {
                return it
            }
        }
        return null
    }

    private fun pickBest(recordings: JSONArray, query: LookupQuery): MetadataMatch? {
        var best: MetadataMatch? = null
        var bestRank = Int.MIN_VALUE
        for (i in 0 until recordings.length()) {
            val rec = recordings.getJSONObject(i)
            val score = rec.optInt("score", 0)
            if (score < MIN_SCORE) continue
            val releases = rec.optJSONArray("releases") ?: JSONArray()
            val release = pickRelease(releases, query.album)
            var rank = score * 10
            // Prefer recordings whose length is close to the file's, and real album releases.
            val length = rec.optLong("length", -1)
            if (length > 0 && query.durationMs != null) {
                rank -= (abs(length - query.durationMs) / 1000).toInt().coerceAtMost(30)
            }
            if (release != null && isPlainAlbum(release)) rank += 20
            if (
                release != null &&
                    query.album != null &&
                    release.optString("title").equals(query.album, ignoreCase = true)
            ) {
                rank += 50
            }
            if (rank > bestRank) {
                bestRank = rank
                best = toMatch(rec, release, score)
            }
        }
        return best
    }

    private fun pickRelease(releases: JSONArray, album: String?): JSONObject? {
        var fallback: JSONObject? = null
        var official: JSONObject? = null
        for (i in 0 until releases.length()) {
            val release = releases.getJSONObject(i)
            if (album != null && release.optString("title").equals(album, ignoreCase = true)) {
                return release
            }
            if (fallback == null) fallback = release
            if (
                official == null &&
                    release.optString("status") == "Official" &&
                    isPlainAlbum(release)
            ) {
                official = release
            }
        }
        return official ?: fallback
    }

    private fun isPlainAlbum(release: JSONObject): Boolean {
        val group = release.optJSONObject("release-group") ?: return false
        val secondary = group.optJSONArray("secondary-types")
        return group.optString("primary-type") == "Album" &&
            (secondary == null || secondary.length() == 0)
    }

    private fun toMatch(rec: JSONObject, release: JSONObject?, score: Int): MetadataMatch {
        val credits = rec.optJSONArray("artist-credit") ?: JSONArray()
        val artist = buildString {
            for (i in 0 until credits.length()) {
                val credit = credits.getJSONObject(i)
                append(credit.optString("name"))
                append(credit.optString("joinphrase"))
            }
        }
        val date =
            release?.optString("date")?.takeIf { it.isNotBlank() }
                ?: rec.optString("first-release-date")
        return MetadataMatch(
            recordingId = rec.getString("id"),
            releaseId = release?.optString("id")?.takeIf { it.isNotBlank() },
            releaseGroupId =
                release?.optJSONObject("release-group")?.optString("id")?.takeIf {
                    it.isNotBlank()
                },
            title = rec.optString("title"),
            artist = artist,
            album = release?.optString("title")?.takeIf { it.isNotBlank() },
            year = date.take(4).toIntOrNull(),
            score = score,
        )
    }

    private suspend fun get(url: String, rateLimited: Boolean): ByteArray? {
        if (rateLimited) {
            rateLimit.withLock {
                val wait = lastRequestMs + MIN_INTERVAL_MS - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                lastRequestMs = System.currentTimeMillis()
            }
        }
        return withContext(Dispatchers.IO) {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Accept", "application/json, image/*")
                when (val code = connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> connection.inputStream.use { it.readBytes() }
                    HttpURLConnection.HTTP_NOT_FOUND -> null
                    else -> throw IOException("HTTP $code from $url")
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun escape(text: String) = text.replace(LUCENE_SPECIAL) { "\\" + it.value }

    private companion object {
        const val MUSICBRAINZ_BASE = "https://musicbrainz.org/ws/2"
        const val COVER_ART_BASE = "https://coverartarchive.org"
        val USER_AGENT =
            "Openwave/${BuildConfig.VERSION_NAME} ( https://github.com/jrod-86/openwave )"
        const val MIN_INTERVAL_MS = 1100L
        const val TIMEOUT_MS = 15_000
        const val DURATION_SLACK_MS = 5_000L
        const val MIN_SCORE = 80
        val LUCENE_SPECIAL = Regex("""[+\-&|!(){}\[\]^"~*?:\\/]""")
    }
}
