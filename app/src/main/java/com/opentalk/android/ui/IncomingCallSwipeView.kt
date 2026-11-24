package com.opentalk.android.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.ViewCompat
import com.opentalk.android.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Modern Frosted-Glass Dual-Direction Swipe Control for Incoming Calls.
 *
 * - Swipe RIGHT past threshold -> Accept call (smooth emerald glow + haptic confirmation)
 * - Swipe LEFT past threshold  -> Decline call (smooth crimson glow + rotated handset + haptic)
 * - Accidental tap / short drag -> Springs back to center with gentle directional hint nudge
 */
class IncomingCallSwipeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onAccept: (() -> Unit)? = null
    var onDecline: (() -> Unit)? = null
    var onDragStateChanged: ((progress: Float) -> Unit)? = null
    var onTapHint: (() -> Unit)? = null

    var isInteractionEnabled: Boolean = true
        set(value) {
            field = value
            if (!value) {
                isDragging = false
                activePointerId = MotionEvent.INVALID_POINTER_ID
                releaseVelocityTracker()
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            invalidate()
        }

    private val density = resources.displayMetrics.density

    private fun blendColor(fraction: Float, startColor: Int, endColor: Int): Int {
        val f = fraction.coerceIn(0f, 1f)
        val a = (Color.alpha(startColor) + ((Color.alpha(endColor) - Color.alpha(startColor)) * f)).toInt().coerceIn(0, 255)
        val r = (Color.red(startColor) + ((Color.red(endColor) - Color.red(startColor)) * f)).toInt().coerceIn(0, 255)
        val g = (Color.green(startColor) + ((Color.green(endColor) - Color.green(startColor)) * f)).toInt().coerceIn(0, 255)
        val b = (Color.blue(startColor) + ((Color.blue(endColor) - Color.blue(startColor)) * f)).toInt().coerceIn(0, 255)
        return Color.argb(a, r, g, b)
    }

    // Track & Thumb Geometry
    private val trackHeightPx = 80f * density
    private val thumbRadiusPx = 31f * density
    private val targetBadgeRadiusPx = 24f * density
    private val trackInnerPaddingPx = 8f * density
    private val triggerThreshold = 0.70f
    private val tapSlopPx = 10f * density
    private val flingVelocityPx = 1400f * density

    private val trackRect = RectF()
    private val chevronPath = Path()

    private val defaultTrackFillColor = ContextCompat.getColor(context, R.color.incoming_swipe_track)
    private val defaultTrackStrokeColor = ContextCompat.getColor(context, R.color.incoming_swipe_stroke)
    private val declineChevronBaseColor = ContextCompat.getColor(context, R.color.incoming_decline_text)
    private val acceptChevronBaseColor = ContextCompat.getColor(context, R.color.incoming_accept_text)

    init {
        isFocusable = true
        isClickable = true
        if (contentDescription.isNullOrBlank()) {
            contentDescription = "Incoming call controls. Slide right to accept or slide left to decline."
        }
        ViewCompat.addAccessibilityAction(this, "Accept call") { _, _ ->
            if (isInteractionEnabled && !isCommitted) {
                commitSwipe(isAccept = true)
                true
            } else {
                false
            }
        }
        ViewCompat.addAccessibilityAction(this, "Decline call") { _, _ ->
            if (isInteractionEnabled && !isCommitted) {
                commitSwipe(isAccept = false)
                true
            } else {
                false
            }
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (isInteractionEnabled && !isCommitted) {
            playTapHintNudge(1f)
            onTapHint?.invoke()
        }
        return true
    }

    // Paints
    private val trackFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = defaultTrackFillColor
    }
    private val trackGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val trackStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.25f * density
    }
    private val targetBadgeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val targetBadgeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.1f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val thumbHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val thumbFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val thumbBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }

    // Drawables
    private val phoneIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_phone)?.mutate()
    private val hangupIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_phone_hangup)?.mutate()
    private val thumbPhoneIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_phone)?.mutate()

    // Animation & Gesture State
    private var dragOffsetPx: Float = 0f
    private var maxDragPx: Float = 1f
    private var isDragging: Boolean = false
    private var activePointerId: Int = MotionEvent.INVALID_POINTER_ID
    private var downX: Float = 0f
    private var maxMovedPx: Float = 0f
    private var hasCrossedThreshold: Boolean = false
    private var isCommitted: Boolean = false
    private var velocityTracker: VelocityTracker? = null

    private var animPhase: Float = 0f // 0..1 continuous loop
    private var idleAnimator: ValueAnimator? = null
    private var settleAnimator: ValueAnimator? = null
    private var rightGlowShader: LinearGradient? = null
    private var leftGlowShader: LinearGradient? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredHeight = (100f * density).toInt()
        val w = resolveSize((320f * density).toInt(), widthMeasureSpec)
        val h = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val horizontalMargin = 4f * density
        val top = (h - trackHeightPx) * 0.5f
        trackRect.set(horizontalMargin, top, w - horizontalMargin, top + trackHeightPx)
        val halfTrack = trackRect.width() * 0.5f
        maxDragPx = max(1f, halfTrack - thumbRadiusPx - trackInnerPaddingPx)
        val cx = trackRect.centerX()
        val cy = trackRect.centerY()
        rightGlowShader = LinearGradient(
            cx, cy, trackRect.right, cy,
            Color.TRANSPARENT, Color.rgb(16, 185, 129), Shader.TileMode.CLAMP
        )
        leftGlowShader = LinearGradient(
            trackRect.left, cy, cx, cy,
            Color.rgb(239, 68, 68), Color.TRANSPARENT, Shader.TileMode.CLAMP
        )
    }

    fun startIdleAnimations() {
        if (idleAnimator?.isRunning == true) return
        idleAnimator?.cancel()
        idleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1600L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener {
                animPhase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stopIdleAnimations() {
        idleAnimator?.cancel()
        idleAnimator = null
        settleAnimator?.cancel()
        settleAnimator = null
    }

    fun reset() {
        settleAnimator?.cancel()
        settleAnimator = null
        isDragging = false
        isCommitted = false
        hasCrossedThreshold = false
        isInteractionEnabled = true
        dragOffsetPx = 0f
        activePointerId = MotionEvent.INVALID_POINTER_ID
        releaseVelocityTracker()
        onDragStateChanged?.invoke(0f)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (windowVisibility == VISIBLE) {
            startIdleAnimations()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            if (!isCommitted) {
                startIdleAnimations()
            }
        } else {
            idleAnimator?.cancel()
            idleAnimator = null
        }
    }

    override fun onDetachedFromWindow() {
        stopIdleAnimations()
        releaseVelocityTracker()
        super.onDetachedFromWindow()
    }

    private fun releaseVelocityTracker() {
        try {
            velocityTracker?.recycle()
        } catch (_: Exception) {}
        velocityTracker = null
    }

    private fun currentProgress(): Float {
        return (dragOffsetPx / maxDragPx).coerceIn(-1f, 1f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (trackRect.width() <= 0f) return

        val cx = trackRect.centerX()
        val cy = trackRect.centerY()
        val trackRadius = trackRect.height() * 0.5f
        val progress = currentProgress()
        val absProgress = abs(progress)

        // 1. Frosted Dark Glass Pill Track
        canvas.drawRoundRect(trackRect, trackRadius, trackRadius, trackFillPaint)

        // 2. Directional Color Glow Fill inside Track as user drags
        if (absProgress > 0.02f) {
            val glowAlpha = (absProgress * 125f).toInt().coerceIn(0, 130)
            trackGlowPaint.shader = if (progress > 0f) rightGlowShader else leftGlowShader
            trackGlowPaint.alpha = glowAlpha
            canvas.drawRoundRect(trackRect, trackRadius, trackRadius, trackGlowPaint)
            trackGlowPaint.alpha = 255
        }

        // Track Rim Stroke (morphs smoothly toward emerald or crimson)
        val rimColor = when {
            progress > 0f -> blendColor(absProgress, defaultTrackStrokeColor, 0x9934D399.toInt())
            progress < 0f -> blendColor(absProgress, defaultTrackStrokeColor, 0x99F87171.toInt())
            else -> defaultTrackStrokeColor
        }
        trackStrokePaint.color = rimColor
        canvas.drawRoundRect(trackRect, trackRadius, trackRadius, trackStrokePaint)

        // 3. Left Decline Target Badge & Right Accept Target Badge
        val leftTargetX = trackRect.left + trackRadius
        val rightTargetX = trackRect.right - trackRadius

        val leftAlphaFactor = if (progress > 0f) (1f - absProgress * 1.15f).coerceIn(0.12f, 1f) else 1f
        val rightAlphaFactor = if (progress < 0f) (1f - absProgress * 1.15f).coerceIn(0.12f, 1f) else 1f

        val leftScale = if (progress < 0f) 1f + absProgress * 0.16f else 1f
        val rightScale = if (progress > 0f) 1f + absProgress * 0.16f else 1f

        // Left Decline Target (Vibrant Red Circular Badge for instant visual clarity)
        val leftFillAlpha = ((175f + (if (progress < 0f) absProgress * 80f else 0f)) * leftAlphaFactor).toInt().coerceIn(24, 255)
        targetBadgeFillPaint.color = Color.argb(leftFillAlpha, 220, 38, 38)
        targetBadgeStrokePaint.color = Color.argb((165f * leftAlphaFactor).toInt().coerceIn(20, 255), 252, 165, 165)
        canvas.drawCircle(leftTargetX, cy, targetBadgeRadiusPx * leftScale, targetBadgeFillPaint)
        canvas.drawCircle(leftTargetX, cy, targetBadgeRadiusPx * leftScale, targetBadgeStrokePaint)
        drawCenteredDrawable(
            canvas,
            hangupIcon,
            leftTargetX,
            cy,
            (21f * density * leftScale).toInt(),
            Color.argb((255f * leftAlphaFactor).toInt().coerceIn(30, 255), 255, 255, 255)
        )

        // Right Accept Target (Vibrant Emerald Circular Badge for instant visual clarity)
        val rightFillAlpha = ((175f + (if (progress > 0f) absProgress * 80f else 0f)) * rightAlphaFactor).toInt().coerceIn(24, 255)
        targetBadgeFillPaint.color = Color.argb(rightFillAlpha, 16, 185, 129)
        targetBadgeStrokePaint.color = Color.argb((165f * rightAlphaFactor).toInt().coerceIn(20, 255), 110, 231, 183)
        canvas.drawCircle(rightTargetX, cy, targetBadgeRadiusPx * rightScale, targetBadgeFillPaint)
        canvas.drawCircle(rightTargetX, cy, targetBadgeRadiusPx * rightScale, targetBadgeStrokePaint)
        drawCenteredDrawable(
            canvas,
            phoneIcon,
            rightTargetX,
            cy,
            (21f * density * rightScale).toInt(),
            Color.argb((255f * rightAlphaFactor).toInt().coerceIn(30, 255), 255, 255, 255)
        )

        // 4. Animated Outward Chevrons (‹ ‹ on left, › › on right)
        val thumbX = cx + dragOffsetPx
        val chevronCount = 3
        val chevronSize = 5.5f * density
        val chevronSpacing = 9f * density

        val leftZoneStart = cx - thumbRadiusPx - 12f * density
        val leftZoneEnd = leftTargetX + targetBadgeRadiusPx + 8f * density
        if (leftZoneStart > leftZoneEnd + chevronSpacing) {
            val leftMid = (leftZoneStart + leftZoneEnd) * 0.5f
            val baseAlphaLeft = if (progress > 0f) (1f - absProgress * 2.2f).coerceIn(0f, 1f) else (1f - absProgress * 1.1f).coerceIn(0f, 1f)
            if (baseAlphaLeft > 0.03f) {
                for (i in 0 until chevronCount) {
                    val x = leftMid + (1 - i) * chevronSpacing
                    if (x < thumbX - thumbRadiusPx - 4f * density) {
                        val wave = (sin((animPhase * 2.0 * Math.PI) - i * 0.9) * 0.5 + 0.5).toFloat()
                        val alpha = ((0.30f + 0.70f * wave) * baseAlphaLeft * 255f).toInt().coerceIn(0, 255)
                        chevronPaint.color = Color.argb(
                            alpha,
                            Color.red(declineChevronBaseColor),
                            Color.green(declineChevronBaseColor),
                            Color.blue(declineChevronBaseColor)
                        )
                        drawChevron(canvas, x, cy, chevronSize, pointingRight = false)
                    }
                }
            }
        }

        val rightZoneStart = cx + thumbRadiusPx + 12f * density
        val rightZoneEnd = rightTargetX - targetBadgeRadiusPx - 8f * density
        if (rightZoneEnd > rightZoneStart + chevronSpacing) {
            val rightMid = (rightZoneStart + rightZoneEnd) * 0.5f
            val baseAlphaRight = if (progress < 0f) (1f - absProgress * 2.2f).coerceIn(0f, 1f) else (1f - absProgress * 1.1f).coerceIn(0f, 1f)
            if (baseAlphaRight > 0.03f) {
                for (i in 0 until chevronCount) {
                    val x = rightMid + (i - 1) * chevronSpacing
                    if (x > thumbX + thumbRadiusPx + 4f * density) {
                        val wave = (sin((animPhase * 2.0 * Math.PI) - i * 0.9) * 0.5 + 0.5).toFloat()
                        val alpha = ((0.30f + 0.70f * wave) * baseAlphaRight * 255f).toInt().coerceIn(0, 255)
                        chevronPaint.color = Color.argb(
                            alpha,
                            Color.red(acceptChevronBaseColor),
                            Color.green(acceptChevronBaseColor),
                            Color.blue(acceptChevronBaseColor)
                        )
                        drawChevron(canvas, x, cy, chevronSize, pointingRight = true)
                    }
                }
            }
        }

        // 5. Center Draggable Thumb with Idle Pulse Ripples & Dynamic Color Morphing
        if (!isDragging && !isCommitted && absProgress < 0.03f) {
            // Primary expanding ripple ring
            val r1 = thumbRadiusPx * (1.0f + animPhase * 0.58f)
            val a1 = ((1f - animPhase) * 95f).toInt().coerceIn(0, 95)
            ripplePaint.color = Color.argb(a1, 52, 211, 153)
            canvas.drawCircle(thumbX, cy, r1, ripplePaint)

            // Secondary staggered ripple ring
            val p2 = (animPhase + 0.5f) % 1f
            val r2 = thumbRadiusPx * (1.0f + p2 * 0.58f)
            val a2 = ((1f - p2) * 75f).toInt().coerceIn(0, 75)
            ripplePaint.color = Color.argb(a2, 165, 180, 252)
            canvas.drawCircle(thumbX, cy, r2, ripplePaint)
        }

        // Ambient glow halo behind thumb
        val haloColor = when {
            progress > 0f -> Color.argb((65 + absProgress * 85).toInt().coerceIn(0, 160), 16, 185, 129)
            progress < 0f -> Color.argb((65 + absProgress * 85).toInt().coerceIn(0, 160), 239, 68, 68)
            else -> Color.argb(55, 255, 255, 255)
        }
        thumbHaloPaint.color = haloColor
        val breathScale = if (!isDragging && !isCommitted) {
            1f + 0.045f * sin(animPhase * 2.0 * Math.PI).toFloat()
        } else {
            1.05f
        }
        canvas.drawCircle(thumbX, cy, (thumbRadiusPx + 6f * density) * breathScale, thumbHaloPaint)

        // Thumb circle fill: Luminous White at center -> Emerald Green (right) / Crimson Red (left)
        val thumbColor = when {
            progress > 0f -> blendColor(min(1f, absProgress * 1.25f), 0xFFFFFFFF.toInt(), 0xFF10B981.toInt())
            progress < 0f -> blendColor(min(1f, absProgress * 1.25f), 0xFFFFFFFF.toInt(), 0xFFEF4444.toInt())
            else -> 0xFFFFFFFF.toInt()
        }
        thumbFillPaint.color = thumbColor
        val activeThumbRadius = thumbRadiusPx * (if (isDragging) 1.05f else breathScale)
        canvas.drawCircle(thumbX, cy, activeThumbRadius, thumbFillPaint)

        // Subtle inner rim on thumb
        thumbBorderPaint.color = when {
            progress > 0f -> 0x66A7F3D0
            progress < 0f -> 0x66FECACA
            else -> 0x44CBD5E1
        }
        canvas.drawCircle(thumbX, cy, activeThumbRadius - 1f * density, thumbBorderPaint)

        // Handset icon inside thumb (smoothly rotates to 135 deg hangup angle on left drag, wiggles when idle)
        val iconColor = when {
            absProgress > 0.35f -> 0xFFFFFFFF.toInt()
            progress > 0f -> blendColor((absProgress / 0.35f).coerceIn(0f, 1f), 0xFF059669.toInt(), 0xFFFFFFFF.toInt())
            progress < 0f -> blendColor((absProgress / 0.35f).coerceIn(0f, 1f), 0xFF059669.toInt(), 0xFFFFFFFF.toInt())
            else -> 0xFF059669.toInt()
        }

        val iconRotation = when {
            progress < 0f -> 135f * min(1f, absProgress * 1.3f)
            !isDragging && !isCommitted && absProgress < 0.02f -> {
                // Subtle realistic handset ring wiggle during first 45% of each 1.6s cycle
                if (animPhase < 0.45f) {
                    sin((animPhase / 0.45f) * 6.0 * Math.PI).toFloat() * 11f
                } else 0f
            }
            else -> 0f
        }

        canvas.save()
        canvas.rotate(iconRotation, thumbX, cy)
        drawCenteredDrawable(canvas, thumbPhoneIcon, thumbX, cy, (27f * density).toInt(), iconColor)
        canvas.restore()
    }

    private fun drawChevron(canvas: Canvas, cx: Float, cy: Float, size: Float, pointingRight: Boolean) {
        chevronPath.reset()
        if (pointingRight) {
            chevronPath.moveTo(cx - size * 0.45f, cy - size)
            chevronPath.lineTo(cx + size * 0.55f, cy)
            chevronPath.lineTo(cx - size * 0.45f, cy + size)
        } else {
            chevronPath.moveTo(cx + size * 0.45f, cy - size)
            chevronPath.lineTo(cx - size * 0.55f, cy)
            chevronPath.lineTo(cx + size * 0.45f, cy + size)
        }
        canvas.drawPath(chevronPath, chevronPaint)
    }

    private fun drawCenteredDrawable(canvas: Canvas, drawable: Drawable?, cx: Float, cy: Float, sizePx: Int, tintColor: Int) {
        if (drawable == null) return
        val half = sizePx / 2
        val left = (cx - half).toInt()
        val top = (cy - half).toInt()
        drawable.setBounds(left, top, left + sizePx, top + sizePx)
        DrawableCompat.setTint(drawable, tintColor)
        drawable.draw(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isInteractionEnabled || isCommitted) return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val cx = trackRect.centerX() + dragOffsetPx
                val cy = trackRect.centerY()
                val distX = abs(event.x - cx)
                val distY = abs(event.y - cy)
                val grabRadius = thumbRadiusPx * 1.75f
                if (distX <= grabRadius && distY <= grabRadius) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    settleAnimator?.cancel()
                    activePointerId = event.getPointerId(0)
                    downX = event.x - dragOffsetPx
                    maxMovedPx = 0f
                    isDragging = true
                    hasCrossedThreshold = false
                    releaseVelocityTracker()
                    velocityTracker = VelocityTracker.obtain().apply { addMovement(event) }
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    invalidate()
                    return true
                }
                // Tapping left/right edge without dragging from thumb plays gentle nudge hint
                if (trackRect.contains(event.x, event.y)) {
                    playTapHintNudge(if (event.x >= trackRect.centerX()) 1f else -1f)
                    onTapHint?.invoke()
                    return true
                }
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) return false
                val idx = event.findPointerIndex(activePointerId)
                if (idx < 0) return false
                velocityTracker?.addMovement(event)

                val x = event.getX(idx)
                val newOffset = (x - downX).coerceIn(-maxDragPx, maxDragPx)
                maxMovedPx = max(maxMovedPx, abs(newOffset))
                dragOffsetPx = newOffset

                val progress = currentProgress()
                val crossedNow = abs(progress) >= triggerThreshold
                if (crossedNow && !hasCrossedThreshold) {
                    hasCrossedThreshold = true
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                } else if (!crossedNow && hasCrossedThreshold) {
                    hasCrossedThreshold = false
                }
                onDragStateChanged?.invoke(progress)
                invalidate()
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (!isDragging) return false
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                if (pointerId == activePointerId) {
                    val newIndex = if (pointerIndex == 0) 1 else 0
                    if (newIndex < event.pointerCount) {
                        activePointerId = event.getPointerId(newIndex)
                        downX = event.getX(newIndex) - dragOffsetPx
                        velocityTracker?.clear()
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isDragging) return false
                parent?.requestDisallowInterceptTouchEvent(false)
                isDragging = false
                activePointerId = MotionEvent.INVALID_POINTER_ID

                var vx = 0f
                velocityTracker?.let {
                    it.addMovement(event)
                    it.computeCurrentVelocity(1000)
                    vx = it.xVelocity
                }
                releaseVelocityTracker()

                val progress = currentProgress()
                val isCancel = event.actionMasked == MotionEvent.ACTION_CANCEL
                val acceptTriggered = !isCancel && (progress >= triggerThreshold || (progress >= 0.42f && vx >= flingVelocityPx))
                val declineTriggered = !isCancel && (progress <= -triggerThreshold || (progress <= -0.42f && vx <= -flingVelocityPx))

                when {
                    acceptTriggered -> commitSwipe(isAccept = true)
                    declineTriggered -> commitSwipe(isAccept = false)
                    maxMovedPx < tapSlopPx && !isCancel -> {
                        // Accidental single tap: NEVER answer or decline; bounce gently to show swipe motion
                        playTapHintNudge(1f)
                        onTapHint?.invoke()
                    }
                    else -> springBackToCenter()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun commitSwipe(isAccept: Boolean) {
        isCommitted = true
        isInteractionEnabled = false
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        // Fire Accept/Decline immediately so an animation cancellation (e.g. window transition) can NEVER freeze the slider!
        if (isAccept) {
            onAccept?.invoke()
        } else {
            onDecline?.invoke()
        }
        val targetOffset = if (isAccept) maxDragPx else -maxDragPx
        settleAnimator?.cancel()
        settleAnimator = ValueAnimator.ofFloat(dragOffsetPx, targetOffset).apply {
            duration = 65L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                dragOffsetPx = it.animatedValue as Float
                onDragStateChanged?.invoke(currentProgress())
                invalidate()
            }
            start()
        }
    }

    private fun springBackToCenter() {
        settleAnimator?.cancel()
        settleAnimator = ValueAnimator.ofFloat(dragOffsetPx, 0f).apply {
            duration = 320L
            interpolator = OvershootInterpolator(1.15f)
            addUpdateListener {
                dragOffsetPx = it.animatedValue as Float
                onDragStateChanged?.invoke(currentProgress())
                invalidate()
            }
            start()
        }
    }

    private fun playTapHintNudge(direction: Float) {
        settleAnimator?.cancel()
        val nudgePx = 22f * density * direction
        settleAnimator = ValueAnimator.ofFloat(dragOffsetPx, nudgePx, -nudgePx * 0.55f, 0f).apply {
            duration = 460L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                dragOffsetPx = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }
}
