package com.suisheng.player.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.suisheng.player.R
import com.suisheng.player.data.SettingsRepository
import com.suisheng.player.databinding.FragmentPlaylistBinding
import com.google.android.material.tabs.TabLayoutMediator

class PlaylistFragment : Fragment() {
    private var _binding: FragmentPlaylistBinding? = null
    private val binding get() = _binding!!
    private var tabMediator: TabLayoutMediator? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPlaylistBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val settings = SettingsRepository(requireContext())
        AppThemeManager.applyFragmentRoot(binding.root, AppThemeManager.palette(requireContext(), settings))
        binding.sectionPager.adapter = PlaylistSectionAdapter(this)
        binding.sectionPager.offscreenPageLimit = 4
        tabMediator = TabLayoutMediator(binding.sectionTabs, binding.sectionPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.section_local)
                1 -> getString(R.string.section_online)
                2 -> getString(R.string.section_radio)
                else -> getString(R.string.section_podcast)
            }
        }.also { it.attach() }
    }

    fun refreshFromHost() {
        childFragmentManager.fragments.forEach { fragment ->
            when (fragment) {
                is LocalPlaylistFragment -> fragment.refreshFromHost()
                is OnlineMusicFragment -> fragment.refreshLibrary()
            }
        }
    }

    override fun onDestroyView() {
        tabMediator?.detach()
        tabMediator = null
        _binding = null
        super.onDestroyView()
    }

    private class PlaylistSectionAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        override fun getItemCount(): Int = 4

        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> LocalPlaylistFragment()
            1 -> OnlineMusicFragment()
            2 -> RadioFragment()
            else -> PodcastFragment()
        }
    }
}
