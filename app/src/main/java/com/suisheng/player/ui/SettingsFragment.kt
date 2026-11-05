package com.suisheng.player.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.children
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.suisheng.player.BuildConfig
import com.suisheng.player.ClusterLyricService
import com.suisheng.player.LyricsOverlayService
import com.suisheng.player.R
import com.suisheng.player.data.ScanPathHelper
import com.suisheng.player.data.SettingsRepository
import com.suisheng.player.databinding.FragmentSettingsBinding
import com.suisheng.player.model.LyricFontFamily
import com.suisheng.player.model.LyricThemePreset
import com.suisheng.player.update.AppUpdateInfo
import com.suisheng.player.update.AppUpdateManager
import com.suisheng.player.update.InstallRequest
import com.google.android.material.chip.Chip
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import com.google.android.material.color.MaterialColors

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var settings: SettingsRepository
    private var switchBootOpenApp: SwitchMaterial? = null
    private var switchBootReturnHome: SwitchMaterial? = null
    private var switchOverlayBold: SwitchMaterial? = null
    private var switchOverlayStroke: SwitchMaterial? = null
    private var overlayStrokeWidthSlider: Slider? = null
    private lateinit var updateManager: AppUpdateManager
    private var pendingUpdateApk: File? = null
    private var availableUpdate: AppUpdateInfo? = null

    private val overlayPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        binding.switchOverlay.isChecked = settings.overlayEnabled && Settings.canDrawOverlays(requireContext())
    }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        runCatching {
            requireContext().contentResolver.takePersistableUriPermission(uri, flags)
        }
        settings.addScanTreeUri(uri.toString())
        refreshScanPathsUi()
        Toast.makeText(requireContext(), R.string.folder_added, Toast.LENGTH_SHORT).show()
    }

    private val installPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        pendingUpdateApk?.let { apk ->
            runCatching { updateManager.requestInstall(apk) }
                .onSuccess { handleInstallRequest(it, canOpenPermissionSettings = false) }
                .onFailure { showUpdateError(it) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = SettingsRepository(requireContext())
        updateManager = AppUpdateManager(requireContext().applicationContext)
        binding.updateStatus.text = getString(R.string.settings_update_current, BuildConfig.VERSION_NAME)
        // Long-press the version line to send the last crash report. This is the only
        // way to get a stack trace off a head unit without a PC attached.
        binding.updateStatus.setOnLongClickListener {
            shareCrashReport()
            true
        }
        binding.btnCheckUpdate.setOnClickListener {
            val update = availableUpdate
            if (update == null) checkForUpdate() else downloadAndInstall(update)
        }
        binding.switchAutoResume.isChecked = settings.autoResumePlayback
        binding.switchBootAutoStart.isChecked = settings.bootAutoStart
        setupBootExtraSwitches()
        binding.switchClusterLyrics.isChecked = settings.clusterLyricsEnabled
        binding.switchOverlay.isChecked = settings.overlayEnabled
        binding.switchOnlineLyrics.isChecked = settings.onlineLyricsEnabled
        binding.switchOnlineCover.isChecked = settings.onlineCoverEnabled
        binding.switchSmoothLyrics.isChecked = settings.smoothLyrics
        binding.playerFontSizeSlider.value = settings.playerFontSizeSp
        binding.playerNextFontSizeSlider.value = settings.playerNextFontSizeSp
        binding.playerLineSpacingSlider.value = settings.playerLyricLineSpacing
        binding.fontSizeSlider.value = settings.fontSizeSp
        binding.currentLineScaleSlider.value = settings.currentLineScale
        binding.nextLineScaleSlider.value = settings.nextLineScale
        binding.maxLyricLinesSlider.value = settings.maxLyricVisualLines.toFloat()

        when (settings.overlayPosition) {
            SettingsRepository.POSITION_TOP -> binding.posTop.isChecked = true
            SettingsRepository.POSITION_BOTTOM -> binding.posBottom.isChecked = true
            else -> binding.posCenter.isChecked = true
        }

        setupLyricThemeChips()
        setupLyricFontChips()
        refreshScanPathsUi()
        setupOnlineSettings()

        binding.btnPickFolder.setOnClickListener { pickFolder.launch(null) }
        binding.btnAddMusic.setOnClickListener { addPresetPath("内置 Music") }
        binding.btnAddDownload.setOnClickListener { addPresetPath("下载目录") }

        binding.btnScan.setOnClickListener {
            binding.btnScan.isEnabled = false
            binding.btnScan.text = getString(R.string.scanning)
            (activity as? MainHost)?.scanMusic { musicCount ->
                binding.btnScan.isEnabled = true
                binding.btnScan.text = getString(R.string.settings_scan)
                Toast.makeText(
                    requireContext(),
                    getString(R.string.scan_done, musicCount),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        binding.switchAutoResume.setOnCheckedChangeListener { _, checked ->
            settings.autoResumePlayback = checked
        }
        binding.switchBootAutoStart.setOnCheckedChangeListener { _, checked ->
            settings.bootAutoStart = checked
        }
        binding.switchClusterLyrics.setOnCheckedChangeListener { _, checked ->
            settings.clusterLyricsEnabled = checked
            if (checked) ClusterLyricService.start(requireContext()) else ClusterLyricService.stop(requireContext())
        }
        binding.switchOnlineLyrics.setOnCheckedChangeListener { _, checked ->
            settings.onlineLyricsEnabled = checked
        }
        binding.switchOnlineCover.setOnCheckedChangeListener { _, checked ->
            settings.onlineCoverEnabled = checked
        }
        binding.switchSmoothLyrics.setOnCheckedChangeListener { _, checked ->
            settings.smoothLyrics = checked
        }

        binding.rowThemeEntry.setOnClickListener {
            ThemeFragment().show(parentFragmentManager, ThemeFragment.TAG)
        }

        binding.switchOverlay.setOnCheckedChangeListener { _, checked ->
            if (checked && !Settings.canDrawOverlays(requireContext())) {
                binding.switchOverlay.isChecked = false
                requestOverlay()
                return@setOnCheckedChangeListener
            }
            settings.overlayEnabled = checked
            if (checked) LyricsOverlayService.start(requireContext()) else LyricsOverlayService.stop(requireContext())
        }

        binding.btnGrantOverlay.setOnClickListener { requestOverlay() }

        binding.playerFontSizeSlider.addOnChangeListener { _, value, _ ->
            settings.playerFontSizeSp = value
            notifyLyricStyleChanged()
        }
        binding.playerNextFontSizeSlider.addOnChangeListener { _, value, _ ->
            settings.playerNextFontSizeSp = value
            notifyLyricStyleChanged()
        }
        binding.playerLineSpacingSlider.addOnChangeListener { _, value, _ ->
            settings.playerLyricLineSpacing = value
            notifyLyricStyleChanged()
        }
        binding.fontSizeSlider.addOnChangeListener { _, value, _ ->
            settings.fontSizeSp = value
            LyricsOverlayService.refresh(requireContext())
        }
        setupOverlayBoldSwitch()
        binding.currentLineScaleSlider.addOnChangeListener { _, value, _ ->
            settings.currentLineScale = value
            notifyLyricStyleChanged()
        }
        binding.nextLineScaleSlider.addOnChangeListener { _, value, _ ->
            settings.nextLineScale = value
            notifyLyricStyleChanged()
        }
        binding.maxLyricLinesSlider.addOnChangeListener { _, value, _ ->
            settings.maxLyricVisualLines = value.toInt()
            notifyLyricStyleChanged()
        }

        binding.positionGroup.setOnCheckedChangeListener { _, checkedId ->
            settings.overlayPosition = when (checkedId) {
                R.id.posTop -> SettingsRepository.POSITION_TOP
                R.id.posBottom -> SettingsRepository.POSITION_BOTTOM
                else -> SettingsRepository.POSITION_CENTER
            }
            LyricsOverlayService.refresh(requireContext())
        }

    }

    private fun checkForUpdate() {
        binding.btnCheckUpdate.isEnabled = false
        binding.btnCheckUpdate.text = getString(R.string.settings_update_checking)
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { updateManager.check() }
                .onSuccess { info ->
                    if (info.isNewer) {
                        availableUpdate = info
                        binding.updateStatus.text = getString(R.string.settings_update_ready, info.version)
                        binding.btnCheckUpdate.text = getString(R.string.settings_update_install)
                    } else {
                        availableUpdate = null
                        binding.updateStatus.text = getString(R.string.settings_update_latest)
                        binding.btnCheckUpdate.text = getString(R.string.settings_update_check)
                    }
                }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    binding.updateStatus.text = getString(
                        R.string.settings_update_failed,
                        error.message ?: "网络不可用",
                    )
                    binding.btnCheckUpdate.text = getString(R.string.settings_update_check)
                }
            binding.btnCheckUpdate.isEnabled = true
        }
    }

    private fun downloadAndInstall(info: AppUpdateInfo) {
        binding.btnCheckUpdate.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                val apk = updateManager.download(info) { progress ->
                    activity?.runOnUiThread {
                        _binding?.btnCheckUpdate?.text =
                            getString(R.string.settings_update_downloading, progress)
                    }
                }
                pendingUpdateApk = apk
                updateManager.requestInstall(apk)
            }.onSuccess { request ->
                handleInstallRequest(request)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                showUpdateError(error)
            }
            binding.btnCheckUpdate.isEnabled = true
            binding.btnCheckUpdate.text = if (availableUpdate != null) {
                getString(R.string.settings_update_install)
            } else {
                getString(R.string.settings_update_check)
            }
        }
    }

    private fun handleInstallRequest(
        request: InstallRequest,
        canOpenPermissionSettings: Boolean = true,
    ) {
        when (request) {
            InstallRequest.Launched -> pendingUpdateApk = null
            is InstallRequest.PermissionRequired -> {
                Toast.makeText(
                    requireContext(),
                    R.string.settings_update_permission,
                    Toast.LENGTH_LONG,
                ).show()
                if (canOpenPermissionSettings) {
                    installPermission.launch(request.intent)
                }
            }
        }
    }

    private fun showUpdateError(error: Throwable) {
        val message = getString(
            R.string.settings_update_failed,
            error.message ?: "系统安装器不可用",
        )
        _binding?.updateStatus?.text = message
        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
    }

    private fun setupBootExtraSwitches() {
        val parent = binding.switchBootAutoStart.parent as? LinearLayout ?: return
        switchBootOpenApp?.let { parent.removeView(it) }
        switchBootReturnHome?.let { parent.removeView(it) }

        val openApp = SwitchMaterial(requireContext()).apply {
            text = getString(R.string.boot_open_app)
            isChecked = settings.bootOpenApp
            setOnCheckedChangeListener { _, checked -> settings.bootOpenApp = checked }
        }
        val returnHome = SwitchMaterial(requireContext()).apply {
            text = getString(R.string.boot_return_home)
            isChecked = settings.bootReturnHome
            setOnCheckedChangeListener { _, checked -> settings.bootReturnHome = checked }
        }
        val insertAt = parent.indexOfChild(binding.switchBootAutoStart) + 1
        parent.addView(openApp, insertAt)
        parent.addView(returnHome, insertAt + 1)
        switchBootOpenApp = openApp
        switchBootReturnHome = returnHome
    }

    private fun setupOverlayBoldSwitch() {
        val parent = binding.fontSizeSlider.parent as? LinearLayout ?: return
        switchOverlayBold?.let { parent.removeView(it) }
        switchOverlayStroke?.let { parent.removeView(it) }
        overlayStrokeWidthSlider?.let { parent.removeView(it) }
        parent.children.toList().filter { it.tag == "overlay_stroke_width_label" }.forEach { parent.removeView(it) }
        val boldSwitch = SwitchMaterial(requireContext()).apply {
            text = getString(R.string.overlay_lyric_bold)
            isChecked = settings.overlayLyricBold
            setOnCheckedChangeListener { _, checked ->
                settings.overlayLyricBold = checked
                notifyLyricStyleChanged()
                LyricsOverlayService.refresh(requireContext())
            }
        }
        val insertAt = parent.indexOfChild(binding.fontSizeSlider) + 1
        parent.addView(boldSwitch, insertAt)
        switchOverlayBold = boldSwitch
        val strokeSwitch = SwitchMaterial(requireContext()).apply {
            text = getString(R.string.overlay_lyric_stroke)
            isChecked = settings.overlayStrokeEnabled
            setOnCheckedChangeListener { _, checked ->
                settings.overlayStrokeEnabled = checked
                notifyLyricStyleChanged()
                LyricsOverlayService.refresh(requireContext())
            }
        }
        parent.addView(strokeSwitch, insertAt + 1)
        switchOverlayStroke = strokeSwitch
        val strokeWidthLabel = TextView(requireContext()).apply {
            tag = "overlay_stroke_width_label"
            text = getString(R.string.overlay_stroke_width)
            setTextColor(MaterialColors.getColor(requireContext(), com.google.android.material.R.attr.colorOnSurface, android.graphics.Color.BLACK))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * resources.displayMetrics.density).toInt() }
        }
        parent.addView(strokeWidthLabel, insertAt + 2)
        val strokeWidthSlider = Slider(requireContext()).apply {
            valueFrom = 1f
            valueTo = 10f
            stepSize = 1f
            value = settings.overlayStrokeWidth.toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addOnChangeListener { _, value, _ ->
                settings.overlayStrokeWidth = value.toInt()
                notifyLyricStyleChanged()
                LyricsOverlayService.refresh(requireContext())
            }
        }
        parent.addView(strokeWidthSlider, insertAt + 3)
        overlayStrokeWidthSlider = strokeWidthSlider
    }

    private fun setupLyricThemeChips() {
        binding.lyricThemeGroup.removeAllViews()
        val current = settings.lyricTheme()
        LyricThemePreset.entries.forEach { preset ->
            val chip = Chip(requireContext()).apply {
                text = preset.displayName
                isCheckable = true
                isChecked = preset == current
                setOnClickListener {
                    settings.setLyricTheme(preset)
                    binding.playerFontSizeSlider.value = settings.playerFontSizeSp
                    binding.playerNextFontSizeSlider.value = settings.playerNextFontSizeSp
                    binding.fontSizeSlider.value = settings.fontSizeSp
                    notifyLyricStyleChanged()
                }
            }
            binding.lyricThemeGroup.addView(chip)
        }
    }

    private fun setupLyricFontChips() {
        binding.lyricFontGroup.removeAllViews()
        val current = settings.lyricFontFamily()
        LyricFontFamily.entries.forEach { family ->
            val chip = Chip(requireContext()).apply {
                text = family.displayName
                isCheckable = true
                isChecked = family == current
                setOnClickListener {
                    settings.setLyricFontFamily(family)
                    LyricFontProvider.invalidate()
                    LyricFontProvider.preload(requireContext(), family) {
                        activity?.runOnUiThread { notifyLyricStyleChanged() }
                    }
                    notifyLyricStyleChanged()
                }
            }
            binding.lyricFontGroup.addView(chip)
        }
    }

    private fun notifyLyricStyleChanged() {
        LyricsOverlayService.refresh(requireContext())
        ClusterLyricService.refresh(requireContext())
        (activity as? MainHost)?.notifyLyricStyleChanged()
    }

    /** Shares the most recent crash report as plain text (no file, no extra permission). */
    private fun shareCrashReport() {
        val report = com.suisheng.player.util.CrashLogger.readLatest(requireContext())
        if (report.isNullOrBlank()) {
            Toast.makeText(requireContext(), "没有崩溃记录", Toast.LENGTH_SHORT).show()
            return
        }
        val ok = runCatching {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "随声 崩溃日志 v${BuildConfig.VERSION_NAME}")
                        putExtra(Intent.EXTRA_TEXT, report)
                    },
                    "发送崩溃日志"
                )
            )
        }.isSuccess
        if (!ok) {
            Toast.makeText(requireContext(), "没有可用的发送方式", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupOnlineSettings() {
        val container = binding.onlineSettingsContainer
        container.removeAllViews()
        val title = TextView(requireContext()).apply {
            text = getString(R.string.settings_online)
            setTextColor(MaterialColors.getColor(requireContext(), com.google.android.material.R.attr.colorOnSurface, android.graphics.Color.BLACK))
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        container.addView(title)

        fun addField(hint: String, value: String, onSave: (String) -> Unit): TextInputEditText {
            val inputLayout = TextInputLayout(requireContext()).apply {
                this.hint = hint
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 12 }
            }
            val edit = TextInputEditText(requireContext()).apply {
                setText(value)
                setTextColor(MaterialColors.getColor(requireContext(), com.google.android.material.R.attr.colorOnSurface, android.graphics.Color.BLACK))
                setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) onSave(text?.toString().orEmpty())
                }
            }
            inputLayout.addView(edit)
            container.addView(inputLayout)
            return edit
        }

        addField(getString(R.string.online_api_primary), settings.onlineMusicApiUrl) {
            settings.onlineMusicApiUrl = it
        }
        addField(getString(R.string.online_api_backup), settings.onlineMusicApiBackup) {
            settings.onlineMusicApiBackup = it
        }
        addField(getString(R.string.radio_api_url), settings.radioBrowserApiUrl) {
            settings.radioBrowserApiUrl = it
        }
        addField(getString(R.string.podcast_rss_urls), settings.podcastRssText) {
            settings.podcastRssText = it
        }

        val skipVip = SwitchMaterial(requireContext()).apply {
            text = getString(R.string.skip_vip_songs)
            isChecked = settings.skipUnplayableVip
            setOnCheckedChangeListener { _, checked -> settings.skipUnplayableVip = checked }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
        }
        container.addView(skipVip)

        val podcastDesc = SwitchMaterial(requireContext()).apply {
            text = getString(R.string.podcast_show_desc)
            isChecked = settings.podcastShowDescription
            setOnCheckedChangeListener { _, checked -> settings.podcastShowDescription = checked }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
        }
        container.addView(podcastDesc)
    }

    private fun addPresetPath(label: String) {
        val path = ScanPathHelper.presetPaths().firstOrNull { it.first == label }?.second ?: return
        settings.addScanPath(path)
        refreshScanPathsUi()
        Toast.makeText(requireContext(), getString(R.string.folder_added), Toast.LENGTH_SHORT).show()
    }

    private fun refreshScanPathsUi() {
        binding.scanPathsChipGroup.removeAllViews()
        val entries = settings.allScanEntries()
        if (entries.isEmpty()) {
            binding.scanPathsEmpty.visibility = View.VISIBLE
        } else {
            binding.scanPathsEmpty.visibility = View.GONE
            entries.forEach { entry ->
                val chip = Chip(requireContext()).apply {
                    text = ScanPathHelper.displayName(requireContext(), entry)
                    isCloseIconVisible = true
                    setOnCloseIconClickListener {
                        settings.removeScanEntry(entry)
                        refreshScanPathsUi()
                    }
                }
                binding.scanPathsChipGroup.addView(chip)
            }
        }
    }

    private fun requestOverlay() {
        overlayPermission.launch(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${requireContext().packageName}"))
        )
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
