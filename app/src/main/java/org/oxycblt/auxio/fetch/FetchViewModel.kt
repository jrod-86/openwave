/*
 * Copyright (c) 2026 Auxio Project
 * FetchViewModel.kt is part of Auxio.
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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.metadata.TagWriter
import org.oxycblt.musikr.tag.Name
import timber.log.Timber as L

/** Information a song is missing. */
enum class MissingField {
    TITLE,
    ARTIST,
    ALBUM,
    ARTWORK,
}

/** One song in the "Fetch artwork & info" list. */
data class FetchItem(
    val song: Song,
    val missing: Set<MissingField>,
    val status: Status = Status.Waiting,
) {
    sealed interface Status {
        data object Waiting : Status

        data object Searching : Status

        data class Found(val match: MetadataMatch, val cover: FetchedCover?) : Status

        data object NotFound : Status

        data class Failed(val reason: String) : Status

        data object Skipped : Status

        data class Approved(val match: MetadataMatch, val cover: FetchedCover?) : Status

        data object Saving : Status

        data object Saved : Status

        data object SaveFailed : Status
    }
}

/** Downloaded artwork: the full image saved in the cache, plus a small preview. */
data class FetchedCover(val file: File, val thumbnail: Bitmap)

/** Overall progress of the fetch screen. */
data class FetchProgress(
    val scanned: Boolean,
    val running: Boolean,
    val done: Int,
    val total: Int,
    val saving: Boolean = false,
    val approved: Int = 0,
)

@HiltViewModel
class FetchViewModel
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val musicRepository: MusicRepository,
) : ViewModel() {
    private val lookup = MetadataLookup()
    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)
    private var job: Job? = null

    /** Whether to copy each original file into the backup folder before changing it. */
    var backupOriginals: Boolean
        get() = prefs.getBoolean(KEY_BACKUP, true)
        set(value) = prefs.edit { putBoolean(KEY_BACKUP, value) }

    val items: StateFlow<List<FetchItem>>
        field = MutableStateFlow<List<FetchItem>>(emptyList())

    val progress: StateFlow<FetchProgress>
        field = MutableStateFlow(FetchProgress(scanned = false, running = false, 0, 0))

    /** Find every song with missing info or artwork, then look each one up online. */
    fun scan() {
        if (job?.isActive == true) return
        val songs = musicRepository.library?.songs ?: emptyList()
        val found =
            songs
                .mapNotNull { song ->
                    findMissing(song).takeIf { it.isNotEmpty() }?.let { FetchItem(song, it) }
                }
                .sortedBy { it.song.path.resolve(context) }
        L.d("Found ${found.size} songs with missing info out of ${songs.size}")
        items.value = found
        progress.value = FetchProgress(scanned = true, running = true, 0, found.size)
        job = viewModelScope.launch { lookUpAll() }
    }

    /** Stop looking songs up. Results found so far are kept. */
    fun stop() {
        job?.cancel()
        progress.update { it.copy(running = false) }
    }

    fun skip(item: FetchItem) {
        setStatus(item.song.uid, FetchItem.Status.Skipped)
        updateApprovedCount()
    }

    fun approve(item: FetchItem) {
        val status = item.status as? FetchItem.Status.Found ?: return
        setStatus(item.song.uid, FetchItem.Status.Approved(status.match, status.cover))
        updateApprovedCount()
    }

    fun approveAllFound() {
        items.update { list ->
            list.map {
                val status = it.status
                if (status is FetchItem.Status.Found) {
                    it.copy(status = FetchItem.Status.Approved(status.match, status.cover))
                } else {
                    it
                }
            }
        }
        updateApprovedCount()
    }

    /**
     * Permanently write every approved match into its file. Only fields the song is missing are
     * filled in (plus MusicBrainz IDs), so tags the user already has are never overwritten. The
     * library is reloaded afterwards so the changes show up.
     */
    fun saveApproved() {
        if (job?.isActive == true) return
        val approved = items.value.filter { it.status is FetchItem.Status.Approved }
        if (approved.isEmpty()) return
        progress.update { it.copy(saving = true) }
        job = viewModelScope.launch {
            var saved = 0
            for (item in approved) {
                val status = item.status as FetchItem.Status.Approved
                setStatus(item.song.uid, FetchItem.Status.Saving)
                val ok =
                    withContext(Dispatchers.IO) {
                        try {
                            if (backupOriginals) backUp(item.song)
                            TagWriter.write(
                                context,
                                item.song.uri,
                                tagsFor(item, status.match),
                                status.cover
                                    ?.takeIf { MissingField.ARTWORK in item.missing }
                                    ?.file
                                    ?.readBytes(),
                            )
                        } catch (e: Exception) {
                            L.e(e, "Unable to save ${item.song.path}")
                            false
                        }
                    }
                if (ok) saved++
                setStatus(
                    item.song.uid,
                    if (ok) FetchItem.Status.Saved else FetchItem.Status.SaveFailed,
                )
            }
            L.d("Saved $saved of ${approved.size} songs")
            updateApprovedCount()
            progress.update { it.copy(saving = false) }
            if (saved > 0) musicRepository.requestIndex(true)
        }
    }

    private fun tagsFor(item: FetchItem, match: MetadataMatch): Map<String, String> {
        val tags = mutableMapOf<String, String>()
        if (MissingField.TITLE in item.missing) tags["TITLE"] = match.title
        if (MissingField.ARTIST in item.missing) tags["ARTIST"] = match.artist
        if (MissingField.ALBUM in item.missing) match.album?.let { tags["ALBUM"] = it }
        if (item.song.date == null) match.year?.let { tags["DATE"] = it.toString() }
        tags["MUSICBRAINZ_TRACKID"] = match.recordingId
        match.releaseId?.let { tags["MUSICBRAINZ_ALBUMID"] = it }
        match.releaseGroupId?.let { tags["MUSICBRAINZ_RELEASEGROUPID"] = it }
        return tags
    }

    private fun backUp(song: Song) {
        val dir = File(context.getExternalFilesDir(null), BACKUP_DIR).apply { mkdirs() }
        val name = song.path.name ?: "${song.uid}"
        var target = File(dir, name)
        var n = 1
        while (target.exists()) target = File(dir, "${n++}_$name")
        val input =
            context.contentResolver.openInputStream(song.uri)
                ?: throw IllegalStateException("Unable to read ${song.path}")
        input.use { source -> target.outputStream().use { source.copyTo(it) } }
    }

    private fun updateApprovedCount() {
        val count = items.value.count { it.status is FetchItem.Status.Approved }
        progress.update { it.copy(approved = count) }
    }

    private suspend fun lookUpAll() {
        var done = 0
        for (item in items.value) {
            if (item.status != FetchItem.Status.Waiting) continue
            setStatus(item.song.uid, FetchItem.Status.Searching)
            val status =
                try {
                    lookUp(item)
                } catch (e: CancellationException) {
                    setStatus(item.song.uid, FetchItem.Status.Waiting)
                    throw e
                } catch (e: Exception) {
                    L.w(e, "Lookup failed for ${item.song.path}")
                    FetchItem.Status.Failed(e.message ?: e.javaClass.simpleName)
                }
            // The user may have skipped the song while it was being looked up.
            if (currentStatus(item.song.uid) == FetchItem.Status.Searching) {
                setStatus(item.song.uid, status)
            }
            done++
            progress.update { it.copy(done = done) }
        }
        progress.update { it.copy(running = false) }
    }

    private suspend fun lookUp(item: FetchItem): FetchItem.Status {
        val query = buildQuery(item) ?: return FetchItem.Status.NotFound
        val match = lookup.findMatch(query) ?: return FetchItem.Status.NotFound
        val cover = lookup.fetchCover(match)?.let { saveCover(match, it) }
        return FetchItem.Status.Found(match, cover)
    }

    private fun buildQuery(item: FetchItem): LookupQuery? {
        val song = item.song
        var title = song.name.raw.takeUnless { MissingField.TITLE in item.missing }
        var artist =
            song.artists.firstOrNull()?.name?.let { it as? Name.Known }?.raw
                ?: song.album.artists.firstOrNull()?.name?.let { it as? Name.Known }?.raw
        if (title == null) {
            // Many untagged files are named like "01 - Artist - Title.mp3".
            val parts =
                (song.path.name ?: return null)
                    .substringBeforeLast('.')
                    .replace('_', ' ')
                    .split(" - ")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && it.toIntOrNull() == null }
            title = parts.lastOrNull()?.replace(LEADING_TRACK, "") ?: return null
            if (artist == null && parts.size >= 2) artist = parts[parts.size - 2]
        }
        val album = song.album.name.let { it as? Name.Known }?.raw
        return LookupQuery(
            title = title,
            artist = artist,
            album = album.takeUnless { MissingField.ALBUM in item.missing },
            durationMs = song.durationMs.takeIf { it > 0 },
        )
    }

    private suspend fun saveCover(match: MetadataMatch, bytes: ByteArray): FetchedCover? =
        withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0) return@withContext null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= THUMBNAIL_SIZE) sample *= 2
            val options =
                BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
            val thumbnail =
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    ?: return@withContext null
            val dir = File(context.cacheDir, "fetched_covers").apply { mkdirs() }
            val file = File(dir, "${match.releaseId ?: match.recordingId}.jpg")
            file.writeBytes(bytes)
            FetchedCover(file, thumbnail)
        }

    private fun findMissing(song: Song): Set<MissingField> {
        val missing = mutableSetOf<MissingField>()
        val fileName = song.path.name
        if (fileName != null && song.name.raw == fileName.substringBeforeLast('.')) {
            missing.add(MissingField.TITLE)
        }
        if (song.artists.all { it.name is Name.Unknown }) missing.add(MissingField.ARTIST)
        val albumName = song.album.name
        if (albumName !is Name.Known || albumName.raw == song.path.directory.name) {
            missing.add(MissingField.ALBUM)
        }
        if (song.cover == null) missing.add(MissingField.ARTWORK)
        return missing
    }

    private fun currentStatus(uid: Music.UID) = items.value.find { it.song.uid == uid }?.status

    private fun setStatus(uid: Music.UID, status: FetchItem.Status) {
        items.update { list ->
            list.map { if (it.song.uid == uid) it.copy(status = status) else it }
        }
    }

    override fun onCleared() {
        super.onCleared()
        job?.cancel()
    }

    private companion object {
        const val THUMBNAIL_SIZE = 128
        const val KEY_BACKUP = "openwave_fetch_backup_originals"
        const val BACKUP_DIR = "Original files"
        val LEADING_TRACK = Regex("""^\d{1,3}[.\s-]+""")
    }
}
