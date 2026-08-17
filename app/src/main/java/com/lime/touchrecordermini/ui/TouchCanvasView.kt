package com.lime.touchrecordermini.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.lime.touchrecordermini.recorder.TouchRecorder

/**
 * Область сбора касаний, у которой две задачи: отдать MotionEvent в [TouchRecorder]
 * и нарисовать след пальца.
 *
 * Вся логика записи живёт в TouchRecorder, View про JSON и потоки ничего не знает.
 */
class TouchCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var recorder: TouchRecorder? = null

    /** Вызывается после каждого записанного события; force = жест завершился. */
    var onEventRecorded: ((force: Boolean) -> Unit)? = null

    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.parseColor("#1E88E5")
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val recorder = this.recorder ?: return false

        when (event.actionMasked) { // Получить информацию о касании, исключая множественные касания
            MotionEvent.ACTION_DOWN -> {
                // Чтобы родитель (например, скролл) не перехватил жест на середине.
                parent?.requestDisallowInterceptTouchEvent(true)
                path.reset()
                path.moveTo(event.x, event.y)
            }

            MotionEvent.ACTION_MOVE -> {
                // Рисуем и по историческим точкам: так видно, что в пакете их несколько.
                for (h in 0 until event.historySize) {
                    path.lineTo(event.getHistoricalX(h), event.getHistoricalY(h))
                }
                path.lineTo(event.x, event.y)
            }
        }

        recorder.feed(event)

        val finished = event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        onEventRecorded?.invoke(finished) // Отобразить статистику на экране

        invalidate()
        // true обязателен уже на DOWN, иначе MOVE/UP до этой View не дойдут.
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!path.isEmpty) canvas.drawPath(path, paint)
    }
}
