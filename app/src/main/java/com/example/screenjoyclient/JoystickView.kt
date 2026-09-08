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
import kotlin.math.hypot
import kotlin.math.min

/**
 * 원형 베이스 + 드래그 가능한 노브로 이루어진 아날로그 스틱.
 * onStickMoved로 -1f..1f 범위의 정규화된 x,y를 전달한다 (y는 위쪽이 양수).
 */
class JoystickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onStickMoved: ((x: Float, y: Float) -> Unit)? = null

    private val accentColor = Color.parseColor("#00E5FF")

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val baseBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(140, 0, 229, 255)
    }
    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knobBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = accentColor
    }

    private var centerX = 0f
    private var centerY = 0f
    private var baseRadius = 0f
    private var knobRadius = 0f

    private var knobX = 0f
    private var knobY = 0f
    private var isActive = false

    private var trackedPointerId = MotionEvent.INVALID_POINTER_ID

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        baseRadius = min(w, h) / 2f
        knobRadius = baseRadius / 2.5f
        knobX = centerX
        knobY = centerY
        baseBorderPaint.strokeWidth = baseRadius * 0.04f
        knobBorderPaint.strokeWidth = baseRadius * 0.05f
        basePaint.shader = RadialGradient(
            centerX, centerY, baseRadius.coerceAtLeast(1f),
            intArrayOf(Color.argb(70, 25, 35, 40), Color.argb(45, 10, 16, 18)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        updateKnobShader()
    }

    private fun updateKnobShader() {
        val r = knobRadius.coerceAtLeast(1f)
        val (innerAlpha, outerAlpha) = if (isActive) 220 to 160 else 170 to 110
        knobPaint.shader = RadialGradient(
            centerX, centerY, r,
            intArrayOf(Color.argb(innerAlpha, 235, 250, 255), Color.argb(outerAlpha, 0, 229, 255)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawCircle(centerX, centerY, baseRadius - baseBorderPaint.strokeWidth / 2f, basePaint)
        canvas.drawCircle(centerX, centerY, baseRadius - baseBorderPaint.strokeWidth / 2f, baseBorderPaint)
        canvas.drawCircle(knobX, knobY, knobRadius, knobPaint)
        knobBorderPaint.color = if (isActive) accentColor else Color.argb(150, 0, 229, 255)
        canvas.drawCircle(knobX, knobY, knobRadius - knobBorderPaint.strokeWidth / 2f, knobBorderPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 이 뷰가 받는 모든 포인터가 아니라, 노브를 처음 잡은 손가락(pointerId) 하나만 추적한다.
        // 같은 뷰 안에 다른 손가락이 더 들어와도 노브가 그쪽으로 튀지 않도록 하기 위함.
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                trackedPointerId = event.getPointerId(0)
                isActive = true
                updateKnobShader()
                updateKnob(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(trackedPointerId)
                if (index != -1) {
                    updateKnob(event.getX(index), event.getY(index))
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == trackedPointerId) {
                    resetKnob()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                resetKnob()
            }
        }
        return true
    }

    private fun resetKnob() {
        trackedPointerId = MotionEvent.INVALID_POINTER_ID
        isActive = false
        updateKnobShader()
        knobX = centerX
        knobY = centerY
        invalidate()
        onStickMoved?.invoke(0f, 0f)
    }

    private fun updateKnob(rawX: Float, rawY: Float) {
        val dx = rawX - centerX
        val dy = rawY - centerY
        val distance = hypot(dx, dy)
        val maxDistance = baseRadius - knobRadius

        if (distance <= maxDistance) {
            knobX = rawX
            knobY = rawY
        } else {
            val ratio = maxDistance / distance
            knobX = centerX + dx * ratio
            knobY = centerY + dy * ratio
        }
        invalidate()

        val normX = (knobX - centerX) / maxDistance
        // 화면 좌표는 아래로 갈수록 Y가 커지므로, 스틱은 위=양수가 되도록 반전
        val normY = -(knobY - centerY) / maxDistance
        onStickMoved?.invoke(normX.coerceIn(-1f, 1f), normY.coerceIn(-1f, 1f))
    }
}