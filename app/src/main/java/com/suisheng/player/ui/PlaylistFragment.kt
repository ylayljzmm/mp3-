package com.suisheng.player.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.suisheng.player.R
import com.suisheng.player.databinding.FragmentPlaylistBinding

class PlaylistFragment : Fragment() {
    private var _binding: FragmentPlaylistBinding? = null
    private val binding get() = _binding!!
    private var pageChangeCallback: ViewPager2.OnPageChangeCallback? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPlaylistBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.sectionPager.adapter = PlaylistSectionAdapter(this)
        binding.sectionPager.offscreenPageLimit = 4

        val titles = listOf(
            getString(R.string.section_local),
            getString(R.string.section_online),
            getString(R.string.section_radio),
            getString(R.string.section_podcast),
        )
        binding.sectionTabs.setTitles(titles)
        binding.sectionTabs.onSelected = { index -> binding.sectionPager.setCurrentItem(index, true) }

        val callback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                binding.sectionTabs.setSelectedIndex(position)
            }
        }
        pageChangeCallback = callback
        binding.sectionPager.registerOnPageChangeCallback(callback)
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
        pageChangeCallback?.let { binding.sectionPager.unregisterOnPageChangeCallback(it) }
        pageChangeCallback = null
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
