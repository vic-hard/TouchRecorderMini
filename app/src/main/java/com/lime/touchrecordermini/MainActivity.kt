package com.lime.touchrecordermini

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.lime.touchrecordermini.recorder.TouchRecorder
import com.lime.touchrecordermini.ui.TouchCanvasView
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var recorder: TouchRecorder
    private lateinit var statsView: TextView
    private lateinit var shareButton: Button
    private lateinit var exportButton: Button

    /** Экспорт (сериализация + запись файла) не должен идти на UI-потоке. */
    private val io = Executors.newSingleThreadExecutor()

    private var lastExportUri: Uri? = null
    private var lastStatsRenderMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recorder = TouchRecorder(this)
        recorder.start()

        statsView = findViewById(R.id.stats)
        exportButton = findViewById(R.id.export)
        shareButton = findViewById(R.id.share)

        val canvas = findViewById<TouchCanvasView>(R.id.canvas)
        canvas.recorder = recorder
        canvas.onEventRecorded = { force -> renderStats(force) }

        exportButton.setOnClickListener { exportSession() }
        shareButton.setOnClickListener { shareLastExport() }

        renderStats(force = true)
    }

    override fun onDestroy() {
        io.shutdown()
        recorder.stop()
        super.onDestroy()
    }

    /**
     * Счётчики обновляем не чаще ~20 раз в секунду: на 120 Гц каждый MOVE
     * дёргал бы перевёрстку TextView прямо в обработчике касания.
     */
    private fun renderStats(force: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastStatsRenderMs < 50) return
        lastStatsRenderMs = now

        val stats = recorder.stats()
        statsView.text = getString(R.string.stats_format, stats.events, stats.historySamples)
    }

    private fun exportSession() {
        exportButton.isEnabled = false
        io.execute {
            val message = try {
                val result = recorder.export()
                runOnUiThread {
                    lastExportUri = result.uri
                    shareButton.isEnabled = true
                }
                getString(
                    R.string.export_ok,
                    result.displayName,
                    result.gestures,
                    result.events,
                    result.historySamples,
                )
            } catch (t: Throwable) {
                getString(R.string.export_error, t.message ?: t.javaClass.simpleName)
            }
            runOnUiThread {
                exportButton.isEnabled = true
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun shareLastExport() {
        val uri = lastExportUri ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_title)))
    }
}
