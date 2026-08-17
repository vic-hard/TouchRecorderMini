package com.lime.touchrecordermini.recorder

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.view.WindowManager
import com.lime.touchrecordermini.BuildConfig
import java.util.UUID

/**
 * Один исторический сэмпл из MotionEvent (getHistoricalX/Y/Pressure/Size/EventTime).
 */
data class HistorySample(
    val eventTimeMs: Long,
    val x: Float,
    val y: Float,
    val pressure: Float,
    val size: Float,
)

/**
 * Снятые с MotionEvent данные одного события.
 *
 * Важно: это обычный immutable-объект. Сам MotionEvent переиспользуется системой
 * и после возврата из onTouchEvent недействителен, поэтому все поля читаются
 * синхронно на UI-потоке, а в фоновый поток уходит уже эта копия.
 */
data class TouchEventRecord(
    val gestureId: Int,
    val action: String,
    val eventTimeMs: Long,
    val procTimeNs: Long,
    val x: Float,
    val y: Float,
    val pressure: Float,
    val size: Float,
    val touchMajor: Float,
    val touchMinor: Float,
    val history: List<HistorySample>,
)

/** Жест: от ACTION_DOWN до ACTION_UP / ACTION_CANCEL. */
class Gesture(
    val gestureId: Int,
    val events: MutableList<TouchEventRecord> = mutableListOf(),
)

/** Метаданные сессии (п. 2.1.6 ТЗ). */
data class SessionMeta(
    val sessionId: String,
    val startedAtMs: Long,
    val manufacturer: String,
    val model: String,
    val sdkInt: Int,
    val screenWidthPx: Int,
    val screenHeightPx: Int,
    val appVersion: String,
) {
    companion object {
        fun create(context: Context): SessionMeta {
            val (w, h) = screenSize(context)
            return SessionMeta(
                sessionId = UUID.randomUUID().toString(),
                startedAtMs = System.currentTimeMillis(),
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                sdkInt = Build.VERSION.SDK_INT,
                screenWidthPx = w,
                screenHeightPx = h,
                appVersion = BuildConfig.VERSION_NAME,
            )
        }

        private fun screenSize(context: Context): Pair<Int, Int> {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = wm.currentWindowMetrics.bounds
                bounds.width() to bounds.height()
            } else {
                @Suppress("DEPRECATION")
                val point = Point().also { wm.defaultDisplay.getRealSize(it) }
                point.x to point.y
            }
        }
    }
}

/** Текущая статистика сессии для экранных счётчиков. */
data class RecorderStats(val events: Long, val historySamples: Long)

/** Результат экспорта: куда легло и что внутри. */
data class ExportResult(
    val uri: android.net.Uri,
    val displayName: String,
    val gestures: Int,
    val events: Int,
    val historySamples: Int,
)
