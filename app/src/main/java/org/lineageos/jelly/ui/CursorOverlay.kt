/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.ui

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.os.SystemClock
import androidx.core.content.ContextCompat
import org.lineageos.jelly.R

/**
 * Remote-control pointer visual and synthesizer. It only draws the cursor and turns
 * coordinates into touch/swipe events for the rest of the window; key handling lives in
 * the activity so it keeps working no matter which view currently has focus (mouse clicks,
 * text fields etc.). The icon is the system pointer, color-inverted.
 */
class CursorOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    companion object {
        private val HIGHLIGHT_PAINT = Paint().apply {
            color = Color.argb(0x66, 0x4A, 0x90, 0xD9)
        }
        const val ARM_NONE = -1
        const val ARM_UP = 0
        const val ARM_LEFT = 1
        const val ARM_RIGHT = 2
    }

    var cursorX = 0f
        private set
    var cursorY = 0f
        private set

    private val arrowCache = HashMap<String, Drawable>()
    private val arrow: Drawable

    init {
        // Exactly the system cursor bitmap (same art, same density-correct size). The vector
        // variant cannot be used: its fillColor/strokeColor are framework theme attributes
        // that do not resolve in app resources and render transparent.
        val icon = loadSystemIcon("pointer_arrow")
            ?: ContextCompat.getDrawable(context, R.drawable.ic_cursor) as Drawable
        icon.setBounds(0, 0, icon.intrinsicWidth, icon.intrinsicHeight)
        arrow = icon
    }

    private fun loadSystemIcon(name: String): Drawable? {
        val res = Resources.getSystem().getIdentifier(name, "drawable", "android")
        if (res == 0) return null
        return try {
            Resources.getSystem().getDrawable(res)
        } catch (ex: Exception) {
            null
        }
    }

    /** Notified when the cursor settles so the activity can resolve the icon (JS hit test). */
    var iconCallback: (() -> Unit)? = null

    private var iconPinned = false

    /** Hit-test driven icon updates; ignored while a mode pins its own icon. */
    fun setIconKind(kind: String) {
        if (iconPinned) return
        applyIcon(kind)
    }

    /** Pins a mode icon (e.g. "all_scroll" for wheel mode) until [unpinIcon]. */
    fun pinIcon(kind: String) {
        iconPinned = true
        applyIcon(kind)
    }

    fun unpinIcon() {
        iconPinned = false
        applyIcon("arrow")
    }

    private fun applyIcon(kind: String) {
        val d = if (kind == "arrow") arrow
        else arrowCache.getOrPut(kind) { loadSystemIcon("pointer_$kind") ?: arrow }
        setArrowDrawable(d)
    }

    var hoverEnabled = true

    /**
     * Dispatches a synthetic hover event at the cursor position (real hover feedback in the
     * page) and asks the activity to resolve the cursor icon. Disabled once a real mouse is
     * seen: the mouse generates its own hover, and injecting ours would cancel Blink's
     * long-press selection gesture.
     */
    fun onCursorSettled() {
        if (hoverEnabled) {
            val loc = windowLocation()
            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(
                now, now, MotionEvent.ACTION_HOVER_MOVE,
                cursorX + loc[0], cursorY + loc[1], 0
            )
            event.source = android.view.InputDevice.SOURCE_MOUSE
            rootView.dispatchGenericMotionEvent(event)
            event.recycle()
        }
        iconCallback?.invoke()
    }

    private var shownArrow: Drawable? = null
    private val currentDrawable: Drawable
        get() = shownArrow ?: arrow

    private fun setArrowDrawable(d: Drawable) {
        if (shownArrow === d) return
        shownArrow = d
        d.setBounds(0, 0, d.intrinsicWidth, d.intrinsicHeight)
        invalidateArrow()
    }

    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    var isPressPending = false
        private set
    private var lastDrawX = 0f
    private var lastDrawY = 0f

    private fun invalidateArrow() {
        val density = context.resources.displayMetrics.density
        val size = currentDrawable.bounds.width() + 6 * density
        val drawX = (cursorX - 4.5f * density).toInt()
        val drawY = (cursorY - 3.5f * density).toInt()
        val right = (drawX + size).toInt()
        val bottom = (drawY + size).toInt()
        invalidate(drawX, drawY, right, bottom)
        val oldX = (lastDrawX - 4.5f * density).toInt()
        val oldY = (lastDrawY - 4.5f * density).toInt()
        invalidate(oldX, oldY, (oldX + size).toInt(), (oldY + size).toInt())
        lastDrawX = cursorX
        lastDrawY = cursorY
    }

    init {
        isFocusable = false
        // Elevation keeps us above the WebView (added programmatically later), but a full
        // screen outline would make the compositor draw a giant shadow every frame.
        outlineProvider = null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (cursorX == 0f && cursorY == 0f) {
            cursorX = w / 2f
            cursorY = h / 2f
            targetX = cursorX
            targetY = cursorY
        }
    }

    private var targetX = 0f
    private var targetY = 0f
    private var gliding = false
    private var lastMoveNotify = 0L
    private val glideFrame: Runnable = Runnable {
        val dx = targetX - cursorX
        val dy = targetY - cursorY
        if (kotlin.math.abs(dx) < 0.5f && kotlin.math.abs(dy) < 0.5f) {
            cursorX = targetX
            cursorY = targetY
            gliding = false
            invalidateArrow()
            onCursorSettled()
        } else {
            cursorX += dx * 0.35f
            cursorY += dy * 0.35f
            gliding = true
            postOnAnimation(glideFrame)
            invalidateArrow()
            // Live hover/icon feedback while moving, throttled so the JS hit test
            // cannot run on every animation frame.
            val now = SystemClock.uptimeMillis()
            if (now - lastMoveNotify > 110L) {
                lastMoveNotify = now
                onCursorSettled()
            }
        }
    }

    private fun startGlide() {
        if (!gliding) {
            gliding = true
            postOnAnimation(glideFrame)
        }
    }

    fun setPosition(x: Float, y: Float) {
        targetX = x.coerceIn(0f, width.toFloat())
        targetY = y.coerceIn(0f, height.toFloat())
        gliding = false
        cursorX = targetX
        cursorY = targetY
        invalidateArrow()
        onCursorSettled()
    }

    /** Moves by the given delta; returns false when the cursor is stuck at the edge. */
    fun moveBy(dx: Float, dy: Float): Boolean {
        releasePendingPress()
        val newX = (targetX + dx).coerceIn(0f, width.toFloat())
        val newY = (targetY + dy).coerceIn(0f, height.toFloat())
        if (newX == targetX && newY == targetY) return false
        targetX = newX
        targetY = newY
        startGlide()
        return true
    }

    /** Ends an in-progress press with a synthetic UP (keeps touch streams balanced). */
    private fun releasePendingPress() {
        if (!isPressPending) return
        isPressPending = false
        touch(MotionEvent.ACTION_UP, downTime, SystemClock.uptimeMillis(), downX, downY)
    }

    fun pressDown() {
        releasePendingPress()
        downTime = SystemClock.uptimeMillis()
        downX = cursorX
        downY = cursorY
        touch(MotionEvent.ACTION_DOWN, downTime, downTime, downX, downY)
        isPressPending = true
    }

    fun pressUp() {
        if (!isPressPending) return
        isPressPending = false
        touch(MotionEvent.ACTION_UP, downTime, SystemClock.uptimeMillis(), cursorX, cursorY)
    }

    /** Drag while the press is held: arrows move the pressed finger (e.g. selection handles). */
    fun dragBy(dx: Float, dy: Float) {
        cursorX = (cursorX + dx).coerceIn(0f, width.toFloat())
        cursorY = (cursorY + dy).coerceIn(0f, height.toFloat())
        targetX = cursorX
        targetY = cursorY
        invalidateArrow()
        touch(MotionEvent.ACTION_MOVE, downTime, SystemClock.uptimeMillis(), cursorX, cursorY)
    }

    /** Converts window coordinates (e.g. from a mouse) to cursor coordinates. */
    fun moveToWindowPoint(windowX: Float, windowY: Float) {
        val loc = windowLocation()
        setPosition(windowX - loc[0], windowY - loc[1])
    }

    private fun touch(action: Int, downTime: Long, eventTime: Long, x: Float, y: Float) {
        val loc = windowLocation()
        val event = MotionEvent.obtain(downTime, eventTime, action, x + loc[0], y + loc[1], 0)
        rootView.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun windowLocation(): IntArray {
        val loc = IntArray(2)
        getLocationInWindow(loc)
        return loc
    }

    // ---------------------------------------------------------------- cross menu

    /**
     * The action cross: opens around the cursor on a long OK press and offers the arms
     * reload (up), text selection (left) and mouse wheel (right). Keys act on it directly,
     * so it only needs to look right and follow the cursor.
     */
    private val density = context.resources.displayMetrics.density
    private val scrimPaint = Paint()
    private val diamondFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC6E7280")
    }
    private val diamondStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#F2E3E6EB")
        strokeWidth = 2f * density
    }
    private val ringFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#80000000") }
    private val ringStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#E6FFFFFF")
        strokeWidth = 1.6f * density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f * context.resources.displayMetrics.scaledDensity
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val diamondPath = Path()
    private val diamondRect = RectF()
    private val rotate45 = Matrix().apply { setRotate(45f) }

    var crossProgress = 0f
        private set

    private var crossAnim: ValueAnimator? = null

    private val chipFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E64A90D9") }
    private val chipStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 1.4f * density
    }
    private var focusedArm = ARM_NONE
    private var focusGlow = 0f
    private var focusAnim: ValueAnimator? = null

    /** Highlights the arm the arrows currently point at; OK acts on it. */
    fun setFocusedArm(arm: Int) {
        if (focusedArm == arm) return
        focusedArm = arm
        focusAnim?.cancel()
        if (arm == ARM_NONE) {
            focusGlow = 0f
            invalidate()
            return
        }
        focusAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 110L
            addUpdateListener {
                focusGlow = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun showCross() {
        crossAnim?.cancel()
        crossAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 170L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                crossProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun hideCross() {
        crossAnim?.cancel()
        crossAnim = null
        crossProgress = 0f
        invalidate()
    }

    private fun drawCross(canvas: Canvas) {
        val p = crossProgress
        val cx = cursorX
        val cy = cursorY
        scrimPaint.color = Color.argb((0x50 * p).toInt(), 0, 0, 0)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
        val icon = 20f * density
        val textGap = 8f * density
        val wReload = icon + textGap + labelPaint.measureText(context.getString(R.string.cross_reload))
        val wSelect = icon + textGap + labelPaint.measureText(context.getString(R.string.cross_select))
        val wWheel = icon + textGap + labelPaint.measureText(context.getString(R.string.cross_wheel))
        val armLen = maxOf(wReload, maxOf(wSelect, wWheel))
        val radius = armLen + 56f * density
        diamondFill.alpha = (0xCC * p).toInt()
        diamondStroke.alpha = (0xF2 * p).toInt()
        ringFill.alpha = (0x80 * p).toInt()
        ringStroke.alpha = (0xE6 * p).toInt()
        labelPaint.alpha = (255 * p).toInt()
        // The ring belongs to the panel center. With room around the cursor the panel is
        // centered on it; near an edge the panel slides away so it fits on screen AND the
        // cursor stays clear of the diamond (pushed out to just beyond its corner).
        val ring = 26f * density
        val need = radius + ring + 6f * density
        // Centered on the cursor whenever it fits; otherwise slide fully to the free
        // side, far enough that the cursor (and its ring radius) stays outside the diamond.
        val pcx = (when {
            cx < radius -> cx + need
            cx > width - radius -> cx - need
            else -> cx
        }).coerceIn(minOf(radius, width / 2f), maxOf(width - radius, width / 2f))
        val pcy = (when {
            cy < radius -> cy + need
            cy > height - radius -> cy - need
            else -> cy
        }).coerceIn(minOf(radius, height / 2f), maxOf(height - radius, height / 2f))
        canvas.save()
        canvas.scale(0.86f + 0.14f * p, 0.86f + 0.14f * p, pcx, pcy)
        // Rounded diamond panel: a round rect rotated by 45 degrees around the cursor.
        val side = radius * 0.7071f
        diamondRect.set(pcx - side, pcy - side, pcx + side, pcy + side)
        diamondPath.reset()
        diamondPath.addRoundRect(diamondRect, 26f * density, 26f * density, Path.Direction.CW)
        rotate45.setRotate(45f, pcx, pcy)
        diamondPath.transform(rotate45)
        canvas.drawPath(diamondPath, diamondFill)
        canvas.drawPath(diamondPath, diamondStroke)
        // Ring at the center of the cross.
        canvas.drawCircle(pcx, pcy, ring, ringFill)
        canvas.drawCircle(pcx, pcy, ring, ringStroke)
        // Focus chip behind the icon of the arm the arrows point at.
        if (focusedArm != ARM_NONE && focusGlow > 0f) {
            val shift = 11f * density
            val chip = icon / 2f + 7f * density
            val (chipCx, chipCy) = when (focusedArm) {
                ARM_LEFT -> Pair(pcx - radius * 0.52f - wSelect / 2f + icon / 2f, pcy)
                ARM_RIGHT -> Pair(pcx + radius * 0.52f + wWheel / 2f - icon / 2f, pcy)
                else -> Pair(pcx, pcy - radius * 0.52f - shift)
            }
            val glow = focusGlow * crossProgress
            chipFill.alpha = (0xE6 * glow).toInt()
            chipStroke.alpha = (0xCC * glow).toInt()
            canvas.drawCircle(chipCx, chipCy, chip, chipFill)
            canvas.drawCircle(chipCx, chipCy, chip, chipStroke)
        }
        // Arms sit on the axes at the diamond's corners (bottom axis reserved).
        drawArmRow(canvas, context.getString(R.string.cross_select),
            R.drawable.ic_cross_select, pcx - radius * 0.52f, pcy, icon, textGap, iconLeft = true)
        drawArmRow(canvas, context.getString(R.string.cross_wheel),
            R.drawable.ic_cross_wheel, pcx + radius * 0.52f, pcy, icon, textGap, iconLeft = false)
        drawArmTop(canvas, context.getString(R.string.cross_reload),
            R.drawable.ic_cross_reload, pcx, pcy - radius * 0.52f, icon)
        canvas.restore()
    }

    private fun drawArmRow(
        canvas: Canvas, label: String, iconRes: Int,
        centerX: Float, centerY: Float, icon: Float, textGap: Float, iconLeft: Boolean
    ) {
        val textW = labelPaint.measureText(label)
        val total = icon + textGap + textW
        val left = centerX - total / 2f
        val iconCx = if (iconLeft) left + icon / 2f else left + total - icon / 2f
        val textCx = if (iconLeft) left + icon + textGap + textW / 2f else left + textW / 2f
        drawGlyph(canvas, iconRes, iconCx, centerY, icon)
        canvas.drawText(
            label, textCx, centerY - (labelPaint.ascent() + labelPaint.descent()) / 2f, labelPaint
        )
    }

    private fun drawArmTop(
        canvas: Canvas, label: String, iconRes: Int, centerX: Float, centerY: Float, icon: Float
    ) {
        val shift = 11f * density
        drawGlyph(canvas, iconRes, centerX, centerY - shift, icon)
        canvas.drawText(
            label, centerX, centerY + shift - (labelPaint.ascent() + labelPaint.descent()) / 2f,
            labelPaint
        )
    }

    private fun drawGlyph(canvas: Canvas, iconRes: Int, cx: Float, cy: Float, size: Float) {
        val drawable = loadSystemIconCached(iconRes) ?: return
        val s = size / 2f
        drawable.setBounds(
            (cx - s).toInt(), (cy - s).toInt(), (cx + s).toInt(), (cy + s).toInt()
        )
        drawable.alpha = (255 * crossProgress).toInt()
        drawable.draw(canvas)
    }

    private val iconCache = HashMap<Int, Drawable?>()

    private fun loadSystemIconCached(resId: Int): Drawable? =
        iconCache.getOrPut(resId) {
            ContextCompat.getDrawable(context, resId)?.mutate()
        }

    private var highlightRects = listOf<FloatArray>()

    fun setHighlight(rects: List<FloatArray>) {
        highlightRects = rects
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = context.resources.displayMetrics.density
        for (r in highlightRects) {
            // Rects are already in window pixels (CSS px scaled + WebView offset).
            canvas.drawRect(r[0], r[1], r[0] + r[2], r[1] + r[3], HIGHLIGHT_PAINT)
        }
        if (crossProgress > 0f) {
            drawCross(canvas)
        }
        canvas.save()
        // System hotspot: the pointer tip is at (4.5dp, 3.5dp) inside the bitmap.
        canvas.translate(cursorX - 4.5f * density, cursorY - 3.5f * density)
        currentDrawable.draw(canvas)
        canvas.restore()
    }
}
