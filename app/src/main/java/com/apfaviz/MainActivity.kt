package com.apfaviz

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.example.liquidglass.GlassMaterial
import com.example.liquidglass.LiquidGlassView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * aPFAViz setup screen.
 *
 * Pure UI shell — responsive in portrait and landscape. It picks the MIDI +
 * soundfont and captures playback settings; the native engine (libapfa.so)
 * remains completely separate from the presentation layer.
 */
class MainActivity : Activity() {

    companion object {
        init {
            // Load BASS + BASSMIDI first so libapfa.so resolves them.
            System.loadLibrary("bass")
            System.loadLibrary("bassmidi")
            System.loadLibrary("apfa")
        }
        private const val REQ_MIDI = 1
        private const val REQ_SOUNDFONT = 2
        private const val REQ_BG_IMAGE = 3
        private const val REQ_EXPORT_PROFILE = 4
        private const val REQ_IMPORT_PROFILE = 5
        private const val REQ_STORAGE_PERM = 100

        // Responsive shell spacing. The UI is rebuilt on orientation changes,
        // while the playback engine itself stays alive.
        private const val EDGE_MARGIN_DP = 20
        private const val CARD_GAP_DP = 18

        private const val SF_PREFIX = "SF: "

        // Pagefile Location radio ids. RadioGroup.check() needs real ids and
        // View.generateViewId() is API 17+, so they're fixed — they only have to
        // be unique inside that group.
        private const val ID_LOC_INTERNAL = 1
        private const val ID_LOC_SD = 2
    }

    // Setup state — persisted across launches, handed to the engine on playback.
    private var midiUri: Uri? = null
    private var soundfontUri: Uri? = null
    private var voiceCount = 250
    private var noteSpeed = 0.05f
    // Bitmask of CPUs the engine is allowed to use. 0 = "Auto" (engine picks the
    // fastest core itself, see chooseBigCore in engine.cpp).
    private var cpuMask: Long = 0L
    // Use the ES2 "Legacy Renderer (GLES 2.0)" instead of the default ES3
    // renderer. Required on ES2-only GPUs (e.g. Mali-400 / MT6570) that cannot
    // create an ES3 context.
    private var legacyRenderer = false
    // Visual-only switch. When false, no LiquidGlassView is instantiated on the
    // launcher/settings/Ready flow; the same layout falls back to opaque rounded
    // Android surfaces. Native playback/rendering is unaffected either way.
    private var liquidGlassEnabled = true
    // Whether THIS process is 32-bit, which is the only case where the pool is
    // ever split into sub-2 GB files (streamer.cpp, poolFileBytes()). The APK
    // ships both ABIs, so a 64-bit device runs a 64-bit process and the device
    // capability is a faithful proxy; anything before API 21 is 32-bit outright.
    private val is32BitProcess: Boolean
        get() = !android.os.Process.is64Bit()

    // "Force Chunked Disk Sort" (Advanced Settings): large streaming loads
    // chunk automatically when RAM would spike; this switch forces the bounded
    // disk-backed sort for smaller streaming loads too. Pref/extra key remains
    // "diskStreaming" for settings compatibility.
    private var chunkedStreaming = false
    // "Pagefile Location = SD Card" (Advanced Settings): put the streaming pool
    // file on the removable card instead of internal storage. Only offered where
    // it can work — see SdCard.unavailableReason. Pref/extra key stays
    // "sdPagefile" for settings compat.
    private var sdPagefile = false
    // Background color in PFA BGR format (R=bit0, G=bit8, B=bit16). Default = PFA's 0x464646.
    private var bgColor: Int = 0x00464646
    // Optional background image path (in filesDir). When set, it overrides bgColor
    // during playback. Cleared when the user picks a solid colour instead.
    private var bgImagePath: String? = null
    // Profile waiting to be written out by the ACTION_CREATE_DOCUMENT result.
    private var pendingExport: JSONObject? = null

    private lateinit var soundfontButton: Button

    private data class RecentMidi(val uri: Uri, val name: String)

    // LiquidGlass panels are invalidated only while the launcher scrolls.
    // This avoids an always-on redraw loop on API 24-32's CPU fallback.
    private val shellGlassPanels = ArrayList<LiquidGlassView>()

    // Shell-only palette. None of this touches native rendering or PFA timing.
    private val uiBg       = Color.rgb(9, 11, 18)
    private val uiPanel    = Color.argb(224, 18, 21, 32)
    private val uiPanelAlt = Color.argb(214, 24, 28, 42)
    private val uiAccent   = Color.rgb(139, 92, 246)
    private val uiAccent2  = Color.rgb(45, 212, 191)
    private val uiMuted    = Color.rgb(174, 180, 196)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.navigationBarColor = uiBg
        loadSettings()
        setContentView(buildSetupScreen())
        ensureStoragePermission()
        // Reclaim any multi-GB pagefile a previous run died holding. Touches
        // the card, so keep it off the UI thread.
        Thread { SdCard.sweep(this) }.start()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // MainActivity handles rotation itself so the setup shell can swap
        // between its portrait stack and landscape two-column layout.
        setContentView(buildSetupScreen())
    }

    // Old devices (and some OEM pickers, e.g. Huawei EMUI) return file:// URIs from
    // the document picker. Opening those reads the raw /storage path, which needs
    // READ_EXTERNAL_STORAGE. Modern phones (API 33+) always get content:// — no
    // permission needed, and the permission no longer exists — so we only ask on 24..32.
    private fun ensureStoragePermission() {
        if (Build.VERSION.SDK_INT <= 32) {
            val perm = Manifest.permission.READ_EXTERNAL_STORAGE
            if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(perm), REQ_STORAGE_PERM)
            }
        }
    }

    private fun buildSetupScreen(): View {
        shellGlassPanels.clear()
        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        val wc = ViewGroup.LayoutParams.WRAP_CONTENT
        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

        val root = FrameLayout(this).apply { setBackgroundColor(uiBg) }
        // Keep the visual stage in one sibling ViewGroup so LiquidGlass samples
        // the exact wallpaper + vignette that is actually behind the controls.
        val backdrop = FrameLayout(this)
        val bg = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.58f
        }
        loadAsset("apfa-wp.jpg")?.let { bg.setImageBitmap(it) } ?: bg.setBackgroundColor(uiBg)
        backdrop.addView(bg, FrameLayout.LayoutParams(mp, mp))
        backdrop.addView(View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.argb(200, 5, 7, 14),
                    Color.argb(88, 8, 10, 18),
                    Color.argb(228, 5, 7, 14)
                )
            )
        }, FrameLayout.LayoutParams(mp, mp))
        root.addView(backdrop, FrameLayout.LayoutParams(mp, mp))

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(if (portrait) 16 else 28), dp(16), dp(if (portrait) 16 else 28), dp(28))
        }

        // Compact header: brand, version, one settings entry.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        loadAsset("aPFAlogo.png")?.let { logo ->
            header.addView(ImageView(this).apply {
                setImageBitmap(logo)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        header.addView(TextView(this).apply {
            text = "aPFAViz"
            setTextColor(Color.WHITE)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), 0, dp(8), 0)
        })
        header.addView(TextView(this).apply {
            text = "v${appVersion()}"
            setTextColor(uiMuted)
            textSize = 12f
        }, LinearLayout.LayoutParams(0, wc, 1f))
        val gear = glassIconControl(backdrop, "⚙", "Settings") { showSettingsDialog() }
        header.addView(gear, LinearLayout.LayoutParams(dp(48), dp(48)))
        page.addView(header, LinearLayout.LayoutParams(mp, wc).apply { bottomMargin = dp(20) })

        // Primary action in the lower, easy-to-reach half of the first card.
        val openCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        openCard.addView(TextView(this).apply {
            text = "MIDI"
            setTextColor(uiMuted)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
        })
        openCard.addView(TextView(this).apply {
            text = "Choose a file to load"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(mp, wc).apply { topMargin = dp(4) })
        val openAction = glassActionButton(backdrop, "Open MIDI") {
            pickFile(REQ_MIDI)
        }
        openCard.addView(openAction, LinearLayout.LayoutParams(mp, dp(56)).apply {
            topMargin = dp(18)
        })
        page.addView(glassPanel(openCard, backdrop, accented = true),
            LinearLayout.LayoutParams(mp, wc))

        // Recent files use the persisted SAF URI grants; no duplicate metadata scan.
        val recents = loadRecentMidis()
        if (recents.isNotEmpty()) {
            page.addView(sectionTitle("Recent files"), LinearLayout.LayoutParams(mp, wc).apply {
                topMargin = dp(22)
                bottomMargin = dp(8)
            })
            val recentCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                }
            recents.forEachIndexed { index, item ->
                val row = TextView(this).apply {
                    text = item.name
                    setTextColor(Color.rgb(215, 220, 232))
                    textSize = 14f
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setOnClickListener {
                        midiUri = item.uri
                        launchPlayback()
                    }
                }
                recentCard.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
                if (index != recents.lastIndex) recentCard.addView(View(this).apply {
                    setBackgroundColor(Color.argb(38, 255, 255, 255))
                }, LinearLayout.LayoutParams(mp, dp(1)))
            }
            page.addView(glassPanel(recentCard, backdrop))
        }

        // Compact SoundFont chip row.
        page.addView(sectionTitle("SoundFont"), LinearLayout.LayoutParams(mp, wc).apply {
            topMargin = dp(22)
            bottomMargin = dp(8)
        })
        val sfRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(8), dp(10), dp(8))
        }
        sfRow.addView(TextView(this).apply {
            text = "SF"
            setTextColor(uiAccent2)
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(36), dp(36)))
        soundfontButton = Button(this).apply {
            text = soundfontUri?.let { displayName(it) } ?: "No SoundFont"
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setTextColor(Color.WHITE)
            textSize = 14f
            background = null
            isClickable = false
            isFocusable = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        sfRow.addView(soundfontButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        sfRow.addView(TextView(this).apply {
            text = "›"
            setTextColor(uiMuted)
            textSize = 24f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(28), dp(48)))
        val sfGlass = glassPanel(sfRow, backdrop, cornerDp = 18).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { pickFile(REQ_SOUNDFONT) }
        }
        if (sfGlass is LiquidGlassView) {
            sfGlass.enablePressEffect = true
            sfGlass.pressScale = 0.99f
            sfGlass.elasticity = 0.48f
        }
        page.addView(sfGlass)

        // Quick settings: exact value is tappable; slider retains fast tuning.
        page.addView(sectionTitle("Quick settings"), LinearLayout.LayoutParams(mp, wc).apply {
            topMargin = dp(22)
            bottomMargin = dp(8)
        })
        val quick = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        addVoiceControl(quick, backdrop)
        quick.addView(View(this).apply {
            setBackgroundColor(Color.argb(36,255,255,255))
        }, LinearLayout.LayoutParams(mp, dp(1)).apply { topMargin = dp(12); bottomMargin = dp(12) })
        addSpeedControl(quick, backdrop)

        val bgRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
            setOnClickListener { showBgColorDialog() }
        }
        val swatchColor = Color.rgb(bgColor and 0xFF, (bgColor shr 8) and 0xFF, (bgColor shr 16) and 0xFF)
        bgRow.addView(View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (bgImagePath == null) swatchColor else Color.DKGRAY)
                setStroke(dp(1), Color.argb(100,255,255,255))
            }
        }, LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginEnd = dp(12) })
        val bgText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bgText.addView(TextView(this).apply {
            text = "Background"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        })
        bgText.addView(TextView(this).apply {
            text = if (bgImagePath != null) "Image" else "Solid colour"
            setTextColor(uiMuted)
            textSize = 12f
        })
        bgRow.addView(bgText, LinearLayout.LayoutParams(0, wc, 1f))
        bgRow.addView(TextView(this).apply {
            text = "›"
            setTextColor(uiMuted)
            textSize = 24f
        })
        quick.addView(bgRow, LinearLayout.LayoutParams(mp, wc))
        page.addView(glassPanel(quick, backdrop))

        scroll.addView(page, FrameLayout.LayoutParams(mp, wc))
        root.addView(scroll, FrameLayout.LayoutParams(mp, mp))
        scroll.viewTreeObserver.addOnScrollChangedListener {
            shellGlassPanels.forEach { it.invalidate() }
        }
        return root
    }

    private fun panelBackground(
        color: Int,
        cornerDp: Int,
        stroke: Int = Color.argb(72, 255, 255, 255)
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(cornerDp).toFloat()
        setColor(color)
        setStroke(dp(1), stroke)
    }

    private fun glassActionButton(
        backdrop: View,
        label: String,
        click: () -> Unit
    ): View {
        val text = TextView(this).apply {
            this.text = label
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        }
        if (!liquidGlassEnabled) {
            return FrameLayout(this).apply {
                background = shellButtonBackground(primary = true)
                isClickable = true
                isFocusable = true
                addView(text, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ))
                setOnClickListener { click() }
            }
        }
        return LiquidGlassView(this).apply {
            cornerRadius = dp(17).toFloat()
            material = GlassMaterial.CLEAR
            blurAmount = 0.12f
            saturation = 128f
            refractionHeight = dp(19).toFloat()
            bevelWidth = dp(15).toFloat()
            refractionFalloff = 2.7f
            dispersionStrength = 0.12f
            enablePressEffect = true
            pressScale = 0.99f
            elasticity = 0.56f
            enableDynamicBackground = false
            collectFrameStats = false
            backdropSource = backdrop
            setGlassTint(uiAccent, 0.50f)
            isClickable = true
            isFocusable = true
            addView(text, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            setOnClickListener { click() }
            shellGlassPanels.add(this)
        }
    }

    private fun glassIconControl(
        backdrop: View,
        glyph: String,
        description: String,
        click: () -> Unit
    ): View {
        val text = TextView(this).apply {
            this.text = glyph
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
        }
        if (!liquidGlassEnabled) {
            return FrameLayout(this).apply {
                background = shellButtonBackground(primary = false)
                isClickable = true
                isFocusable = true
                contentDescription = description
                addView(text, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                ))
                setOnClickListener { click() }
            }
        }
        return LiquidGlassView(this).apply {
            cornerRadius = dp(16).toFloat()
            material = GlassMaterial.CLEAR
            blurAmount = 0.10f
            saturation = 122f
            refractionHeight = dp(15).toFloat()
            bevelWidth = dp(12).toFloat()
            refractionFalloff = 2.7f
            dispersionStrength = 0.09f
            enablePressEffect = true
            pressScale = 0.99f
            elasticity = 0.52f
            enableDynamicBackground = false
            collectFrameStats = false
            backdropSource = backdrop
            setGlassTint(Color.rgb(27, 31, 47), 0.30f)
            isClickable = true
            isFocusable = true
            contentDescription = description
            addView(text, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            setOnClickListener { click() }
            shellGlassPanels.add(this)
        }
    }

    private fun glassValuePill(label: TextView, backdrop: View): View {
        label.isClickable = false
        if (!liquidGlassEnabled) {
            return FrameLayout(this).apply {
                background = panelBackground(
                    Color.rgb(19, 46, 47), 14, Color.argb(105, 45, 212, 191)
                )
                isClickable = true
                isFocusable = true
                addView(label, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    dp(40),
                    Gravity.CENTER
                ))
                setOnClickListener { label.performClick() }
            }
        }
        return LiquidGlassView(this).apply {
            cornerRadius = dp(14).toFloat()
            material = GlassMaterial.CLEAR
            blurAmount = 0.075f
            saturation = 120f
            refractionHeight = dp(11).toFloat()
            bevelWidth = dp(10).toFloat()
            refractionFalloff = 2.6f
            dispersionStrength = 0.055f
            enablePressEffect = true
            pressScale = 0.995f
            elasticity = 0.50f
            enableDynamicBackground = false
            collectFrameStats = false
            backdropSource = backdrop
            setGlassTint(uiAccent2, 0.16f)
            isClickable = true
            isFocusable = true
            addView(label, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(40),
                Gravity.CENTER
            ))
            setOnClickListener { label.performClick() }
            shellGlassPanels.add(this)
        }
    }

    private fun glassPanel(
        content: View,
        backdrop: View,
        accented: Boolean = false,
        cornerDp: Int = 22
    ): View {
        if (!liquidGlassEnabled) {
            return FrameLayout(this).apply {
                elevation = dp(if (accented) 8 else 5).toFloat()
                background = panelBackground(
                    if (accented) Color.rgb(44, 29, 78) else Color.rgb(18, 21, 32),
                    cornerDp,
                    if (accented) Color.argb(165, 139, 92, 246)
                    else Color.argb(72, 255, 255, 255)
                )
                addView(content, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            }
        }
        return LiquidGlassView(this).apply {
            cornerRadius = dp(cornerDp).toFloat()
            elevation = dp(if (accented) 8 else 5).toFloat()
            material = GlassMaterial.REGULAR
            blurAmount = if (accented) 0.14f else 0.10f
            saturation = 124f
            refractionHeight = dp(if (accented) 30 else 21).toFloat()
            bevelWidth = dp(if (accented) 22 else 18).toFloat()
            refractionFalloff = 2.6f
            dispersionStrength = if (accented) 0.13f else 0.075f
            enableSensorHighlight = false
            enableAdaptiveTint = false
            enablePressEffect = true
            pressScale = 0.997f
            elasticity = 0.22f
            collectFrameStats = false
            enableDynamicBackground = false
            backdropSource = backdrop
            setGlassTint(
                if (accented) uiAccent else Color.rgb(18, 21, 32),
                if (accented) 0.18f else 0.24f
            )
            addView(content, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            shellGlassPanels.add(this)
        }
    }

    private fun settingsRowSurface(
        row: View,
        backdrop: View,
        click: () -> Unit
    ): View {
        row.isClickable = false
        if (!liquidGlassEnabled) {
            return FrameLayout(this).apply {
                isClickable = true
                isFocusable = true
                addView(row, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))
                setOnClickListener { click() }
            }
        }
        return LiquidGlassView(this).apply {
            cornerRadius = dp(16).toFloat()
            material = GlassMaterial.CLEAR
            blurAmount = 0.10f
            saturation = 116f
            refractionHeight = dp(13).toFloat()
            bevelWidth = dp(11).toFloat()
            refractionFalloff = 2.7f
            dispersionStrength = 0.065f
            enablePressEffect = true
            pressScale = 0.995f
            elasticity = 0.48f
            enableDynamicBackground = false
            collectFrameStats = false
            backdropSource = backdrop
            setGlassTint(Color.rgb(9, 12, 22), 0.46f)
            isClickable = true
            isFocusable = true
            addView(row, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            setOnClickListener { click() }
        }
    }

    private fun addVoiceControl(parent: LinearLayout, backdrop: View) {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "Voice Count"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val value = TextView(this).apply {
            text = voiceCount.toString()
            setTextColor(uiAccent2)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { showVoiceInput() }
        }
        header.addView(glassValuePill(value, backdrop))
        parent.addView(header)
        val bar = SeekBar(this).apply {
            max = 499
            progress = (voiceCount - 1).coerceIn(0, 499)
            minHeight = dp(48)
            styleShellSeekBar(this)
            setOnSeekBarChangeListener(simpleListener { p ->
                voiceCount = p + 1
                value.text = voiceCount.toString()
            })
        }
        parent.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        parent.addView(rangeLabels("1", "500"))
    }

    private fun addSpeedControl(parent: LinearLayout, backdrop: View) {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = "Note Speed"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val value = TextView(this).apply {
            text = "%.3f×".format(noteSpeed)
            setTextColor(uiAccent2)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { showSpeedInput() }
        }
        header.addView(glassValuePill(value, backdrop))
        parent.addView(header)
        val bar = SeekBar(this).apply {
            max = 1000
            progress = progressFromSpeed(noteSpeed)
            minHeight = dp(48)
            styleShellSeekBar(this)
            setOnSeekBarChangeListener(simpleListener { p ->
                noteSpeed = speedFromProgress(p)
                value.text = "%.3f×".format(noteSpeed)
            })
        }
        parent.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        parent.addView(rangeLabels("0.005×", "1.000×"))
    }

    private fun rangeLabels(min: String, max: String): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(TextView(this@MainActivity).apply {
                text = min; setTextColor(uiMuted); textSize = 11f
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(this@MainActivity).apply {
                text = max; setTextColor(uiMuted); textSize = 11f; gravity = Gravity.END
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

    private fun showVoiceInput() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(voiceCount.toString())
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Voice Count")
            .setMessage("1–500  •  default 250")
            .setView(input)
            .setPositiveButton("Apply") { _, _ ->
                voiceCount = (input.text.toString().toIntOrNull() ?: voiceCount).coerceIn(1, 500)
                saveSettings()
                setContentView(buildSetupScreen())
            }
            .setNeutralButton("Reset") { _, _ ->
                voiceCount = 250
                saveSettings()
                setContentView(buildSetupScreen())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSpeedInput() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("%.3f".format(noteSpeed))
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Note Speed")
            .setMessage("0.005×–1.000×  •  default 0.050×")
            .setView(input)
            .setPositiveButton("Apply") { _, _ ->
                noteSpeed = (input.text.toString().toFloatOrNull() ?: noteSpeed).coerceIn(0.005f, 1.0f)
                saveSettings()
                setContentView(buildSetupScreen())
            }
            .setNeutralButton("Reset") { _, _ ->
                noteSpeed = 0.05f
                saveSettings()
                setContentView(buildSetupScreen())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun shellCardBackground(accented: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(22).toFloat()
            setColor(if (accented) uiPanelAlt else uiPanel)
            setStroke(dp(1), if (accented) Color.argb(155, 139, 92, 246)
                              else Color.argb(72, 255, 255, 255))
        }

    private fun shellButtonBackground(primary: Boolean): RippleDrawable {
        val shape = GradientDrawable().apply {
            cornerRadius = dp(15).toFloat()
            setColor(if (primary) uiAccent else Color.argb(185, 35, 39, 55))
            if (!primary) setStroke(dp(1), Color.argb(82, 255, 255, 255))
        }
        return RippleDrawable(
            ColorStateList.valueOf(Color.argb(56, 255, 255, 255)),
            shape,
            null
        )
    }

    private fun styleShellButton(button: Button, primary: Boolean, compact: Boolean = false) {
        button.isAllCaps = false
        button.setTextColor(Color.WHITE)
        button.textSize = if (compact) 18f else 14.5f
        button.typeface = Typeface.DEFAULT_BOLD
        button.letterSpacing = 0.015f
        button.background = shellButtonBackground(primary)
        button.stateListAnimator = null
        button.elevation = if (primary) dp(5).toFloat() else dp(2).toFloat()
        if (!compact) button.setPadding(dp(16), 0, dp(16), 0)
    }

    private fun styleShellSeekBar(bar: SeekBar) {
        val accent = ColorStateList.valueOf(uiAccent2)
        bar.progressTintList = accent
        bar.thumbTintList = accent
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 19f
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun settingText(name: String, value: String): String = "$name    $value"

    private fun settingLabel(name: String, value: String): TextView = TextView(this).apply {
        text = settingText(name, value)
        setTextColor(Color.WHITE)
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun showSettingsDialog() {
        val dialog = Dialog(this)
        val activityBackdrop = findViewById<View>(android.R.id.content)

        val sheetContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = "Settings"
            setTextColor(Color.WHITE)
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(TextView(this).apply {
            text = "×"
            contentDescription = "Close settings"
            setTextColor(Color.rgb(220, 225, 238))
            textSize = 26f
            gravity = Gravity.CENTER
            setOnClickListener { dialog.dismiss() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        sheetContent.addView(titleRow)

        fun group(title: String) {
            sheetContent.addView(TextView(this).apply {
                text = title.uppercase()
                setTextColor(uiAccent2)
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.15f
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(18); bottomMargin = dp(5) })
        }

        fun action(title: String, subtitle: String, click: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(8), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(15).toFloat()
                    setColor(Color.argb(72, 6, 9, 18))
                    setStroke(dp(1), Color.argb(58, 255, 255, 255))
                }
            }
            val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            copy.addView(TextView(this).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
            })
            copy.addView(TextView(this).apply {
                text = subtitle
                setTextColor(Color.rgb(205, 211, 226))
                textSize = 12f
                maxLines = 2
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) })
            row.addView(copy, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ))
            row.addView(TextView(this).apply {
                text = "›"
                setTextColor(Color.rgb(196, 203, 222))
                textSize = 24f
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(30), dp(44)))
            val surface = settingsRowSurface(row, activityBackdrop) {
                dialog.dismiss()
                click()
            }
            sheetContent.addView(surface, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) })
        }

        fun toggle(title: String, subtitle: String, checked: Boolean, changed: (Boolean) -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(8), dp(10))
                background = GradientDrawable().apply {
                    cornerRadius = dp(15).toFloat()
                    setColor(Color.argb(72, 6, 9, 18))
                    setStroke(dp(1), Color.argb(58, 255, 255, 255))
                }
            }
            val copy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            copy.addView(TextView(this).apply {
                text = title
                setTextColor(Color.WHITE)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
            })
            copy.addView(TextView(this).apply {
                text = subtitle
                setTextColor(Color.rgb(205, 211, 226))
                textSize = 12f
                maxLines = 2
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) })
            row.addView(copy, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            ))

            val box = CheckBox(this).apply {
                isChecked = checked
                isClickable = false
                isFocusable = false
                buttonTintList = ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_checked),
                        intArrayOf()
                    ),
                    intArrayOf(uiAccent2, Color.rgb(126, 133, 151))
                )
            }
            row.addView(box, LinearLayout.LayoutParams(dp(48), dp(48)))

            val surface = settingsRowSurface(row, activityBackdrop) {
                val next = !box.isChecked
                box.isChecked = next
                changed(next)
            }
            sheetContent.addView(surface, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) })
        }

        group("Appearance")
        toggle(
            "Liquid Glass",
            "Refraction, blur and elastic glass surfaces",
            liquidGlassEnabled
        ) { enabled ->
            liquidGlassEnabled = enabled
            saveSettings()
            dialog.dismiss()
            setContentView(buildSetupScreen())
        }

        group("Performance")
        action("Core affinity", if (cpuMask == 0L) "Auto" else "Custom mask") {
            showCoreAffinityDialog()
        }
        action("Compatibility & streaming", "Legacy renderer, chunked streaming, pagefile location") {
            showAdvancedSettingsDialog()
        }

        group("About")
        action("aPFAViz v${appVersion()}", "Starzainia • HexagonMIDIs • mappazinho • LexonBlackzz") {
            AlertDialog.Builder(this)
                .setTitle("About aPFAViz")
                .setMessage(
                    "aPFAViz v${appVersion()}\n\nContributors\nStarzainia • HexagonMIDIs\nmappazinho • LexonBlackzz\n\n" +
                    "A PFA-faithful Android MIDI player.\n\n" +
                    "LiquidGlass Android by pandadog / QWEA0 — MIT License."
                )
                .setPositiveButton("OK", null)
                .show()
        }

        val sheetScroll = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(sheetContent)
        }
        val sheetSurface: View = if (liquidGlassEnabled) {
            LiquidGlassView(this).apply {
                cornerRadius = dp(30).toFloat()
                material = GlassMaterial.REGULAR
                blurAmount = 0.22f
                saturation = 112f
                refractionHeight = dp(27).toFloat()
                bevelWidth = dp(22).toFloat()
                refractionFalloff = 2.7f
                dispersionStrength = 0.10f
                enableSensorHighlight = false
                enableAdaptiveTint = false
                enableDynamicBackground = false
                enablePressEffect = false
                collectFrameStats = false
                backdropSource = activityBackdrop
                setGlassTint(Color.rgb(8, 10, 20), 0.62f)
                addView(sheetScroll, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            }
        } else {
            FrameLayout(this).apply {
                background = panelBackground(
                    Color.rgb(10, 13, 23), 30, Color.argb(86, 255, 255, 255)
                )
                elevation = dp(12).toFloat()
                addView(sheetScroll, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            }
        }

        val outer = FrameLayout(this).apply {
            setPadding(dp(14), dp(14), dp(14), dp(18))
            addView(sheetSurface, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM })
        }

        dialog.setContentView(outer)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.34f)
            setWindowAnimations(0)
            setGravity(Gravity.BOTTOM)
        }
        dialog.show()
        dialog.window?.apply {
            decorView.setPadding(0, 0, 0, 0)
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        animateGlassInDialog(sheetSurface)
    }

    private fun animateGlassInDialog(view: View) {
        view.alpha = 0f
        view.translationY = dp(22).toFloat()
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(240L)
            .start()
    }

    // --- Advanced Settings dialog ---------------------------------------------
    // Legacy Renderer: ES2 fallback for GPUs (Mali-400 / MT6570) that can't
    // create an ES3 context. Large streaming loads now chunk automatically when
    // their sort scratch would become too large; this switch forces the same
    // disk-backed sort for smaller streaming loads as well.
    // Pagefile Location: sits under that as Internal / SD Card, for the phones
    // whose internal storage was never going to hold it (SdCard.kt).
    private fun showAdvancedSettingsDialog() {
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(dp(20), dp(10), dp(20), dp(10))

        val legacyBox = CheckBox(this)
        legacyBox.text = "Legacy Renderer (GLES 2.0)"
        legacyBox.isChecked = legacyRenderer
        legacyBox.setOnCheckedChangeListener { _, checked ->
            legacyRenderer = checked
            saveSettings()
        }
        container.addView(legacyBox)

        val streamBox = CheckBox(this)
        streamBox.text = "Force Chunked Disk Sort"
        streamBox.isChecked = chunkedStreaming
        streamBox.setOnCheckedChangeListener { _, checked ->
            if (checked && !chunkedStreaming) {
                AlertDialog.Builder(this)
                    .setTitle("Force Chunked Disk Sort")
                    .setMessage("Forces the streaming sort to use bounded " +
                                "disk-backed chunks even when the MIDI is small " +
                                "enough to sort in RAM. Very large loads already " +
                                "switch to chunked sorting automatically to keep " +
                                "parse RAM under control.\n\n" +
                                "This can lower peak RAM further, at the cost of " +
                                "more temporary storage writes and longer loading " +
                                "times.\n\n" +
                                // Only on a 32-bit phone, because the pagefile
                                // is only ever split there (streamer.cpp,
                                // poolFileBytes()).
                                (if (is32BitProcess)
                                    "ADDITIONALLY: On this phone (has been " +
                                    "detected to be 32-bit / ARMv7), a MIDI " +
                                    "whose pagefile grows past 2 GB is written " +
                                    "as several files instead of one, because " +
                                    "32-bit Android cannot reach past 2 GB " +
                                    "inside a single file. Instability and a " +
                                    "lack of responsiveness may occur if any " +
                                    "MIDI goes past around 1.5GB streamed " +
                                    "(15M+ notes).\n\n"
                                 else "") +
                                "Are you sure you want to enable this?")
                    .setPositiveButton("Yes") { _, _ ->
                        chunkedStreaming = true
                        saveSettings()
                    }
                    .setNegativeButton("No") { _, _ ->
                        streamBox.isChecked = false
                    }
                    .setOnCancelListener {
                        streamBox.isChecked = false
                    }
                    .show()
            } else if (!checked) {
                chunkedStreaming = false
                saveSettings()
            }
        }
        container.addView(streamBox)

        // --- Pagefile Location (streaming pool storage) ---
        // Where the pagefile is written, rather than whether there is one: the
        // pagefile IS the streaming pool, so this applies to any streaming load
        // — which is why the choice stays live with the box above unticked.
        //
        // SD is offered only where it can actually work, and is otherwise
        // greyed out with the reason spelled out underneath: pre-KitKat (no
        // app-private path on a secondary volume), a 32-bit process (the pool
        // mapping needs 64-bit address space — the reason distinguishes a
        // 32-bit CPU from a 64-bit CPU on a 32-bit ROM, which is what an
        // SD425 phone like the LG X410 actually is), or no card mounted.
        val sdReason = SdCard.unavailableReason(this)

        val locHeader = TextView(this)
        locHeader.text = "Pagefile Location"
        locHeader.textSize = 14f
        // Take the checkboxes' resolved colour instead of guessing at whether
        // this dialog came up light or dark.
        locHeader.setTextColor(streamBox.currentTextColor)
        locHeader.setPadding(dp(32), dp(8), 0, dp(2))
        container.addView(locHeader)

        val locGroup = RadioGroup(this)
        locGroup.orientation = RadioGroup.VERTICAL
        locGroup.setPadding(dp(32), 0, 0, 0)
        val internalRadio = RadioButton(this)
        internalRadio.id = ID_LOC_INTERNAL
        internalRadio.text = "Internal"
        locGroup.addView(internalRadio)
        val sdRadio = RadioButton(this)
        sdRadio.id = ID_LOC_SD
        sdRadio.text = "SD Card"
        sdRadio.isEnabled = sdReason == null
        locGroup.addView(sdRadio)
        container.addView(locGroup)

        val sdNote = TextView(this)
        sdNote.textSize = 12f
        sdNote.setTextColor(Color.LTGRAY)
        sdNote.setPadding(dp(32), 0, 0, dp(4))
        container.addView(sdNote)

        // The card may have been pulled since the setting was saved — drop it
        // so nothing downstream tries to use a path that isn't there.
        if (sdReason != null && sdPagefile) { sdPagefile = false; saveSettings() }

        // The note doubles as the greyed-out reason, so it tracks the selection.
        fun describeLocation() {
            sdNote.text = when {
                sdReason != null -> sdReason
                sdPagefile -> "Writes the streaming pagefile to " +
                              (SdCard.cacheDir(this)?.absolutePath ?: "the card") +
                              "\nCards are slower than internal storage, so expect " +
                              "more pagefile lag."
                else -> "Writes the streaming pagefile to the app's cache dir on " +
                        "internal storage."
            }
        }

        // Set the starting position before listening, or check() writes the
        // setting straight back on open.
        locGroup.check(if (sdPagefile) ID_LOC_SD else ID_LOC_INTERNAL)
        describeLocation()
        locGroup.setOnCheckedChangeListener { _, checkedId ->
            sdPagefile = checkedId == ID_LOC_SD
            saveSettings()
            describeLocation()
        }

        AlertDialog.Builder(this)
            .setTitle("Advanced Settings")
            .setView(container)
            .setPositiveButton("Done", null)
            .show()
    }

    // --- Note Speed slider: non-linear so the 0.05 default sits at the midpoint ---
    // progress 0..500   -> noteSpeed 0.005..0.05
    // progress 500..1000-> noteSpeed 0.05 ..1.0
    private fun speedFromProgress(p: Int): Float =
        if (p <= 500) 0.005f + (p / 500f) * 0.045f
        else          0.05f + ((p - 500) / 500f) * 0.95f

    private fun progressFromSpeed(s: Float): Int =
        if (s <= 0.05f) (((s - 0.005f) / 0.045f) * 500f).toInt().coerceIn(0, 500)
        else            (500 + ((s - 0.05f) / 0.95f) * 500f).toInt().coerceIn(500, 1000)

    // --- Core Affinity dialog -------------------------------------------------
    // Lists every cpuN dir under /sys/devices/system/cpu with its cpuinfo_max_freq.
    // Multi-select; saves the chosen set as a 64-bit mask. Empty = "Auto".

    private data class CpuRow(val index: Int, val maxKHz: Long)

    private fun readCpuList(): List<CpuRow> {
        val dir = File("/sys/devices/system/cpu")
        val entries = dir.listFiles { f ->
            f.isDirectory && f.name.matches(Regex("cpu[0-9]+"))
        } ?: return emptyList()
        return entries
            .mapNotNull { f ->
                val idx = f.name.substring(3).toIntOrNull() ?: return@mapNotNull null
                val freqFile = File(f, "cpufreq/cpuinfo_max_freq")
                val khz = try {
                    if (freqFile.exists()) freqFile.readText().trim().toLong() else 0L
                } catch (e: Exception) { 0L }
                CpuRow(idx, khz)
            }
            .sortedBy { it.index }
    }

    private fun formatCpuRow(row: CpuRow): String =
        if (row.maxKHz > 0)
            "cpu%d  —  %.2f GHz".format(row.index, row.maxKHz / 1_000_000.0)
        else
            "cpu%d  —  (offline)".format(row.index)

    private fun showCoreAffinityDialog() {
        val cpus = readCpuList()
        if (cpus.isEmpty()) {
            Toast.makeText(this, "No CPU info available", Toast.LENGTH_SHORT).show()
            return
        }
        val labels  = cpus.map { formatCpuRow(it) }.toTypedArray()
        val checked = BooleanArray(cpus.size) { i ->
            ((cpuMask shr cpus[i].index) and 1L) == 1L
        }
        AlertDialog.Builder(this)
            .setTitle("Core Affinity")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("OK") { _, _ ->
                var m = 0L
                for (i in cpus.indices)
                    if (checked[i]) m = m or (1L shl cpus[i].index)
                cpuMask = m
                saveSettings()
                val msg = if (m == 0L) "Core Affinity: Auto"
                          else "Core Affinity: " + cpus
                              .filter { ((m shr it.index) and 1L) == 1L }
                              .joinToString(", ") { "cpu${it.index}" }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Auto") { _, _ ->
                cpuMask = 0L
                saveSettings()
                Toast.makeText(this, "Core Affinity: Auto", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- Background dialog: custom RGB / Hue-Sat-Lum colour + profiles + image ----
    // No presets. The user dials in a colour with R,G,B and Hue/Sat/Lum text boxes
    // (the two groups stay in sync), names + saves it as a profile, and can export
    // a profile to a file to carry to another device. "I want an Image!" picks a
    // .png/.jpg to use as the playback background instead of a solid colour.
    //
    // Colours are stored in PFA BGR format (R=bit0, G=bit8, B=bit16). The Hue/Sat/
    // Lum fields use PFA's exact HSV maths (PFA labels Value "Lum"); see Misc.cpp.

    // PFA Util::RGBtoHSV (Misc.cpp:200). Returns H 0..359, S/V 0..100.
    private fun rgbToHsv(R: Int, G: Int, B: Int): Triple<Int, Int, Int> {
        val dR = R / 255.0; val dG = G / 255.0; val dB = B / 255.0
        val M = maxOf(dR, dG, dB); val m = minOf(dR, dG, dB); val C = M - m
        var dH = when {
            C == 0.0 -> 0.0
            M == dR  -> (dG - dB) / C
            M == dG  -> (dB - dR) / C + 2.0
            else     -> (dR - dG) / C + 4.0
        }
        if (dH < 0) dH += 6.0
        val dV = M; val dS = if (dV > 0.0) C / dV else 0.0
        return Triple(((dH * 60.0 + 0.5).toInt()) % 360,
                      (dS * 100.0 + 0.5).toInt(),
                      (dV * 100.0 + 0.5).toInt())
    }

    // PFA Util::HSVtoRGB (Misc.cpp:220). H 0..359, S/V 0..100 -> R,G,B 0..255.
    private fun hsvToRgb(H: Int, S: Int, V: Int): Triple<Int, Int, Int> {
        val dH = H / 60.0; val dS = S / 100.0; val dV = V / 100.0
        val C = dV * dS; val mm = dV - C
        var r1 = 0.0; var g1 = 0.0; var b1 = 0.0
        when {
            dH < 1.0 -> { r1 = C;               g1 = C * dH;        b1 = 0.0 }
            dH < 2.0 -> { r1 = C * (2.0 - dH);  g1 = C;             b1 = 0.0 }
            dH < 3.0 -> { r1 = 0.0;             g1 = C;             b1 = C * (dH - 2.0) }
            dH < 4.0 -> { r1 = 0.0;             g1 = C * (4.0 - dH); b1 = C }
            dH < 5.0 -> { r1 = C * (dH - 4.0);  g1 = 0.0;           b1 = C }
            else     -> { r1 = C;               g1 = 0.0;           b1 = C * (6.0 - dH) }
        }
        return Triple(((r1 + mm) * 255.0 + 0.5).toInt(),
                      ((g1 + mm) * 255.0 + 0.5).toInt(),
                      ((b1 + mm) * 255.0 + 0.5).toInt())
    }

    private fun showBgColorDialog() {
        val wc = ViewGroup.LayoutParams.WRAP_CONTENT
        val mp = ViewGroup.LayoutParams.MATCH_PARENT

        // Current colour seeds the fields.
        var R = bgColor and 0xFF
        var G = (bgColor shr 8) and 0xFF
        var B = (bgColor shr 16) and 0xFF

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val swatch = View(this)
        root.addView(swatch, LinearLayout.LayoutParams(mp, dp(40)))
        root.addView(dialogLabel("Tap the colour above to apply it (switches off any image)").apply {
            textSize = 12f
        }, LinearLayout.LayoutParams(mp, wc).apply { bottomMargin = dp(12) })

        val rEt = colorField(); val gEt = colorField(); val bEt = colorField()
        val hEt = colorField(); val sEt = colorField(); val lEt = colorField()
        root.addView(colorRow("R", rEt, "G", gEt, "B", bEt))
        root.addView(colorRow("Hue", hEt, "Sat", sEt, "Lum", lEt))

        val restoreBtn = Button(this).apply { text = "Restore to Default (PFA Grey)" }
        root.addView(restoreBtn, LinearLayout.LayoutParams(mp, wc).apply { topMargin = dp(8) })

        var updating = false
        fun applySwatch() = swatch.setBackgroundColor(Color.rgb(R, G, B))
        fun pushRgb() { updating = true; rEt.setText(R.toString()); gEt.setText(G.toString()); bEt.setText(B.toString()); updating = false }
        fun pushHsv() { val (h, s, v) = rgbToHsv(R, G, B); updating = true; hEt.setText(h.toString()); sEt.setText(s.toString()); lEt.setText(v.toString()); updating = false }
        // Commit the current R,G,B as the solid background, dropping any image.
        fun commitColor() {
            bgColor = (R and 0xFF) or ((G and 0xFF) shl 8) or ((B and 0xFF) shl 16)
            bgImagePath = null            // picking a colour drops any image
            saveSettings()
            toast("Background colour set")
        }

        val onRgb = {
            R = clampField(rEt, 0, 255); G = clampField(gEt, 0, 255); B = clampField(bEt, 0, 255)
            pushHsv(); applySwatch()
        }
        val onHsv = {
            val (nr, ng, nb) = hsvToRgb(clampField(hEt, 0, 359), clampField(sEt, 0, 100), clampField(lEt, 0, 100))
            R = nr; G = ng; B = nb; pushRgb(); applySwatch()
        }
        for (e in listOf(rEt, gEt, bEt)) watch(e) { if (!updating) onRgb() }
        for (e in listOf(hEt, sEt, lEt)) watch(e) { if (!updating) onHsv() }
        pushRgb(); pushHsv(); applySwatch()

        // --- profile name + buttons ---
        val nameEt = EditText(this).apply {
            hint = "Profile name"; setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY)
        }
        root.addView(dialogLabel("Save this colour as a named profile:"),
            LinearLayout.LayoutParams(mp, wc).apply { topMargin = dp(8) })
        root.addView(nameEt)

        root.addView(Button(this).apply {
            text = "Save Profile"
            setOnClickListener {
                val nm = nameEt.text.toString().trim()
                if (nm.isEmpty()) { toast("Enter a profile name first"); return@setOnClickListener }
                saveProfile(nm, R, G, B); toast("Saved profile \"$nm\"")
            }
        }, LinearLayout.LayoutParams(mp, wc).apply { topMargin = dp(4) })

        val applyColor: (Int, Int, Int) -> Unit = { r, g, b -> R = r; G = g; B = b; pushRgb(); pushHsv(); applySwatch() }
        root.addView(Button(this).apply {
            text = "Load Profile"
            setOnClickListener { showLoadProfileDialog(applyColor) }
        }, LinearLayout.LayoutParams(mp, wc))
        root.addView(Button(this).apply {
            text = "Export Profile to File"
            setOnClickListener { showExportProfileDialog() }
        }, LinearLayout.LayoutParams(mp, wc))

        val imageBtn = Button(this).apply { text = "I want an Image!" }
        root.addView(imageBtn, LinearLayout.LayoutParams(mp, wc).apply { topMargin = dp(8) })

        val scroll = ScrollView(this).apply { addView(root) }
        val dlg = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Background")
            .setView(scroll)
            .setPositiveButton("Apply Colour") { _, _ -> commitColor() }
            .setNegativeButton("Cancel", null)
            .create()
        // Tapping the swatch applies the current colour and closes — the quick way
        // to swap an image back for a solid colour.
        swatch.setOnClickListener { commitColor(); dlg.dismiss() }
        // Restore the standard PFA grey (0x464646) and apply it immediately.
        restoreBtn.setOnClickListener {
            R = 0x46; G = 0x46; B = 0x46
            pushRgb(); pushHsv(); applySwatch()
            commitColor(); dlg.dismiss()
        }
        imageBtn.setOnClickListener { dlg.dismiss(); pickFile(REQ_BG_IMAGE, "image/*") }
        dlg.show()
    }

    // --- colour-dialog view helpers ---

    private fun colorField(): EditText = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        textSize = 16f
        setTextColor(Color.WHITE)
        setSelectAllOnFocus(true)
    }

    private fun dialogLabel(text: String): TextView = TextView(this).apply {
        this.text = text; setTextColor(Color.WHITE); textSize = 14f
    }

    private fun colorRow(l1: String, e1: EditText, l2: String, e2: EditText,
                         l3: String, e3: EditText): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun cell(lbl: String, et: EditText) {
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(dialogLabel(lbl))
            col.addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            })
        }
        cell(l1, e1); cell(l2, e2); cell(l3, e3)
        return row
    }

    private fun watch(et: EditText, onChange: () -> Unit) {
        et.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = onChange()
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    private fun clampField(et: EditText, lo: Int, hi: Int): Int =
        (et.text.toString().toIntOrNull() ?: lo).coerceIn(lo, hi)

    // --- colour profiles (stored as JSON in SharedPreferences) -------------------

    private fun loadProfiles(): JSONArray = try {
        JSONArray(getPreferences(MODE_PRIVATE).getString("colorProfiles", "[]"))
    } catch (e: Exception) { JSONArray() }

    private fun profileJson(name: String, R: Int, G: Int, B: Int): JSONObject {
        val (h, s, v) = rgbToHsv(R, G, B)
        return JSONObject().apply {
            put("name", name); put("r", R); put("g", G); put("b", B)
            put("hue", h); put("sat", s); put("lum", v)
        }
    }

    private fun saveProfile(name: String, R: Int, G: Int, B: Int) {
        val arr = loadProfiles(); val out = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("name") != name) out.put(o)   // replace same-named
        }
        out.put(profileJson(name, R, G, B))
        getPreferences(MODE_PRIVATE).edit().putString("colorProfiles", out.toString()).apply()
    }

    private fun showLoadProfileDialog(onPick: (Int, Int, Int) -> Unit) {
        val arr = loadProfiles()
        val items = ArrayList<String>().apply {
            add("Import from file…")
            for (i in 0 until arr.length()) add(arr.getJSONObject(i).optString("name"))
        }
        AlertDialog.Builder(this)
            .setTitle("Load Profile")
            .setItems(items.toTypedArray()) { _, which ->
                if (which == 0) { pickFile(REQ_IMPORT_PROFILE); return@setItems }
                val o = arr.getJSONObject(which - 1)
                onPick(o.optInt("r"), o.optInt("g"), o.optInt("b"))
                toast("Loaded \"${o.optString("name")}\"")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showExportProfileDialog() {
        val arr = loadProfiles()
        if (arr.length() == 0) { toast("No saved profiles to export"); return }
        val names = Array(arr.length()) { arr.getJSONObject(it).optString("name") }
        AlertDialog.Builder(this)
            .setTitle("Export Profile to File")
            .setItems(names) { _, which ->
                pendingExport = arr.getJSONObject(which)
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/json"
                    putExtra(Intent.EXTRA_TITLE, names[which] + ".apfacolor.json")
                }
                startActivityForResult(intent, REQ_EXPORT_PROFILE)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun rememberRecentMidi(uri: Uri) {
        val arr = JSONArray()
        arr.put(JSONObject().apply {
            put("uri", uri.toString())
            put("name", displayName(uri))
        })
        loadRecentMidis().filter { it.uri != uri }.take(4).forEach {
            arr.put(JSONObject().apply {
                put("uri", it.uri.toString())
                put("name", it.name)
            })
        }
        getPreferences(MODE_PRIVATE).edit().putString("recentMidis", arr.toString()).apply()
    }

    private fun loadRecentMidis(): List<RecentMidi> {
        val raw = getPreferences(MODE_PRIVATE).getString("recentMidis", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val uri = o.optString("uri")
                    val name = o.optString("name")
                    if (uri.isNotEmpty() && name.isNotEmpty()) add(RecentMidi(Uri.parse(uri), name))
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // --- settings persistence ---

    private fun loadSettings() {
        val p = getPreferences(MODE_PRIVATE)
        voiceCount = p.getInt("voiceCount", voiceCount)
        noteSpeed  = p.getFloat("noteSpeed", noteSpeed)
        cpuMask    = p.getLong("cpuMask", cpuMask)
        legacyRenderer = p.getBoolean("legacyRenderer", legacyRenderer)
        liquidGlassEnabled = p.getBoolean("liquidGlass", liquidGlassEnabled)
        chunkedStreaming = p.getBoolean("diskStreaming", chunkedStreaming)
        sdPagefile = p.getBoolean("sdPagefile", sdPagefile)
        bgColor    = p.getInt("bgColor", bgColor)
        bgImagePath = p.getString("bgImage", null)?.takeIf { File(it).exists() }
        p.getString("soundfont", null)?.let { soundfontUri = Uri.parse(it) }
    }

    private fun saveSettings() {
        getPreferences(MODE_PRIVATE).edit()
            .putInt("voiceCount", voiceCount)
            .putFloat("noteSpeed", noteSpeed)
            .putLong("cpuMask", cpuMask)
            .putBoolean("legacyRenderer", legacyRenderer)
            .putBoolean("liquidGlass", liquidGlassEnabled)
            .putBoolean("diskStreaming", chunkedStreaming)
            .putBoolean("sdPagefile", sdPagefile)
            .putInt("bgColor", bgColor)
            .putString("bgImage", bgImagePath)
            .putString("soundfont", soundfontUri?.toString())
            .apply()
    }

    // --- helpers ---

    private fun label(text: String): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(Color.WHITE)
        tv.textSize = 15f
        return tv
    }

    private fun simpleListener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) = onChange(progress)
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) { saveSettings() }
    }

    private fun loadAsset(name: String): Bitmap? = try {
        assets.open(name).use { BitmapFactory.decodeStream(it) }
    } catch (e: Exception) {
        null
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun appVersion(): String =
        packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0"

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // API 23+ always has the Storage Access Framework, so every picker goes
    // through one modern path. This replaces the old pre-KitKat file browser.
    private fun pickFile(requestCode: Int, mime: String = "*/*") {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mime
        }
        startActivityForResult(intent, requestCode)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        handlePicked(requestCode, uri)
    }

    private fun handlePicked(requestCode: Int, uri: Uri) {
        val name = displayName(uri).lowercase()
        when (requestCode) {
            REQ_MIDI -> {
                if (!name.endsWith(".mid") && !name.endsWith(".midi")) {
                    Toast.makeText(this, "Not a MIDI file (.mid)", Toast.LENGTH_SHORT).show()
                    return
                }
                persist(uri)
                midiUri = uri
                rememberRecentMidi(uri)
                launchPlayback()
            }
            REQ_SOUNDFONT -> {
                if (!name.endsWith(".sf2") && !name.endsWith(".sf3") &&
                    !name.endsWith(".sfz")) {
                    Toast.makeText(this, "Not a soundfont (.sf2/.sf3/.sfz)",
                                   Toast.LENGTH_SHORT).show()
                    return
                }
                persist(uri)
                soundfontUri = uri
                soundfontButton.text = displayName(uri)
                saveSettings()        // remember it across launches
            }
            REQ_BG_IMAGE -> {
                if (!name.endsWith(".png") && !name.endsWith(".jpg") && !name.endsWith(".jpeg")) {
                    toast("Pick a .png or .jpg image")
                    return
                }
                // Copy into app storage so it survives the picker's transient grant.
                // BitmapFactory detects the format from content, so a fixed name is fine.
                val path = copyUriToFile(uri, "bg_image")
                if (path == null) { toast("Could not read that image"); return }
                bgImagePath = path
                saveSettings()
                toast("Background image set")
            }
            REQ_EXPORT_PROFILE -> {
                val json = pendingExport ?: return
                pendingExport = null
                try {
                    contentResolver.openOutputStream(uri)!!.use {
                        it.write(json.toString(2).toByteArray())
                    }
                    toast("Exported \"${json.optString("name")}\"")
                } catch (e: Exception) {
                    toast("Export failed")
                }
            }
            REQ_IMPORT_PROFILE -> {
                try {
                    val text = contentResolver.openInputStream(uri)!!.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }
                    val o = JSONObject(text)
                    val nm = o.optString("name").ifEmpty { "Imported" }
                    saveProfile(nm, o.getInt("r"), o.getInt("g"), o.getInt("b"))
                    toast("Imported \"$nm\" — open Load Profile to use it")
                } catch (e: Exception) {
                    toast("Not a valid colour profile file")
                }
            }
        }
    }

    private fun copyUriToFile(uri: Uri, outName: String): String? = try {
        val out = File(filesDir, outName)
        contentResolver.openInputStream(uri)!!.use { input ->
            FileOutputStream(out).use { output -> input.copyTo(output, 1 shl 16) }
        }
        out.absolutePath
    } catch (e: Exception) {
        null
    }

    private fun persist(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // the in-session grant still applies — fine for this launch
        }
    }

    private fun launchPlayback() {
        val midi = midiUri ?: return
        saveSettings()
        val intent = Intent(this, PlaybackActivity::class.java)
        intent.putExtra(PlaybackActivity.EXTRA_MIDI, midi.toString())
        soundfontUri?.let { intent.putExtra(PlaybackActivity.EXTRA_SF, it.toString()) }
        intent.putExtra(PlaybackActivity.EXTRA_VOICES, voiceCount)
        intent.putExtra(PlaybackActivity.EXTRA_SPEED, noteSpeed)
        intent.putExtra(PlaybackActivity.EXTRA_CPU_MASK, cpuMask)
        intent.putExtra(PlaybackActivity.EXTRA_LEGACY, legacyRenderer)
        intent.putExtra(PlaybackActivity.EXTRA_LIQUID_GLASS, liquidGlassEnabled)
        intent.putExtra(PlaybackActivity.EXTRA_STREAM, chunkedStreaming)
        // Pass the intent, not a path: the card can be pulled between here and
        // the load, so PlaybackActivity resolves the directory fresh.
        intent.putExtra(PlaybackActivity.EXTRA_SD_POOL, sdPagefile)
        intent.putExtra(PlaybackActivity.EXTRA_BG_COLOR, bgColor)
        bgImagePath?.let { intent.putExtra(PlaybackActivity.EXTRA_BG_IMAGE, it) }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(intent)
    }

    private fun sfButtonLabel(uri: Uri): String =
        SF_PREFIX + displayName(uri)

    private fun displayName(uri: Uri): String {
        var name = uri.lastPathSegment ?: "file"
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = c.getString(idx)
                }
            }
        } catch (e: Exception) {
            // keep the path-segment fallback
        }
        return name
    }
}