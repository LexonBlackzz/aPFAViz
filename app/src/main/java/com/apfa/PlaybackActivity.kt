package com.apfa

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
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
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.os.Environment
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

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
        const val EXTRA_SD_POOL  = "sdPagefile"       // put the pagefile on the SD card
        // Cap the decoded background so it fits comfortably in a GL texture on
        // budget devices; it's stretched anyway, so detail loss is fine.
        private const val BG_MAX_DIM = 1280

        // Ceilings for rebuilding an SFZ instrument in the cache (below). An
        // .sfz can name a whole sample library; these stop a mis-pick filling
        // the phone. Hit either and the bundle stops and says so.
        private const val SFZ_MAX_FILES = 4096
        private const val SFZ_MAX_BYTES = 512L * 1024 * 1024
        private const val SFZ_MAX_TEXT  = 8L * 1024 * 1024

        // opcode=value, several to a line. Deliberately not anchored: the value
        // of the PREVIOUS opcode is whatever sits between two of these matches.
        private val SFZ_OPCODE  = Regex("([A-Za-z_][A-Za-z0-9_]*)\\s*=")
        private val SFZ_INCLUDE = Regex("^\\s*#include\\s+\"([^\"]+)\"")
        // SFZ v2 / ARIA macros: "#define $EXT flac", used later as "...-PP.$EXT".
        private val SFZ_DEFINE  = Regex("^\\s*#define\\s+(\\$[A-Za-z0-9_]+)\\s+(\\S+)")
    }

    // poolDir: where the streaming pagefile goes. "" = next to the MIDI, in the
    // app cache dir; otherwise the app's cache dir on the SD card.
    private external fun nativeLoad(midiPath: String, sfPath: String,
                                    voiceCount: Int, noteSpeed: Float,
                                    cpuMask: Long, legacyRenderer: Boolean,
                                    allowChunked: Boolean,
                                    poolDir: String): Boolean
    // Why the last nativeLoad returned false: 0 generic, 1 needs Chunked Disk
    // Streaming (Advanced Settings), 2 not enough free storage for the pagefile,
    // 3 pagefile past the volume's file-size limit (a FAT32 card stops at 4 GB),
    // 4 the pool's address-space reservation failed (32-bit OS), 5 streaming was
    // unavailable and the in-RAM parse was already predicted not to fit.
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

    private lateinit var transportBar: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.navigationBarColor = Color.rgb(7, 9, 15)

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
        val sdPagefile = intent.getBooleanExtra(EXTRA_SD_POOL, false)

        showLoadingScreen()

        Thread {
            val midiPath = copyToCache(midiUri, "input.mid")
            val sfPath   = if (sfUri.isNullOrEmpty()) "" else prepareSoundfont(sfUri)
            copying = false
            if (midiPath == null) {
                ui.post { fail("Could not read the MIDI file") }
                return@Thread
            }
            // No soundfont means no sound. Say it once, up front, so a silent
            // playback reads as a choice rather than as a broken load. Covers
            // both "none was picked" and "the copy failed" — sfPath is what
            // nativeLoad actually gets either way.
            if (sfPath.isEmpty()) {
                ui.post {
                    Toast.makeText(this,
                        "You are going to play this MIDI without a soundfont!",
                        Toast.LENGTH_LONG).show()
                }
            }
            // Re-checked here, on the loading thread, rather than trusted from
            // the saved setting: this is the point of use, and the card can be
            // gone (or the whole option invalid) since the box was ticked.
            // Anything wrong = internal storage, which is where the pagefile
            // went before this setting existed — say so and load anyway.
            var poolDir = ""
            if (sdPagefile) {
                val why = SdCard.unavailableReason(this)
                val sd  = if (why == null) SdCard.cacheDir(this) else null
                if (sd != null) {
                    poolDir = sd.absolutePath
                } else {
                    Log.w("aPFA", "SD pagefile requested but unavailable: $why")
                    ui.post {
                        Toast.makeText(this,
                            "Using internal storage for the pagefile — " +
                            (why ?: "no SD card detected."),
                            Toast.LENGTH_LONG).show()
                    }
                }
            }
            val ok = nativeLoad(midiPath, sfPath, voiceCount, noteSpeed, cpuMask, legacy,
                                chunked, poolDir)
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
                                  "Please free up space!" +
                                  // Only suggest the card where it is a real option.
                                  if (!sdPagefile && SdCard.unavailableReason(this) == null)
                                      " You can also set Pagefile Location to SD Card " +
                                      "in Advanced Settings."
                                  else "")
                        3 -> fail("This MIDI's pagefile is over the 4 GB per-file limit of " +
                                  "a FAT32 card. Reformat the card as exFAT to use it for " +
                                  "MIDIs this big.")
                        4 -> fail("This MIDI is too big for a 32-bit version of Android. " +
                                  "A 32-bit system gives an app about 3 GB of memory " +
                                  "address space in total, and this MIDI needs more than " +
                                  "is left after Android's own share — no matter how much " +
                                  "RAM or storage is free. Many phones have a 64-bit " +
                                  "processor but shipped with a 32-bit OS, and this " +
                                  "appears to be one of them.")
                        5 -> fail("This MIDI is too big for this phone's RAM, and disk " +
                                  "streaming isn't available for it here.")
                        else -> fail("Could not parse the MIDI file")
                    }
                }
            }
        }.start()
    }

    // ---- loading screen ----

    private fun showLoadingScreen() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(7, 9, 15)) }
        root.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.rgb(20, 16, 38), Color.rgb(7, 9, 15), Color.rgb(8, 24, 28))
        )

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(26), dp(28), dp(26))
            background = transportPanelBackground()
            elevation = dp(10).toFloat()
        }
        card.addView(TextView(this).apply {
            text = "aPFA"
            setTextColor(Color.WHITE)
            textSize = 30f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.03f
        })
        card.addView(TextView(this).apply {
            text = "Preparing the engine"
            setTextColor(Color.rgb(176, 184, 205))
            textSize = 13f
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(2) })

        val spinner = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(Color.rgb(45, 212, 191))
        }
        card.addView(spinner, LinearLayout.LayoutParams(dp(42), dp(42)).apply {
            topMargin = dp(20)
            bottomMargin = dp(14)
        })

        loadingText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            text = "Loading…"
        }
        card.addView(loadingText)

        root.addView(card, FrameLayout.LayoutParams(
            dp(300), ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.CENTER })
        setContentView(root)
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

    // A load that cannot proceed has something to SAY — which storage ran out,
    // which setting to turn on, that the MIDI needs a 64-bit Android. As a
    // Toast fired while the activity was already finishing, that message
    // flashed past over a progress bar frozen mid-count and the whole thing
    // read as a crash (it was mistaken for one twice while testing the 32-bit
    // address-space limit). A dialog that waits to be dismissed says the same
    // thing where it can actually be read, and only then drops back to setup.
    private fun fail(msg: String) {
        if (isFinishing) return
        try {
            AlertDialog.Builder(this)
                .setTitle("Can't load this MIDI")
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton("OK") { _, _ -> finish() }
                .show()
        } catch (e: Exception) {
            // No window to attach to (activity already going away) — say it the
            // old way rather than not at all.
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // ---- playback screen ----

    private fun showPlaybackScreen() {
        ui.removeCallbacks(loadingPoll)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        val surface = SurfaceView(this)
        surface.holder.addCallback(this)
        root.addView(surface, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Hold (400 ms): pause/resume immediately when threshold is reached.
        // Double-tap (two short taps < 300 ms apart): hide/show transport chrome.
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
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime < 300L) toggleUi()
                        lastTapTime = now
                    }
                }
            }
            true
        }

        // Floating transport: one layout works in portrait and landscape and
        // avoids tying playback controls to the old platform ActionBar.
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(10), dp(8))
            background = transportPanelBackground()
            elevation = dp(10).toFloat()
        }
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        brand.addView(TextView(this).apply {
            text = "aPFA"
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
        })
        brand.addView(TextView(this).apply {
            text = "PLAYBACK"
            setTextColor(Color.rgb(45, 212, 191))
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
        })
        bar.addView(brand, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(12) })

        seekBar = SeekBar(this).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(Color.rgb(45, 212, 191))
            thumbTintList = ColorStateList.valueOf(Color.rgb(139, 92, 246))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(s: SeekBar?) { userSeeking = true }
                override fun onStopTrackingTouch(s: SeekBar?) {
                    val minU = nativeGetMinMicros()
                    val maxU = nativeGetMaxMicros()
                    if (maxU > minU)
                        nativeSeek(minU + (maxU - minU) *
                            (s?.progress ?: 0).toLong() / 1000L)
                    userSeeking = false
                }
            })
        }
        bar.addView(seekBar, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        ))

        pauseButton = Button(this).apply {
            text = "❚❚"
            contentDescription = "Pause"
            setOnClickListener { togglePause() }
        }
        styleTransportButton(pauseButton)
        bar.addView(pauseButton, LinearLayout.LayoutParams(dp(48), dp(48)).apply {
            marginStart = dp(8)
        })

        transportBar = bar
        root.addView(bar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP
            setMargins(dp(14), dp(14), dp(14), 0)
        })

        loadingOverlay = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(224, 7, 9, 15))
            text = "Starting engine…\n\n$infoLine"
        }
        root.addView(loadingOverlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        setContentView(root)
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
        if (!::transportBar.isInitialized) return
        uiHidden = !uiHidden
        transportBar.visibility = if (uiHidden) View.GONE else View.VISIBLE
    }

    private fun togglePause() {
        paused = !paused
        if (paused) nativePause() else nativeResume()
        if (::pauseButton.isInitialized)
            pauseButton.text = if (paused) "▶" else "❚❚"
    }

    private fun transportPanelBackground(): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(Color.argb(226, 18, 21, 32))
            setStroke(dp(1), Color.argb(105, 139, 92, 246))
        }

    private fun styleTransportButton(button: Button) {
        val shape = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(Color.rgb(139, 92, 246))
        }
        button.isAllCaps = false
        button.setTextColor(Color.WHITE)
        button.textSize = 15f
        button.typeface = Typeface.DEFAULT_BOLD
        button.background = RippleDrawable(
            ColorStateList.valueOf(Color.argb(64, 255, 255, 255)),
            shape,
            null
        )
        button.stateListAnimator = null
        button.elevation = dp(4).toFloat()
        button.setPadding(0, 0, 0, 0)
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

    // ---- Soundfont preparation ------------------------------------------
    //
    // SF2 and SF3 are single self-contained files: any copy of the bytes plays.
    // SFZ is not. An .sfz is a TEXT file that names its samples — and any
    // further .sfz files — by RELATIVE path, so it only works while it sits in
    // its own folder next to them. Copying just the .sfz into the cache, which
    // is what this did for every soundfont, produced a font that parsed and
    // then rendered silence.
    //
    // Two routes, best first:
    //   1. Hand BASSMIDI the file where it already lives. Costs nothing and the
    //      samples resolve themselves. Covers providers whose document URI can
    //      still be resolved to a readable filesystem path (typically API 23-28).
    //   2. Rebuild the instrument in the cache: copy the .sfz, parse it, and
    //      pull every file it names through the same provider, preserving the
    //      relative layout so the paths inside the .sfz stay correct.
    //
    // Route 2 is best-effort by nature — SAF grants one document, not its
    // folder — so it reports what it actually fetched instead of pretending.
    private fun prepareSoundfont(uriStr: String): String {
        val uri   = Uri.parse(uriStr)
        val name  = sanitiseName(displayName(uri))
        val isSfz = name.endsWith(".sfz", ignoreCase = true)

        val local = resolveLocalPath(uri)
        if (local != null && File(local).canRead()) {
            Log.i("aPFA", "soundfont: playing in place, $local")
            return local
        }
        if (!isSfz) return copyToCache(uriStr, name) ?: ""
        return bundleSfz(uri, name) ?: ""
    }

    /**
     * The real filesystem path behind a picked URI, or null.
     *
     * This stays as provider-agnostic Uri string work because several OEM
     * document providers use the same document-id shape without behaving
     * exactly like ExternalStorageProvider.
     */
    private fun resolveLocalPath(uri: Uri): String? {
        if ("file".equals(uri.scheme, ignoreCase = true)) return uri.path
        if (!"content".equals(uri.scheme, ignoreCase = true)) return null
        val id = documentId(uri) ?: return null
        // ExternalStorageProvider ids read "primary:Download/x.sfz", or
        // "1A2B-3C4D:Fonts/x.sfz" on a removable volume.
        val colon = id.indexOf(':')
        if (colon < 0) return null
        val volume = id.substring(0, colon)
        val rel    = id.substring(colon + 1)
        if (rel.isEmpty()) return null
        return if (volume.equals("primary", ignoreCase = true))
            File(Environment.getExternalStorageDirectory(), rel).absolutePath
        else
            File(File("/storage", volume), rel).absolutePath
    }

    /** The decoded document id of a SAF URI (.../document/<id>). */
    private fun documentId(uri: Uri): String? {
        val segs = uri.pathSegments ?: return null
        val i = segs.lastIndexOf("document")
        return if (i >= 0 && i + 1 < segs.size) segs[i + 1] else null
    }

    /** The same SAF URI with its document id repointed at a sibling file. */
    private fun siblingUri(uri: Uri, rel: String): Uri? {
        val segs = uri.pathSegments ?: return null
        val i = segs.lastIndexOf("document")
        if (i < 0 || i + 1 >= segs.size) return null
        val id  = segs[i + 1]
        val cut = id.lastIndexOf('/')
        if (cut < 0) return null
        val b = Uri.Builder().scheme(uri.scheme).authority(uri.authority)
        segs.forEachIndexed { idx, seg ->
            b.appendPath(if (idx == i + 1) id.substring(0, cut + 1) + rel else seg)
        }
        return b.build()
    }

    /**
     * Rebuild an SFZ instrument under cache/sfz, keeping every path relative to
     * the .sfz exactly as written so BASSMIDI resolves them unchanged. Returns
     * the path to the copied .sfz, or null if even that could not be read.
     */
    private fun bundleSfz(uri: Uri, rootName: String): String? {
        val dir = File(cacheDir, "sfz")
        wipe(dir)
        if (!dir.mkdirs() && !dir.isDirectory) {
            Log.e("aPFA", "sfz: could not create $dir")
            return null
        }
        val localRoot = resolveLocalPath(uri)

        // Breadth-first over the instrument. Paths are relative to the .sfz's
        // own folder, which is also the layout we write, so one string does for
        // both the source lookup and the destination.
        val queue = ArrayList<String>()
        val seen  = HashSet<String>()
        queue.add(rootName)
        seen.add(rootName)

        var qi = 0
        var files = 0
        var bytes = 0L
        var missing = 0
        var rootOk = false
        var capped = false

        while (qi < queue.size) {
            val rel = queue[qi++]
            if (files >= SFZ_MAX_FILES || bytes >= SFZ_MAX_BYTES) { capped = true; break }
            val out = File(dir, rel)
            if (!withinBundle(dir, out)) {
                Log.w("aPFA", "sfz: refusing path outside the bundle: $rel")
                continue
            }
            out.parentFile?.mkdirs()
            val n = fetchInto(uri, localRoot, rootName, rel, out)
            if (n < 0) {
                if (rel == rootName) { Log.e("aPFA", "sfz: cannot read $rel"); return null }
                missing++
                Log.w("aPFA", "sfz: could not fetch $rel")
                continue
            }
            files++
            bytes += n
            if (rel == rootName) rootOk = true
            if (rel.endsWith(".sfz", ignoreCase = true) && out.length() <= SFZ_MAX_TEXT) {
                val refs = try {
                    sfzReferences(out.readText())
                } catch (e: Exception) {
                    Log.w("aPFA", "sfz: unreadable text in $rel", e)
                    emptyList<String>()
                }
                for (r in refs) if (seen.add(r)) queue.add(r)
            }
        }
        if (!rootOk) return null

        Log.i("aPFA", "sfz: bundled $files file(s), %.1f MB, $missing missing%s"
            .format(bytes / 1048576.0, if (capped) " (capped)" else ""))
        if (missing > 0 || capped) {
            val why = if (capped)
                "This SFZ is larger than aPFA will copy ($files files)."
            else
                "$missing sample file(s) of this SFZ could not be read."
            ui.post {
                Toast.makeText(this,
                    "$why It may play silent or incomplete. Picking it from " +
                    "internal storage, or using a .sf2, avoids this.",
                    Toast.LENGTH_LONG).show()
            }
        }
        return File(dir, rootName).absolutePath
    }

    /** Pull one file of the instrument into [out]. Returns bytes written, or -1. */
    private fun fetchInto(base: Uri, localRoot: String?, rootName: String,
                          rel: String, out: File): Long {
        // The root is the one document the picker actually granted.
        if (rel == rootName) {
            try {
                contentResolver.openInputStream(base)?.use { return writeTo(it, out) }
            } catch (e: Exception) {
                Log.w("aPFA", "sfz: root open failed", e)
            }
        } else {
            // A SAF grant is per-document, but ExternalStorageProvider will
            // often still answer for a sibling of a granted file — and on
            // API 29+ this is the only route there is.
            siblingUri(base, rel)?.let { sib ->
                try {
                    contentResolver.openInputStream(sib)?.use { return writeTo(it, out) }
                } catch (e: Exception) {
                    // not granted, or not that kind of provider — try the path
                }
            }
        }
        if (localRoot != null) {
            val parent = File(localRoot).parentFile
            if (parent != null) {
                val f = File(parent, rel)
                if (f.canRead()) try {
                    FileInputStream(f).use { return writeTo(it, out) }
                } catch (e: Exception) {
                    Log.w("aPFA", "sfz: path read failed for $rel", e)
                }
            }
        }
        out.delete()
        return -1
    }

    private fun writeTo(input: InputStream, out: File): Long =
        FileOutputStream(out).use { o -> input.copyTo(o, 1 shl 16) }

    /**
     * Every file an .sfz names: sample= values (prefixed by the running
     * default_path=) plus #include targets, as relative '/'-separated paths.
     *
     * SFZ packs several opcode=value pairs per line and treats // as a comment.
     * sample= is the awkward one — filenames may contain spaces, so its value
     * runs to the next opcode token, the next <header>, or end of line, NOT to
     * the next space. Paths are conventionally written with backslashes.
     */
    private fun sfzReferences(text: String): List<String> {
        val lines = text.split('\n')

        // Pass 1 — #define. Collected up front so a macro defined below its
        // first use still resolves: this is a hunt for files, not a render, so
        // the widest reading is the right one. Longest name first, or $E would
        // eat the head of $EXT.
        val defines = ArrayList<Pair<String, String>>()
        for (raw in lines) SFZ_DEFINE.find(raw)?.let {
            defines.add(Pair(it.groupValues[1], it.groupValues[2]))
        }
        defines.sortByDescending { it.first.length }

        val out = ArrayList<String>()
        var defaultPath = ""
        for (line0 in lines) {
            if (SFZ_DEFINE.containsMatchIn(line0)) continue
            val raw = expandDefines(line0, defines)
            val incl = SFZ_INCLUDE.find(raw)
            if (incl != null) {
                normalise(incl.groupValues[1])?.let { out.add(it) }
                continue
            }
            val c = raw.indexOf("//")
            val line = if (c >= 0) raw.substring(0, c) else raw
            val hits = SFZ_OPCODE.findAll(line).toList()
            for ((n, m) in hits.withIndex()) {
                val from = m.range.last + 1
                val to   = if (n + 1 < hits.size) hits[n + 1].range.first else line.length
                var v = line.substring(from, to)
                val lt = v.indexOf('<')          // a header ends the value
                if (lt >= 0) v = v.substring(0, lt)
                v = v.trim()
                when (m.groupValues[1].lowercase()) {
                    "sample" -> if (v.isNotEmpty())
                        normalise(defaultPath + v)?.let { out.add(it) }
                    "default_path" -> {
                        val d = v.replace('\\', '/')
                        defaultPath = if (d.isEmpty() || d.endsWith("/")) d else "$d/"
                    }
                }
            }
        }
        return out
    }

    private fun expandDefines(line: String, defines: List<Pair<String, String>>): String {
        if (defines.isEmpty() || line.indexOf('$') < 0) return line
        var s = line
        for ((k, v) in defines) s = s.replace(k, v)
        return s
    }

    /**
     * A referenced path reduced to a safe relative one. Absolute paths (a
     * leftover "C:\..." or "/home/...") and anything climbing out of the
     * instrument folder are dropped rather than chased.
     */
    private fun normalise(p: String): String? {
        val s = p.trim().trim('"').replace('\\', '/')
        if (s.isEmpty()) return null
        if (s.startsWith("/")) return null
        if (s.length > 1 && s[1] == ':') return null
        val parts = ArrayList<String>()
        for (seg in s.split('/')) {
            when (seg) {
                "", "." -> {}
                ".."    -> if (parts.isEmpty()) return null else parts.removeAt(parts.size - 1)
                else    -> parts.add(seg)
            }
        }
        return if (parts.isEmpty()) null else parts.joinToString("/")
    }

    private fun withinBundle(root: File, f: File): Boolean = try {
        f.canonicalPath.startsWith(root.canonicalPath + File.separator)
    } catch (e: Exception) {
        false
    }

    private fun wipe(f: File) {
        if (f.isDirectory) f.listFiles()?.forEach { wipe(it) }
        f.delete()
    }

    /** Keep the picked name (and its extension) but make it safe as a filename. */
    private fun sanitiseName(n: String): String {
        val base = n.substringAfterLast('/').substringAfterLast('\\')
        val safe = base.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim()
        return if (safe.isEmpty() || safe == "." || safe == "..") "font.sf2" else safe
    }

    private fun displayName(uri: Uri): String {
        var name = uri.lastPathSegment ?: "font.sf2"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) c.getString(idx)?.let { name = it }
                }
            }
        } catch (e: Exception) {
            // keep the path-segment fallback
        }
        return name
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