package com.lime.touchrecordermini.recorder

import android.util.JsonWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.math.BigDecimal

/**
 * Сериализация сессии в JSON по схеме из п. 2.2 ТЗ.
 *
 * Пишем потоково прямо в OutputStream (android.util.JsonWriter, без внешних зависимостей):
 * длинный свайп на 120 Гц с историческими сэмплами даёт десятки тысяч точек,
 * и держать весь документ строкой в памяти незачем.
 */
object SessionJsonWriter {

    fun write(out: OutputStream, meta: SessionMeta, gestures: List<Gesture>) {
        JsonWriter(OutputStreamWriter(out, Charsets.UTF_8)).use { w ->
            w.setIndent("  ")
            w.beginObject()

            w.name("session_id").value(meta.sessionId)
            w.name("started_at_ms").value(meta.startedAtMs)

            w.name("device").beginObject()
            w.name("manufacturer").value(meta.manufacturer)
            w.name("model").value(meta.model)
            w.name("sdk_int").value(meta.sdkInt.toLong())
            w.name("screen").beginObject()
            w.name("width_px").value(meta.screenWidthPx.toLong())
            w.name("height_px").value(meta.screenHeightPx.toLong())
            w.endObject()
            w.name("app_version").value(meta.appVersion)
            w.endObject()

            w.name("gestures").beginArray()
            for (gesture in gestures) {
                w.beginObject()
                w.name("gesture_id").value(gesture.gestureId.toLong())
                w.name("events").beginArray()
                for (event in gesture.events) {
                    writeEvent(w, event)
                }
                w.endArray()
                w.endObject()
            }
            w.endArray()

            w.endObject()
        }
    }

    private fun writeEvent(w: JsonWriter, e: TouchEventRecord) {
        w.beginObject()
        w.name("action").value(e.action)
        w.name("event_time_ms").value(e.eventTimeMs)
        w.name("proc_time_ns").value(e.procTimeNs)
        w.name("x").floatValue(e.x)
        w.name("y").floatValue(e.y)
        w.name("pressure").floatValue(e.pressure)
        w.name("size").floatValue(e.size)
        w.name("touch_major").floatValue(e.touchMajor)
        w.name("touch_minor").floatValue(e.touchMinor)
        w.name("history").beginArray()
        for (h in e.history) {
            w.beginObject()
            w.name("event_time_ms").value(h.eventTimeMs)
            w.name("x").floatValue(h.x)
            w.name("y").floatValue(h.y)
            w.name("pressure").floatValue(h.pressure)
            w.name("size").floatValue(h.size)
            w.endObject()
        }
        w.endArray()
        w.endObject()
    }

    /**
     * Float -> JSON-число.
     *
     * Через BigDecimal(v.toString()), а не value(v.toDouble()): расширение float до double
     * печатает 0.42f как 0.41999998688697815. Драйверы некоторых устройств отдают для
     * pressure/size NaN — это невалидный JSON, поэтому такие значения пишем как null.
     */
    private fun JsonWriter.floatValue(v: Float) {
        if (v.isFinite()) value(BigDecimal(v.toString())) else nullValue()
    }
}
