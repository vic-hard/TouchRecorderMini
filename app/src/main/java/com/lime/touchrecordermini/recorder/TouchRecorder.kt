package com.lime.touchrecordermini.recorder

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Сборщик касаний. Не знает ничего про Activity, View и разметку —
 * снаружи ему отдают MotionEvent, он отдаёт статистику и файл.
 *
 * Публичный API: [start], [feed], [stats], [export], [stop].
 *
 * Потоковая модель (п. 2.1.4 ТЗ):
 *  - UI-поток в [feed] только копирует поля MotionEvent в [TouchEventRecord]
 *    (включая цикл по историческим сэмплам) и кладёт запись в очередь;
 *  - фоновый worker разбирает очередь и накапливает жесты в памяти;
 *  - сериализация и запись файла идут вне UI-потока, в потоке вызвавшего [export].
 */
class TouchRecorder(context: Context) {

    private val appContext = context.applicationContext

    /** Метаданные фиксируются в момент создания сессии. */
    val meta: SessionMeta = SessionMeta.create(appContext)

    /** Очередь команд UI-поток -> worker. Допустимые сообщения перечислены в [Command]. */
    private val queue = LinkedBlockingQueue<Command>()

    private val gestures = mutableListOf<Gesture>()

    private var worker: Thread? = null

    @Volatile
    private var running = false

    // Счётчики инкрементируются на UI-потоке в момент постановки в очередь,
    // чтобы экранная статистика не отставала от реального ввода.
    private val eventCount = AtomicLong(0)
    private val historyCount = AtomicLong(0)
    private val gestureCounter = AtomicInteger(0)

    /** id указателя, с которого начался текущий жест; остальные пальцы игнорируем (п. 7 ТЗ). */
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var currentGestureId = 0

    fun start() {
        if (running) return
        running = true
        worker = Thread({ workerLoop() }, "touch-recorder-worker").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!running) return
        running = false
        queue.put(Command.Stop)
        worker?.join(2_000)
        worker = null
    }

    fun stats(): RecorderStats = RecorderStats(eventCount.get(), historyCount.get())

    /**
     * Вызывается из View.onTouchEvent на UI-потоке.
     * @return true, если событие принято к записи.
     */
    fun feed(ev: MotionEvent): Boolean {
        if (!running) return false

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // всегда брать информацию только о первом пальце, multi-touch не обрабатывать
                activePointerId = ev.getPointerId(0)
                currentGestureId = gestureCounter.incrementAndGet()
            }

            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (activePointerId == MotionEvent.INVALID_POINTER_ID) return false
            }

            // ACTION_POINTER_DOWN / ACTION_POINTER_UP и прочее — лишние пальцы, не пишем.
            else -> return false
        }

        val pointerIndex = ev.findPointerIndex(activePointerId)
        if (pointerIndex < 0) return false

        // Время обработки снимаем до чтения полей, чтобы оно относилось к моменту прихода события.
        val procTimeNs = SystemClock.elapsedRealtimeNanos()

        // Исторические сэмплы читаем для любого типа события: их наличие зависит
        // от того, как система склеила пакет, а не от action (п. 2.1.2 ТЗ).
        val historySize = ev.historySize
        val history = ArrayList<HistorySample>(historySize)
        for (h in 0 until historySize) {
            history.add(
                HistorySample(
                    eventTimeMs = ev.getHistoricalEventTime(h),
                    x = ev.getHistoricalX(pointerIndex, h),
                    y = ev.getHistoricalY(pointerIndex, h),
                    pressure = ev.getHistoricalPressure(pointerIndex, h),
                    size = ev.getHistoricalSize(pointerIndex, h),
                )
            )
        }

        val record = TouchEventRecord(
            gestureId = currentGestureId,
            action = actionName(ev.actionMasked),
            eventTimeMs = ev.eventTime,
            procTimeNs = procTimeNs,
            x = ev.getX(pointerIndex),
            y = ev.getY(pointerIndex),
            pressure = ev.getPressure(pointerIndex),
            size = ev.getSize(pointerIndex),
            touchMajor = ev.getTouchMajor(pointerIndex),
            touchMinor = ev.getTouchMinor(pointerIndex),
            history = history,
        )

        queue.put(Command.Event(record))
        eventCount.incrementAndGet()
        historyCount.addAndGet(historySize.toLong())

        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            activePointerId = MotionEvent.INVALID_POINTER_ID
        }
        return true
    }

    /**
     * Сериализует накопленное в JSON-файл в Download/.
     * Блокирующий вызов — вызывать из фонового потока, не из UI.
     */
    fun export(): ExportResult {
        val snapshot = snapshot()
        return DownloadsExporter.export(appContext, meta, snapshot)
    }

    /**
     * Просит worker отдать копию накопленных жестов.
     * Копия делается на worker-потоке, поэтому [gestures] остаётся однопоточным,
     * а публикация через очередь даёт корректную видимость памяти.
     */
    private fun snapshot(): List<Gesture> {
        check(running) { "TouchRecorder не запущен" }
        val request = Command.Snapshot()
        queue.put(request)
        return request.reply.take()
    }

    private fun workerLoop() {
        while (true) {
            // when по sealed-типу исчерпывающий: забыть ветку для нового
            // типа команды не даст компилятор, сообщение не потеряется молча.
            when (val command = queue.take()) {
                is Command.Event -> append(command.record)
                is Command.Snapshot -> command.reply.put(copyGestures())
                Command.Stop -> return
            }
        }
    }

    private fun append(record: TouchEventRecord) {
        val last = gestures.lastOrNull()
        if (last == null || last.gestureId != record.gestureId) {
            gestures.add(Gesture(record.gestureId, mutableListOf(record)))
        } else {
            last.events.add(record)
        }
    }

    private fun copyGestures(): List<Gesture> =
        gestures.map { Gesture(it.gestureId, ArrayList(it.events)) }

    private fun actionName(actionMasked: Int): String = when (actionMasked) {
        MotionEvent.ACTION_DOWN -> "down"
        MotionEvent.ACTION_MOVE -> "move"
        MotionEvent.ACTION_UP -> "up"
        MotionEvent.ACTION_CANCEL -> "cancel"
        else -> "other"
    }

    /**
     * Протокол общения с worker-потоком. Всё, что может прийти в [queue].
     *
     * Порядок в очереди даёт гарантию: [Snapshot] не обгонит уже
     * поставленные [Event], а [Stop] не оборвёт разбор того, что пришло раньше.
     */
    private sealed interface Command {

        /** Записанное событие касания. */
        class Event(val record: TouchEventRecord) : Command

        /** Запрос копии накопленных жестов; ответ приходит в [reply]. */
        class Snapshot : Command {
            val reply = ArrayBlockingQueue<List<Gesture>>(1)
        }

        /** Завершить worker после разбора всего, что стоит в очереди перед этой командой. */
        data object Stop : Command
    }
}
