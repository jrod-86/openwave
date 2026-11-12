/*
 * Copyright (c) 2026 Auxio Project
 * FetchAdapter.kt is part of Auxio.
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

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.ItemFetchBinding

/** Shows each song with missing info, what was found for it, and approve/skip buttons. */
class FetchAdapter(
    private val onApprove: (FetchItem) -> Unit,
    private val onSkip: (FetchItem) -> Unit,
) : ListAdapter<FetchItem, FetchAdapter.ViewHolder>(DIFF) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(ItemFetchBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(getItem(position))

    inner class ViewHolder(private val binding: ItemFetchBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(item: FetchItem) {
            val context = binding.root.context
            binding.fetchFile.text = item.song.path.name ?: item.song.name.raw
            binding.fetchMissing.text =
                context.getString(
                    R.string.fmt_fetch_missing,
                    item.missing.joinToString(", ") { context.getString(it.label) },
                )

            val (match, cover) =
                when (val status = item.status) {
                    is FetchItem.Status.Found -> status.match to status.cover
                    is FetchItem.Status.Approved -> status.match to status.cover
                    else -> null to null
                }

            if (cover != null) {
                binding.fetchCover.setImageBitmap(cover.thumbnail)
            } else {
                binding.fetchCover.setImageResource(R.drawable.ic_song_24)
            }

            binding.fetchProposed.isVisible = match != null
            if (match != null) {
                binding.fetchProposed.text =
                    context.getString(
                        R.string.fmt_fetch_proposed,
                        match.title,
                        match.artist,
                        listOfNotNull(match.album, match.year?.toString()).joinToString(" · "),
                    )
            }

            binding.fetchStatus.setText(
                when (item.status) {
                    FetchItem.Status.Waiting -> R.string.lbl_fetch_waiting
                    FetchItem.Status.Searching -> R.string.lbl_fetch_searching
                    is FetchItem.Status.Found ->
                        if (cover != null) R.string.lbl_fetch_found
                        else R.string.lbl_fetch_found_no_art
                    FetchItem.Status.NotFound -> R.string.lbl_fetch_not_found
                    is FetchItem.Status.Failed -> R.string.lbl_fetch_failed
                    FetchItem.Status.Skipped -> R.string.lbl_fetch_skipped
                    is FetchItem.Status.Approved -> R.string.lbl_fetch_approved
                }
            )

            binding.fetchApprove.isVisible = item.status is FetchItem.Status.Found
            binding.fetchSkip.isVisible =
                item.status !is FetchItem.Status.Skipped &&
                    item.status !is FetchItem.Status.Approved
            binding.fetchApprove.setOnClickListener { onApprove(item) }
            binding.fetchSkip.setOnClickListener { onSkip(item) }
        }
    }

    private companion object {
        val DIFF =
            object : DiffUtil.ItemCallback<FetchItem>() {
                override fun areItemsTheSame(oldItem: FetchItem, newItem: FetchItem) =
                    oldItem.song.uid == newItem.song.uid

                override fun areContentsTheSame(oldItem: FetchItem, newItem: FetchItem) =
                    oldItem == newItem
            }
    }
}

private val MissingField.label: Int
    get() =
        when (this) {
            MissingField.TITLE -> R.string.lbl_fetch_missing_title
            MissingField.ARTIST -> R.string.lbl_fetch_missing_artist
            MissingField.ALBUM -> R.string.lbl_fetch_missing_album
            MissingField.ARTWORK -> R.string.lbl_fetch_missing_artwork
        }
