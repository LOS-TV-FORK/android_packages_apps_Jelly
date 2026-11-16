/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.jelly.ui

import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
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

    fun setIconKind(kind: String) {
        val d = if (kind == "arrow") arrow
        else arrowCache.getOrPut(kind) { loadSystemIcon("pointer_$kind") ?: arrow }
        setArrowDrawable(d)
    }

    /**
     * Dispatches a synthetic hover event at the cursor position (real hover feedback in the
     * page) and asks the activity to resolve the cursor icon.
     */
    fun onCursorSettled() {
        val loc = windowLocation()
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_HOVER_MOVE,
            cursorX + loc[0], cursorY + loc[1], 0
        )
        event.source = android.view.InputDevice.SOURCE_MOUSE
        rootView.dispatchGenericMotionEvent(event)
        event.recycle()
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
        canvas.save()
        // System hotspot: the pointer tip is at (4.5dp, 3.5dp) inside the bitmap.
        canvas.translate(cursorX - 4.5f * density, cursorY - 3.5f * density)
        currentDrawable.draw(canvas)
        canvas.restore()
    }
}
