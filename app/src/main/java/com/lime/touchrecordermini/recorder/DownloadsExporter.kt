package com.lime.touchrecordermini.recorder

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Запись JSON-сессии в общую папку Download/ через MediaStore.
 *
 * minSdk = 29, поэтому scoped storage доступен всегда и WRITE_EXTERNAL_STORAGE не нужен.
 * Возвращаемый content:// Uri сразу годится для стандартного «Поделиться».
 */
object DownloadsExporter {

    fun export(context: Context, meta: SessionMeta, gestures: List<Gesture>): ExportResult {
        val displayName = fileName(meta)
        val resolver = context.contentResolver

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            // Пока пишем — файл не виден другим приложениям.
            put(MediaStore.Downloads.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore не выдал Uri для записи в Download/")

        try {
            val stream = resolver.openOutputStream(uri)
                ?: error("Не удалось открыть поток записи для $uri")
            stream.use { SessionJsonWriter.write(it, meta, gestures) }
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }

        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)

        return ExportResult(
            uri = uri,
            displayName = displayName,
            gestures = gestures.size,
            events = gestures.sumOf { it.events.size },
            historySamples = gestures.sumOf { g -> g.events.sumOf { it.history.size } },
        )
    }

    private fun fileName(meta: SessionMeta): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(meta.startedAtMs))
        val shortId = meta.sessionId.take(8)
        return "touch_session_${stamp}_$shortId.json"
    }
}
