package com.hnwd.bdplayer

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.withRotation
import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation
import androidx.core.view.isVisible
import kotlin.math.min

/**
 * 音频页 B 槽的黑胶唱片动画，视觉对齐 `docs/音频页-黑胶唱片动画原型.html`。
 *
 * 播放：唱片匀速转（3.4s / 圈，线性）、读头落在纹路上；
 * 暂停：读头抬到盘外、唱片停在当前角度（不回零）。
 *
 * 绘制使用固定的 280x280 设计坐标系（与原型 SVG viewBox 一致），onDraw 里按实际边长缩放。
 * 所有 Paint / Shader / Path 在构造期创建复用，避免每帧 allocate。
 *
 * 生命周期：attached 且可见才跑动画；detached / GONE / 窗口不可见时暂停或取消，
 * 避免无限动画泄漏。调用方的 update() 每 500ms 跑一次，因此 setPlaying() 只在状态
 * 翻转时做动作，首次调用直接落到目标态（不播放唱臂飞入动画）。
 */
internal class VinylDiscView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private companion object {
        const val SPIN_MS = 3_400L
        const val ARM_MS = 680L
        const val ARM_OPEN_DEG = -46f
        const val ARM_LIFT = -5f
    }

    private val accent = ContextCompat.getColor(context, R.color.accent)
    private val onAccent = ContextCompat.getColor(context, R.color.onAccent)

    // ---------- 复用画笔 ----------
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33000000
        style = Paint.Style.FILL
    }
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            118f, 100f, 200f,
            intArrayOf(0xFF2B2D34.toInt(), 0xFF15161B.toInt(), 0xFF0A0B0E.toInt()),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
        )
    }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(84f, 62f, 224f, 0x21FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x40BFC6FF
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val groovePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x0EFFFFFF
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val grooveHiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x1CFFFFFF
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x0CFFFFFF
        style = Paint.Style.FILL
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        style = Paint.Style.FILL
    }
    private val labelEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x38FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = onAccent
        style = Paint.Style.STROKE
        strokeWidth = 3.2f
        strokeCap = Paint.Cap.ROUND
    }
    private val spindlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF2F3F8.toInt()
        style = Paint.Style.FILL
    }
    private val pivotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            230f, 20f, 190f, 110f,
            intArrayOf(0xFFE8EAF2.toInt(), 0xFFA9ADBA.toInt(), 0xFF6F7482.toInt()),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
        )
    }
    private val pivotInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF3A3D47.toInt()
        style = Paint.Style.FILL
    }
    private val armShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x47000000
        style = Paint.Style.STROKE
        strokeWidth = 11f
        strokeCap = Paint.Cap.ROUND
    }
    private val armPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(
            240f, 28f, 190f, 104f,
            intArrayOf(0xFFE8EAF2.toInt(), 0xFFA9ADBA.toInt(), 0xFF6F7482.toInt()),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
        )
        style = Paint.Style.STROKE
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF33363F.toInt()
        style = Paint.Style.FILL
    }
    private val headStripePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8D92A0.toInt()
        style = Paint.Style.FILL
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE9EBF2.toInt()
        style = Paint.Style.FILL
    }
    private val glossPath = Path().apply {
        moveTo(140f, 140f)
        lineTo(140f, 28f)
        arcTo(28f, 28f, 252f, 252f, -90f, 45f, false)
        close()
    }
    private val waveHeights = floatArrayOf(9f, 16f, 22f, 16f, 9f)

    // ---------- 动画状态 ----------
    private var discAngle = 0f
    private var armProgress = 1f // 1 = 读头落下（播放），0 = 读头抬开（暂停）
    private var playing: Boolean? = null

    private val spin = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = SPIN_MS
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            discAngle = it.animatedValue as Float
            invalidate()
        }
    }
    private val armAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = ARM_MS
        interpolator = DecelerateInterpolator(1.5f)
        addUpdateListener {
            armProgress = it.animatedValue as Float
            invalidate()
        }
    }

    /** 由 update() 每 500ms 调用；只在状态翻转时做动作。 */
    fun setPlaying(value: Boolean) {
        if (playing == value) return
        val first = playing == null
        playing = value
        if (first) {
            armProgress = if (value) 1f else 0f
            invalidate()
        } else {
            armAnim.cancel()
            armAnim.setFloatValues(armProgress, if (value) 1f else 0f)
            armAnim.start()
        }
        if (canAnimate()) {
            if (value) resumeSpin() else pauseSpin()
        }
    }

    /** 页面关闭时由宿主显式调用；onDetachedFromWindow 也会兜底。 */
    fun release() {
        spin.cancel()
        armAnim.cancel()
    }

    private fun canAnimate() =
        isAttachedToWindow && isVisible && windowVisibility == VISIBLE

    private fun resumeSpin() {
        when {
            spin.isPaused -> spin.resume()
            !spin.isRunning -> spin.start()
        }
    }

    private fun pauseSpin() {
        if (spin.isRunning && !spin.isPaused) spin.pause()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (playing == true && canAnimate()) resumeSpin()
    }

    override fun onDetachedFromWindow() {
        release()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) pauseSpin()
        else if (playing == true && canAnimate()) resumeSpin()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) pauseSpin()
        else if (playing == true && canAnimate()) resumeSpin()
    }

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        if (size <= 0f) return
        val scale = size / 280f
        canvas.withTranslation((width - size) / 2f, (height - size) / 2f) {
            withScale(scale, scale) {
                // 唱片投影不随盘转动
                drawOval(28f, 240f, 252f, 264f, shadowPaint)

                withRotation(discAngle, 140f, 140f) { drawDisc(this) }

                val armAngle = ARM_OPEN_DEG * (1f - armProgress)
                val lift = ARM_LIFT * (1f - armProgress)
                withTranslation(0f, lift) {
                    withRotation(armAngle, 248f, 34f) { drawArm(this) }
                }
            }
        }
    }

    private fun drawDisc(c: Canvas) {
        c.drawCircle(140f, 140f, 112f, discPaint)
        c.drawCircle(140f, 140f, 112f, sheenPaint)
        c.drawCircle(140f, 140f, 112f, edgePaint)
        for (r in 104 downTo 54 step 5) {
            c.drawCircle(140f, 140f, r.toFloat(), groovePaint)
        }
        c.drawCircle(140f, 140f, 107f, grooveHiPaint)
        c.drawCircle(140f, 140f, 49f, grooveHiPaint)
        c.drawPath(glossPath, glossPaint)
        c.drawCircle(140f, 140f, 45f, labelPaint)
        c.drawCircle(140f, 140f, 45f, labelEdgePaint)
        for (i in 0..4) {
            val x = 122f + i * 9f
            val h = waveHeights[i]
            c.drawLine(x, 140f, x, 140f - h, wavePaint)
            c.drawLine(x, 140f, x, 140f + h, wavePaint)
        }
        c.drawCircle(140f, 140f, 4.5f, spindlePaint)
    }

    private fun drawArm(c: Canvas) {
        c.drawCircle(248f, 34f, 15f, pivotPaint)
        c.drawCircle(248f, 34f, 6.5f, pivotInnerPaint)
        c.drawLine(248f, 34f, 196f, 96f, armShadowPaint)
        c.drawLine(248f, 34f, 196f, 96f, armPaint)
        c.withTranslation(196f, 96f) {
            withRotation(-50f) {
                drawRoundRect(-11f, -8f, 13f, 7f, 3.5f, 3.5f, headPaint)
                drawRoundRect(-9f, 5f, 11f, 9f, 2f, 2f, headStripePaint)
                drawCircle(0f, 10f, 2f, needlePaint)
            }
        }
    }
}
