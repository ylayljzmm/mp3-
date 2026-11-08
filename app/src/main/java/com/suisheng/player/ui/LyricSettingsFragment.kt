package com.suisheng.player.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.suisheng.player.ClusterLyricService
import com.suisheng.player.LyricsOverlayService
import com.suisheng.player.R
import com.suisheng.player.data.SettingsRepository
import com.suisheng.player.databinding.FragmentLyricSettingsBinding
import com.suisheng.player.databinding.ItemLyricProviderBinding
import com.suisheng.player.lrc.LyricProviders
import com.suisheng.player.model.LyricFontFamily
import com.suisheng.player.model.LyricThemePreset

/**
 * 歌词设置（设置页的下一级页面）。
 *
 * 这个类存在的唯一理由是**防崩溃**：把 20+ 个歌词控件从设置页里搬出来之后，
 * 每个 Slider 的赋值都再过一道 [SliderBinding] 的 clamp，存量的越界值
 * （旧版本把上限调到过 60、后来收到 56 这种情况）不会再让整页 `IllegalStateException`。
 *
 * 布局 `fragment_lyric_settings.xml` 里的 id 与旧设置页完全一致，
 * 绑定逻辑是从 `SettingsFragment` 原样搬过来的 —— 行为一字不改，只是换了容器。
 */
class LyricSettingsFragment : Fragment() {

    private var _binding: FragmentLyricSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var settings: SettingsRepository

    /**
     * 系统「悬浮窗权限」页的回调。
     *
     * 车机上点「去授权」会跳到系统设置再回来，页面完全可能在回来之前被回收，
     * 所以这里**不能**用 `_binding!!`：拿不到视图就整段跳过，重建时 onViewCreated 会重读设置。
     */
    private val overlayPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val views = _binding ?: return@registerForActivityResult
        val ctx = context ?: return@registerForActivityResult
        views.switchOverlay.isChecked = settings.overlayEnabled && Settings.canDrawOverlays(ctx)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentLyricSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings = SettingsRepository(requireContext())
        bindPlayerLyricCard()
        bindStyleCard()
        bindOverlayCard()
        bindLayoutCard()
        bindProviderCard()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    // ------------------------------------------------------------ 卡片 1

    private fun bindPlayerLyricCard() {
        binding.switchSmoothLyrics.isChecked = settings.smoothLyrics
        binding.switchSmoothLyrics.setOnCheckedChangeListener { _, checked ->
            settings.smoothLyrics = checked
        }

        // 存量的字号 / 行距 / 缩放全部可能是越界值，一律走 SliderBinding：
        // 先 clamp 再赋，越界只是「滑块停在边界」，不会抛 IllegalStateException。
        SliderBinding.bind(binding.playerFontSizeSlider, settings.playerFontSizeSp) { value ->
            settings.playerFontSizeSp = value
            notifyLyricStyleChanged()
        }
        SliderBinding.bind(binding.playerNextFontSizeSlider, settings.playerNextFontSizeSp) { value ->
            settings.playerNextFontSizeSp = value
            notifyLyricStyleChanged()
        }
        SliderBinding.bind(binding.playerLineSpacingSlider, settings.playerLyricLineSpacing) { value ->
            settings.playerLyricLineSpacing = value
            notifyLyricStyleChanged()
        }
        SliderBinding.bind(binding.maxLyricLinesSlider, settings.maxLyricVisualLines.toFloat()) { value ->
            settings.maxLyricVisualLines = value.toInt()
            notifyLyricStyleChanged()
        }
        SliderBinding.bind(binding.currentLineScaleSlider, settings.currentLineScale) { value ->
            settings.currentLineScale = value
            notifyLyricStyleChanged()
        }
        SliderBinding.bind(binding.nextLineScaleSlider, settings.nextLineScale) { value ->
            settings.nextLineScale = value
            notifyLyricStyleChanged()
        }
    }

    // ------------------------------------------------------------ 卡片 2

    private fun bindStyleCard() {
        setupLyricThemeChips()
        setupLyricFontChips()
    }

    /**
     * 歌词配色预设。
     *
     * 切换预设会**连带改写三处字号**（预设自带 current / next / overlay 字号），
     * 所以切完之后要把三个滑块重新对齐一次 —— 用的必须是
     * [SliderBinding.setValueSafely] 而不是 [SliderBinding.bind]，
     * 否则每切一次配色就多挂一个监听。
     */
    private fun setupLyricThemeChips() {
        val group = binding.lyricThemeGroup
        group.removeAllViews()
        val current = settings.lyricTheme()
        LyricThemePreset.entries.forEach { preset ->
            val chip = Chip(requireContext()).apply {
                // ChipGroup 的 singleSelection 用 View.getId() 当选中键。不显式赋 id
                // 的话所有 chip 都是 NO_ID，等于同一个键 —— 会出现「点一个、亮好几个」。
                id = View.generateViewId()
                text = preset.displayName
                isCheckable = true
                isChecked = preset == current
                setOnClickListener { clicked ->
                    settings.setLyricTheme(preset)
                    SliderBinding.setValueSafely(binding.playerFontSizeSlider, settings.playerFontSizeSp)
                    SliderBinding.setValueSafely(
                        binding.playerNextFontSizeSlider,
                        settings.playerNextFontSizeSp,
                    )
                    SliderBinding.setValueSafely(binding.fontSizeSlider, settings.fontSizeSp)
                    syncChipSelection(group, clicked.id)
                    notifyLyricStyleChanged()
                }
            }
            group.addView(chip)
        }
    }

    /** 歌词字体。换字体要先把新字型预载进来，预载完成后才通知页面重排。 */
    private fun setupLyricFontChips() {
        val group = binding.lyricFontGroup
        group.removeAllViews()
        val current = settings.lyricFontFamily()
        LyricFontFamily.entries.forEach { family ->
            val chip = Chip(requireContext()).apply {
                // 同上：没有 id 的 chip 在 ChipGroup 里会被当成同一个
                id = View.generateViewId()
                text = family.displayName
                isCheckable = true
                isChecked = family == current
                setOnClickListener { clicked ->
                    settings.setLyricFontFamily(family)
                    LyricFontProvider.invalidate()
                    LyricFontProvider.preload(requireContext(), family) {
                        activity?.runOnUiThread { notifyLyricStyleChanged() }
                    }
                    syncChipSelection(group, clicked.id)
                    notifyLyricStyleChanged()
                }
            }
            group.addView(chip)
        }
    }

    /**
     * 按 id 手动对齐选中态。
     *
     * 不相信 `ChipGroup` 的内部记账：`singleSelection` 在「点击已选中的 chip」和
     * 「代码改 `isChecked`」两条路径上行为并不一致，重算一遍是最省心的写法，
     * 反正 chip 只有个位数。
     */
    private fun syncChipSelection(group: ChipGroup, checkedId: Int) {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i) as? Chip ?: continue
            child.isChecked = child.id == checkedId
        }
    }

    // ------------------------------------------------------------ 卡片 3

    private fun bindOverlayCard() {
        binding.switchOverlay.isChecked = settings.overlayEnabled
        binding.switchOverlay.setOnCheckedChangeListener { _, checked ->
            if (checked && !Settings.canDrawOverlays(requireContext())) {
                // 没有悬浮窗权限时不要把开关留在「已开」：服务起不来，
                // 开关却亮着，用户会以为功能坏了。回滚开关并引导去授权。
                binding.switchOverlay.isChecked = false
                requestOverlay()
                return@setOnCheckedChangeListener
            }
            settings.overlayEnabled = checked
            if (checked) {
                LyricsOverlayService.start(requireContext())
            } else {
                LyricsOverlayService.stop(requireContext())
            }
        }
        binding.btnGrantOverlay.setOnClickListener { requestOverlay() }

        SliderBinding.bind(binding.fontSizeSlider, settings.fontSizeSp) { value ->
            settings.fontSizeSp = value
            LyricsOverlayService.refresh(requireContext())
        }

        binding.switchOverlayBold.isChecked = settings.overlayLyricBold
        binding.switchOverlayBold.setOnCheckedChangeListener { _, checked ->
            settings.overlayLyricBold = checked
            notifyLyricStyleChanged()
        }
        binding.switchOverlayStroke.isChecked = settings.overlayStrokeEnabled
        binding.switchOverlayStroke.setOnCheckedChangeListener { _, checked ->
            settings.overlayStrokeEnabled = checked
            notifyLyricStyleChanged()
        }
        SliderBinding.bind(
            binding.overlayStrokeWidthSlider,
            settings.overlayStrokeWidth.toFloat(),
        ) { value ->
            settings.overlayStrokeWidth = value.toInt()
            notifyLyricStyleChanged()
        }

        when (settings.overlayPosition) {
            SettingsRepository.POSITION_TOP -> binding.posTop.isChecked = true
            SettingsRepository.POSITION_BOTTOM -> binding.posBottom.isChecked = true
            else -> binding.posCenter.isChecked = true
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

    // ------------------------------------------------------------ 卡片 4

    /**
     * 歌词版面：左右栏间距。
     *
     * 这个值只作用于横屏双栏右栏歌词的 `paddingStart`（中轴右边界到歌词文本起点那段空白），
     * `paddingEnd` 与上下边界不吃它；竖屏是三页 deck、没有右栏，写进来的值不会改到竖屏歌词页。
     *
     * 存量值同样可能越界（旧版本没有这一项，默认 8 恒在 0~32 内），仍统一走 [SliderBinding]。
     */
    private fun bindLayoutCard() {
        updatePaneGapLabel(settings.lyricPaneGapDp)
        SliderBinding.bind(binding.lyricPaneGapSlider, settings.lyricPaneGapDp.toFloat()) { value ->
            settings.lyricPaneGapDp = value.toInt()
            updatePaneGapLabel(settings.lyricPaneGapDp)
            notifyLyricStyleChanged()
        }
    }

    /** 标题后面挂当前值：不显示数值的话，用户没法判断这首歌到底还剩多少间距。 */
    private fun updatePaneGapLabel(gapDp: Int) {
        binding.lyricPaneGapLabel.text = getString(R.string.lyric_pane_gap) + " · " +
            getString(R.string.lyric_pane_gap_value, gapDp)
    }

    // ------------------------------------------------------------ 卡片 5

    /**
     * 联网歌词渠道：顺序 + 开关。
     *
     * 行是**按注册表动态生成**的，不写死在 XML 里 —— 渠道集合来自 [LyricProviders]，
     * 写死就等于把注册表抄了一遍，以后加渠道必然漏改一处。
     */
    private fun bindProviderCard() {
        renderProviderRows()
        binding.lyricProviderReset.setOnClickListener {
            settings.saveLyricProviderConfig(settings.lyricProviderConfig().reset())
            renderProviderRows()
        }
    }

    private fun renderProviderRows() {
        val views = _binding ?: return
        val container = views.lyricProviderList
        container.removeAllViews()
        val config = settings.lyricProviderConfig()
        config.order.forEach { id ->
            val row = ItemLyricProviderBinding.inflate(layoutInflater, container, false)
            row.providerSwitch.text = providerLabelText(id)
            // 先赋初值再挂监听：否则绑定监听时会被当成一次用户操作，把设置写脏。
            row.providerSwitch.isChecked = config.isEnabled(id)
            row.providerSwitch.setOnCheckedChangeListener { _, checked ->
                val current = settings.lyricProviderConfig()
                val next = current.withEnabled(id, checked)
                if (next == current) {
                    // 被拒绝（例如想把最后一个开着的渠道也关掉）：整段重建，开关回到原位。
                    scheduleProviderRender()
                } else {
                    settings.saveLyricProviderConfig(next)
                }
            }
            row.providerUp.setOnClickListener { moveProvider(id, up = true) }
            row.providerDown.setOnClickListener { moveProvider(id, up = false) }
            container.addView(row.root)
        }
    }

    /**
     * 延到下一帧再重建。
     *
     * 直接在开关 / 按钮自己的回调里 `removeAllViews()` 会把正在派发点击事件的 View 抽掉，
     * 部分 ROM 上表现为 `NullPointerException` 或点完没反应。
     */
    private fun scheduleProviderRender() {
        val container = _binding?.lyricProviderList ?: return
        container.post {
            if (_binding != null && isAdded) renderProviderRows()
        }
    }

    private fun moveProvider(id: String, up: Boolean) {
        val current = settings.lyricProviderConfig()
        val next = current.moved(id, up)
        if (next != current) settings.saveLyricProviderConfig(next)
        scheduleProviderRender()
    }

    /** 纯文本渠道（拿不到时间轴）在开关文案后加一句警告，避免用户以为是自己调错了。 */
    private fun providerLabelText(id: String): String {
        val label = LyricAdjustPanel.providerLabel(requireContext(), id)
        return if (id in LyricProviders.defaultDisabled) {
            label + " · " + getString(R.string.lyric_provider_plain_warning)
        } else {
            label
        }
    }

    // ------------------------------------------------------------ 公共

    /**
     * 歌词样式变了：桌面悬浮歌词 / 车机副屏 / 播放页三处都要重排。
     *
     * 这里必须用可空的 `context` 而不是 `requireContext()`：本方法会被
     * [LyricFontProvider.preload] 的异步回调重新投递到 UI 线程，用户改完字体
     * 立刻退出页面时 Fragment 已经 detach，`requireContext()` 会抛
     * `IllegalStateException` —— 正是这次版本要消灭的那类闪退。
     * 拿不到 Context 说明界面已经没了，样式通知也就没有意义，直接返回。
     */
    private fun notifyLyricStyleChanged() {
        val ctx = context ?: return
        LyricsOverlayService.refresh(ctx)
        ClusterLyricService.refresh(ctx)
        (activity as? MainHost)?.notifyLyricStyleChanged()
    }

    private fun requestOverlay() {
        // 同上：授权回调链路里也可能在页面销毁后才被调到
        val ctx = context ?: return
        overlayPermission.launch(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${ctx.packageName}"),
            )
        )
    }
}
