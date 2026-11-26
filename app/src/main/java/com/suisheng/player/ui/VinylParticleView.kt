package com.suisheng.player.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Choreographer
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * 黑胶上浮的粒子（音符 + 光点）。
 *
 * ## 对应 QQ 的什么（REA 逆向的结论，不是猜的）
 *
 * QQ 的粒子是 **Filament 点精灵**：`libparticle-android-jni.so` 里只有 5 个导出函数，
 * `nCreate` 用 `filamat::MaterialBuilder` 运行时编译材质，所以 GLSL 明文躺在字节里。
 * 片元着色器全文只有四件事：
 *  - `if (lifeCycleProgress >= 1.0) discard;`
 *  - 按 `variable_custom0.z` 绕 (0.5, 0.5) 旋转 UV；
 *  - 按 `variable_custom0.w` 索引 sprite sheet；
 *  - `material.baseColor = textureColor * color * textureColor.a;`
 *
 * **没有噪声、没有菲涅尔、没有流光、没有拖尾。** 观感全部来自
 * 「贴图形状 + color1→color2 沿寿命插值 + 尺寸渐变 + 加法混合」。
 * 既然逻辑就这么点，用 Canvas 完全可以复刻，不需要拉 GPU 管线进来。
 *
 * ## 参数映射（QQ → 我们）
 *
 * | QQ (`flare2.json` / `.so`) | 我们 | 为什么 |
 * |---|---|---|
 * | `capacity 200` | **48**（低端 24） | 200 个精灵在车机 GPU 上会掉帧；48 个已够铺满视野 |
 * | `emitRate 10/s` | **6/s** | 手机上 10/s 略显"喷"，6/s 更像"飘" |
 * | `minLifeTime 0.5` / `maxLifeTime 5.0` | **1.2 / 3.4s** | 5 秒的粒子会飘出视野再消失，浪费 |
 * | `color1 (1,0.5,0)` → `color2 (1,1,0)` | 同 | 橙→黄，实测就是这个 |
 * | `gravity (0,-1,0)` | **+Y 减速上浮** | QQ 是向下坠（火花）；黑胶场景要向上飘（音符） |
 * | `minSize 0.05` / `maxSize 0.5` | **2 / 7dp** | 换算到手机尺寸 |
 * | `ALPHA_ADD` 混合 | `PorterDuff.Mode.ADD` | 加法混合，亮部叠加才会发光 |
 * | 三张贴图 | **程序化绘制** | `particle_1` 是红色八分音符、`particle_2` 橙圆点、`particle_3` 小红音符；版权原因不打包，改成自己画 |
 *
 * ## 为什么粒子撒在盘沿外侧
 *
 * 第一版按 `rad ∈ [0.05R, 1.02R]` 均匀撒点，粒子盖满封面，看起来"很脏"。
 * 改成 `rad ∈ [0.58R, 1.30R]`：主要落在盘沿与盘外，只有少量靠近封面 ——
 * 这样粒子读起来是「盘在发光」而不是「盘上落灰」。
 *
 * ## 性能红线
 *
 * - 30fps 上限，用 `Choreographer` 而不是 `ValueAnimator`：前者跟 vsync，
 *   后者切后台还在跑。
 * - **只在自己可见 + 正在播放 + 开关打开时跑**，任一不满足立刻停帧回调。
 * - **每帧零分配**：所有 sprite 都是 init 阶段预渲染好的 Bitmap，
 *   `Matrix` / `Paint` 都是实例字段，`onDraw` 里不 new 任何东西。
 * - 系统开了「移除动画」(`ANIMATOR_DURATION_SCALE == 0`) 时完全不跑。
 */
class VinylParticleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** 粒子池。容量固定，复用而不是重新分配。 */
    private var particles: Array<Particle> = emptyArray()

    /** 容量：低端机折半。 */
    private var capacity = 0

    private var centerX = 0f
    private var centerY = 0f
    private var radiusPx = 0f

    /** 预渲染的光点 sprite（橙 / 黄两档，加法混合下颜色叠加由 alpha 决定）。 */
    private var dotSprites: Array<Bitmap> = emptyArray()

    /** 预渲染的音符 sprite（橙 / 黄两档）。 */
    private var noteSprites: Array<Bitmap> = emptyArray()

    /** 绘制用：复用同一个 Paint 与 Matrix。 */
    private val spritePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val spriteMatrix = Matrix()

    private var lastFrameAt = 0L
    private var running = false
    private var playing = false
    private var enabled = true
    private var emitAccumulator = 0f

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val now = frameTimeNanos / 1_000_000L
            val delta = if (lastFrameAt == 0L) 0L else now - lastFrameAt
            lastFrameAt = now
            if (delta > 0L) {
                step(delta.coerceAtMost(MAX_DELTA_MS))
                invalidate()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        // 纯装饰，不参与点击/焦点（否则会把黑胶的点击吞掉）
        isClickable = false
        isFocusable = false
        setWillNotDraw(false)
        capacity = if (isLowRam()) LOW_RAM_CAPACITY else FULL_CAPACITY
        particles = Array(capacity) { Particle() }
        dotSprites = arrayOf(buildDotSprite(ORANGE), buildDotSprite(YELLOW))
        noteSprites = arrayOf(buildNoteSprite(ORANGE), buildNoteSprite(YELLOW))
        spritePaint.xfermode = ADD_MODE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        radiusPx = min(w, h) / 2f
        particles.forEach { it.alive = false }
        emitAccumulator = 0f
        syncLoop()
    }

    /** 播放态：暂停时不再生成新粒子，但已存在的会飘完（和 QQ 一致）。 */
    fun setPlaying(value: Boolean) {
        if (playing == value) return
        playing = value
        syncLoop()
    }

    /** 用户在设置里关掉粒子。关掉时立刻清空，避免残留的粒子停在半空。 */
    fun setParticlesEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        if (!value) {
            stopLoop()
            particles.forEach { it.alive = false }
            invalidate()
        } else {
            syncLoop()
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        syncLoop()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncLoop()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncLoop()
    }

    override fun onDetachedFromWindow() {
        stopLoop()
        destroySprites()
        super.onDetachedFromWindow()
    }

    private fun syncLoop() {
        if (isShown && playing && enabled && animationsEnabled()) startLoop() else stopLoop()
    }

    private fun startLoop() {
        if (running) return
        running = true
        lastFrameAt = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopLoop() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    // ------------------------------------------------------------------ 模拟

    /**
     * 推进一步模拟。
     *
     * - 寿命按 `life / lifeTime` 推进，走完就让位给下一个；
     * - 半径方向缓慢外扩；竖直方向匀速上浮（屏幕 y 向下，所以是减）；
     * - 自转：乐符必须转，否则像贴纸；
     * - 颜色：`lerp(橙, 黄, life)`，与 QQ 的 `color1 → color2` 一致。
     */
    private fun step(deltaMs: Long) {
        val dt = deltaMs / 1000f
        if (radiusPx <= 0f) return
        for (particle in particles) {
            if (!particle.alive) continue
            particle.life += dt
            if (particle.life >= particle.lifeTime) {
                particle.alive = false
                continue
            }
            val t = particle.life / particle.lifeTime
            particle.radius += particle.radialSpeed * dt
            particle.y -= particle.riseSpeed * (1f - t * 0.5f) * dt
            particle.x += cos(particle.driftDir) * particle.driftSpeed * dt
            particle.rotation += particle.angularSpeed * dt
            particle.alpha = fadeAlpha(t)
        }
        emitAccumulator += EMIT_PER_SECOND * dt
        var budget = capacity
        while (emitAccumulator >= 1f && budget > 0) {
            emitAccumulator -= 1f
            budget--
            spawn()
        }
        if (emitAccumulator > 1f) emitAccumulator = 1f
    }

    /** 寿命曲线：前 15% 淡入、后 35% 淡出、中间满亮度。 */
    private fun fadeAlpha(t: Float): Float = when {
        t < 0.15f -> t / 0.15f
        t > 0.65f -> (1f - t) / 0.35f
        else -> 1f
    }.coerceIn(0f, 1f)

    /**
     * 生成一个粒子。
     *
     * 极坐标采样 `rad ∈ [0.58R, 1.30R]`（见类文档）。45% 音符 / 35% 光点 /
     * 20% 小红音符 —— 与 QQ 三张贴图的比例接近。
     */
    private fun spawn() {
        val slot = particles.firstOrNull { !it.alive } ?: return
        val radius = radiusPx
        val roll = random.nextFloat()
        val kind = when {
            roll < 0.45f -> KIND_NOTE
            roll < 0.80f -> KIND_DOT
            else -> KIND_SMALL_NOTE
        }
        val angle = random.nextFloat() * TWO_PI
        val rad = radius * (SPAWN_INNER + random.nextFloat() * (SPAWN_OUTER - SPAWN_INNER))
        slot.alive = true
        slot.kind = kind
        slot.life = 0f
        slot.lifeTime = MIN_LIFE + random.nextFloat() * (MAX_LIFE - MIN_LIFE)
        slot.radius = rad
        slot.x = centerX + cos(angle) * rad
        slot.y = centerY + sin(angle) * rad
        slot.size = when (kind) {
            KIND_DOT -> dp(DOT_MIN_DP + random.nextFloat() * (DOT_MAX_DP - DOT_MIN_DP))
            KIND_SMALL_NOTE -> dp(NOTE_MIN_DP + random.nextFloat() * 1.6f)
            else -> dp(NOTE_MIN_DP + 1.6f + random.nextFloat() * (NOTE_MAX_DP - NOTE_MIN_DP - 1.6f))
        }
        slot.rotation = random.nextFloat() * 360f
        slot.angularSpeed = (random.nextFloat() - 0.5f) * MAX_ANGULAR_SPEED
        slot.radialSpeed = dp(1.5f + random.nextFloat() * 3f)
        slot.riseSpeed = dp(RISE_MIN_DP + random.nextFloat() * (RISE_MAX_DP - RISE_MIN_DP))
        slot.driftDir = random.nextFloat() * TWO_PI
        slot.driftSpeed = dp(0.6f + random.nextFloat() * 1.4f)
        slot.alpha = 0f
    }

    // ------------------------------------------------------------------ 绘制

    override fun onDraw(canvas: Canvas) {
        if (capacity == 0 || radiusPx <= 0f) return
        if (dotSprites.isEmpty() || noteSprites.isEmpty()) return
        for (particle in particles) {
            if (!particle.alive || particle.alpha <= 0.01f) continue
            val t = particle.life / particle.lifeTime
            // 颜色档：前半程用橙、后半程用黄，插值由两档 sprite 的 alpha 过渡近似
            val index = if (t < 0.5f) 0 else 1
            val sprites = if (particle.kind == KIND_DOT) dotSprites else noteSprites
            val sprite = sprites.getOrNull(index) ?: continue
            if (sprite.isRecycled) continue
            val half = particle.size / 2f
            spriteMatrix.reset()
            spriteMatrix.postRotate(particle.rotation)
            spriteMatrix.postScale(particle.size / sprite.width, particle.size / sprite.height)
            spriteMatrix.postTranslate(particle.x - half, particle.y - half)
            spritePaint.alpha = (particle.alpha * 255f).toInt().coerceIn(0, 255)
            canvas.drawBitmap(sprite, spriteMatrix, spritePaint)
        }
    }

    /**
     * 预渲染一个光点 sprite。
     *
     * 对应 `particle_2.png`：纯色实心圆 + 柔和抗锯齿边（实测 alpha 级数 12）。
     * 中心不透明、半径 0.75 处开始衰减、边缘 0。
     */
    private fun buildDotSprite(color: Int): Bitmap {
        val size = SPRITE_PX
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = size / 2f
        paint.shader = RadialGradient(
            r, r, r,
            intArrayOf(color, color, withAlpha(color, 0)),
            floatArrayOf(0f, 0.62f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(r, r, r, paint)
        return bitmap
    }

    /**
     * 预渲染一个音符 sprite。
     *
     * 对应 `particle_1.png`：36×75 的**红色八分音符**（椭圆符头 + 上升符干 + 符尾），
     * 长宽比 1:2.08。这里用同样比例自己画，不打包原图。
     */
    private fun buildNoteSprite(color: Int): Bitmap {
        val w = SPRITE_PX
        val h = (w * NOTE_ASPECT).toInt()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val cx = w / 2f
        // 符头
        canvas.drawOval(cx - w * 0.34f, h * 0.70f, cx + w * 0.20f, h * 0.94f, paint)
        // 符干
        canvas.drawRect(cx + w * 0.10f, h * 0.06f, cx + w * 0.22f, h * 0.82f, paint)
        // 符尾
        canvas.drawRect(cx + w * 0.10f, h * 0.06f, cx + w * 0.48f, h * 0.20f, paint)
        return bitmap
    }

    private fun destroySprites() {
        dotSprites.forEach { if (!it.isRecycled) it.recycle() }
        noteSprites.forEach { if (!it.isRecycled) it.recycle() }
        dotSprites = emptyArray()
        noteSprites = emptyArray()
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private fun animationsEnabled(): Boolean = runCatching {
        android.provider.Settings.Global.getFloat(
            context.contentResolver,
            android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) > 0f
    }.getOrDefault(true)

    private fun isLowRam(): Boolean = runCatching {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
            .isLowRamDevice
    }.getOrDefault(false)

    private fun dp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        value,
        resources.displayMetrics,
    )

    /** 单个粒子。可变类，池里复用。 */
    private class Particle {
        var alive = false
        var kind = KIND_DOT
        var life = 0f
        var lifeTime = 1f
        var x = 0f
        var y = 0f
        var radius = 0f
        var size = 0f
        var rotation = 0f
        var angularSpeed = 0f
        var radialSpeed = 0f
        var riseSpeed = 0f
        var driftDir = 0f
        var driftSpeed = 0f
        var alpha = 0f
    }

    companion object {
        /** 满配容量：48 个（QQ 是 200，车机上会掉帧）。 */
        const val FULL_CAPACITY = 48

        /** 低端机容量。 */
        const val LOW_RAM_CAPACITY = 24

        private const val EMIT_PER_SECOND = 6f

        private const val MIN_LIFE = 1.2f
        private const val MAX_LIFE = 3.4f

        /** 生成半径区间（半径比）。低于这个会盖住封面。 */
        private const val SPAWN_INNER = 0.58f
        private const val SPAWN_OUTER = 1.30f

        private const val DOT_MIN_DP = 2f
        private const val DOT_MAX_DP = 4.5f
        private const val NOTE_MIN_DP = 5f
        private const val NOTE_MAX_DP = 9f

        private const val RISE_MIN_DP = 14f
        private const val RISE_MAX_DP = 30f

        /** 最大自转角速度（度/秒）。 */
        private const val MAX_ANGULAR_SPEED = 60f

        /** 一帧最多推进多少毫秒（切后台回来 delta 可能是几秒，必须钳）。 */
        private const val MAX_DELTA_MS = 48L

        /** sprite 预渲染边长（px）。 */
        private const val SPRITE_PX = 48

        /** 音符长宽比，照 QQ `particle_1.png` 的 36×75。 */
        private const val NOTE_ASPECT = 2.08f

        const val KIND_NOTE = 0
        const val KIND_DOT = 1
        const val KIND_SMALL_NOTE = 2

        /** 橙：QQ `color1 (1,0.5,0)`。 */
        const val ORANGE = 0xFFF08000.toInt()

        /** 黄：QQ `color2 (1,1,0)`。 */
        const val YELLOW = 0xFFFFD200.toInt()

        private const val TWO_PI = (Math.PI * 2.0).toFloat()

        private val ADD_MODE = PorterDuffXfermode(PorterDuff.Mode.ADD)

        private val random = Random(SystemClock.uptimeMillis())

        /** 供测试：确认容量分档常量。 */
        @Suppress("unused")
        fun capacityFor(lowRam: Boolean): Int = if (lowRam) LOW_RAM_CAPACITY else FULL_CAPACITY

        /** 供测试：寿命曲线。 */
        @Suppress("unused")
        fun alphaAt(t: Float): Float = when {
            t < 0.15f -> t / 0.15f
            t > 0.65f -> (1f - t) / 0.35f
            else -> 1f
        }.coerceIn(0f, 1f)

        /** 供测试：颜色插值（橙→黄）。 */
        @Suppress("unused")
        fun colorAt(t: Float): Int {
            val green = (128 + 127 * t).toInt().coerceIn(0, 255)
            val blue = (90 * (1f - t * 0.6f)).toInt().coerceIn(0, 255)
            return Color.rgb(255, green, blue)
        }
    }
}
