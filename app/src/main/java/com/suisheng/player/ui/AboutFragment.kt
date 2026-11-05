package com.suisheng.player.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.suisheng.player.BuildConfig
import com.suisheng.player.R
import com.suisheng.player.databinding.FragmentAboutBinding

/**
 * 关于页（`design/04 §3.5` 第 8 条）：版本 / 下载地址 / U 盘安装说明 / 开源许可。
 *
 * 版本号一律读 [BuildConfig]，页面上不出现任何硬编码的版本字符串 ——
 * 每次发版只要改 `build.gradle.kts` 的两行，这里自动跟上。
 */
class AboutFragment : Fragment() {

    private var _binding: FragmentAboutBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAboutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.aboutVersion.text = getString(
            R.string.about_version_value,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
        )
        binding.aboutDownload.setOnClickListener { openDownloadPage() }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun openDownloadPage() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL))
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // 车机常常没有浏览器：给出可以照着敲的地址，而不是静默失败
            Toast.makeText(requireContext(), R.string.about_no_browser, Toast.LENGTH_LONG).show()
        }
    }

    private companion object {
        const val DOWNLOAD_URL = "https://github.com/ylayljzmm/mp3-/releases"
    }
}
