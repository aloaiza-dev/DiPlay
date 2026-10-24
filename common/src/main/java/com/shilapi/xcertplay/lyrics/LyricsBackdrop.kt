package com.shilapi.xcertplay.lyrics

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler

/** Copies the picture, scaled into [into], and says whether it could. Called on the main thread. */
internal fun interface FrameSource {
    fun capture(into: Bitmap): Boolean
}

/** A view that draws a blurred copy of the picture behind it as its glass. Bounds are in the picture's coordinates. */
internal interface BackdropTarget {
    /** False while the view is not showing: it costs nothing then. */
    fun backdropBounds(out: Rect): Boolean

    /** A bitmap to draw behind the glass, or null to fall back to plain glass. */
    fun onBackdrop(bitmap: Bitmap?)

    /** A big card of text asks for a denser tint than a small pill. */
    val opaque: Boolean get() = false
}

internal interface LyricsBackdropControl {
    fun setNight(night: Boolean)
    fun wake()
    fun sleep()
}

/**
 * Keeps a blurred, shaded copy of the CarPlay picture behind the lyric glass. The cost is bounded on purpose,
 * because head units differ a lot in power:
 *  - one small copy of the whole picture (at most [MAX_FRAME_WIDTH] px wide) serves every target;
 *  - it runs only while [wake]n, which the lyrics do while a view shows;
 *  - a picture that has not changed is copied less and less often;
 *  - a slow copy slows the rate down, and copies that stay far too slow turn the blur off for the session,
 *    so the targets fall back to plain glass instead of making the video stutter.
 */
internal class LyricsBackdropSampler(
    private val source: FrameSource,
    private val sourceWidth: () -> Int,
    private val sourceHeight: () -> Int,
    private val handler: Handler,
    private val nowNanos: () -> Long = System::nanoTime,
    /** Asked on every [wake], so the setting takes effect at once. */
    private val enabled: () -> Boolean = { true },
) : LyricsBackdropControl {
    private val targets = mutableListOf<BackdropTarget>()
    private val outputs = HashMap<BackdropTarget, Bitmap>()
    private val scrims = arrayOfNulls<LyricsScrim>(4)
    private var night = true
    private val lastBounds = HashMap<BackdropTarget, Rect?>()
    private var captureFailures = 0
    private var frame: Bitmap? = null
    private var pixels = IntArray(0)
    private var work = IntArray(0)
    private var scratch = IntArray(0)
    private val bounds = Rect()
    private var active = false
    private var baseMillis = BASE_INTERVAL_MILLIS
    private var staticRuns = 0
    private var slowRuns = 0
    private var lastSignature = 0L

    var disabled = false
        private set

    private val tick: Runnable = Runnable {
        if (!active || disabled) return@Runnable
        sample()
        if (active && !disabled) handler.postDelayed(tick, intervalMillis())
    }

    fun addTarget(target: BackdropTarget) {
        targets += target
    }

    override fun setNight(night: Boolean) {
        this.night = night
    }

    private fun scrimFor(target: BackdropTarget): LyricsScrim {
        val index = (if (night) 0 else 1) + (if (target.opaque) 2 else 0)
        return scrims[index] ?: (if (target.opaque) {
            LyricsScrim(LyricsTheme.railColors(night), LyricsScrim.RAIL_MIN_TINT)
        } else {
            LyricsScrim.pill(night)
        }).also { scrims[index] = it }
    }

    override fun wake() {
        if (disabled) return
        if (!enabled()) {
            if (active || outputs.isNotEmpty()) stopAndClear()
            return
        }
        if (active) {
            if (targetsChanged()) {
                // A view that came back must not show the bitmap of its last appearance, and a new one should
                // not wait out the slow rate of a still picture.
                staticRuns = 0
                handler.removeCallbacks(tick)
                handler.post(tick)
            }
            return
        }
        active = true
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    // True when a target appeared, went away or moved since the last copy. A hidden one drops its bitmap.
    private fun targetsChanged(): Boolean {
        var changed = false
        val now = Rect()
        for (target in targets) {
            val visible = target.backdropBounds(now)
            val before = lastBounds[target]
            val same = if (visible) before == now else before == null
            if (same) continue
            changed = true
            if (!visible && outputs.remove(target) != null) target.onBackdrop(null)
        }
        return changed
    }

    private fun stopAndClear() {
        active = false
        handler.removeCallbacks(tick)
        val shown = outputs.keys.toList()
        outputs.clear()
        shown.forEach { it.onBackdrop(null) }
    }

    override fun sleep() {
        active = false
        handler.removeCallbacks(tick)
    }

    private fun intervalMillis() = (baseMillis * (1 + staticRuns)).coerceAtMost(MAX_INTERVAL_MILLIS)

    private fun sample() {
        val width = sourceWidth()
        val height = sourceHeight()
        if (width <= 0 || height <= 0) return
        val started = nowNanos()
        val k = ((width + MAX_FRAME_WIDTH - 1) / MAX_FRAME_WIDTH).coerceAtLeast(1)
        val fw = (width / k).coerceAtLeast(1)
        val fh = (height / k).coerceAtLeast(1)
        var copy = frame
        if (copy == null || copy.width != fw || copy.height != fh) {
            copy = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888)
            frame = copy
            pixels = IntArray(fw * fh)
        }
        if (!source.capture(copy)) {
            // The picture cannot be copied (no surface yet, or a zero size): plain glass, and back off.
            if (++captureFailures == CAPTURE_FAILURES_LIMIT) clearOutputs()
            baseMillis = (baseMillis * 2).coerceAtMost(MAX_INTERVAL_MILLIS)
            return
        }
        captureFailures = 0
        copy.getPixels(pixels, 0, fw, 0, 0, fw, fh)
        val signature = signatureOf(pixels)
        val changed = signature != lastSignature
        lastSignature = signature
        for (target in targets) {
            if (target.backdropBounds(bounds)) {
                lastBounds[target] = Rect(bounds)
                render(target, scrimFor(target), k, fw, fh)
            } else {
                lastBounds[target] = null
            }
        }
        adapt(nowNanos() - started, changed)
    }

    // A few evenly spread pixels are enough to tell a still picture from a moving one.
    private fun signatureOf(pixels: IntArray): Long {
        var sum = 0L
        var i = 0
        while (i < pixels.size) {
            sum = sum * 31 + pixels[i]
            i += SIGNATURE_STRIDE
        }
        return sum
    }

    private fun render(target: BackdropTarget, shade: LyricsScrim, k: Int, fw: Int, fh: Int) {
        val x0 = (bounds.left / k).coerceIn(0, fw - 1)
        val y0 = (bounds.top / k).coerceIn(0, fh - 1)
        val x1 = ((bounds.right + k - 1) / k).coerceIn(x0 + 1, fw)
        val y1 = ((bounds.bottom + k - 1) / k).coerceIn(y0 + 1, fh)
        val w = x1 - x0
        val h = y1 - y0
        if (work.size < w * h) {
            work = IntArray(w * h)
            scratch = IntArray(w * h)
        }
        for (row in 0 until h) System.arraycopy(pixels, (y0 + row) * fw + x0, work, row * w, w)
        repeat(BLUR_PASSES) {
            blurAxis(work, scratch, w, h, horizontal = true)
            blurAxis(scratch, work, w, h, horizontal = false)
        }
        for (i in 0 until w * h) work[i] = shade.shade(work[i])
        var out = outputs[target]
        if (out == null || out.width != w || out.height != h) {
            out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            outputs[target] = out
        }
        out.setPixels(work, 0, w, 0, 0, w, h)
        target.onBackdrop(out)
    }

    // A running-sum box blur along one axis, with the edge pixel repeated beyond the region.
    private fun blurAxis(src: IntArray, dst: IntArray, w: Int, h: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val length = if (horizontal) w else h
        val lineStride = if (horizontal) w else 1
        val step = if (horizontal) 1 else w
        val window = 2 * BLUR_RADIUS + 1
        for (line in 0 until lines) {
            val base = line * lineStride
            var r = 0
            var g = 0
            var b = 0
            for (i in -BLUR_RADIUS..BLUR_RADIUS) {
                val p = src[base + i.coerceIn(0, length - 1) * step]
                r += (p ushr 16) and 0xFF
                g += (p ushr 8) and 0xFF
                b += p and 0xFF
            }
            for (i in 0 until length) {
                dst[base + i * step] = (0xFF shl 24) or ((r / window) shl 16) or ((g / window) shl 8) or (b / window)
                val leaving = src[base + (i - BLUR_RADIUS).coerceIn(0, length - 1) * step]
                val entering = src[base + (i + BLUR_RADIUS + 1).coerceIn(0, length - 1) * step]
                r += ((entering ushr 16) and 0xFF) - ((leaving ushr 16) and 0xFF)
                g += ((entering ushr 8) and 0xFF) - ((leaving ushr 8) and 0xFF)
                b += (entering and 0xFF) - (leaving and 0xFF)
            }
        }
    }

    private fun adapt(costNanos: Long, changed: Boolean) {
        if (costNanos > DISABLE_NANOS) {
            if (++slowRuns >= DISABLE_AFTER_RUNS) {
                disable()
                return
            }
        } else {
            slowRuns = 0
        }
        if (costNanos > SLOW_NANOS) baseMillis = (baseMillis * 2).coerceAtMost(MAX_INTERVAL_MILLIS)
        else if (costNanos < FAST_NANOS && baseMillis > BASE_INTERVAL_MILLIS) baseMillis = (baseMillis * 3 / 4).coerceAtLeast(BASE_INTERVAL_MILLIS)
        staticRuns = if (changed) 0 else (staticRuns + 1).coerceAtMost(MAX_STATIC_RUNS)
    }

    private fun clearOutputs() {
        val shown = outputs.keys.toList()
        outputs.clear()
        shown.forEach { it.onBackdrop(null) }
    }

    private fun disable() {
        disabled = true
        active = false
        handler.removeCallbacks(tick)
        outputs.clear()
        frame = null
        pixels = IntArray(0)
        work = IntArray(0)
        scratch = IntArray(0)
        targets.forEach { it.onBackdrop(null) }
    }

    internal companion object {
        const val MAX_FRAME_WIDTH = 320
        private const val BASE_INTERVAL_MILLIS = 150L
        private const val MAX_INTERVAL_MILLIS = 1_500L
        private const val MAX_STATIC_RUNS = 5
        private const val BLUR_RADIUS = 3
        private const val BLUR_PASSES = 2
        private const val SIGNATURE_STRIDE = 17
        private const val CAPTURE_FAILURES_LIMIT = 3
        // Starting values: none is measured on a car yet.
        private const val FAST_NANOS = 6_000_000L
        private const val SLOW_NANOS = 12_000_000L
        private const val DISABLE_NANOS = 30_000_000L
        private const val DISABLE_AFTER_RUNS = 3
    }
}
