package com.example.screenjoyclient

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * D-Pad/ABXY 버튼 하나를 그리는 원형 커스텀 뷰. 오버레이 창 하나당 이 뷰 하나가 들어간다
 * (기본 Button 대신 JoystickView와 같은 패턴으로 직접 그림).
 */
class GamepadButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var label: String = ""
        set(value) {
            field = value
            invalidate()
        }

    /** 눌림 상태가 바뀔 때(down/up)마다 알림 — 게임 상태 갱신은 이 콜백에서 처리한다. */
    var onPressChanged: ((Boolean) -> Unit)? = null

    private var isPressedVisual = false
    private var trackedPointerId = MotionEvent.INVALID_POINTER_ID

    private val accentColor = Color.parseColor("#00E5FF")

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = accentColor
        strokeWidth = 0f // onSizeChanged에서 크기에 비례해 재설정
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        radius = min(w, h) / 2f - borderPaint.strokeWidth
        borderPaint.strokeWidth = radius * 0.05f
        textPaint.textSize = radius * 0.8f
        updateFillShader()
    }

    private fun updateFillShader() {
        val alpha = if (isPressedVisual) 210 else 110
        val edgeAlpha = if (isPressedVisual) 160 else 70
        fillPaint.shader = RadialGradient(
            centerX, centerY, radius.coerceAtLeast(1f),
            intArrayOf(
                Color.argb(alpha, 20, 30, 34),
                Color.argb(edgeAlpha, 10, 16, 18)
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawCircle(centerX, centerY, radius, fillPaint)
        borderPaint.color = if (isPressedVisual) accentColor else Color.argb(160, 0, 229, 255)
        canvas.drawCircle(centerX, centerY, radius - borderPaint.strokeWidth / 2f, borderPaint)
        val textY = centerY - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, centerX, textY, textPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                trackedPointerId = event.getPointerId(0)
                setPressedVisual(true)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == trackedPointerId) {
                    trackedPointerId = MotionEvent.INVALID_POINTER_ID
                    setPressedVisual(false)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                trackedPointerId = MotionEvent.INVALID_POINTER_ID
                setPressedVisual(false)
            }
        }
        return true
    }

    private fun setPressedVisual(pressed: Boolean) {
        if (isPressedVisual == pressed) return
        isPressedVisual = pressed
        updateFillShader()
        invalidate()
        onPressChanged?.invoke(pressed)
    }
}
