/*
 * Copyright (c) 2026 Auxio Project
 * TagWriter.kt is part of Auxio.
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
 
package org.oxycblt.musikr.metadata

import android.content.Context
import android.net.Uri

/**
 * Writes tags and artwork directly into audio files using TagLib.
 *
 * Tag keys use TagLib's unified property names, such as `TITLE`, `ARTIST`, `ALBUM`, `DATE`,
 * `MUSICBRAINZ_TRACKID` or `REPLAYGAIN_TRACK_GAIN`. An empty value removes the tag.
 */
object TagWriter {
    /**
     * Write [tags] and, if given, a front cover [picture] into the file at [uri]. The file must be
     * writable by the app (for example, inside a music folder the user granted access to).
     *
     * This method is blocking and should be called from a background thread.
     *
     * @return true if the file was saved.
     */
    fun write(
        context: Context,
        uri: Uri,
        tags: Map<String, String>,
        picture: ByteArray? = null,
    ): Boolean {
        val pfd = context.contentResolver.openFileDescriptor(uri, "rw") ?: return false
        val fd = pfd.detachFd()
        return TagLibJNI.writeNative(
            fd,
            tags.keys.toTypedArray(),
            tags.values.toTypedArray(),
            picture,
            picture?.let { mimeTypeOf(it) },
        )
    }

    private fun mimeTypeOf(image: ByteArray): String =
        when {
            image.size > 3 &&
                image[0] == 0x89.toByte() &&
                image[1] == 'P'.code.toByte() &&
                image[2] == 'N'.code.toByte() &&
                image[3] == 'G'.code.toByte() -> "image/png"
            else -> "image/jpeg"
        }
}
