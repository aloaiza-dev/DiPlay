package com.shilapi.xcertplay

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.shilapi.xcertplay.airplay.AirPlayInsets
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay

/** G9 Vision's bounded presentation-window route, with DiPlay's own alternate video as source.
 * Main-thread only. No screen capture, activity launch on the instrument, or OEM state writes.
 */
internal class XpengMapWindow(context: Context, private val onSurface: (Surface?) -> Unit,
    private val report: (String) -> Unit) : AutoCloseable {
    private val display = checkNotNull(target(context)) { "XPeng cluster display unavailable" }
    private val displayContext = context.createDisplayContext(display)
    private val manager = displayContext.getSystemService(WindowManager::class.java)
    private val root = XpengMapMask(displayContext)
    private var output: Surface? = null
    private var added = false
    private var active = false
    private var receivedFrame = false
    private val video = TextureView(displayContext).apply { isOpaque = false; visibility = View.INVISIBLE }

    val showing: Boolean get() = added && active && receivedFrame

    init {
        video.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                texture.setDefaultBufferSize(WIDTH, HEIGHT)
                output = Surface(texture).also(onSurface)
                report("XPeng map: surface ready ${WIDTH}x$HEIGHT")
            }
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                if (!receivedFrame) { receivedFrame = true; report("XPeng map: first frame presented") }
            }
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                releaseSurface()
                return true
            }
        }
        root.addView(video, FrameLayout.LayoutParams(WIDTH, HEIGHT))
    }

    fun show() {
        if (added) return
        val size = Point().also(display::getRealSize)
        val params = WindowManager.LayoutParams(WIDTH, HEIGHT, TYPE_PRESENTATION,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = (size.x - WIDTH) / 2
            y = (size.y - HEIGHT) / 2
            setTitle("DiPlay XPeng cluster map")
        }
        manager.addView(root, params)
        added = true
        report("XPeng map: window added display=${display.displayId} rect=${params.x},${params.y},$WIDTH,$HEIGHT")
    }

    fun setStreamActive(value: Boolean) {
        active = value
        // TextureView must be visible to create its Surface and receive the first decoded frame.
        video.visibility = if (value) View.VISIBLE else View.INVISIBLE
        if (!value) receivedFrame = false
    }

    private fun releaseSurface() {
        receivedFrame = false
        if (output == null) return
        onSurface(null)
        output?.release()
        output = null
    }

    override fun close() {
        active = false
        releaseSurface()
        if (added) {
            added = false
            runCatching { manager.removeViewImmediate(root) }
                .onFailure { report("XPeng map: removal failed ${it.javaClass.simpleName}") }
        }
        video.surfaceTextureListener = null
    }

    companion object {
        // Hidden in the public SDK; same framework window type used by G9 Vision.
        private const val TYPE_PRESENTATION = 2037
        const val WIDTH = 1920
        const val HEIGHT = 720
        const val PREFS = "xpeng_map"
        const val KEY = "xpeng_map_window_experimental"
        fun enabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
        fun saveEnabled(context: Context, value: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply()
        }
        fun available(context: Context) = target(context) != null

        /** The driver switched the window on and this head unit can show it. */
        fun active(context: Context) = enabled(context) && available(context)
        private fun target(context: Context) = runCatching {
            if (!com.shilapi.xcertplay.hud.BydOutputSettings.xpengClusterAvailable(context)) return@runCatching null
            // This route was researched only for the G9's Android logical display 2 at 1920x720.
            context.getSystemService(DisplayManager::class.java)?.getDisplay(2)?.takeIf {
                it.isValid && Point().also(it::getRealSize).let { p -> p.x == 1920 && p.y == 720 }
            }
        }.getOrNull()
        fun streamConfig() = CarPlayClusterDisplay.config(WIDTH, HEIGHT, scalePercent = 100,
            content = CarPlayClusterDisplay.Content.MAP,
            baseSafeArea = AirPlayInsets(top = 25, bottom = 23, left = 36, right = 36))
    }
}
