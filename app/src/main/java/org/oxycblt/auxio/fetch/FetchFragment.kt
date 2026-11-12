/*
 * Copyright (c) 2026 Auxio Project
 * FetchFragment.kt is part of Auxio.
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

import android.os.Bundle
import android.view.LayoutInflater
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.transition.MaterialSharedAxis
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentFetchBinding
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.systemBarInsetsCompat

/**
 * The "Fetch artwork & info" screen. Scans the library for songs with missing info or artwork,
 * looks each one up online, and lets the user approve or skip every match.
 */
@AndroidEntryPoint
class FetchFragment : ViewBindingFragment<FragmentFetchBinding>() {
    private val fetchModel: FetchViewModel by viewModels()
    private val adapter =
        FetchAdapter(onApprove = { fetchModel.approve(it) }, onSkip = { fetchModel.skip(it) })

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialSharedAxis(MaterialSharedAxis.X, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.X, false)
    }

    override fun onCreateBinding(inflater: LayoutInflater) = FragmentFetchBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentFetchBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)
        binding.fetchToolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.fetchRecycler.adapter = adapter
        binding.fetchRecycler.setOnApplyWindowInsetsListener { view, insets ->
            view.updatePadding(bottom = insets.systemBarInsetsCompat.bottom)
            insets
        }
        binding.fetchScan.setOnClickListener {
            if (fetchModel.progress.value.running) fetchModel.stop() else fetchModel.scan()
        }
        binding.fetchApproveAll.setOnClickListener { fetchModel.approveAllFound() }
        binding.fetchSave.setOnClickListener { fetchModel.saveApproved() }
        binding.fetchBackup.isChecked = fetchModel.backupOriginals
        binding.fetchBackup.setOnCheckedChangeListener { _, checked ->
            fetchModel.backupOriginals = checked
        }

        collectImmediately(fetchModel.items, adapter::submitList)
        collectImmediately(fetchModel.progress, ::updateProgress)
    }

    override fun onDestroyBinding(binding: FragmentFetchBinding) {
        super.onDestroyBinding(binding)
        binding.fetchRecycler.adapter = null
    }

    private fun updateProgress(progress: FetchProgress) {
        val binding = requireBinding()
        binding.fetchSummary.text =
            when {
                !progress.scanned -> getString(R.string.lbl_fetch_intro)
                progress.total == 0 -> getString(R.string.lbl_fetch_nothing_missing)
                else ->
                    getString(
                        if (progress.running) R.string.fmt_fetch_progress
                        else R.string.fmt_fetch_finished,
                        progress.done,
                        progress.total,
                    )
            }
        binding.fetchScan.setText(
            when {
                progress.running -> R.string.lbl_fetch_stop
                progress.scanned -> R.string.lbl_fetch_rescan
                else -> R.string.lbl_fetch_scan
            }
        )
        binding.fetchScan.isEnabled = !progress.saving
        binding.fetchSave.isVisible = progress.approved > 0 || progress.saving
        binding.fetchSave.isEnabled = !progress.saving && !progress.running
        binding.fetchSave.text =
            if (progress.saving) getString(R.string.lbl_fetch_saving)
            else getString(R.string.fmt_fetch_save, progress.approved)
        binding.fetchProgress.isVisible = progress.running
        binding.fetchProgress.max = progress.total.coerceAtLeast(1)
        binding.fetchProgress.progress = progress.done
        binding.fetchApproveAll.isVisible = progress.scanned && progress.total > 0
    }
}
