package com.apfa

import android.app.Activity
import android.app.ActionBar
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

/**
 * Loading screen -> GL playback surface. The HUD (Time + FPS) is rendered
 * directly by the native engine every frame, like PFA's RenderText(). The
 * seek bar is polled from the UI thread at 16 ms (display rate) — it's a
 * UI control, not a timing-critical element, so this is fine.
 */
class PlaybackActivity : Activity(), SurfaceHolder.Callback {

    companion object {
        init {
            System.loadLibrary("bass")
            System.loadLibrary("bassmidi")
            System.loadLibrary("apfa")
        }
        const val EXTRA_MIDI     = "midi"
        const val EXTRA_SF       = "sf"
        const val EXTRA_VOICES   = "voices"
        const val EXTRA_SPEED    = "speed"
        const val EXTRA_CPU_MASK = "cpuMask"
        const val EXTRA_BG_COLOR = "bgColor"
        const val EXTRA_BG_IMAGE = "bgImage"
        const val EXTRA_LEGACY   = "legacyRenderer"   // ES2 "Legacy Renderer (GLES 2.0)"
        const val EXTRA_STREAM   = "diskStreaming"    // allow the chunked pagefile sort
                                                      // (key name kept for settings compat)
        // Cap the decoded background so it fits comfortably in a GL texture on
        // budget devices; it's stretched anyway, so detail loss is fine.
        private const val BG_MAX_DIM = 1280
    }

    private external fun nativeLoad(midiPath: String, sfPath: String,
                                    voiceCount: Int, noteSpeed: Float,
                                    cpuMask: Long, legacyRenderer: Boolean,
                                    allowChunked: Boolean): Boolean
    // Why the last nativeLoad returned false: 0 generic, 1 needs Chunked Disk
    // Streaming (Advanced Settings), 2 not enough free storage for the pagefile.
    private external fun nativeGetLoadError(): Int
    private external fun nativeGetLoadProgress(): Float
    private external fun nativeGetNoteCount(): Long
    private external fun nativeGetMemoryBytes(): Long
    private external fun nativeGetStreamedBytes(): Long
    private external fun nativeStart(surface: Surface)
    private external fun nativeStop()
    private external fun nativeRelease()
    private external fun nativeSurfaceChanged(w: Int, h: Int)
    private external fun nativePause()
    private external fun nativeResume()
    private external fun nativeSeek(micros: Long)
    private external fun nativeIsPlaying(): Boolean
    private external fun nativeGetStartError(): Int
    private external fun nativeGetTimeMicros(): Long
    private external fun nativeGetTotalMicros(): Long
    private external fun nativeGetMinMicros(): Long
    private external fun nativeGetMaxMicros(): Long
    private external fun nativeGetFps(): Float
    private external fun nativeSetBgColor(bgrColor: Int)
    private external fun nativeSetBgImage(pixels: IntArray, w: Int, h: Int)

    private val ui = Handler(Looper.getMainLooper())

    private lateinit var loadingText: TextView
    private lateinit var loadingOverlay: TextView

    @Volatile private var copying = true
    private var stopped     = false
    private var infoLine    = ""
    private var paused      = false
    private var userSeeking = false
    private lateinit var pauseButton: Button
    private lateinit var seekBar: SeekBar
    private var uiHidden     = false
    private var lastTapTime  = 0L
    private var holdFired    = false

    // getActionBar() is API 11+; on Gingerbread the method does not exist and
    // calling it throws NoSuchMethodError (an Error — catch(Exception) misses it).
    // Below 11 we never touch it: playback runs bare on the fullscreen surface,
    // no seek bar, hold-to-pause still works.
    private val hasActionBar = Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Pre-Honeycomb the default theme has a title bar, and FLAG_FULLSCREEN only
        // takes the status bar. Drop it so playback is truly fullscreen. Never do
        // this at 11+ — it would remove the action bar we host the transport in.
        if (!hasActionBar) requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val midiUri = intent.getStringExtra(EXTRA_MIDI)
        if (midiUri == null) { finish(); return }
        val sfUri      = intent.getStringExtra(EXTRA_SF)
        val voiceCount = intent.getIntExtra(EXTRA_VOICES, 250)
        val noteSpeed  = intent.getFloatExtra(EXTRA_SPEED, 0.05f)
        val cpuMask    = intent.getLongExtra(EXTRA_CPU_MASK, 0L)
        val bgColor    = intent.getIntExtra(EXTRA_BG_COLOR, 0x00464646)
        val bgImage    = intent.getStringExtra(EXTRA_BG_IMAGE)
        val legacy     = intent.getBooleanExtra(EXTRA_LEGACY, false)
        val chunked    = intent.getBooleanExtra(EXTRA_STREAM, false)

        showLoadingScreen()

        Thread {
            val midiPath = copyToCache(midiUri, "input.mid")
            val sfPath   = if (sfUri.isNullOrEmpty()) "" else (copyToCache(sfUri, "font.sf2") ?: "")
            copying = false
            if (midiPath == null) {
                ui.post { fail("Could not read the MIDI file") }
                return@Thread
            }
            val ok = nativeLoad(midiPath, sfPath, voiceCount, noteSpeed, cpuMask, legacy, chunked)
            // Decode + upload the background image off the UI thread (it can be big).
            // The engine just stashes it; the render thread does the GL upload.
            if (ok && !bgImage.isNullOrEmpty()) applyBgImage(bgImage)
            ui.post {
                if (ok) {
                    nativeSetBgColor(bgColor)
                    val mb = nativeGetMemoryBytes() / 1048576.0
                    // Nonzero only when the load went through the streaming
                    // pool — then the line shows how much pool was pagefiled.
                    val streamedMb = nativeGetStreamedBytes() / 1048576.0
                    infoLine = if (streamedMb > 0)
                        "%,d notes  -  %.1f MB RAM (%.1f MB streamed)"
                            .format(nativeGetNoteCount(), mb, streamedMb)
                    else
                        "%,d notes  -  %.1f MB".format(nativeGetNoteCount(), mb)
                    Log.i("aPFA", infoLine)
                    showPlaybackScreen()
                } else {
                    when (nativeGetLoadError()) {
                        1 -> fail("This Black MIDI likely needs Chunked Disk Streaming, " +
                                  "please enable it in Advanced Settings.")
                        2 -> fail("Not enough space on disk to load this MIDI! " +
                                  "Please free up space!")
                        else -> fail("Could not parse the MIDI file")
                    }
                }
            }
        }.start()
    }

    // ---- loading screen ----

    private fun showLoadingScreen() {
        loadingText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(18, 18, 24))
            text = "Loading..."
        }
        setContentView(loadingText)
        ui.post(loadingPoll)
    }

    private val loadingPoll = object : Runnable {
        override fun run() {
            if (stopped) return
            loadingText.text = if (copying) "Copying file..."
                else "Loading MIDI...  %d%%".format((nativeGetLoadProgress() * 100).toInt())
            ui.postDelayed(this, 120)
        }
    }

    private fun fail(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }

    // ---- playback screen ----

    private fun showPlaybackScreen() {
        ui.removeCallbacks(loadingPoll)
        val root = FrameLayout(this)

        val surface = SurfaceView(this)
        surface.holder.addCallback(this)
        root.addView(surface, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Hold (400 ms): pause/resume immediately when threshold is reached.
        // Double-tap (two short taps < 300 ms apart): hide/show the action bar.
        val holdRunnable = Runnable {
            holdFired = true
            togglePause()
        }
        surface.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    holdFired = false
                    ui.postDelayed(holdRunnable, 400L)
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    ui.removeCallbacks(holdRunnable)
                    if (!holdFired) {
                        // Short tap — check for double-tap
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime < 300L) toggleUi()
                        lastTapTime = now
                    }
                }
            }
            true
        }

        // Action bar: seek bar + pause button.
        // Time and FPS are drawn by the GL renderer every frame — no TextView needed.
        if (hasActionBar) actionBar?.let { ab ->
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val title = TextView(this).apply {
                text = "aPFA"
                setTextColor(Color.WHITE)
                textSize = 18f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            bar.addView(title, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(16) })
            seekBar = SeekBar(this).apply {
                max = 1000
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {}
                    override fun onStartTrackingTouch(s: SeekBar?) { userSeeking = true }
                    override fun onStopTrackingTouch(s: SeekBar?) {
                        // Map the slider across [min, max] = PFA's
                        // [GetMinTime, GetMaxTime], so the far left seeks into the
                        // -3s pre-roll (shows "-00:03"), like stock PFA:
                        // JumpTo(llFirstTime + (llLastTime-llFirstTime)*p/1000).
                        val minU = nativeGetMinMicros()
                        val maxU = nativeGetMaxMicros()
                        if (maxU > minU)
                            nativeSeek(minU + (maxU - minU) * (s?.progress ?: 0).toLong() / 1000L)
                        userSeeking = false
                    }
                })
            }
            bar.addView(seekBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            pauseButton = Button(this).apply {
                text = "❚❚"
                setOnClickListener { togglePause() }
            }
            bar.addView(pauseButton)
            ab.setDisplayShowTitleEnabled(false)
            ab.setDisplayShowCustomEnabled(true)
            ab.setCustomView(bar, ActionBar.LayoutParams(
                ActionBar.LayoutParams.MATCH_PARENT,
                ActionBar.LayoutParams.MATCH_PARENT))
        }

        loadingOverlay = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(18, 18, 24))
            text = "Starting...\n$infoLine"
        }
        root.addView(loadingOverlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        setContentView(root)
        // Poll seek bar at ~display rate. This is only a UI control, not timing-critical.
        ui.post(seekPoll)
    }

    // Seek bar poll — 16 ms matches display refresh. Only updates the seek bar
    // thumb position; Time and FPS are rendered by GL, not here.
    private val seekPoll = object : Runnable {
        override fun run() {
            if (stopped) return
            val t    = nativeGetTimeMicros()
            val minU = nativeGetMinMicros()
            val maxU = nativeGetMaxMicros()
            if (::seekBar.isInitialized && !userSeeking && maxU > minU)
                seekBar.progress = (((t - minU) * 1000L) / (maxU - minU)).toInt()
            if (::loadingOverlay.isInitialized &&
                loadingOverlay.visibility == View.VISIBLE) {
                if (nativeIsPlaying()) {
                    loadingOverlay.visibility = View.GONE
                } else {
                    // Engine aborted during start-up (synth or GL init). Show why
                    // instead of an infinite "Starting…" and stop polling — the
                    // user can back out. Full driver error is in logcat (tag aPFA).
                    val err = nativeGetStartError()
                    if (err != 0) {
                        loadingOverlay.text = when (err) {
                            1 -> "Audio engine failed to start.\n\n" +
                                 "This device's audio output could not be opened."
                            2 -> "Graphics failed to initialize.\n\n" +
                                 "If this is an older (OpenGL ES 2.0) device, enable " +
                                 "\"Legacy Renderer (GLES 2.0)\" in Settings and try " +
                                 "again. See logcat (tag aPFA) for details."
                            else -> "Playback failed to start."
                        }
                        return
                    }
                }
            }
            ui.postDelayed(this, 16)
        }
    }

    // ---- SurfaceHolder.Callback ----
    // Surface lifecycle (attach/detach the render thread) is deliberately kept
    // separate from the engine's lifecycle. Going to recents destroys the surface,
    // so we only stop the render thread here — the engine and its parsed MIDI live
    // on, and surfaceCreated re-attaches and resumes. The engine is freed only in
    // onDestroy (nativeRelease).

    override fun surfaceCreated(holder: SurfaceHolder)  { nativeStart(holder.surface) }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) { nativeSurfaceChanged(w, h) }
    override fun surfaceDestroyed(holder: SurfaceHolder) { nativeStop() }

    // ---- lifecycle ----

    override fun onPause()  { super.onPause();  nativePause() }
    override fun onResume() { super.onResume(); if (!paused) nativeResume() }

    private fun toggleUi() {
        if (!hasActionBar) return  // nothing to hide — already bare
        uiHidden = !uiHidden
        actionBar?.let { ab ->
            if (uiHidden) ab.hide() else ab.show()
        }
    }

    private fun togglePause() {
        paused = !paused
        if (paused) nativePause() else nativeResume()
        if (::pauseButton.isInitialized)
            pauseButton.text = if (paused) "▶" else "❚❚"
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        stopped = true
        ui.removeCallbacksAndMessages(null)
        nativeRelease()   // activity finishing for real — free the engine + MIDI
    }

    // ---- helpers ----

    // Decode the chosen background (downscaled to BG_MAX_DIM), pull its ARGB
    // pixels, and hand them to the engine. Stretching to fit happens in the GL
    // shader, so we don't care about aspect ratio here.
    private fun applyBgImage(path: String) {
        try {
            // First pass: bounds only, to pick a power-of-two downsample factor.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
            var sample = 1
            while (bounds.outWidth / sample > BG_MAX_DIM ||
                   bounds.outHeight / sample > BG_MAX_DIM) sample *= 2

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = BitmapFactory.decodeFile(path, opts) ?: return
            val w = bmp.width
            val h = bmp.height
            val pixels = IntArray(w * h)
            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
            bmp.recycle()
            nativeSetBgImage(pixels, w, h)
        } catch (e: Exception) {
            Log.e("aPFA", "applyBgImage failed", e)
        }
    }

    private fun copyToCache(uriStr: String, name: String): String? = try {
        val out = File(cacheDir, name)
        contentResolver.openInputStream(Uri.parse(uriStr))!!.use { input ->
            FileOutputStream(out).use { output -> input.copyTo(output, 1 shl 20) }
        }
        out.absolutePath
    } catch (e: Exception) {
        Log.e("aPFA", "copyToCache failed: $name", e)
        null
    }
}
