package com.suisheng.player.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.color.MaterialColors
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.suisheng.player.BuildConfig
import com.suisheng.player.ClusterLyricService
import com.suisheng.player.R
import com.suisheng.player.data.HiddenFolderStore
import com.suisheng.player.data.ScanPathHelper
import com.suisheng.player.data.SettingsRepository
import com.suisheng.player.databinding.FragmentSettingsBinding
import com.suisheng.player.update.AppUpdateInfo
import com.suisheng.player.update.AppUpdateManager
import com.suisheng.player.update.InstallRequest
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 设置页。
 *
 * 5.6.0 起**不再包含任何歌词控件**：配色 / 字体 / 播放页字号 / 悬浮歌词的 20+ 个设置项
 * 整体搬到独立子页 [LyricSettingsFragment]（设置页只留一行入口）。
 * 原因是原来那块「原地展开」的折叠区在 `onViewCreated` 里一次性给 8 个 Slider 赋存量值，
 * 存量值越出 `valueFrom..valueTo` 会抛 `IllegalStateException` 直接崩页 ——
 * 车机上用户点了「高级歌词设置」就闪退。搬走之后本页只剩开关、按钮与入口行，
 * 不再有 slider，也就没有这一类崩溃面。
 */
class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var settings: SettingsRepository
    private var switchBootOpenApp: SwitchMaterial? = null
    private var switchBootReturnHome: SwitchMaterial? = null
    private lateinit var updateManager: AppUpdateManager
    private var pendingUpdateApk: File? = null
    private var availableUpdate: AppUpdateInfo? = null

    /**
     * 选文件夹（SAF）的回调，同样可能在页面销毁后到达；`requireContext()` 在这个时机
     * 会抛 `IllegalStateException`，因此返回的 Context 也必须走可空分支。
     */
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        val views = _binding ?: return@registerForActivityResult
        val ctx = context ?: return@registerForActivityResult
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(uri, flags)
        }
        settings.addScanTreeUri(uri.toString())
        refreshScanPathsUi(views)
        Toast.makeText(ctx, R.string.folder_added, Toast.LENGTH_SHORT).show()
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
        // 长按版本行发一份崩溃日志：车机上没有电脑，这是唯一的取栈方式。
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
        binding.switchOnlineLyrics.isChecked = settings.onlineLyricsEnabled
        binding.switchOnlineCover.isChecked = settings.onlineCoverEnabled

        refreshScanPathsUi(binding)
        setupOnlineSettings()
        setupHiddenFolders()

        binding.btnPickFolder.setOnClickListener { pickFolder.launch(null) }
        binding.btnAddMusic.setOnClickListener { addPresetPath("内置 Music") }
        binding.btnAddDownload.setOnClickListener { addPresetPath("下载目录") }

        binding.btnScan.setOnClickListener {
            val views = _binding ?: return@setOnClickListener
            views.btnScan.isEnabled = false
            views.btnScan.text = getString(R.string.scanning)
            // 扫描是异步的（有 U 盘时可达数十秒），期间切页 / 返回都会让视图销毁，
            // 所以回调里一律重新取 _binding，拿不到就静默放弃恢复按钮状态。
            (activity as? MainHost)?.scanMusic { musicCount ->
                val current = _binding ?: return@scanMusic
                val ctx = current.root.context
                current.btnScan.isEnabled = true
                current.btnScan.text = ctx.getString(R.string.settings_scan)
                Toast.makeText(
                    ctx,
                    ctx.getString(R.string.scan_done, musicCount),
                    Toast.LENGTH_SHORT,
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

        binding.rowThemeEntry.setOnClickListener {
            // 外观与主题能力集中在主题页；抽屉底部药丸与设置页这里共用同一条导航
            (activity as? MainHost)?.openTheme()
        }
        // 歌词设置是独立子页：这里的点击只负责「去哪儿」，控件全在那一页
        binding.rowAdvancedLyrics.setOnClickListener {
            (activity as? MainHost)?.openLyricSettings()
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
        val views = _binding ?: return
        val path = ScanPathHelper.presetPaths().firstOrNull { it.first == label }?.second ?: return
        settings.addScanPath(path)
        refreshScanPathsUi(views)
        Toast.makeText(views.root.context, R.string.folder_added, Toast.LENGTH_SHORT).show()
    }

    /**
     * 重建「曲库范围」的 chip 列表。
     *
     * 显式收一个 binding 参数而不是用 `_binding!!`：调用方之一是 SAF 回调，
     * 那里已经判过空，把非空引用传下来就不会在函数内部二次踩空。
     */
    private fun refreshScanPathsUi(views: FragmentSettingsBinding) {
        views.scanPathsChipGroup.removeAllViews()
        val entries = settings.allScanEntries()
        val ctx = views.root.context
        if (entries.isEmpty()) {
            views.scanPathsEmpty.visibility = View.VISIBLE
        } else {
            views.scanPathsEmpty.visibility = View.GONE
            entries.forEach { entry ->
                val chip = Chip(ctx).apply {
                    text = ScanPathHelper.displayName(ctx, entry)
                    isCloseIconVisible = true
                    setOnCloseIconClickListener {
                        settings.removeScanEntry(entry)
                        // chip 的 × 是异步回调（动画结束才派发），同样要重新取 binding
                        _binding?.let { refreshScanPathsUi(it) }
                    }
                }
                views.scanPathsChipGroup.addView(chip)
            }
        }
    }

    // ------------------------------------------------------- 已隐藏的文件夹

    /**
     * 隐藏文件夹的恢复入口。
     *
     * 面板用 `BottomSheetDialog` + 代码建行，不新增 XML 布局：这一块的行数随隐藏项变化，
     * 用 XML 只能写死一个空容器再往里塞 view，反而多一层 ViewBinding 改动
     * （与 `SpeedPanel` 的处理方式保持一致）。
     */
    private fun setupHiddenFolders() {
        binding.rowHiddenFolders.setOnClickListener { showHiddenFoldersPanel() }
        refreshHiddenFolderCount()
    }

    /** 入口行右侧的「N 个」；0 个时留空，避免出现「0 个」这种噪音。 */
    private fun refreshHiddenFolderCount() {
        // 走 _binding 而不是非空断言：恢复动作可能在页面已经开始销毁后回调
        val views = _binding ?: return
        val count = HiddenFolderStore(requireContext()).count()
        views.hiddenFolderCount.text =
            if (count > 0) getString(R.string.hidden_folder_count, count) else ""
    }

    private fun showHiddenFoldersPanel() {
        if (!isAdded) return
        val context = requireContext()
        val dialog = BottomSheetDialog(context)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
        }
        // 外面套一层 ScrollView：隐藏项多起来时（比如一整个 U 盘的分类目录），
        // 面板不能顶出屏幕，否则底部的「恢复」反而点不到。
        val scroller = ScrollView(context).apply { addView(container) }
        dialog.setContentView(scroller)
        rebuildHiddenFolderRows(container)
        dialog.show()
    }

    /**
     * 重建面板内容。恢复一个就整块重建，而不是「删掉某一行」：
     * 恢复父目录会连带让子目录条目失效（父目录被移除后，子目录本来就该独立成条），
     * 重新从存储读一次最省心，也不会出现「列表与存储对不上」的中间态。
     */
    private fun rebuildHiddenFolderRows(container: LinearLayout) {
        if (!isAdded) return
        val context = requireContext()
        val store = HiddenFolderStore(context)
        container.removeAllViews()

        container.addView(
            TextView(context).apply {
                text = getString(R.string.hidden_folder_settings_entry)
                textSize = 18f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(
                    MaterialColors.getColor(
                        context,
                        com.google.android.material.R.attr.colorOnSurface,
                        Color.BLACK,
                    )
                )
            }
        )

        val paths = store.paths()
        if (paths.isEmpty()) {
            container.addView(
                TextView(context).apply {
                    text = getString(R.string.hidden_folder_empty)
                    textSize = 15f
                    setPadding(0, dp(12), 0, 0)
                    setTextColor(
                        MaterialColors.getColor(
                            context,
                            com.google.android.material.R.attr.colorOnSurfaceVariant,
                            Color.GRAY,
                        )
                    )
                }
            )
            return
        }

        paths.forEach { path ->
            container.addView(
                buildHiddenFolderRow(context, path) {
                    store.restore(path)
                    // 立刻生效：宿主持有唯一的可见曲库，恢复后无需重扫
                    (activity as? MainHost)?.refreshHiddenFilter()
                    Toast.makeText(context, R.string.hidden_folder_restored, Toast.LENGTH_SHORT).show()
                    refreshHiddenFolderCount()
                    rebuildHiddenFolderRows(container)
                }
            )
        }
    }

    /** 一行 = 目录名 + 完整路径（小字、单行、尾部省略），整行可点即「恢复」。 */
    private fun buildHiddenFolderRow(
        context: Context,
        path: String,
        onRestore: () -> Unit,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(12) }
        // 点击反馈：从主题里取 selectableItemBackground，兼容明暗两套配色
        val outValue = TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)) {
            setBackgroundResource(outValue.resourceId)
        }
        addView(
            buildHiddenFolderLabels(context, path),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(
            TextView(context).apply {
                text = getString(R.string.hidden_folder_restore)
                textSize = 15f
                setPadding(dp(12), dp(10), 0, dp(10))
                setTextColor(
                    MaterialColors.getColor(
                        context,
                        com.google.android.material.R.attr.colorPrimary,
                        Color.BLACK,
                    )
                )
            }
        )
        setOnClickListener { onRestore() }
    }

    private fun buildHiddenFolderLabels(context: Context, path: String): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, dp(10))
            addView(
                TextView(context).apply {
                    text = File(path).name.ifEmpty { path }
                    textSize = 16f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(
                        MaterialColors.getColor(
                            context,
                            com.google.android.material.R.attr.colorOnSurface,
                            Color.BLACK,
                        )
                    )
                }
            )
            addView(
                TextView(context).apply {
                    text = path
                    textSize = 12f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(
                        MaterialColors.getColor(
                            context,
                            com.google.android.material.R.attr.colorOnSurfaceVariant,
                            Color.GRAY,
                        )
                    )
                }
            )
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
