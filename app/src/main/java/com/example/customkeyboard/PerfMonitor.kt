package com.example.customkeyboard

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Printer
import android.view.FrameMetrics
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper

/**
 * A flight recorder for typing speed. While the keyboard is up it notes, for
 * every touch, how long the touch waited before the keyboard's code saw it
 * and how long the keyboard then took, which steps took the time, every
 * main-thread job that ran long, how long each screen frame took to draw,
 * and how long the app took to answer. [report] turns it into text that
 * can be pasted into a chat (hold the gear key).
 *
 * It only measures; it never changes what is typed.
 */
object PerfMonitor {

    /** Bump with each build that changes what is measured, so reports can be told apart. */
    const val BUILD = "mon8"

    private const val MAX_EVENTS = 600
    private const val MAX_FRAMES = 8000
    private const val SLOW_MESSAGE_MS = 8L

    private class Event(val kind: Char, val eventTimeMs: Long, val waitMs: Long) {
        var label = "?"
        var workMs = 0f
        var handledNs = 0L
        val spans = StringBuilder()
    }

    private class Stat {
        var n = 0
        var totalMs = 0.0
        var maxMs = 0.0
    }

    private val events = ArrayList<Event>()
    private val stats = LinkedHashMap<String, Stat>()
    private val slowMessages = ArrayList<String>()
    private val echoes = ArrayList<Float>()
    /** Per frame: [intendedVsyncNs, total, input, animation, layoutMeasure, draw, sync, commands, swap] (durations in ns). */
    private val frames = ArrayList<LongArray>()
    @Volatile
    private var framesUnreported = 0

    private var startedMs = SystemClock.uptimeMillis()
    private var current: Event? = null
    private var currentStartNs = 0L
    private var last: Event? = null
    private var moveCount = 0
    private var moveMaxWait = 0L
    private var busyMs = 0L
    private var echoStartNs = 0L
    private var gcAtStart = gcSnapshot()

    // ---------- touches ----------

    /** A finger went down ('D') or up ('U'); [eventTimeMs] is when the screen felt it. */
    fun begin(kind: Char, eventTimeMs: Long) {
        val now = SystemClock.uptimeMillis()
        val e = Event(kind, eventTimeMs, now - eventTimeMs)
        current = e
        currentStartNs = System.nanoTime()
    }

    fun label(text: String) {
        current?.label = text
    }

    /** Extra facts about the touch being handled (finger number, where in the key it landed). */
    fun detail(text: String) {
        current?.let { it.label = it.label + " " + text }
    }

    /** Something worth a line of its own between the touches (the system took a touch away, say). */
    fun note(kind: Char, text: String) {
        val e = Event(kind, SystemClock.uptimeMillis(), 0)
        e.label = text
        e.handledNs = System.nanoTime()
        if (events.size >= MAX_EVENTS) events.removeAt(0)
        events.add(e)
        notes++
    }

    /**
     * Where a letter touch landed inside the key that was typed, in percent of
     * the key's size from its centre (right and down are positive), for the
     * left and right halves of the keyboard. [moved] = the word guess typed a
     * different key from the one under the finger.
     */
    fun offset(rightSide: Boolean, dxPct: Int, dyPct: Int, moved: Boolean) {
        val o: LongArray = if (rightSide) offsetRight else offsetLeft
        o[0] = o[0] + dxPct.toLong()
        o[1] = o[1] + dyPct.toLong()
        o[2] = o[2] + 1L
        if (moved) o[3] = o[3] + 1L
    }

    // ---------- fingers on their way in, before the keyboard's code sees them ----------

    /** One finger landing, and how far in it got: the window, the keyboard's top view, the keyboard's code. */
    private class Arrival(val timeMs: Long, val id: Int, val sx: Float, val sy: Float, val size: Float) {
        var window = false
        var tree = false
        var handled = false
    }

    private val arrivals = ArrayList<Arrival>()
    private var windowCancels = 0
    private var accidentalCancels = 0

    /** Supplied by the keyboard: the key under a point on the screen ("gap", "outside"). */
    var keyAt: ((Float, Float) -> String)? = null

    private fun arrivalFor(e: MotionEvent): Arrival? {
        val m = e.actionMasked
        if (m != MotionEvent.ACTION_DOWN && m != MotionEvent.ACTION_POINTER_DOWN) return null
        val i = e.actionIndex
        val id = e.getPointerId(i)
        for (k in arrivals.indices.reversed()) {
            val a = arrivals[k]
            if (a.timeMs == e.eventTime && a.id == id) return a
            if (e.eventTime - a.timeMs > 3000L) break
        }
        val sx = if (Build.VERSION.SDK_INT >= 29) e.getRawX(i) else e.rawX
        val sy = if (Build.VERSION.SDK_INT >= 29) e.getRawY(i) else e.rawY
        val a = Arrival(e.eventTime, id, sx, sy, e.getTouchMajor(i))
        if (arrivals.size >= 1500) arrivals.removeAt(0)
        arrivals.add(a)
        return a
    }

    /** Every touch event the keyboard's window receives. */
    fun windowEvent(e: MotionEvent) {
        if (e.actionMasked == MotionEvent.ACTION_CANCEL) {
            windowCancels++
            // FLAG_CANCELED (Android 13+): the phone decided the touch was accidental (a palm, say).
            if (e.flags and 0x20 != 0) accidentalCancels++
            return
        }
        arrivalFor(e)?.window = true
    }

    /** Every touch event that reaches the keyboard's top view. */
    fun treeEvent(e: MotionEvent) {
        arrivalFor(e)?.tree = true
    }

    /** A finger landing that the keyboard's own touch code got. */
    fun handledEvent(e: MotionEvent) {
        arrivalFor(e)?.handled = true
    }

    /** One more line for the report, supplied by the keyboard (the touch model's state). */
    var extra: (() -> String)? = null

    private val offsetLeft: LongArray = LongArray(4)
    private val offsetRight: LongArray = LongArray(4)
    private var notes: Int = 0

    fun end() {
        val e = current ?: return
        val now = System.nanoTime()
        e.workMs = (now - currentStartNs) / 1e6f
        e.handledNs = now
        if (events.size >= MAX_EVENTS) events.removeAt(0)
        events.add(e)
        last = e
        current = null
    }

    fun move(eventTimeMs: Long) {
        val wait = SystemClock.uptimeMillis() - eventTimeMs
        moveCount++
        if (wait > moveMaxWait) moveMaxWait = wait
    }

    // ---------- steps ----------

    /** Times one step of the keyboard's work and files it under [name]. */
    inline fun <T> span(name: String, block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            record(name, (System.nanoTime() - start) / 1e6)
        }
    }

    fun record(name: String, ms: Double) {
        val s = stats.getOrPut(name) { Stat() }
        s.n++
        s.totalMs += ms
        if (ms > s.maxMs) s.maxMs = ms
        if (ms >= 1.0) {
            // Shown next to the touch it happened in, or the one it followed.
            val e = current ?: last ?: return
            if (e.spans.length < 160) {
                if (current == null) e.spans.append(" +")
                e.spans.append(' ').append(name).append('=').append(fmt(ms))
            }
        }
    }

    // ---------- the app's answer ----------

    /** Text was just sent to the app; [echoEnd] is called when the app reports back. */
    fun echoStart() {
        if (echoStartNs == 0L) echoStartNs = System.nanoTime()
    }

    fun echoEnd() {
        if (echoStartNs == 0L) return
        val ms = (System.nanoTime() - echoStartNs) / 1e6f
        echoStartNs = 0L
        if (echoes.size < 2000) echoes.add(ms)
        if (ms >= 30f) last?.let { if (it.spans.length < 160) it.spans.append(" appEcho=").append(fmt(ms.toDouble())) }
    }

    /** Wraps the connection to the app so every call into it is timed. */
    private var wrappedTarget: InputConnection? = null
    private var wrapped: InputConnection? = null

    fun wrap(ic: InputConnection): InputConnection {
        if (wrappedTarget === ic) wrapped?.let { return it }
        val w = TimedConnection(ic)
        wrappedTarget = ic
        wrapped = w
        return w
    }

    private class TimedConnection(target: InputConnection) : InputConnectionWrapper(target, true) {
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            echoStart()
            return span("ic.commit") { super.commitText(text, newCursorPosition) }
        }
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            echoStart()
            return span("ic.delete") { super.deleteSurroundingText(beforeLength, afterLength) }
        }
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? =
            span("ic.textBefore") { super.getTextBeforeCursor(n, flags) }
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? =
            span("ic.textAfter") { super.getTextAfterCursor(n, flags) }
        override fun getCursorCapsMode(reqModes: Int): Int =
            span("ic.capsMode") { super.getCursorCapsMode(reqModes) }
        override fun sendKeyEvent(event: KeyEvent?): Boolean =
            span("ic.keyEvent") { super.sendKeyEvent(event) }
        override fun beginBatchEdit(): Boolean = span("ic.batch") { super.beginBatchEdit() }
        override fun endBatchEdit(): Boolean = span("ic.batch") { super.endBatchEdit() }
    }

    // ---------- everything else the main thread does ----------

    private var messageStartMs = 0L
    private var messageText: String? = null

    private val printer = Printer { line ->
        if (line.startsWith(">")) {
            messageStartMs = SystemClock.uptimeMillis()
            messageText = line
        } else {
            val took = SystemClock.uptimeMillis() - messageStartMs
            busyMs += took
            if (took >= SLOW_MESSAGE_MS && slowMessages.size < 400) {
                val at = (messageStartMs - startedMs) / 1000f
                slowMessages.add(String.format("%.2fs %dms %s", at, took, shorten(messageText)))
            }
        }
    }

    /** ">>>>> Dispatching to Handler (a.b.C) {1f} a.b.D$E@2c: 0" -> "C / D$E" */
    private fun shorten(line: String?): String {
        if (line == null) return "?"
        val handler = line.substringAfter("(", "").substringBefore(")").substringAfterLast('.')
        val rest = line.substringAfter("} ", "")
        val callback = rest.substringBefore('@').substringBefore(':').substringAfterLast('.')
        val what = rest.substringAfterLast(": ", "")
        return if (callback.isEmpty() || callback == "null") "$handler what=$what" else "$handler / $callback"
    }

    private var frameThread: HandlerThread? = null
    private var frameWindow: Window? = null
    private var frameListener: Window.OnFrameMetricsAvailableListener? = null

    /** Start watching. Call when the keyboard comes up; safe to call again. */
    fun start(window: Window?) {
        Looper.getMainLooper().setMessageLogging(printer)
        if (window == null || frameWindow === window || Build.VERSION.SDK_INT < 26) return
        stopFrames()
        try {
            val thread = HandlerThread("kbmon").also { it.start() }
            val listener = Window.OnFrameMetricsAvailableListener { _, m, dropped ->
                if (dropped > 0) framesUnreported += dropped
                val row = longArrayOf(
                    m.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP),
                    m.getMetric(FrameMetrics.TOTAL_DURATION),
                    m.getMetric(FrameMetrics.INPUT_HANDLING_DURATION),
                    m.getMetric(FrameMetrics.ANIMATION_DURATION),
                    m.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION),
                    m.getMetric(FrameMetrics.DRAW_DURATION),
                    m.getMetric(FrameMetrics.SYNC_DURATION),
                    m.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION),
                    m.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION)
                )
                synchronized(frames) {
                    if (frames.size >= MAX_FRAMES) frames.removeAt(0)
                    frames.add(row)
                }
            }
            window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
            frameThread = thread
            frameWindow = window
            frameListener = listener
        } catch (e: Exception) {
            // No frame timings on this device; everything else still works.
        }
    }

    /** Stop watching (the keyboard went away). What was recorded is kept. */
    fun stop() {
        Looper.getMainLooper().setMessageLogging(null)
        stopFrames()
    }

    private fun stopFrames() {
        val w = frameWindow
        val l = frameListener
        if (w != null && l != null) {
            try {
                w.removeOnFrameMetricsAvailableListener(l)
            } catch (e: Exception) {
                // Already gone.
            }
        }
        frameThread?.quitSafely()
        frameThread = null
        frameWindow = null
        frameListener = null
    }

    fun reset() {
        events.clear()
        stats.clear()
        slowMessages.clear()
        echoes.clear()
        synchronized(frames) { frames.clear() }
        framesUnreported = 0
        startedMs = SystemClock.uptimeMillis()
        last = null
        moveCount = 0
        moveMaxWait = 0
        busyMs = 0
        echoStartNs = 0
        gcAtStart = gcSnapshot()
        offsetLeft.fill(0)
        offsetRight.fill(0)
        notes = 0
        arrivals.clear()
        windowCancels = 0
        accidentalCancels = 0
    }

    // ---------- the report ----------

    private fun gcSnapshot(): LongArray {
        fun stat(name: String): Long = try {
            Debug.getRuntimeStat(name)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        }
        return longArrayOf(
            stat("art.gc.gc-count"), stat("art.gc.gc-time"),
            stat("art.gc.blocking-gc-count"), stat("art.gc.blocking-gc-time")
        )
    }

    private fun fmt(ms: Double): String = if (ms >= 10) String.format("%.0f", ms) else String.format("%.1f", ms)

    private fun pct(sorted: List<Float>, p: Double): Float =
        if (sorted.isEmpty()) 0f else sorted[((sorted.size - 1) * p).toInt()]

    private fun summary(values: List<Float>): String {
        if (values.isEmpty()) return "none"
        val s = values.sorted()
        return "p50 ${fmt(pct(s, 0.5).toDouble())} p90 ${fmt(pct(s, 0.9).toDouble())} " +
                "max ${fmt(s.last().toDouble())} (n ${s.size})"
    }

    fun report(context: Context, appPackage: String?): String {
        val now = SystemClock.uptimeMillis()
        val sb = StringBuilder()
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val hz = try {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.refreshRate
        } catch (e: Exception) {
            0f
        }
        val thermal = if (Build.VERSION.SDK_INT >= 29) power?.currentThermalStatus ?: -1 else -1
        val rt = Runtime.getRuntime()
        sb.append("KBMON ").append(BUILD).append(" | ").append(Build.MODEL).append(" sdk ").append(Build.VERSION.SDK_INT)
            .append(" | ").append(hz.toInt()).append("Hz | powerSave=").append(power?.isPowerSaveMode)
            .append(" thermal=").append(thermal).append(" | app=").append(appPackage ?: "?").append('\n')
        sb.append("window ").append(String.format("%.1f", (now - startedMs) / 1000f)).append("s | touches ")
            .append(events.size).append(" | main thread busy ").append(busyMs).append("ms | heap ")
            .append((rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)).append("/").append(rt.maxMemory() / (1024 * 1024))
            .append("MB\n")

        val downs = events.filter { it.kind == 'D' }
        val ups = events.filter { it.kind == 'U' }
        sb.append("WAIT ms (finger -> keyboard code): down ").append(summary(downs.map { it.waitMs.toFloat() }))
            .append(" | up ").append(summary(ups.map { it.waitMs.toFloat() }))
            .append(" | moves n ").append(moveCount).append(" max ").append(moveMaxWait).append('\n')
        sb.append("WORK ms (keyboard code): down ").append(summary(downs.map { it.workMs }))
            .append(" | up ").append(summary(ups.map { it.workMs })).append('\n')

        // Finger down -> the pressed key actually on screen: the first frame after the touch.
        // (Touches are handled inside a frame, so the frame that started before the
        // keyboard's code ran is the one that draws the press.)
        val frameCopy = synchronized(frames) { ArrayList(frames) }
        val visual = ArrayList<Float>()
        var fi = 0
        for (e in downs) {
            while (fi < frameCopy.size && frameCopy[fi][0] < e.eventTimeMs * 1_000_000L) fi++
            if (fi >= frameCopy.size) break
            val f = frameCopy[fi]
            val ms = (f[0] + f[1]) / 1e6f - e.eventTimeMs
            if (ms in 0f..1000f) visual.add(ms)
        }
        sb.append("PRESS SHOWN ms (finger down -> pressed key drawn): ").append(summary(visual)).append('\n')
        sb.append("APP ECHO ms (text sent -> app answers): ").append(summary(echoes)).append('\n')

        val totals = frameCopy.map { it[1] / 1e6f }
        sb.append("FRAMES ms: ").append(summary(totals)).append(" | >17ms ").append(totals.count { it > 17f })
            .append(" | >34ms ").append(totals.count { it > 34f }).append(" | not reported ").append(framesUnreported).append('\n')
        val slowFrames = frameCopy.filter { it[1] > 20_000_000L }.sortedByDescending { it[1] }.take(12)
        if (slowFrames.isNotEmpty()) {
            sb.append("SLOW FRAMES (at total: input anim layout draw sync cmds swap):\n")
            for (f in slowFrames) {
                sb.append(String.format("%.2fs %.0f:", (f[0] / 1_000_000L - startedMs) / 1000f, f[1] / 1e6f))
                for (k in 2..8) sb.append(' ').append(fmt(f[k] / 1e6))
                sb.append('\n')
            }
        }

        sb.append("STEPS (n avg max ms):\n")
        for ((name, s) in stats.entries.sortedByDescending { it.value.totalMs }) {
            sb.append(' ').append(name).append(' ').append(s.n).append(' ').append(fmt(s.totalMs / s.n)).append(' ')
                .append(fmt(s.maxMs)).append('\n')
        }

        if (slowMessages.isNotEmpty()) {
            sb.append("SLOW MAIN-THREAD JOBS (>=").append(SLOW_MESSAGE_MS).append("ms), ").append(slowMessages.size).append(":\n")
            for (m in slowMessages.sortedByDescending { it.substringAfter(' ').substringBefore("ms").toIntOrNull() ?: 0 }.take(25)) {
                sb.append(' ').append(m).append('\n')
            }
        }

        val gc = gcSnapshot()
        sb.append("GC: count +").append(gc[0] - gcAtStart[0]).append(" time +").append(gc[1] - gcAtStart[1])
            .append("ms blocking +").append(gc[2] - gcAtStart[2]).append(" (").append(gc[3] - gcAtStart[3]).append("ms)\n")

        fun side(name: String, o: LongArray) {
            sb.append(name).append(' ')
            if (o[2] == 0L) sb.append("none") else sb.append("dx ").append(o[0] / o[2]).append(" dy ").append(o[1] / o[2])
                .append(" (n ").append(o[2]).append(", moved by word guess ").append(o[3]).append(")")
        }
        sb.append("LANDING % of key from centre (right+/down+): ")
        side("left half", offsetLeft)
        sb.append(" | ")
        side("right half", offsetRight)
        sb.append('\n')
        extra?.let { sb.append(it()).append('\n') }

        val seenWindow = arrivals.count { it.window }
        val seenTree = arrivals.count { it.tree }
        val handled = arrivals.count { it.handled }
        sb.append("ARRIVALS fingers down: at window ").append(seenWindow).append(" | at keyboard view ").append(seenTree)
            .append(" | handled ").append(handled).append(" | window cancels ").append(windowCancels)
            .append(" (phone said accidental ").append(accidentalCancels).append(")\n")
        sb.append("CONTACT SIZE px: ").append(summary(arrivals.filter { it.handled }.map { it.size })).append('\n')
        val lost = arrivals.filter { !it.handled }
        if (lost.isNotEmpty()) {
            sb.append("NEVER REACHED KEYBOARD CODE (time, key under, seen by window/view, size):\n")
            for (a in lost.takeLast(40)) {
                sb.append(String.format("%.2f", (a.timeMs - startedMs) / 1000f)).append(' ')
                    .append(keyAt?.invoke(a.sx, a.sy) ?: "?").append(' ')
                    .append(if (a.window) "W" else "-").append(if (a.tree) "V" else "-").append(' ')
                    .append(a.size.toInt()).append('\n')
            }
        }
        sb.append("TOUCHES TAKEN AWAY / NOTES: ").append(notes).append('\n')
        sb.append("TOUCHES (time, D/U/C, key landing%x,y [raw=key under finger] #finger, wait/work ms, slow steps):\n")
        for (e in events.takeLast(360)) {
            sb.append(String.format("%.2f", (e.eventTimeMs - startedMs) / 1000f)).append(' ').append(e.kind).append(' ')
                .append(e.label).append(' ').append(e.waitMs).append('/').append(fmt(e.workMs.toDouble()))
            if (e.spans.isNotEmpty()) sb.append(e.spans)
            sb.append('\n')
        }
        return sb.toString()
    }
}
