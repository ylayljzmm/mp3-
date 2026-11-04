package com.car.mp3player.ui

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Outline
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import com.car.mp3player.R
import kotlin.math.min

class VinylRecordView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val rotateGroup: FrameLayout
    private val discView: ImageView
    private val coverView: ImageView
    private var rotationAnimator: ObjectAnimator? = null
    private var currentBitmap: Bitmap? = null
    private var discPx = 0

    init {
        clipChildren = false
        clipToPadding = false
        LayoutInflater.from(context).inflate(R.layout.view_vinyl_record, this, true)
        rotateGroup = findViewById(R.id.vinylRotateGroup)
        discView = findViewById(R.id.vinylDisc)
        coverView = findViewById(R.id.vinylCover)
        coverView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        coverView.clipToOutline = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val available = min(w, h).toFloat()
        // 圆形唱片绕圆心旋转是对称的，不需要为旋转预留 sqrt(2) 空间，直接吃满可用区域
        val discPx = (available * 0.94f).toInt().coerceIn(dp(160), dp(760))
        this.discPx = discPx
        val coverPx = (discPx * 0.50f).toInt()
        layoutDiscAndCover(discPx, coverPx)
    }

    private fun layoutDiscAndCover(discPx: Int, coverPx: Int) {
        (rotateGroup.layoutParams as FrameLayout.LayoutParams).apply {
            width = discPx
            height = discPx
            gravity = Gravity.CENTER
            rotateGroup.layoutParams = this
        }
        (discView.layoutParams as FrameLayout.LayoutParams).apply {
            width = discPx
            height = discPx
            gravity = Gravity.CENTER
            discView.layoutParams = this
        }
        (coverView.layoutParams as FrameLayout.LayoutParams).apply {
            width = coverPx
            height = coverPx
            gravity = Gravity.CENTER
            coverView.layoutParams = this
        }
        rotateGroup.requestLayout()
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    /** Whether a touch point (view-local coords) hits the visible disc circle. */
    fun isDiscTap(localX: Float, localY: Float): Boolean {
        if (discPx <= 0) return false
        val cx = width / 2f
        val cy = height / 2f
        val r = discPx / 2f
        val dx = localX - cx
        val dy = localY - cy
        return dx * dx + dy * dy <= r * r
    }

    fun setCoverBitmap(bitmap: Bitmap?) {
        val previous = currentBitmap
        currentBitmap = bitmap
        if (bitmap == null) {
            coverView.setImageResource(R.drawable.bg_album_placeholder)
        } else {
            coverView.setImageBitmap(bitmap)
        }
        // Release the old art only after the new one is attached, so the view never
        // draws from a recycled bitmap. Without this, skipping through songs leaks one
        // full cover bitmap per track.
        if (previous != null && previous !== bitmap && !previous.isRecycled) {
            previous.recycle()
        }
    }

    fun setPlaying(playing: Boolean) {
        if (playing) {
            if (rotationAnimator?.isRunning == true) return
            rotationAnimator?.cancel()
            rotationAnimator = ObjectAnimator.ofFloat(
                rotateGroup,
                View.ROTATION,
                rotateGroup.rotation,
                rotateGroup.rotation + 360f
            ).apply {
                duration = 18_000L
                interpolator = LinearInterpolator()
                repeatCount = ObjectAnimator.INFINITE
                start()
            }
        } else {
            rotationAnimator?.cancel()
            rotationAnimator = null
        }
    }

    override fun onDetachedFromWindow() {
        rotationAnimator?.cancel()
        rotationAnimator = null
        super.onDetachedFromWindow()
    }
}
